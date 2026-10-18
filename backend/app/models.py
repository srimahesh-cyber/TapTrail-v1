"""Pydantic models for request and response contracts."""

from typing import List, Literal, Optional
from pydantic import BaseModel, ConfigDict, Field


class GuidanceRequest(BaseModel):
    """Incoming request payload contract for screen guidance."""

    request_id: str = Field(
        ...,
        min_length=1,
        description="Unique identifier for the request",
    )
    instruction: str = Field(
        ...,
        min_length=1,
        description="User's task description or question",
    )
    step_index: int = Field(
        ...,
        ge=0,
        description="Zero-based step index of the current task sequence",
    )
    image: Optional[bytes] = Field(
        default=None,
        description="Raw image bytes (JPEG format)",
    )
    image_width: int = Field(
        ...,
        gt=0,
        description="Screenshot width in pixels",
    )
    image_height: int = Field(
        ...,
        gt=0,
        description="Screenshot height in pixels",
    )

    model_config = ConfigDict(arbitrary_types_allowed=True)


class GuidanceResponse(BaseModel):
    """Outgoing guidance response contract returned to the Android client."""

    status: Literal["answer", "guide", "clarify", "complete"] = Field(
        ...,
        description="Status type of guidance instruction",
    )
    message: str = Field(
        ...,
        description="The instruction or answer text for the user",
    )
    target_label: Optional[str] = Field(
        default=None,
        description="Label or name of the target UI element",
    )
    target_bbox_norm: Optional[List[float]] = Field(
        default=None,
        description="Normalized bounding box [x1, y1, x2, y2] between 0.0 and 1.0",
    )
    interaction: Optional[Literal["tap", "swipe", "long_press"]] = Field(
        default=None,
        description="Interaction type to perform on target element",
    )
    is_fallback: bool = Field(
        default=False,
        description="Flag indicating whether a precomputed fallback response was used",
    )


class ErrorResponse(BaseModel):
    """Standard error response contract."""

    error: str = Field(
        ...,
        description="Human-readable error description",
    )
    request_id: Optional[str] = Field(
        default=None,
        description="Request identifier if known",
    )
    retry: bool = Field(
        default=True,
        description="Indicates whether client should retry the request",
    )


class FallbackRequest(BaseModel):
    """Request payload for precomputed fallback endpoint."""

    step_index: int = Field(
        default=0,
        ge=0,
        description="Current step index for the precomputed sequence",
    )
    request_id: Optional[str] = Field(
        default=None,
        description="Optional request identifier",
    )
