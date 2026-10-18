# TapTrail Architecture

## System Overview

TapTrail is a three-component system for on-screen task guidance:

```
┌─────────────────────────────────────────────────────────────────────┐
│ Android Device                                                       │
│                                                                      │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────────────────┐  │
│  │ User's App  │    │ OverlayService│    │ CaptureManager        │  │
│  │ (Settings)  │    │ (Floating UI) │    │ (MediaProjection)     │  │
│  └─────────────┘    └──────┬───────┘    └──────────┬─────────────┘  │
│                            │                        │                │
│                     ┌──────┴───────┐    ┌──────────┴─────────────┐  │
│                     │GuidanceEngine│◄──►│ ConsentManager         │  │
│                     │(State Machine)│   │ (Dual Authorization)   │  │
│                     └──────┬───────┘    └────────────────────────┘  │
│                            │                                         │
│                     ┌──────┴───────┐                                 │
│                     │BackendClient │                                 │
│                     │  (OkHttp)    │                                 │
│                     └──────┬───────┘                                 │
└────────────────────────────┼─────────────────────────────────────────┘
                             │ HTTPS + Bearer Token
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Developer Laptop                                                     │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Python Backend (FastAPI)                                      │   │
│  │                                                               │   │
│  │  ┌──────────┐  ┌───────────┐  ┌──────────┐  ┌────────────┐ │   │
│  │  │ Security │  │  Routes   │  │ Gemma    │  │ Validator  │ │   │
│  │  │ (Auth,   │──│ /guidance │──│ Client   │──│ (Response  │ │   │
│  │  │  Rate)   │  │ /health   │  │ (GenAI)  │  │  Parsing)  │ │   │
│  │  └──────────┘  └───────────┘  └────┬─────┘  └────────────┘ │   │
│  └────────────────────────────────────┼──────────────────────────┘   │
└───────────────────────────────────────┼──────────────────────────────┘
                                        │ API Key (server-side only)
                                        ▼
                              ┌──────────────────┐
                              │ Google Gemini API │
                              │ (Gemma 4 model)  │
                              └──────────────────┘

```

## Component Details

### Android App Components

| Component | Responsibility |
|---|---|
| **MainActivity** | Entry point; overlay permission request; service lifecycle |
| **OverlayService** | Foreground service managing all overlay views via WindowManager |
| **OverlayManager** | Window creation, positioning, animation, z-ordering |
| **CaptureActivity** | Transparent activity for MediaProjection consent dialog |
| **CaptureManager** | Single-frame screenshot capture via VirtualDisplay + ImageReader |
| **ConsentManager** | Tracks TapTrail consent and Android capture permission independently |
| **GuidanceEngine** | State machine orchestrating the full guidance flow |
| **CoordinateMapper** | Normalized bbox → screen coordinates with inset compensation |
| **BackendClient** | OkHttp multipart requests to the Python backend |

### Python Backend Components

| Component | Responsibility |
|---|---|
| **main.py** | FastAPI app, routes, middleware, request lifecycle |
| **gemma_client.py** | Google Gen AI SDK integration, prompt construction |
| **validator.py** | JSON extraction, schema validation, coordinate verification |
| **security.py** | Bearer token auth, rate limiting, request size checks |
| **models.py** | Pydantic request/response schemas |
| **config.py** | Environment-based settings via pydantic-settings |

## State Machine

```
                ┌──────┐
                │ IDLE │◄─────────── Stop/Close
                └──┬───┘                  ▲
                   │ User taps button     │
                   ▼                      │
         ┌─────────────────┐              │
         │ REQUESTING_HELP │              │
         └────────┬────────┘              │
                  │ User submits request  │
                  ▼                       │
        ┌──────────────────┐              │
        │REQUESTING_CONSENT│──── Denied ──┘
        └────────┬─────────┘
                 │ Granted
                 ▼
    ┌────────────────────────┐
    │AWAITING_CAPTURE_PERM   │──── Denied ──┘
    └────────────┬───────────┘
                 │ Granted
                 ▼
          ┌────────────┐
    ┌────►│ CAPTURING   │
    │     └──────┬─────┘
    │            │ Screenshot taken
    │            ▼
    │     ┌────────────┐
    │     │  SENDING    │──── Error ──► RETRY/STOP
    │     └──────┬─────┘
    │            │ Response received
    │            ▼
    │  ┌──────────────────┐
    │  │ SHOWING_GUIDANCE  │──── Complete ──► IDLE
    │  └────────┬─────────┘
    │           │ User taps "Next Step"
    └───────────┘
```

## Security Model

1. **Transport:** HTTPS between Android app and backend on private network
2. **Authentication:** Short-lived bearer token in Authorization header
3. **Rate Limiting:** Per-IP request throttling (10/min default)
4. **Key Isolation:** GEMINI_API_KEY never leaves the backend environment
5. **Logging:** Only request_id, model, latency, status code — never request bodies
6. **Consent:** Dual authorization required before any screenshot transmission
