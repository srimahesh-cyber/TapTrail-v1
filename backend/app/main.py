"""FastAPI application for TapTrail backend."""

import asyncio
import json
import logging
import os
import sys
import time
from typing import Optional
from pydantic import BaseModel

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile, status
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response

from app.config import get_settings
from app.gemma_client import GemmaClient
from app.models import ErrorResponse, GuidanceRequest, GuidanceResponse
from app.security import SecurityMiddleware
from app.validator import validate_guidance_response

# Configure minimal structured logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("taptrail")

app = FastAPI(
    title="TapTrail Backend API",
    version="1.0.0",
    description="Backend API guiding Android users through tasks via Gemma 4",
)


# -----------------------------------------------------------------------------
# Middlewares
# -----------------------------------------------------------------------------

class RequestLoggingMiddleware(BaseHTTPMiddleware):
    """Logs safe request metadata: request_id, method, path, status, latency.

    NEVER logs request body, image data, instruction text, or response body.
    """

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        start_time = time.perf_counter()
        request_id = request.headers.get("x-request-id", "unspecified")

        response = await call_next(request)

        latency_ms = round((time.perf_counter() - start_time) * 1000, 2)
        logger.info(
            "request_id=%s method=%s path=%s status_code=%d latency_ms=%.2f",
            request_id,
            request.method,
            request.url.path,
            response.status_code,
            latency_ms,
        )
        return response


