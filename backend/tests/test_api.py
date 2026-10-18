"""Integration tests for FastAPI endpoints."""

from unittest.mock import AsyncMock, patch
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app
from app.security import get_rate_limiter

MINIMAL_JPEG = (
    b"\xff\xd8"
    b"\xff\xc0\x00\x11\x08\x04\x38\x07\x80\x03\x01\x22\x00\x02\x11\x01\x03\x11\x01"
    b"\xff\xd9"
)


@pytest.fixture(autouse=True)
def reset_rate_limits():
    """Reset rate limiter state before each test."""
    get_rate_limiter().reset()


@pytest.fixture
def client():
    return TestClient(app)


def test_health_check(client):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "model" in data


def test_fallback_unauthorized(client):
    response = client.post("/api/v1/guidance/fallback")
    assert response.status_code == 401
    data = response.json()
    assert "error" in data
    assert data["retry"] is False


def test_fallback_hotspot_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    # Step 0: Network & internet
    r0 = client.post("/api/v1/guidance/fallback?step_index=0", headers=auth_headers)
    assert r0.status_code == 200
    d0 = r0.json()
    assert d0["status"] == "guide"
    assert d0["target_label"] == "Network & internet"
    assert d0["is_fallback"] is True
    assert d0["target_bbox_norm"] is not None

    # Step 1: Hotspot & tethering
    r1 = client.post("/api/v1/guidance/fallback?step_index=1", headers=auth_headers)
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1["status"] == "guide"
    assert d1["target_label"] == "Hotspot & tethering"
    assert d1["is_fallback"] is True

    # Step 2: Toggle
    r2 = client.post("/api/v1/guidance/fallback?step_index=2", headers=auth_headers)
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2["status"] == "guide"
    assert d2["target_label"] == "Wi-Fi hotspot toggle"
    assert d2["is_fallback"] is True

    # Step 3+: Complete
    r3 = client.post("/api/v1/guidance/fallback?step_index=3", headers=auth_headers)
    assert r3.status_code == 200
    d3 = r3.json()
    assert d3["status"] == "complete"
    assert d3["is_fallback"] is True


def test_fallback_dark_theme_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    # Step 0: Display
    r0 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Turn on dark theme", "step_index": 0},
    )
    assert r0.status_code == 200
    d0 = r0.json()
    assert d0["status"] == "guide"
    assert d0["target_label"] == "Display"
    assert d0["is_fallback"] is True

    # Step 1: Dark theme switch
    r1 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Turn on dark theme", "step_index": 1},
    )
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1["status"] == "guide"
    assert d1["target_label"] == "Dark theme switch"

    # Step 2: Complete
    r2 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Turn on dark theme", "step_index": 2},
    )
    assert r2.status_code == 200
    assert r2.json()["status"] == "complete"


def test_fallback_battery_saver_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    # Step 0: Battery
    r0 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Help me enable battery saver", "step_index": 0},
    )
    assert r0.status_code == 200
    assert r0.json()["target_label"] == "Battery"

    # Step 1: Battery Saver
    r1 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Help me enable battery saver", "step_index": 1},
    )
    assert r1.status_code == 200
    assert r1.json()["target_label"] == "Battery Saver"

    # Step 2: Toggle
    r2 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Help me enable battery saver", "step_index": 2},
    )
    assert r2.status_code == 200
    assert r2.json()["status"] == "guide"

    # Step 3: Complete
    r3 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Help me enable battery saver", "step_index": 3},
    )
    assert r3.status_code == 200
    assert r3.json()["status"] == "complete"


def test_fallback_bluetooth_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    # Step 0: Connected devices
    r0 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Pair bluetooth headphones", "step_index": 0},
    )
    assert r0.status_code == 200
    assert r0.json()["target_label"] == "Connected devices"

    # Step 1: Pair new device
    r1 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Pair bluetooth headphones", "step_index": 1},
    )
    assert r1.status_code == 200
    assert r1.json()["target_label"] == "Pair new device"

    # Step 2: Complete
    r2 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Pair bluetooth headphones", "step_index": 2},
    )
    assert r2.status_code == 200
    assert r2.json()["status"] == "complete"


