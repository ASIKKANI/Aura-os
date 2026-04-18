# 🌌 Aura: The Multimodal OS Co-Pilot
> **Redefining accessibility with a zero-hop, autonomous Android agent.**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Gemini](https://img.shields.io/badge/Gemini_2.0_Flash-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://ai.google.dev/)
[![GCP](https://img.shields.io/badge/Google_Cloud-4285F4?style=for-the-badge&logo=google-cloud&logoColor=white)](https://cloud.google.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

---

## 📺 Project Demo
**[🎬 Watch the 4-Minute Demo Video](YOUR_YOUTUBE_OR_LOOM_LINK)** | **[📲 Download the APK (v1.0)](YOUR_BITLY_DRIVE_LINK)**

---

## 💡 Inspiration
For millions in the Global South, smartphones are essential but app interfaces are a maze. Cluttered UIs, hidden menus, and language barriers make digital life high-friction. We built **Aura** to move the needle from *Automation* to *Agency*. Aura isn't a chatbot; she's an invisible hand that navigates the complex OS so you don't have to.

## 🌟 Key Features
* **🤖 Full OS Autonomy:** Executes multi-step workflows (e.g., ordering food, searching settings) by physically interacting with third-party apps.
* **👻 Ghost Mode (UI Abstraction):** Hides chaotic app interfaces behind a sleek frosted-glass overlay while Aura’s "ghost hands" do the work underneath.
* **🎙️ Zero-Hop Live Conversation:** Direct bidi-audio stream to Gemini 2.0 Flash for near-instant conversational latency.
* **🚨 Savior Protocol:** A voice-activated SOS mode that locks the device, broadcasts GPS location, and starts recording—completely hands-free.

## 🛠️ Technical Architecture
Aura is built with a **Zero-Hop Native Edge Architecture** to ensure peak performance and minimal latency.

### The Stack
* **Nervous System:** Raw **OkHttp WebSockets** in Kotlin for bidirectional audio/JSON streaming.
* **The Eyes:** **AccessibilityService** + custom `SemanticFlattener` to tokenize UI trees into lightweight JSON.
* **The Hands:** **GestureDescription API** powered by **Jaro-Winkler fuzzy matching** for resilient UI targeting.
* **The Brain:** **Gemini 2.0 Flash Multimodal Live API** executing a strict ReAct (Reason + Act) cognitive loop.

---

## ⚙️ Installation & Setup (For Judges)

> [!IMPORTANT]
> Because Aura is an autonomous OS controller, she requires specific permissions to "see" and "act."

1.  **Download & Install:** Download the `Aura_Orion_Hackathon.apk` from the link above.
2.  **Allow Restricted Settings:** If the toggle is greyed out: `Settings > Apps > Aura > 3-dots (top right) > Allow Restricted Settings`.
3.  **Accessibility Access:** `Settings > Accessibility > Downloaded Apps > Aura` -> Toggle **ON**.
4.  **Overlay Permission:** Allow "Display over other apps" when prompted.

### 🎯 The "Golden Path" Test
1. Open Aura and tap **Start Session**.
2. Say: *"Aura, find the battery percentage in my settings."*
3. **Watch:** Aura will autonomously navigate the Settings app to reveal the battery menu for you.

---

## 🚧 Challenges Faced
* **Asynchronous Desync:** Managed real-time state across bidi-streams using thread-safe `ConcurrentHashMap`.
* **UI Parsing Latency:** Implemented a deterministic 3x 800ms retry loop to wait for Android UI draw cycles before feeding context to the AI.
* **Keyboard Submission:** Injected a `"submitted": "true/false"` flag to force the agent to find and tap the physical "Search" icon after typing.

---

## 🗺️ Roadmap
- [ ] **Spatial Vision:** Integrating CameraX to allow "Point-and-Act" functionality for real-world posters/objects.
- [ ] **Digital Immune System:** Proactive phishing and scam interception using `SYSTEM_ALERT_WINDOW`.
- [ ] **Multi-lingual UI Overlays:** Real-time on-screen translation for regional languages.

## 👥 The Team
* **Asik Kani** - *Lead Android & AI Engineer*

---
