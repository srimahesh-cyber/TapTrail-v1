"""Response parsing and validation for model outputs."""

import json
import logging
import re
from typing import Any, List, Optional
from pydantic import ValidationError

from app.models import GuidanceResponse

logger = logging.getLogger(__name__)

# Minimum and maximum geometric limits for normalized bounding boxes
MIN_BBOX_DIMENSION = 0.005  # Elements must be at least 0.5% screen width/height
MIN_BBOX_AREA = 0.0001      # Impossibly small tap target
MAX_BBOX_AREA = 0.95        # Impossibly large (almost entire screen)


def get_safe_clarification(message: Optional[str] = None) -> GuidanceResponse:
    """Return a safe fallback clarification response when validation fails."""
    default_msg = (
        "I could not clearly determine the next step from this screen. "
        "Please ensure the target app or setting is visible and try again."
    )
    return GuidanceResponse(
        status="clarify",
        message=message or default_msg,
        target_label=None,
        target_bbox_norm=None,
        interaction=None,
        is_fallback=False,
    )


def extract_json_from_text(raw_text: str) -> dict:
    """Extract and parse JSON from model output, handling markdown blocks."""
    if not raw_text or not isinstance(raw_text, str):
        raise ValueError("Raw text is empty or not a string")

    cleaned = raw_text.strip()

    # Case 1: Markdown code block ```json ... ``` or ``` ... ```
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", cleaned, re.DOTALL)
    if match:
        candidate = match.group(1).strip()
        return json.loads(candidate)

    # Case 2: Direct JSON object string or embedded between braces
    first_brace = cleaned.find("{")
    last_brace = cleaned.rfind("}")
    if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
        candidate = cleaned[first_brace : last_brace + 1].strip()
        return json.loads(candidate)

    # Fallback to direct json loads
    return json.loads(cleaned)


def validate_bbox_norm(bbox: Any) -> List[float]:
    """Validate normalized bounding box coordinates [x1, y1, x2, y2]."""
    if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
        raise ValueError("Bounding box must be a list of 4 numbers [x1, y1, x2, y2]")

    try:
        x1, y1, x2, y2 = [float(v) for v in bbox]
    except (ValueError, TypeError) as exc:
        raise ValueError("Bounding box coordinates must be numeric") from exc

    # Validate coordinate bounds [0.0, 1.0]
    for name, val in [("x1", x1), ("y1", y1), ("x2", x2), ("y2", y2)]:
        if not (0.0 <= val <= 1.0):
            raise ValueError(f"Coordinate {name}={val} is outside [0.0, 1.0] range")

    # Validate geometric ordering (x1 < x2 and y1 < y2)
    if x1 >= x2:
        raise ValueError(f"Invalid horizontal ordering: x1 ({x1}) >= x2 ({x2})")
    if y1 >= y2:
        raise ValueError(f"Invalid vertical ordering: y1 ({y1}) >= y2 ({y2})")

    # Validate area and dimensions
    width = x2 - x1
    height = y2 - y1
    area = width * height

    if width < MIN_BBOX_DIMENSION or height < MIN_BBOX_DIMENSION or area < MIN_BBOX_AREA:
        raise ValueError(f"Bounding box area {area:.6f} is impossibly small")

    if area > MAX_BBOX_AREA:
        raise ValueError(f"Bounding box area {area:.4f} is impossibly large")

    return [x1, y1, x2, y2]


def validate_guidance_response(raw_output: Any) -> GuidanceResponse:
    """Parse and strictly validate raw model output into GuidanceResponse.

    Never passes unvalidated model output to the client. Returns a safe
    clarification response if parsing or validation fails.
    """
    try:
        if isinstance(raw_output, dict):
            data = raw_output
        elif isinstance(raw_output, str):
            data = extract_json_from_text(raw_output)
        else:
            raise ValueError("Output is neither string nor dict")

        # Validate status field
        status = data.get("status")
        valid_statuses = {"answer", "guide", "clarify", "complete"}
        if status not in valid_statuses:
            raise ValueError(f"Invalid status '{status}'. Must be one of {valid_statuses}")

        # Validate message
        message = data.get("message")
        if not message or not isinstance(message, str) or not message.strip():
            raise ValueError("Message must be a non-empty string")

        target_bbox_norm: Optional[List[float]] = None
        raw_bbox = data.get("target_bbox_norm")

        # Status 'guide' requires valid bbox and interaction
        if status == "guide":
            if raw_bbox is None:
                raise ValueError("Status 'guide' requires target_bbox_norm")
            target_bbox_norm = validate_bbox_norm(raw_bbox)

            interaction = data.get("interaction")
            valid_interactions = {"tap", "swipe", "long_press"}
            if interaction not in valid_interactions:
                raise ValueError(
                    f"Interaction '{interaction}' invalid for guide. Must be one of {valid_interactions}"
                )
        else:
            # For non-guide, validate bbox if provided, otherwise leave as None
            if raw_bbox is not None:
                target_bbox_norm = validate_bbox_norm(raw_bbox)

        # Validate final schema through Pydantic
        response = GuidanceResponse(
            status=status,
            message=message.strip(),
            target_label=data.get("target_label"),
            target_bbox_norm=target_bbox_norm,
            interaction=data.get("interaction"),
            is_fallback=bool(data.get("is_fallback", False)),
        )
        return response

    except (ValueError, TypeError, json.JSONDecodeError, ValidationError) as exc:
        logger.warning("Response parsing/validation failed: %s", type(exc).__name__)
        return get_safe_clarification()
    except Exception as exc:
        logger.error("Unexpected error validating response: %s", type(exc).__name__)
        return get_safe_clarification()
