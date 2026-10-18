# TapTrail

*A guide for whatever you're doing on your phone.*

TapTrail helps users understand a phone screen and complete a task one step at a time. A small floating button interprets the screen and request, moves beside the relevant control, and displays a short instruction. The user performs every action manually — TapTrail never taps, swipes, types, or changes settings.

> **Hackathon MVP** — Scoped to one two-step Android Settings task on one test device. Uses synthetic screenshots for live API tests until data-use gates are resolved.

## Architecture

```
┌──────────────┐     HTTPS/Token     ┌──────────────┐     API Key     ┌─────────────┐
│  Android App │ ──────────────────► │ Python Backend│ ────────────► │  Gemma 4 API │
│  (Kotlin)    │ ◄────────────────── │ (FastAPI)     │ ◄──────────── │  (Cloud)     │
│              │    JSON Response     │ Dev Laptop    │   Validated    │              │
└──────────────┘                     └──────────────┘   JSON          └─────────────┘
```

**Data flow:** Screenshot + instruction → backend validation → Gemma 4 analysis → response validation → coordinate mapping → floating button guidance.

## Quick Start

### Prerequisites

- Android device or emulator (API 29+, Android 10+)
- Android Studio (or Android SDK + Gradle)
- Python 3.10+
- Gemini API key ([Get one here](https://aistudio.google.com/apikey))

### 1. Start the Backend

```bash
cd backend

# Create virtual environment
python3 -m venv .venv
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Configure
cp .env.example .env
# Edit .env: set GEMINI_API_KEY and TAPTRAIL_AUTH_TOKEN

# Run
python run.py
```

The backend runs on `http://0.0.0.0:8000` by default.

### 2. Test the Backend

```bash
# Health check
curl http://localhost:8000/api/v1/health

# Test fallback (no API key needed)
curl -X POST http://localhost:8000/api/v1/guidance/fallback \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"step_index": 0, "instruction": "Help me turn on hotspot"}'
```

### 3. Build and Run the Android App

```bash
cd android

# Set your SDK path in local.properties
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Configure the Android App

Update the backend URL in `app/build.gradle.kts`:

- **Emulator:** `http://10.0.2.2:8000` (default, routes to host's localhost)
- **Physical device on same Wi-Fi:** `http://<laptop-ip>:8000`
- Update `AUTH_TOKEN` to match your backend's `TAPTRAIL_AUTH_TOKEN`

### 5. Demo Flow

1. Open TapTrail → tap "Start TapTrail"
2. Grant overlay permission when prompted
3. Open Android Settings
4. Tap the floating TapTrail button
5. Type "Help me turn on hotspot"
6. Approve transmission disclosure
7. Grant screen capture permission
8. Follow the guided steps

## Project Structure

```
TapTrail/
├── android/                    # Kotlin Android app
│   └── app/src/main/
│       └── java/com/taptrail/app/
│           ├── MainActivity.kt           # Entry, permissions
│           ├── overlay/                   # Floating button & UI
│           │   ├── OverlayService.kt      # Core overlay service
│           │   └── OverlayManager.kt      # Window management
│           ├── capture/                   # Screen capture
│           │   ├── CaptureActivity.kt     # MediaProjection consent
│           │   └── CaptureManager.kt      # Screenshot logic
│           ├── consent/                   # Dual consent tracking
│           │   └── ConsentManager.kt
│           ├── network/                   # Backend communication
│           │   ├── BackendClient.kt
│           │   └── ApiModels.kt
│           ├── guidance/                  # Step orchestration
│           │   ├── GuidanceEngine.kt
│           │   └── CoordinateMapper.kt
│           └── ui/                        # Overlay dialogs
│               ├── HelpRequestDialog.kt
│               └── ConsentDialog.kt
├── backend/                    # Python FastAPI backend
│   ├── app/
│   │   ├── main.py              # Routes & middleware
│   │   ├── gemma_client.py      # Gemma 4 API integration
│   │   ├── validator.py         # Response validation
│   │   ├── security.py          # Auth & rate limiting
│   │   ├── models.py            # Request/response schemas
│   │   └── config.py            # Environment settings
│   └── tests/
└── docs/
    ├── ARCHITECTURE.md
    ├── PRIVACY.md
    └── DEMO.md
```

## How It Works

### Gemma 4 Integration

TapTrail uses **Gemma 4 (gemma-4-26b-a4b-it)** through the Gemini API. The model receives a screenshot and instruction, then returns:

- A short guidance message
- A normalized bounding box `[x1, y1, x2, y2]` for the target UI element
- The recommended interaction type (tap, swipe, long press)

The backend validates the response format, coordinate bounds, and geometric validity before forwarding to the Android app.

### Coordinate System

Target coordinates are normalized to `[0, 1]` relative to screenshot dimensions. The Android app maps them to screen coordinates accounting for:

- Display density and metrics
- Status bar and navigation bar insets
- Screen orientation

### Consent Model

TapTrail enforces **two independent authorizations**:

1. **TapTrail Transmission Consent** — User acknowledges that screenshots will leave the device and be sent to TapTrail's backend and Google's API.
2. **Android Capture Permission** — System MediaProjection consent for screen capture.

Both must be granted before any screenshot is captured or transmitted. Neither implies the other.

## Privacy & Data Handling

> **⚠ Important:** Read [PRIVACY.md](docs/PRIVACY.md) for full details.

- **No persistent storage:** Screenshots and requests are processed in memory and released promptly. The backend has no database.
- **API key isolation:** `GEMINI_API_KEY` stays in the backend environment. It is never embedded in the Android app or committed to source control.
- **No automatic capture:** Screenshots are taken only when the user explicitly starts help or selects "Next Step."
- **Free API tier notice:** Under the free Gemini API tier, Google may use submitted content to improve products. Human reviewers may read inputs and outputs. See [Gemini API Terms](https://ai.google.dev/gemini-api/terms).
- **Upstream retention:** Google's abuse-monitoring logs retention of prompts and outputs for 55 days. TapTrail cannot control or guarantee deletion of data sent to Google's API.

## Limitations

- **Hackathon MVP:** Supports one Settings task on one device configuration.
- **Synthetic screenshots:** Real-screen API testing is gated on resolving data-use terms.
- **No automatic actions:** TapTrail guides only; it never interacts with the screen.
- **Target accuracy:** Model-based localization may be inconsistent. Invalid targets fail safely.
- **Latency:** Cloud round-trip adds delay to each step.
- **Not a consumer product:** This is a developer-run prototype, not approved for public distribution.

## API Response Contract

```json
{
  "status": "answer | guide | clarify | complete",
  "message": "Short instruction text",
  "target_label": "Hotspot",
  "target_bbox_norm": [0.42, 0.36, 0.79, 0.44],
  "interaction": "tap | swipe | long_press",
  "is_fallback": false
}
```

## License

Apache 2.0 — See [LICENSE](LICENSE).
