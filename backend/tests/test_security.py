"""Unit tests for security middleware and rate limiter."""

import pytest
from app.security import InMemoryRateLimiter, verify_bearer_token


class TestRateLimiter:
    """Tests for InMemoryRateLimiter."""

    def test_allows_under_limit(self):
        limiter = InMemoryRateLimiter(requests_per_minute=3, window_seconds=60.0)
        assert limiter.is_allowed("192.168.1.1") is True
        assert limiter.is_allowed("192.168.1.1") is True
        assert limiter.is_allowed("192.168.1.1") is True

    def test_blocks_over_limit(self):
        limiter = InMemoryRateLimiter(requests_per_minute=2, window_seconds=60.0)
        assert limiter.is_allowed("192.168.1.2") is True
        assert limiter.is_allowed("192.168.1.2") is True
        assert limiter.is_allowed("192.168.1.2") is False

    def test_separate_ips(self):
        limiter = InMemoryRateLimiter(requests_per_minute=1, window_seconds=60.0)
        assert limiter.is_allowed("10.0.0.1") is True
        assert limiter.is_allowed("10.0.0.2") is True
        assert limiter.is_allowed("10.0.0.1") is False
        assert limiter.is_allowed("10.0.0.2") is False


class TestVerifyBearerToken:
    """Tests for bearer token verification."""

    def test_missing_or_malformed_header(self):
        assert verify_bearer_token(None) is False
        assert verify_bearer_token("") is False
        assert verify_bearer_token("Basic token123") is False
        assert verify_bearer_token("Bearer") is False
        assert verify_bearer_token("Bearer token extra") is False

    def test_matching_and_non_matching_token(self, monkeypatch):
        monkeypatch.setenv("TAPTRAIL_AUTH_TOKEN", "secret123")
        from app.config import get_settings
        get_settings.cache_clear()

        assert verify_bearer_token("Bearer secret123") is True
        assert verify_bearer_token("Bearer wrongsecret") is False

        get_settings.cache_clear()
