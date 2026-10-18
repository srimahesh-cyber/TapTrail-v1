"""Unit tests for Pydantic models."""

import pytest
from pydantic import ValidationError

from app.models import ErrorResponse, FallbackRequest, GuidanceRequest, GuidanceResponse


class TestGuidanceRequest:
    """Tests for GuidanceRequest model."""

    def test_valid_guidance_request(self):
        req = GuidanceRequest(
            request_id="req-1234",
            instruction="Turn on hotspot",
            step_index=0,
            image=b"\xff\xd8\xfffakeimage",
            image_width=1080,
            image_height=2400,
        )
        assert req.request_id == "req-1234"
        assert req.instruction == "Turn on hotspot"
        assert req.step_index == 0
        assert req.image_width == 1080
        assert req.image_height == 2400
        assert req.image == b"\xff\xd8\xfffakeimage"

    def test_missing_required_fields(self):
        with pytest.raises(ValidationError):
            GuidanceRequest(
                request_id="req-1",
                # missing instruction
                step_index=0,
                image_width=1080,
                image_height=1920,
            )

    def test_invalid_negative_step_index(self):
        with pytest.raises(ValidationError):
            GuidanceRequest(
                request_id="req-1",
                instruction="Turn on hotspot",
                step_index=-1,
                image_width=1080,
                image_height=1920,
            )

    def test_invalid_zero_or_negative_dimensions(self):
        with pytest.raises(ValidationError):
            GuidanceRequest(
                request_id="req-1",
                instruction="Turn on hotspot",
                step_index=0,
                image_width=0,
                image_height=1920,
            )
        with pytest.raises(ValidationError):
            GuidanceRequest(
                request_id="req-1",
                instruction="Turn on hotspot",
                step_index=0,
                image_width=1080,
                image_height=-10,
            )

    def test_empty_request_id_or_instruction(self):
        with pytest.raises(ValidationError):
            GuidanceRequest(
                request_id="",
                instruction="Turn on hotspot",
                step_index=0,
                image_width=1080,
                image_height=1920,
            )
        with pytest.raises(ValidationError):
            GuidanceRequest(
                request_id="req-1",
                instruction="",
                step_index=0,
                image_width=1080,
                image_height=1920,
            )


class TestGuidanceResponse:
    """Tests for GuidanceResponse model."""

    def test_valid_guide_response(self):
        resp = GuidanceResponse(
            status="guide",
            message="Tap Network & internet",
            target_label="Network & internet",
            target_bbox_norm=[0.1, 0.2, 0.9, 0.3],
            interaction="tap",
            is_fallback=False,
        )
        assert resp.status == "guide"
        assert resp.target_label == "Network & internet"
        assert resp.target_bbox_norm == [0.1, 0.2, 0.9, 0.3]
        assert resp.interaction == "tap"
        assert resp.is_fallback is False

    @pytest.mark.parametrize("status_val", ["answer", "guide", "clarify", "complete"])
    def test_all_valid_statuses(self, status_val):
        resp = GuidanceResponse(status=status_val, message="Some message")
        assert resp.status == status_val
        assert resp.is_fallback is False

    def test_invalid_status(self):
        with pytest.raises(ValidationError):
            GuidanceResponse(status="unknown_status", message="Invalid status")

    @pytest.mark.parametrize("interaction_val", ["tap", "swipe", "long_press"])
    def test_valid_interactions(self, interaction_val):
        resp = GuidanceResponse(
            status="guide",
            message="Perform interaction",
            interaction=interaction_val,
        )
        assert resp.interaction == interaction_val

    def test_invalid_interaction(self):
        with pytest.raises(ValidationError):
            GuidanceResponse(
                status="guide",
                message="Perform action",
                interaction="double_tap",
            )


class TestErrorResponse:
    """Tests for ErrorResponse model."""

    def test_default_error_response(self):
        err = ErrorResponse(error="Something went wrong")
        assert err.error == "Something went wrong"
        assert err.request_id is None
        assert err.retry is True

    def test_custom_error_response(self):
        err = ErrorResponse(
            error="Unauthorized token",
            request_id="req-999",
            retry=False,
        )
        assert err.error == "Unauthorized token"
        assert err.request_id == "req-999"
        assert err.retry is False


class TestFallbackRequest:
    """Tests for FallbackRequest model."""

    def test_defaults(self):
        req = FallbackRequest()
        assert req.step_index == 0
        assert req.request_id is None

    def test_custom_step(self):
        req = FallbackRequest(step_index=2, request_id="custom-req")
        assert req.step_index == 2
        assert req.request_id == "custom-req"

    def test_negative_step_rejected(self):
        with pytest.raises(ValidationError):
            FallbackRequest(step_index=-1)
