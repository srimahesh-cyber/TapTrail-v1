"""Gemma 4 client integration using google-genai SDK."""

import asyncio
import logging
from google import genai
from google.genai import types

logger = logging.getLogger(__name__)


class GemmaClient:
    """Client wrapper for Google Gemma multimodal models."""

    def __init__(self, api_key: str, model_name: str):
        self.client = genai.Client(api_key=api_key)
        self.model_name = model_name

    def _downsample_image(self, image_bytes: bytes, max_dim: int = 720) -> bytes:
        """Downsample image to reduce vision token consumption significantly."""
        try:
            from io import BytesIO
            from PIL import Image

            with Image.open(BytesIO(image_bytes)) as img:
                w, h = img.size
                if max(w, h) > max_dim:
                    scale = max_dim / float(max(w, h))
                    new_size = (int(w * scale), int(h * scale))
                    resized = img.resize(new_size, Image.Resampling.LANCZOS)
                    out = BytesIO()
                    resized.convert("RGB").save(out, format="JPEG", quality=80)
                    return out.getvalue()
        except Exception as e:
            logger.debug("Image downsampling skipped: %s", e)
        return image_bytes

    def _build_prompt(self, instruction: str, step_index: int) -> str:
        """Construct a token-efficient prompt for screen guidance."""
        return f"""TapTrail Android Guide.
Task: {instruction} (Step {step_index})
Locate the next UI element on this screen.

Output strictly valid JSON with no markdown:
{{
  "status": "guide" | "answer" | "clarify" | "complete",
  "message": "1 short instruction sentence",
  "target_label": "Label or null",
  "target_bbox_norm": [x1, y1, x2, y2] or null,
  "interaction": "tap" | "swipe" | "long_press" | null
}}
Bounding box values must be normalized floats between 0.0 and 1.0."""

    CANDIDATE_MODELS = [
        "gemini-2.5-flash-image",
        "gemini-3.1-flash-image",
        "gemini-3.8-flash",
        "gemini-pro-latest",
        "gemini-3.1-pro-preview",
        "gemini-4-argon",
        "gemma-4-26b-a4b-it",
    ]

    def _generate_content_sync(self, image_bytes: bytes, prompt: str) -> str:
        """Execute synchronous model call with token-optimized payload."""
        processed_bytes = self._downsample_image(image_bytes)
        image_part = types.Part.from_bytes(
            data=processed_bytes,
            mime_type="image/jpeg",
        )

        models_to_try = [self.model_name] + [m for m in self.CANDIDATE_MODELS if m != self.model_name]
        last_error = None

        for model in models_to_try:
            try:
                response = self.client.models.generate_content(
                    model=model,
                    contents=[
                        types.Content(
                            parts=[
                                image_part,
                                types.Part.from_text(text=prompt),
                            ]
                        )
                    ],
                    config=types.GenerateContentConfig(
                        temperature=0.1,
                        max_output_tokens=180,
                    ),
                )
                if response.text and response.text.strip():
                    if model != self.model_name:
                        logger.info("Successfully connected to AI model: %s", model)
                        self.model_name = model
                    return response.text
            except Exception as e:
                last_error = e
                logger.debug("Model %s failed: %s", model, e)
                continue

        if last_error:
            raise last_error
        return ""

    async def analyze_screen(
        self,
        image_bytes: bytes,
        instruction: str,
        step_index: int,
    ) -> str:
        """Send screenshot and instruction to Gemma for analysis.

        The generate_content call is synchronous in google-genai; wrapped in
        asyncio.to_thread() for async execution in FastAPI.
        """
        prompt = self._build_prompt(instruction, step_index)

        # Offload synchronous SDK call to thread pool
        raw_text = await asyncio.to_thread(
            self._generate_content_sync,
            image_bytes,
            prompt,
        )
        return raw_text
