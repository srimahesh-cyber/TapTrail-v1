"""Security utilities: Bearer auth, IP rate limiting, and size validation."""

import time
import logging
from typing import Dict, List, Optional, Set
from fastapi import Request, HTTPException, status
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response

from app.config import get_settings
from app.models import ErrorResponse

logger = logging.getLogger(__name__)


class InMemoryRateLimiter:
    """Sliding-window in-memory rate limiter per client IP."""

    def __init__(self, requests_per_minute: int = 10, window_seconds: float = 60.0):
        self.requests_per_minute = requests_per_minute
        self.window_seconds = window_seconds
        self._history: Dict[str, List[float]] = {}
        self._last_prune = time.time()

    def _prune(self, now: float) -> None:
        """Prune stale client histories every 5 minutes."""
        if now - self._last_prune < 300.0:
            return
        cutoff = now - self.window_seconds
        stale_ips = [ip for ip, timestamps in self._history.items() if not timestamps or timestamps[-1] < cutoff]
        for ip in stale_ips:
            del self._history[ip]
        self._last_prune = now

    def is_allowed(self, client_ip: str) -> bool:
        """Check if request from client_ip is within allowed rate limit."""
        now = time.time()
        self._prune(now)

        cutoff = now - self.window_seconds
        timestamps = self._history.get(client_ip, [])
        # Filter timestamps within current window
        valid_timestamps = [t for t in timestamps if t > cutoff]

        if len(valid_timestamps) >= self.requests_per_minute:
            self._history[client_ip] = valid_timestamps
            return False

        valid_timestamps.append(now)
        self._history[client_ip] = valid_timestamps
        return True

    def reset(self) -> None:
        """Reset all rate limiting state (useful for tests)."""
        self._history.clear()


# Module-level rate limiter initialized with settings
_rate_limiter: Optional[InMemoryRateLimiter] = None


def get_rate_limiter() -> InMemoryRateLimiter:
    """Obtain or initialize the global rate limiter instance."""
    global _rate_limiter
    if _rate_limiter is None:
        settings = get_settings()
        _rate_limiter = InMemoryRateLimiter(
            requests_per_minute=settings.RATE_LIMIT_PER_MINUTE,
            window_seconds=60.0,
        )
    return _rate_limiter


def extract_client_ip(request: Request) -> str:
    """Extract client IP from headers or connection info."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "127.0.0.1"


def verify_bearer_token(auth_header: Optional[str]) -> bool:
    """Verify that Authorization header matches expected Bearer token."""
    if not auth_header:
        return False
    parts = auth_header.split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return False
    expected_token = get_settings().TAPTRAIL_AUTH_TOKEN
    return parts[1] == expected_token


class SecurityMiddleware(BaseHTTPMiddleware):
    """Enforces authentication, rate limits, and payload size checks."""

    # Paths exempt from authentication and rate limits
    EXEMPT_PATHS: Set[str] = {
        "/api/v1/health",
        "/docs",
        "/redoc",
        "/openapi.json",
    }

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        path = request.url.path

        # Allow exempt endpoints unconditionally
        if path in self.EXEMPT_PATHS:
            return await call_next(request)

        settings = get_settings()

        # 1. Check payload size from Content-Length header
        content_length = request.headers.get("content-length")
        # Allow 2MB extra over MAX_IMAGE_SIZE_BYTES for multipart metadata
        max_allowed_payload = settings.MAX_IMAGE_SIZE_BYTES + (2 * 1024 * 1024)
        if content_length:
            try:
                length_int = int(content_length)
                if length_int > max_allowed_payload:
                    err = ErrorResponse(
                        error=f"Payload too large. Maximum allowed size is {max_allowed_payload} bytes.",
                        request_id=request.headers.get("x-request-id"),
                        retry=False,
                    )
                    return JSONResponse(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, content=err.model_dump())
            except ValueError:
                pass

        # 2. Check Bearer Token Authorization
        auth_header = request.headers.get("authorization")
        if not verify_bearer_token(auth_header):
            err = ErrorResponse(
                error="Unauthorized. Valid Bearer token required.",
                request_id=request.headers.get("x-request-id"),
                retry=False,
            )
            return JSONResponse(
                status_code=status.HTTP_401_UNAUTHORIZED,
                content=err.model_dump(),
                headers={"WWW-Authenticate": "Bearer"},
            )

        # 3. Rate limiting per IP
        client_ip = extract_client_ip(request)
        limiter = get_rate_limiter()
        if not limiter.is_allowed(client_ip):
            err = ErrorResponse(
                error="Rate limit exceeded. Please wait before retrying.",
                request_id=request.headers.get("x-request-id"),
                retry=True,
            )
            return JSONResponse(status_code=status.HTTP_429_TOO_MANY_REQUESTS, content=err.model_dump())

        return await call_next(request)
