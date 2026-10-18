# TapTrail Demo Guide

## Pre-Demo Checklist

- [ ] Backend running on laptop (`python run.py`)
- [ ] `.env` configured with valid `GEMINI_API_KEY` and `TAPTRAIL_AUTH_TOKEN`
- [ ] Android app installed on demo device
- [ ] App's `BACKEND_URL` points to laptop's IP (or 10.0.2.2 for emulator)
- [ ] App's `AUTH_TOKEN` matches backend's `TAPTRAIL_AUTH_TOKEN`
- [ ] Device and laptop on same network
- [ ] Backend health check passes: `curl http://localhost:8000/api/v1/health`
- [ ] Fallback endpoint works (in case live API fails)
- [ ] Settings app ready on device

## Demo Script

### Step 1: Launch TapTrail
- Open the TapTrail app
- Tap "Start TapTrail"
- Grant overlay permission if prompted
- **Show:** The floating blue button appears over the home screen

### Step 2: Navigate to Settings
- Open the Android Settings app
- **Show:** The TapTrail button is still visible, floating over Settings

### Step 3: Request Help
- Tap the TapTrail floating button
- Type: "Help me turn on hotspot"
- Tap "Ask"

### Step 4: Consent Flow
- **Show:** TapTrail's transmission disclosure appears, explaining:
  - Screenshot will leave the phone
  - Data goes to backend and Google's API
  - Google may retain and review data
- Tap "I Understand, Proceed"
- **Show:** Android's system capture permission dialog appears separately
- Grant the capture permission

### Step 5: First Guided Step
- **Show:** The button animates to move beside "Network & internet"
- **Show:** An instruction bubble says "Tap 'Network & internet' to access network settings"
- **Explain:** This is live Gemma 4 inference (or clearly state if using fallback)
- Manually tap "Network & internet"

### Step 6: Next Step
- Tap "Next Step" on the instruction bubble
- **Show:** A new screenshot is captured and analyzed
- **Show:** The button moves beside "Hotspot & tethering"
- **Show:** Instruction says "Tap 'Hotspot & tethering' to configure the hotspot"
- Manually tap "Hotspot & tethering"

### Step 7: Completion
- Tap "Next Step" again
- **Show:** TapTrail confirms the task is complete (or guides the toggle step)
- Tap "Stop" to end the session

### Step 8: Architecture Explanation
- Show the data flow diagram
- Explain Gemma 4's role in image interpretation
- Mention the unresolved data-use gate and synthetic screenshot usage
- Emphasize: TapTrail guides, never acts

## Fallback Plan

If the live API fails during the demo:

1. The backend automatically falls back to precomputed responses
2. The UI will show a ⚠ notice: "This is a precomputed response, not live AI inference"
3. The demo flow still works — it just uses hardcoded guidance for the hotspot task

**Never present fallback responses as live AI inference.**

## Troubleshooting

| Issue | Solution |
|---|---|
| Button doesn't appear | Check overlay permission in Settings → Apps → TapTrail |
| Can't reach backend | Check IP, port, firewall, same network |
| API timeout | Check API key, internet connection on laptop |
| Wrong target location | Expected — model accuracy varies; show graceful failure |
| Screen capture fails | Re-grant MediaProjection permission; check Android version |

## Key Points to Emphasize

1. **User control:** Every tap and action is performed by the user
2. **Dual consent:** TapTrail consent ≠ Android capture permission
3. **Privacy:** No persistent storage, no background capture
4. **Honest limitations:** Synthetic screenshots, free-tier data use, single task
5. **Gemma 4:** Cloud-based image interpretation, not on-device
