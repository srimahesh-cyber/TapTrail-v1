"""Unit tests for response parsing and validation."""

import pytest
from app.validator import (
    extract_json_from_text,
    validate_bbox_norm,
    validate_guidance_response,
)


class TestExtractJson:
    """Tests for extract_json_from_text."""

    def test_plain_json(self):
        raw = '{"status": "complete", "message": "Task complete."}'
        result = extract_json_from_text(raw)
        assert result["status"] == "complete"
        assert result["message"] == "Task complete."

    def test_json_in_markdown_code_block(self):
        raw = """Here is the guidance:
```json
{
  "status": "guide",
  "message": "Tap the Settings icon.",
  "target_label": "Settings",
  "target_bbox_norm": [0.1, 0.2, 0.4, 0.5],
  "interaction": "tap"
}
```
Hope this helps!"""
        result = extract_json_from_text(raw)
        assert result["status"] == "guide"
        assert result["target_label"] == "Settings"
        assert result["target_bbox_norm"] == [0.1, 0.2, 0.4, 0.5]

    def test_json_in_generic_markdown_code_block(self):
        raw = """```
{"status": "answer", "message": "Direct answer here."}
```"""
        result = extract_json_from_text(raw)
        assert result["status"] == "answer"

    def test_invalid_json_raises(self):
        raw = "This is just text with no json"
        with pytest.raises(Exception):
            extract_json_from_text(raw)


class TestValidateBboxNorm:
    """Tests for validate_bbox_norm."""

    def test_valid_bbox(self):
        bbox = [0.1, 0.2, 0.8, 0.9]
        res = validate_bbox_norm(bbox)
        assert res == [0.1, 0.2, 0.8, 0.9]

    @pytest.mark.parametrize(
        "invalid_bbox",
        [
            [-0.1, 0.2, 0.8, 0.9],  # x1 < 0
            [0.1, -0.2, 0.8, 0.9],  # y1 < 0
            [0.1, 0.2, 1.1, 0.9],   # x2 > 1
            [0.1, 0.2, 0.8, 1.5],   # y2 > 1
            [0.8, 0.2, 0.1, 0.9],   # x1 > x2 (reversed horizontal)
            [0.1, 0.9, 0.8, 0.2],   # y1 > y2 (reversed vertical)
            [0.5, 0.2, 0.5, 0.9],   # x1 == x2 (zero width)
            [0.1, 0.5, 0.8, 0.5],   # y1 == y2 (zero height)
            [0.1, 0.2, 0.1001, 0.2001],  # Impossibly small area
            [0.001, 0.001, 0.999, 0.999],  # Impossibly large area (> 0.95)
            [0.1, 0.2, 0.3],        # Length != 4
            "not a list",           # Wrong type
            [0.1, "bad", 0.3, 0.4], # Non-numeric
        ],
    )
    def test_invalid_bboxes(self, invalid_bbox):
        with pytest.raises(ValueError):
            validate_bbox_norm(invalid_bbox)


class TestValidateGuidanceResponse:
    """Tests for top-level validate_guidance_response function."""

    def test_valid_guide_response_from_dict(self):
        data = {
            "status": "guide",
            "message": "Tap 'Network & internet'",
            "target_label": "Network & internet",
            "target_bbox_norm": [0.05, 0.18, 0.95, 0.26],
            "interaction": "tap",
        }
        res = validate_guidance_response(data)
        assert res.status == "guide"
        assert res.message == "Tap 'Network & internet'"
        assert res.target_label == "Network & internet"
        assert res.target_bbox_norm == [0.05, 0.18, 0.95, 0.26]
        assert res.interaction == "tap"
        assert res.is_fallback is False

    def test_valid_guide_response_from_markdown_string(self):
        raw = """```json
{
  "status": "guide",
  "message": "Tap 'Hotspot & tethering'",
  "target_label": "Hotspot & tethering",
  "target_bbox_norm": [0.05, 0.32, 0.95, 0.40],
  "interaction": "tap"
}
```"""
        res = validate_guidance_response(raw)
        assert res.status == "guide"
        assert res.target_label == "Hotspot & tethering"
        assert res.interaction == "tap"

    def test_valid_answer_response(self):
        raw = '{"status": "answer", "message": "Your battery level is currently 85%."}'
        res = validate_guidance_response(raw)
        assert res.status == "answer"
        assert res.message == "Your battery level is currently 85%."
        assert res.target_bbox_norm is None

    def test_valid_complete_response(self):
        raw = '{"status": "complete", "message": "Wi-Fi hotspot is now enabled."}'
        res = validate_guidance_response(raw)
        assert res.status == "complete"
        assert res.message == "Wi-Fi hotspot is now enabled."

    def test_invalid_json_returns_safe_clarification(self):
        raw = "Malformed response from AI model without json"
        res = validate_guidance_response(raw)
        assert res.status == "clarify"
        assert "trouble" in res.message or "clearly" in res.message
        assert res.target_bbox_norm is None

    def test_invalid_status_returns_safe_clarification(self):
        raw = '{"status": "unsupported_action", "message": "Do this now"}'
        res = validate_guidance_response(raw)
        assert res.status == "clarify"

    def test_guide_missing_bbox_returns_safe_clarification(self):
        # A guide status without bbox must fail validation and return safe clarify
        raw = '{"status": "guide", "message": "Tap next", "interaction": "tap"}'
        res = validate_guidance_response(raw)
        assert res.status == "clarify"

    def test_out_of_bounds_bbox_returns_safe_clarification(self):
        raw = {
            "status": "guide",
            "message": "Tap here",
            "target_bbox_norm": [1.5, 0.1, 0.9, 0.8],  # x1 > 1.0
            "interaction": "tap",
        }
        res = validate_guidance_response(raw)
        assert res.status == "clarify"

    def test_missing_message_returns_safe_clarification(self):
        raw = '{"status": "complete", "message": ""}'
        res = validate_guidance_response(raw)
        assert res.status == "clarify"

    def test_empty_input_returns_safe_clarification(self):
        assert validate_guidance_response("").status == "clarify"
        assert validate_guidance_response(None).status == "clarify"