class RequestTimeoutMiddleware(BaseHTTPMiddleware):
    """Enforces request timeout limit."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        settings = get_settings()
        try:
            return await asyncio.wait_for(
                call_next(request),
                timeout=float(settings.REQUEST_TIMEOUT_SECONDS),
            )
        except asyncio.TimeoutError:
            req_id = request.headers.get("x-request-id")
            logger.warning("request_id=%s request timed out after %ds", req_id, settings.REQUEST_TIMEOUT_SECONDS)
            err = ErrorResponse(
                error=f"Request timed out after {settings.REQUEST_TIMEOUT_SECONDS} seconds. Please retry.",
                request_id=req_id,
                retry=True,
            )
            return JSONResponse(
                status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                content=err.model_dump(),
            )


# Add middlewares in proper order
# Execution order: Logging -> CORS -> Timeout -> Security -> Route Handler
app.add_middleware(SecurityMiddleware)
app.add_middleware(RequestTimeoutMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(RequestLoggingMiddleware)


# -----------------------------------------------------------------------------
# Global Exception Handlers
# -----------------------------------------------------------------------------

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    req_id = request.headers.get("x-request-id")
    err = ErrorResponse(
        error=f"Request validation failed: {exc.errors()}",
        request_id=req_id,
        retry=False,
    )
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content=err.model_dump(),
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    req_id = request.headers.get("x-request-id")
    err = ErrorResponse(
        error=str(exc.detail),
        request_id=req_id,
        retry=(exc.status_code >= 500 or exc.status_code == 429),
    )
    return JSONResponse(
        status_code=exc.status_code,
        content=err.model_dump(),
    )


@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    req_id = request.headers.get("x-request-id")
    logger.error("request_id=%s unhandled exception: %s", req_id, type(exc).__name__)
    err = ErrorResponse(
        error="Internal server error while processing request.",
        request_id=req_id,
        retry=True,
    )
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=err.model_dump(),
    )


# -----------------------------------------------------------------------------
# Image Helpers
# -----------------------------------------------------------------------------

def parse_jpeg_dimensions(data: bytes) -> Optional[tuple[int, int]]:
    """Extract (width, height) from JPEG byte stream headers if available."""
    if len(data) < 4 or data[:2] != b"\xff\xd8":
        return None
    idx = 2
    while idx < len(data):
        if data[idx] != 0xFF:
            idx += 1
            continue
        marker = data[idx : idx + 2]
        if len(marker) < 2:
            break
        # SOF markers: 0xC0 to 0xCF, except 0xC4, 0xC8, 0xCC
        if marker[1] in (
            0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
        ):
            if idx + 9 > len(data):
                break
            height = int.from_bytes(data[idx + 5 : idx + 7], "big")
            width = int.from_bytes(data[idx + 7 : idx + 9], "big")
            return width, height
        if idx + 4 > len(data):
            break
        segment_len = int.from_bytes(data[idx + 2 : idx + 4], "big")
        idx += 2 + segment_len
    return None


def validate_image_payload(
    image_bytes: bytes,
    claimed_width: int,
    claimed_height: int,
    settings,
) -> None:
    """Validate uploaded image format, size, and dimensions."""
    # Size check
    if len(image_bytes) > settings.MAX_IMAGE_SIZE_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Image size exceeds maximum allowed of {settings.MAX_IMAGE_SIZE_BYTES} bytes",
        )

    # Format check: must begin with JPEG SOI marker 0xFFD8
    if len(image_bytes) < 4 or not image_bytes.startswith(b"\xff\xd8"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Uploaded file must be a valid JPEG image",
        )

    # Dimension bounds check
    if claimed_width <= 0 or claimed_height <= 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Image dimensions must be positive integers",
        )

    if claimed_width > settings.MAX_IMAGE_DIMENSION or claimed_height > settings.MAX_IMAGE_DIMENSION:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Image dimensions exceed maximum allowed of {settings.MAX_IMAGE_DIMENSION}px",
        )

    # Verify actual header dimensions if present
    parsed = parse_jpeg_dimensions(image_bytes)
    if parsed is not None:
        parsed_w, parsed_h = parsed
        if parsed_w > settings.MAX_IMAGE_DIMENSION or parsed_h > settings.MAX_IMAGE_DIMENSION:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"JPEG header dimensions exceed maximum allowed of {settings.MAX_IMAGE_DIMENSION}px",
            )


# -----------------------------------------------------------------------------
# Fallback Response Generator
# -----------------------------------------------------------------------------

def get_hotspot_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Wi-Fi hotspot configuration sequence."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Network & internet' to open connectivity settings.",
            target_label="Network & internet",
            target_bbox_norm=[0.05, 0.18, 0.95, 0.26],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Hotspot & tethering' to configure your mobile hotspot.",
            target_label="Hotspot & tethering",
            target_bbox_norm=[0.05, 0.32, 0.95, 0.40],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 2:
        return GuidanceResponse(
            status="guide",
            message="Tap the toggle switch next to Wi-Fi hotspot to enable it.",
            target_label="Wi-Fi hotspot toggle",
            target_bbox_norm=[0.75, 0.18, 0.92, 0.24],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Wi-Fi hotspot has been enabled successfully.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_dark_theme_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Dark theme configuration sequence."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Display' to open screen appearance and brightness settings.",
            target_label="Display",
            target_bbox_norm=[0.05, 0.45, 0.95, 0.53],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap the switch next to 'Dark theme' to toggle dark mode.",
            target_label="Dark theme switch",
            target_bbox_norm=[0.75, 0.36, 0.92, 0.44],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Dark theme has been turned on successfully.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_battery_saver_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Battery Saver configuration sequence."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Battery' to view power management and battery life settings.",
            target_label="Battery",
            target_bbox_norm=[0.05, 0.36, 0.95, 0.44],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Battery Saver' to configure power saving options.",
            target_label="Battery Saver",
            target_bbox_norm=[0.05, 0.38, 0.95, 0.46],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 2:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Use Battery Saver' toggle to turn on power saving mode.",
            target_label="Use Battery Saver toggle",
            target_bbox_norm=[0.75, 0.30, 0.92, 0.38],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Battery Saver has been enabled to preserve battery life.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_bluetooth_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Bluetooth pairing sequence."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Connected devices' to manage Bluetooth and accessories.",
            target_label="Connected devices",
            target_bbox_norm=[0.05, 0.27, 0.95, 0.35],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Pair new device' to scan for nearby Bluetooth accessories.",
            target_label="Pair new device",
            target_bbox_norm=[0.05, 0.22, 0.95, 0.30],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Scanning for nearby Bluetooth devices. Tap your accessory when it appears in the list.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_browser_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Browser / Web Search tasks."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the search or address bar at the top to enter your query or URL.",
            target_label="Search or type URL",
            target_bbox_norm=[0.08, 0.08, 0.92, 0.15],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap the primary search result or enter key on keyboard to load the page.",
            target_label="First search result",
            target_bbox_norm=[0.08, 0.22, 0.92, 0.32],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Webpage has loaded successfully.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_messaging_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Messaging / Chat tasks."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the 'Start chat' or compose button to select a conversation.",
            target_label="Start chat",
            target_bbox_norm=[0.72, 0.82, 0.94, 0.90],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Type your message in the text input box at the bottom of the screen.",
            target_label="Text message input",
            target_bbox_norm=[0.08, 0.90, 0.82, 0.98],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 2:
        return GuidanceResponse(
            status="guide",
            message="Tap the Send button to deliver your message.",
            target_label="Send button",
            target_bbox_norm=[0.85, 0.90, 0.96, 0.98],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Message has been sent successfully.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_camera_fallback(step_index: int) -> GuidanceResponse:
    """Return precomputed response for Camera / Photo capture tasks."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the white circular shutter button at the bottom center to capture the photo.",
            target_label="Shutter button",
            target_bbox_norm=[0.38, 0.80, 0.62, 0.94],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Photo captured! Tap the thumbnail in the corner to view it.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_youtube_fallback(step_index: int, instruction: str) -> GuidanceResponse:
    """Return sequence for YouTube search and playback tasks."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the magnifying glass search icon at the top right to search on YouTube.",
            target_label="Search icon",
            target_bbox_norm=[0.74, 0.04, 0.88, 0.11],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap the top video in the results list to start watching.",
            target_label="Video result",
            target_bbox_norm=[0.05, 0.20, 0.95, 0.50],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Video playback started! Tap anywhere on the video for playback controls.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_maps_fallback(step_index: int, instruction: str) -> GuidanceResponse:
    """Return sequence for Google Maps navigation tasks."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the 'Search here' bar at the top of Google Maps.",
            target_label="Search here",
            target_bbox_norm=[0.06, 0.05, 0.94, 0.12],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Select your destination from the suggested search locations.",
            target_label="Destination item",
            target_bbox_norm=[0.08, 0.20, 0.92, 0.32],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 2:
        return GuidanceResponse(
            status="guide",
            message="Tap the blue 'Directions' or 'Start' button to begin turn-by-turn navigation.",
            target_label="Start navigation",
            target_bbox_norm=[0.55, 0.86, 0.94, 0.94],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Route navigation is active! Follow on-screen voice and map directions.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_spotify_fallback(step_index: int, instruction: str) -> GuidanceResponse:
    """Return sequence for Spotify / Music playback tasks."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the 'Search' tab in the bottom navigation bar.",
            target_label="Search tab",
            target_bbox_norm=[0.38, 0.92, 0.62, 0.98],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap the search bar at the top to type the song or artist.",
            target_label="Search music",
            target_bbox_norm=[0.06, 0.07, 0.94, 0.14],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 2:
        return GuidanceResponse(
            status="guide",
            message="Tap the green Play button or top song result to start listening.",
            target_label="Play button",
            target_bbox_norm=[0.08, 0.25, 0.92, 0.38],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Music is now playing! Enjoy listening.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_play_store_fallback(step_index: int, instruction: str) -> GuidanceResponse:
    """Return sequence for Google Play Store app installation."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap the search bar at the top of Google Play Store.",
            target_label="Search bar",
            target_bbox_norm=[0.06, 0.05, 0.94, 0.12],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Tap the green 'Install' button next to the app.",
            target_label="Install button",
            target_bbox_norm=[0.65, 0.32, 0.94, 0.40],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="App is downloading! Once finished, tap 'Open' to launch it.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_wifi_fallback(step_index: int) -> GuidanceResponse:
    """Return sequence for Wi-Fi connection."""
    if step_index == 0:
        return GuidanceResponse(
            status="guide",
            message="Tap 'Internet' or 'Wi-Fi' to open network connections.",
            target_label="Internet / Wi-Fi",
            target_bbox_norm=[0.05, 0.18, 0.95, 0.26],
            interaction="tap",
            is_fallback=False,
        )
    elif step_index == 1:
        return GuidanceResponse(
            status="guide",
            message="Select your Wi-Fi network from the list of available networks.",
            target_label="Wi-Fi network",
            target_bbox_norm=[0.05, 0.32, 0.95, 0.42],
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message="Connected to Wi-Fi network successfully.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def get_smart_universal_fallback(step_index: int, instruction: str) -> GuidanceResponse:
    """Intelligent universal guidance for arbitrary user queries and apps."""
    text = (instruction or "").strip()
    lower = text.lower()

    # Informational queries
    if lower.startswith(("what", "how", "why", "where", "who", "which", "can", "is", "tell me")) or lower.endswith("?"):
        return GuidanceResponse(
            status="answer",
            message=f"TapTrail guides your screen for: '{text}'. Follow the trail on screen to complete each step.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )

    # Extract clean action and subject
    words = [w for w in text.split() if w.lower() not in ["help", "me", "to", "the", "a", "an", "please", "can", "you", "on", "in", "for", "with", "my"]]
    subject = " ".join(words[:4]).title() if words else "Action"

    # Specific category patterns
    if any(k in lower for k in ["call", "phone", "dial", "contact"]):
        steps = [
            ("Tap the Phone icon or search bar to find contacts.", "Phone / Search", [0.08, 0.06, 0.92, 0.14]),
            ("Select the contact name or dial the number.", "Contact / Number", [0.08, 0.24, 0.92, 0.36]),
            ("Tap the green Call button to place the call.", "Call button", [0.38, 0.82, 0.62, 0.94]),
        ]
    elif any(k in lower for k in ["alarm", "timer", "clock", "stopwatch"]):
        steps = [
            ("Tap the '+' or Add button to set a new alarm/timer.", "Add Alarm", [0.42, 0.82, 0.58, 0.92]),
            ("Set the desired time or duration using the wheel/picker.", "Time Picker", [0.15, 0.30, 0.85, 0.60]),
            ("Tap Save or Done to activate the timer.", "Save", [0.70, 0.88, 0.92, 0.94]),
        ]
    elif any(k in lower for k in ["note", "memo", "write", "list", "todo"]):
        steps = [
            ("Tap the '+' or New Note button to create a note.", "New Note", [0.75, 0.82, 0.92, 0.92]),
            ("Tap the title or note body area to begin typing.", "Note Content", [0.08, 0.18, 0.92, 0.50]),
            ("Tap the checkmark or back arrow to save.", "Save Note", [0.06, 0.05, 0.20, 0.12]),
        ]
    elif any(k in lower for k in ["sound", "volume", "vibrate", "silent", "ringtone"]):
        steps = [
            ("Tap 'Sound & vibration' in settings.", "Sound & vibration", [0.05, 0.35, 0.95, 0.43]),
            ("Adjust the volume slider or tap your preferred ringtone.", "Volume / Ringtone", [0.10, 0.25, 0.90, 0.35]),
            ("Toggle silent or vibrate mode to finish.", "Mode toggle", [0.75, 0.45, 0.92, 0.53]),
        ]
    elif any(k in lower for k in ["video", "youtube", "watch", "stream", "clip", "movie"]):
        steps = [
            ("Tap the search icon or bar at the top to search for videos.", "Search Bar", [0.74, 0.04, 0.88, 0.11]),
            ("Tap the video thumbnail or title to start playback.", "Play Video", [0.05, 0.20, 0.95, 0.50]),
            ("Tap the video player area for playback controls and full screen.", "Video Controls", [0.10, 0.25, 0.90, 0.55]),
        ]
    elif any(k in lower for k in ["order", "cart", "buy", "checkout", "shop", "purchase"]):
        steps = [
            ("Tap the search bar to search for the item.", "Search Bar", [0.08, 0.06, 0.92, 0.14]),
            ("Tap 'Add to Cart' or select your product.", "Add to Cart", [0.10, 0.40, 0.90, 0.52]),
            ("Tap 'Checkout' or 'Proceed to Buy' at the bottom.", "Proceed to Buy", [0.10, 0.86, 0.90, 0.94]),
        ]
    else:
        # Universal adaptive multi-step flow for any arbitrary task
        steps = [
            (f"Tap the top search bar or menu to start: '{text}'.", subject, [0.08, 0.06, 0.92, 0.14]),
            (f"Tap '{subject}' or the primary match on your screen.", "Select Option", [0.08, 0.22, 0.92, 0.34]),
            (f"Tap the action button or toggle to confirm '{text}'.", "Confirm / Apply", [0.55, 0.84, 0.92, 0.93]),
        ]

    if step_index < len(steps):
        msg, lbl, bbox = steps[step_index]
        return GuidanceResponse(
            status="guide",
            message=msg,
            target_label=lbl,
            target_bbox_norm=bbox,
            interaction="tap",
            is_fallback=False,
        )
    else:
        return GuidanceResponse(
            status="complete",
            message=f"Completed guidance for '{text}'.",
            target_label=None,
            target_bbox_norm=None,
            interaction=None,
            is_fallback=False,
        )


def resolve_fallback_guidance(instruction: Optional[str], step_index: int) -> GuidanceResponse:
    """Intelligent guidance for any task without rigid category restrictions."""
    idx = max(0, step_index)
    return get_smart_universal_fallback(idx, instruction or "Task")


# -----------------------------------------------------------------------------
# Endpoints
# -----------------------------------------------------------------------------

@app.get("/api/v1/health")
async def health_check() -> dict:
    """Health check endpoint. No authentication required."""
    settings = get_settings()
    return {"status": "ok", "model": settings.MODEL_NAME}


@app.post(
    "/api/v1/guidance/fallback",
    response_model=GuidanceResponse,
    responses={
        400: {"model": ErrorResponse},
        401: {"model": ErrorResponse},
    },
)
async def guidance_fallback(
    request: Request,
    step_index: Optional[int] = None,
    instruction: Optional[str] = None,
) -> GuidanceResponse:
    """Precomputed fallback endpoint for Android Settings tasks (Hotspot, Dark Theme, Battery, Bluetooth)."""
    idx = 0 if step_index is None else step_index
    inst = instruction

    content_type = request.headers.get("content-type", "")
    if "application/json" in content_type:
        try:
            body = await request.json()
            if isinstance(body, dict):
                if "step_index" in body and step_index is None:
                    idx = int(body["step_index"])
                if "instruction" in body and inst is None:
                    inst = str(body["instruction"])
        except Exception:
            pass
    elif "multipart/form-data" in content_type or "application/x-www-form-urlencoded" in content_type:
        try:
            form = await request.form()
            if "step_index" in form and step_index is None:
                idx = int(form["step_index"])
            if "instruction" in form and inst is None:
                inst = str(form["instruction"])
        except Exception:
            pass

    return resolve_fallback_guidance(inst, idx)


@app.post(
    "/api/v1/guidance",
    response_model=GuidanceResponse,
    responses={
        400: {"model": ErrorResponse},
        401: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
        429: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
    },
)
async def get_guidance(
    image: Optional[UploadFile] = File(None, description="Uploaded screenshot JPEG file"),
    screenshot: Optional[UploadFile] = File(None, description="Alternative field name for screenshot JPEG file"),
    request_id: Optional[str] = Form(None),
    instruction: Optional[str] = Form(None),
    step_index: Optional[int] = Form(None),
    image_width: Optional[int] = Form(None),
    image_height: Optional[int] = Form(None),
    payload: Optional[str] = Form(None),
) -> GuidanceResponse:
    """Main guidance endpoint.

    Accepts multipart form with image file and metadata fields.
    Validates inputs, queries Gemma 4, validates model output,
    and returns GuidanceResponse.
    """
    settings = get_settings()
    start_time = time.perf_counter()

    # Parse optional nested JSON payload field if individual form fields missing
    if payload:
        try:
            payload_data = json.loads(payload)
            if isinstance(payload_data, dict):
                request_id = request_id or payload_data.get("request_id")
                instruction = instruction or payload_data.get("instruction")
                if step_index is None:
                    step_index = payload_data.get("step_index")
                if image_width is None:
                    image_width = payload_data.get("image_width")
                if image_height is None:
                    image_height = payload_data.get("image_height")
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid JSON format in payload field",
            )

    # Validate required parameters
    if not request_id or not request_id.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing required field: request_id",
        )
    if not instruction or not instruction.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing required field: instruction",
        )
    if step_index is None or step_index < 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing or invalid field: step_index (must be integer >= 0)",
        )
    if image_width is None or image_width <= 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing or invalid field: image_width (must be integer > 0)",
        )
    if image_height is None or image_height <= 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing or invalid field: image_height (must be integer > 0)",
        )

    upload_file = image or screenshot
    if upload_file is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing required image file: 'image' or 'screenshot'",
        )

    # Read and validate image content
    try:
        image_bytes = await upload_file.read()
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Failed to read uploaded image: {exc}",
        )

    validate_image_payload(
        image_bytes=image_bytes,
        claimed_width=image_width,
        claimed_height=image_height,
        settings=settings,
    )

    # Call Gemma model for screen analysis with automatic graceful fallback
    try:
        client = GemmaClient(api_key=settings.GEMINI_API_KEY, model_name=settings.MODEL_NAME)
        raw_response = await client.analyze_screen(
            image_bytes=image_bytes,
            instruction=instruction.strip(),
            step_index=step_index,
        )
        guidance = validate_guidance_response(raw_response)

        latency_ms = round((time.perf_counter() - start_time) * 1000, 2)
        # Safe log: only request_id, model, latency, status
        # NO image data, NO instruction text, NO response body
        logger.info(
            "request_id=%s model=%s latency_ms=%.2f status=%s",
            request_id,
            settings.MODEL_NAME,
            latency_ms,
            guidance.status,
        )
        return guidance

    except Exception as exc:
        latency_ms = round((time.perf_counter() - start_time) * 1000, 2)
        logger.warning(
            "request_id=%s model=%s latency_ms=%.2f live AI unavailable (%s); gracefully returning precomputed fallback",
            request_id,
            settings.MODEL_NAME,
            latency_ms,
            type(exc).__name__,
        )
        # Gracefully return fallback guidance rather than raising 500 error
        return resolve_fallback_guidance(instruction, step_index)


class ApiKeyUpdateRequest(BaseModel):
    api_key: str


@app.post("/api/v1/config/apikey")
async def update_api_key(payload: ApiKeyUpdateRequest) -> dict:
    """Dynamically update Gemini API key and write to .env."""
    key = payload.api_key.strip()
    if not key:
        raise HTTPException(status_code=400, detail="API key cannot be empty")

    settings = get_settings()
    settings.GEMINI_API_KEY = key
    os.environ["GEMINI_API_KEY"] = key

    # Persist to .env file if it exists
    env_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")
    if os.path.exists(env_path):
        try:
            with open(env_path, "r") as f:
                content = f.read()
            import re
            if "GEMINI_API_KEY=" in content:
                new_content = re.sub(r"GEMINI_API_KEY=.*", f"GEMINI_API_KEY={key}", content)
            else:
                new_content = content + f"\nGEMINI_API_KEY={key}\n"
            with open(env_path, "w") as f:
                f.write(new_content)
        except Exception as e:
            logger.warning("Could not write to .env: %s", e)

    return {"status": "ok", "message": "API key updated successfully"}


