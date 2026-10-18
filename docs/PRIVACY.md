# TapTrail Privacy Disclosure

**Last updated:** October 2026  
**Status:** Hackathon MVP — developer-run prototype

## What TapTrail Does

TapTrail captures screenshots of your Android screen when you explicitly request help and transmits them to a backend server for AI-powered analysis. It provides step-by-step guidance by analyzing the screenshot and your request.

## Data Flow

When you request help, the following data leaves your device:

1. **Screenshot** — A JPEG image of your current screen
2. **Your request** — The text you typed (e.g., "Help me turn on hotspot")
3. **Step metadata** — Step number, image dimensions, a random request ID

This data flows through:

```
Your Phone → TapTrail Backend (developer's laptop) → Google Gemini API (cloud)
```

## What TapTrail Controls

- **In-memory processing:** TapTrail's backend processes screenshots in memory and releases references promptly. It does not save screenshots to disk or a database.
- **Minimal logging:** The backend logs only a random request ID, model identifier, latency, and status code. It does not log screenshot data, your request text, or response content.
- **Session-scoped consent:** Your transmission consent is not persisted between sessions.

## What TapTrail Does NOT Control

### Google's Data Use (Free API Tier)

TapTrail currently uses the **free Gemini API tier**. Under Google's current terms:

- **Content may be used to improve products.** Google may use inputs and outputs submitted through unpaid services to provide, improve, and develop Google products and machine-learning technologies.
- **Human review.** Human reviewers may read, annotate, and process your inputs and outputs.
- **Abuse monitoring retention.** Google retains prompts and outputs for **55 days** for safety and policy enforcement.
- **No guaranteed deletion.** The 55-day retention is an abuse-monitoring provision. It is not a guarantee that every copy of free-tier data is deleted after 55 days.

**TapTrail deleting its temporary copy does not affect Google's data use or retention.**

### References

- [Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms)
- [Gemini API Usage Policies & Abuse Monitoring](https://ai.google.dev/gemini-api/docs/usage-policies)
- [Gemini API Zero Data Retention Guidance](https://ai.google.dev/gemini-api/docs/zdr)

## Consent Model

TapTrail requires **two separate authorizations** before capturing and transmitting a screenshot:

1. **TapTrail Transmission Consent** — You explicitly acknowledge that your screenshot and request will be sent to TapTrail's backend and Google's API.
2. **Android Capture Permission** — The system-level MediaProjection consent that authorizes screen capture.

- Neither permission grants the other.
- If either is denied, TapTrail will not capture or transmit your screen.
- No screenshots are captured in the background. Capture occurs only when you start help or tap "Next Step."

## What You Should Not Do

- Do not use TapTrail on screens containing sensitive personal information (passwords, financial data, private messages) while the free API tier is in use.
- Do not assume that TapTrail or Google deletes your data permanently after your session ends.

## Current Limitations

- This is a **developer-run hackathon prototype**, not a consumer product.
- Live API tests currently use **synthetic screenshots only**. Real-screen transmission is gated on resolving the data-use question.
- TapTrail makes no claim of zero data retention.
- TapTrail is not approved for public distribution or use by anyone under 18.

## Changes

This disclosure applies to the hackathon MVP. It will be updated if the data-use gate is resolved or the project scope changes.