def test_fallback_browser_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    r0 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Search on Chrome browser", "step_index": 0},
    )
    assert r0.status_code == 200
    assert r0.json()["target_label"] == "Search or type URL"


def test_fallback_messaging_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    r0 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Send message on WhatsApp", "step_index": 0},
    )
    assert r0.status_code == 200
    assert r0.json()["target_label"] == "Start chat"


def test_fallback_camera_steps(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    r0 = client.post(
        "/api/v1/guidance/fallback",
        headers=auth_headers,
        json={"instruction": "Take a photo with camera", "step_index": 0},
    )
    assert r0.status_code == 200
    assert r0.json()["target_label"] == "Shutter button"


def test_guidance_unauthorized(client):
    response = client.post(
        "/api/v1/guidance",
        files={"image": ("screen.jpg", MINIMAL_JPEG, "image/jpeg")},
        data={
            "request_id": "req-1",
            "instruction": "Open Wi-Fi",
            "step_index": 0,
            "image_width": 1080,
            "image_height": 1920,
        },
    )
    assert response.status_code == 401


def test_guidance_invalid_image_format(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    response = client.post(
        "/api/v1/guidance",
        headers=auth_headers,
        files={"image": ("screen.png", b"PNG_NOT_A_JPEG_FILE", "image/png")},
        data={
            "request_id": "req-1",
            "instruction": "Open Wi-Fi",
            "step_index": 0,
            "image_width": 1080,
            "image_height": 1920,
        },
    )
    assert response.status_code == 400
    data = response.json()
    assert "error" in data


@patch("app.main.GemmaClient.analyze_screen")
def test_guidance_successful(mock_analyze, client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    mock_analyze.return_value = """```json
{
  "status": "guide",
  "message": "Tap 'Network & internet' to proceed.",
  "target_label": "Network & internet",
  "target_bbox_norm": [0.05, 0.18, 0.95, 0.26],
  "interaction": "tap"
}
```"""

    response = client.post(
        "/api/v1/guidance",
        headers=auth_headers,
        files={"image": ("screen.jpg", MINIMAL_JPEG, "image/jpeg")},
        data={
            "request_id": "req-success-1",
            "instruction": "Enable hotspot",
            "step_index": 0,
            "image_width": 1080,
            "image_height": 1920,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "guide"
    assert data["target_label"] == "Network & internet"
    assert data["target_bbox_norm"] == [0.05, 0.18, 0.95, 0.26]
    assert data["interaction"] == "tap"
    assert data["is_fallback"] is False


@patch("app.main.GemmaClient.analyze_screen")
def test_guidance_with_json_payload_field(mock_analyze, client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    mock_analyze.return_value = '{"status": "complete", "message": "Done!"}'

    import json
    payload_str = json.dumps({
        "request_id": "req-json-1",
        "instruction": "Complete action",
        "step_index": 1,
        "image_width": 1080,
        "image_height": 1920,
    })

    response = client.post(
        "/api/v1/guidance",
        headers=auth_headers,
        files={"image": ("screen.jpg", MINIMAL_JPEG, "image/jpeg")},
        data={"payload": payload_str},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "complete"


def test_rate_limiting_enforced(client):
    settings = get_settings()
    auth_headers = {"Authorization": f"Bearer {settings.TAPTRAIL_AUTH_TOKEN}"}

    # Make requests up to rate limit
    for _ in range(settings.RATE_LIMIT_PER_MINUTE):
        r = client.post("/api/v1/guidance/fallback?step_index=0", headers=auth_headers)
        assert r.status_code == 200

    # The next request should receive 429
    r_blocked = client.post("/api/v1/guidance/fallback?step_index=0", headers=auth_headers)
    assert r_blocked.status_code == 429
    data = r_blocked.json()
    assert "error" in data
    assert data["retry"] is True
