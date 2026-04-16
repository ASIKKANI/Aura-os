# Aura 4.0 — System Architecture

## Overview

Aura is a **voice-native, multimodal OS controller** for Android. Users speak to their phone; Aura **sees** the screen, **understands** intent via Gemini Live, and **physically taps** the correct UI element.

## Architecture Diagram

```mermaid
graph TB
    subgraph DEVICE["📱 Android Edge"]
        MIC["🎙️ AudioRecord<br/>16kHz PCM"]
        SPK["🔊 AudioTrack<br/>24kHz PCM"]
        ACC["👁️ AccessibilityService<br/>Screen Observer"]
        FLAT["⚡ SemanticFlattener<br/>XML → Token String"]
        RESOLVE["🎯 TargetResolver<br/>Jaro-Winkler Matcher"]
        GESTURE["🖐️ dispatchGesture<br/>Physical Tap"]
        TELE["📡 GCPTelemetry<br/>Firebase SDK"]
    end

    subgraph GEMINI["☁️ Gemini Live API"]
        LIVE["🧠 gemini-2.0-flash-exp<br/>Bidi Audio Stream"]
    end

    subgraph GCP["☁️ Google Cloud Platform"]
        FIRESTORE["🔥 Firestore<br/>Session State"]
        STORAGE["📦 Cloud Storage<br/>Audit Screenshots"]
        CLOUDRUN["🛡️ Cloud Run<br/>Safety Guardrail"]
        DASHBOARD["📊 Mirror Dashboard<br/>Real-time Viewer"]
    end

    MIC -->|"base64 PCM<br/>realtimeInput"| LIVE
    LIVE -->|"base64 PCM<br/>serverContent"| SPK
    LIVE -->|"execute_tap<br/>toolCall"| RESOLVE
    ACC -->|"rootInActiveWindow"| FLAT
    ACC -->|"takeScreenshot()"| FLAT
    FLAT -->|"token string + JPEG"| LIVE
    RESOLVE -->|"(x, y) coords"| GESTURE
    TELE -->|"async"| FIRESTORE
    TELE -->|"async"| STORAGE
    RESOLVE -.->|"optional safety check"| CLOUDRUN
    FIRESTORE -->|"real-time listener"| DASHBOARD

    style DEVICE fill:#1a1a2e,stroke:#1db954,color:#e0e0e0
    style GEMINI fill:#1a1a2e,stroke:#4c8bf5,color:#e0e0e0
    style GCP fill:#1a1a2e,stroke:#f59e0b,color:#e0e0e0
```

## Data Flow — The 600ms Loop

| Step | Component | Action | Latency Budget |
|------|-----------|--------|----------------|
| 1 | AudioRecord | Capture voice → base64 PCM chunk | ~64ms |
| 2 | WebSocket | Send `realtimeInput.mediaChunks` to Gemini | ~50ms |
| 3 | Gemini Live | Process intent → trigger `execute_tap` tool call | ~300ms |
| 4 | TargetResolver | Fuzzy match target text → calculate (X,Y) | <10ms |
| 5 | AccessibilityService | `dispatchGesture()` at resolved coordinates | ~50ms |
| 6 | GCPTelemetry | Async: log to Firestore + upload screenshot | 0ms (non-blocking) |

**Total E2E: ~475ms** (target <600ms)

## Component Summary

### Edge (Android Kotlin)

| File | Role | Key Detail |
|------|------|------------|
| `LiveSDKManager.kt` | Ears & Voice | OkHttp WSS → Gemini Live, bidi PCM |
| `AuraAccessibilityService.kt` | Eyes & Hands | Screen detection, screenshot, `dispatchGesture` |
| `SemanticFlattener.kt` | UI Compressor | Tree → `[i:ID\|t:Type\|txt:Text\|b:Bounds]` |
| `TargetResolver.kt` | Fuzzy Matcher | Jaro-Winkler, 0.75 threshold, dangerous action check |
| `GCPTelemetry.kt` | Cloud Sync | Firestore + Cloud Storage, async fire-and-forget |

### Cloud (GCP)

| Service | Role | Region |
|---------|------|--------|
| Cloud Run | Safety guardrail API + Mirror Dashboard | asia-south1 |
| Firestore | Real-time session state + action logs | auto |
| Cloud Storage | Audit trail screenshots | auto |

## Safety Guardrail Flow

```mermaid
flowchart TD
    A["Gemini: execute_tap('Pay')"] --> B{"Local: isDangerousAction?"}
    B -->|No| C["Tap immediately"]
    B -->|Yes| D{"Cloud Run: /verify_tap"}
    D -->|safe: true| C
    D -->|safe: false| E["Ask user: 'Are you sure<br/>you want to pay?'"]
    E -->|User confirms| C
    E -->|User declines| F["Action cancelled"]

    style A fill:#1a1a2e,stroke:#4c8bf5,color:#e0e0e0
    style E fill:#1a1a2e,stroke:#f59e0b,color:#e0e0e0
    style F fill:#1a1a2e,stroke:#ef4444,color:#e0e0e0
```
