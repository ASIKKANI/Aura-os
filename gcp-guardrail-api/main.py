"""
══════════════════════════════════════════════════════════════════════════
 Aura Guardrail API — Cloud Run Safety, Telemetry & Mirror Dashboard
══════════════════════════════════════════════════════════════════════════

 Endpoints:
   🛡️  POST /verify_tap          — Safety guardrail (dangerous intent check)
   🔥  POST /telemetry/screen     — Firestore: screen state sync
   👆  POST /telemetry/tap        — Firestore: tap event + latency
   🛡️  POST /telemetry/ghost_tap  — Firestore: prevented tap log
   📸  POST /telemetry/screenshot — Cloud Storage: audit trail upload
   📊  GET  /api/metrics          — Live metrics for dashboard polling
   📊  GET  /api/log              — Recent event log for dashboard
   🔍  GET  /api/health           — Health check
   🖥️  GET  /                     — Mirror Dashboard (auto-polling)
══════════════════════════════════════════════════════════════════════════
"""

import os
import base64
from datetime import datetime
from difflib import SequenceMatcher
from fastapi import FastAPI, BackgroundTasks
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
import uvicorn

app = FastAPI(
    title="Aura Guardrail API",
    description="Safety guardrail, telemetry & dashboard for Project Aura",
    version="4.0.0",
)

# ═══════════════════════════════════════════════════════════════
#  GCP CLIENTS (graceful fallback if not on Cloud Run)
# ═══════════════════════════════════════════════════════════════

_db = None
_gcs = None
GCS_BUCKET = os.environ.get("GCS_BUCKET", "aura-audit-screenshots")
GCP_PROJECT = os.environ.get("GOOGLE_CLOUD_PROJECT") or os.environ.get("GCP_PROJECT_ID")

try:
    from google.cloud import firestore
    _db = firestore.Client(project=GCP_PROJECT) if GCP_PROJECT else firestore.Client()
    print(f"✅ Firestore connected (project={GCP_PROJECT or 'auto-detected'})")
except Exception as e:
    print(f"⚠️  Firestore not available: {e}")

try:
    from google.cloud import storage
    _gcs = storage.Client(project=GCP_PROJECT) if GCP_PROJECT else storage.Client()
    print("✅ Cloud Storage connected")
except Exception as e:
    print(f"⚠️  Cloud Storage not available: {e}")

# ═══════════════════════════════════════════════════════════════
#  IN-MEMORY FALLBACK (used when Firestore is unavailable)
# ═══════════════════════════════════════════════════════════════

_metrics: dict = {
    "total_taps": 0,
    "ghost_taps_prevented": 0,
    "latencies": [],
    "active_package": "—",
    "active_device": "—",
}
_log_events: list = []
_MAX_LOG = 100


def _append_event(icon: str, message: str, device_id: str = ""):
    ts = datetime.now().strftime("%H:%M:%S")
    _log_events.append({"time": ts, "icon": icon, "message": message, "device": device_id})
    if len(_log_events) > _MAX_LOG:
        _log_events.pop(0)


# ═══════════════════════════════════════════════════════════════
#  DANGEROUS INTENTS
# ═══════════════════════════════════════════════════════════════

DANGEROUS_INTENTS = [
    "pay", "pay now", "transfer", "transfer money", "send money",
    "send payment", "delete", "delete account", "remove",
    "confirm order", "place order", "confirm payment", "proceed to pay",
    "logout", "log out", "sign out", "uninstall", "purchase",
    "buy now", "upi transfer", "bhim pay", "confirm transaction",
    "authorize", "debit",
]

HIGH_RISK_PACKAGES = [
    "com.phonepe.app",
    "net.one97.paytm",
    "com.google.android.apps.nbu.paisa.user",
    "in.org.npci.upiapp",
    "com.whatsapp",
    "com.amazon.mShop.android.shopping",
    "com.flipkart.android",
]

SIMILARITY_THRESHOLD = 0.70

# ═══════════════════════════════════════════════════════════════
#  REQUEST / RESPONSE MODELS
# ═══════════════════════════════════════════════════════════════

class VerifyTapRequest(BaseModel):
    target_text: str
    package_name: str = ""

class VerifyTapResponse(BaseModel):
    safe: bool
    require_voice_confirmation: bool
    matched_intent: str = ""
    similarity_score: float = 0.0
    reason: str = ""

class ScreenPayload(BaseModel):
    device_id: str
    package_name: str
    node_count: int
    timestamp: int

class TapPayload(BaseModel):
    device_id: str
    target_text: str
    resolved_text: str
    x: int
    y: int
    confidence: float
    latency_ms: int
    package_name: str
    timestamp: int

class GhostTapPayload(BaseModel):
    device_id: str
    target_text: str
    reason: str
    timestamp: int

class ScreenshotPayload(BaseModel):
    device_id: str
    screenshot_b64: str
    timestamp: int

# ═══════════════════════════════════════════════════════════════
#  ENDPOINTS — HEALTH & GUARDRAIL
# ═══════════════════════════════════════════════════════════════

@app.get("/api/health")
async def health():
    return {
        "status": "healthy",
        "service": "aura-guardrail",
        "version": "4.0.0",
        "firestore": _db is not None,
        "cloud_storage": _gcs is not None,
    }


@app.post("/verify_tap", response_model=VerifyTapResponse)
async def verify_tap(request: VerifyTapRequest):
    """Safety guardrail: checks if target text matches a dangerous intent."""
    target_lower = request.target_text.lower().strip()
    is_high_risk_app = request.package_name in HIGH_RISK_PACKAGES
    threshold = 0.60 if is_high_risk_app else SIMILARITY_THRESHOLD

    best_match = ""
    best_score = 0.0

    for intent in DANGEROUS_INTENTS:
        score = SequenceMatcher(None, target_lower, intent).ratio()
        if score > best_score:
            best_score = score
            best_match = intent

    is_dangerous = best_score >= threshold
    contains_dangerous = any(intent in target_lower for intent in DANGEROUS_INTENTS)
    if contains_dangerous:
        is_dangerous = True
        best_score = max(best_score, 0.95)

    if is_dangerous:
        reason = f"Matched dangerous intent '{best_match}' (score: {best_score:.2f})"
        if is_high_risk_app:
            reason += f" in high-risk app {request.package_name}"
        return VerifyTapResponse(
            safe=False,
            require_voice_confirmation=True,
            matched_intent=best_match,
            similarity_score=round(best_score, 3),
            reason=reason,
        )

    return VerifyTapResponse(
        safe=True,
        require_voice_confirmation=False,
        similarity_score=round(best_score, 3),
    )

# ═══════════════════════════════════════════════════════════════
#  ENDPOINTS — TELEMETRY (Android → Cloud Run → Firestore / GCS)
# ═══════════════════════════════════════════════════════════════

@app.post("/telemetry/screen")
async def telemetry_screen(payload: ScreenPayload, background_tasks: BackgroundTasks):
    """Screen state update — stores in Firestore, updates in-memory metrics."""
    _metrics["active_package"] = payload.package_name
    _metrics["active_device"] = payload.device_id
    _append_event(
        "👁️",
        f"Screen: <b>{payload.package_name}</b> ({payload.node_count} nodes)",
        payload.device_id
    )
    if _db:
        background_tasks.add_task(_firestore_set_session, payload)
    return {"status": "ok"}


@app.post("/telemetry/tap")
async def telemetry_tap(payload: TapPayload, background_tasks: BackgroundTasks):
    """Tap event — stores in Firestore, updates metrics."""
    _metrics["total_taps"] += 1
    _metrics["latencies"].append(payload.latency_ms)
    if len(_metrics["latencies"]) > 100:
        _metrics["latencies"].pop(0)
    _metrics["active_package"] = payload.package_name
    _metrics["active_device"] = payload.device_id

    _append_event(
        "👆",
        f"Tapped <b>'{payload.resolved_text}'</b> @ ({payload.x},{payload.y}) "
        f"<span style='color:#4c8bf5;font-family:monospace;font-size:11px'>"
        f"conf:{payload.confidence:.2f} | {payload.latency_ms}ms</span>",
        payload.device_id
    )
    if _db:
        background_tasks.add_task(_firestore_add_tap, payload)
    return {"status": "ok", "total_taps": _metrics["total_taps"]}


@app.post("/telemetry/ghost_tap")
async def telemetry_ghost_tap(payload: GhostTapPayload, background_tasks: BackgroundTasks):
    """Ghost tap prevented — stores in Firestore."""
    _metrics["ghost_taps_prevented"] += 1
    _append_event(
        "🛡️",
        f"Ghost tap prevented: <b>'{payload.target_text}'</b> — {payload.reason}",
        payload.device_id
    )
    if _db:
        background_tasks.add_task(_firestore_add_ghost_tap, payload)
    return {"status": "ok", "ghost_taps_prevented": _metrics["ghost_taps_prevented"]}


@app.post("/telemetry/screenshot")
async def telemetry_screenshot(payload: ScreenshotPayload, background_tasks: BackgroundTasks):
    """Screenshot audit trail — uploads to Cloud Storage."""
    _append_event("📸", "Audit screenshot captured", payload.device_id)
    if _gcs:
        background_tasks.add_task(_gcs_upload_screenshot, payload)
    return {"status": "ok"}

# ═══════════════════════════════════════════════════════════════
#  ENDPOINTS — DASHBOARD API (polled by JS every 2s)
# ═══════════════════════════════════════════════════════════════

@app.get("/api/metrics")
async def api_metrics():
    lats = _metrics["latencies"]
    avg_lat = int(sum(lats) / len(lats)) if lats else 0
    return {
        "total_taps": _metrics["total_taps"],
        "ghost_taps_prevented": _metrics["ghost_taps_prevented"],
        "avg_latency_ms": avg_lat,
        "active_package": _metrics["active_package"],
        "active_device": _metrics["active_device"],
        "firestore_live": _db is not None,
    }


@app.get("/api/log")
async def api_log(limit: int = 50):
    return {"events": _log_events[-limit:]}

# ═══════════════════════════════════════════════════════════════
#  FIRESTORE BACKGROUND TASKS
# ═══════════════════════════════════════════════════════════════

def _firestore_set_session(payload: ScreenPayload):
    try:
        _db.collection("sessions").document(payload.device_id).set({
            "package_name": payload.package_name,
            "node_count": payload.node_count,
            "last_seen": payload.timestamp,
            "device_id": payload.device_id,
        }, merge=True)
    except Exception as e:
        print(f"⚠️ Firestore set_session failed: {e}")


def _firestore_add_tap(payload: TapPayload):
    try:
        _db.collection("sessions").document(payload.device_id) \
            .collection("taps").add({
                "target_text": payload.target_text,
                "resolved_text": payload.resolved_text,
                "x": payload.x,
                "y": payload.y,
                "confidence": payload.confidence,
                "latency_ms": payload.latency_ms,
                "package_name": payload.package_name,
                "timestamp": payload.timestamp,
            })
    except Exception as e:
        print(f"⚠️ Firestore add_tap failed: {e}")


def _firestore_add_ghost_tap(payload: GhostTapPayload):
    try:
        _db.collection("sessions").document(payload.device_id) \
            .collection("ghost_taps").add({
                "target_text": payload.target_text,
                "reason": payload.reason,
                "timestamp": payload.timestamp,
            })
    except Exception as e:
        print(f"⚠️ Firestore add_ghost_tap failed: {e}")


def _gcs_upload_screenshot(payload: ScreenshotPayload):
    try:
        bucket = _gcs.bucket(GCS_BUCKET)
        blob_name = f"{payload.device_id}/{payload.timestamp}.jpg"
        blob = bucket.blob(blob_name)
        img_bytes = base64.b64decode(payload.screenshot_b64)
        blob.upload_from_string(img_bytes, content_type="image/jpeg")
        print(f"✅ Screenshot uploaded: gs://{GCS_BUCKET}/{blob_name}")
    except Exception as e:
        print(f"⚠️ GCS upload failed: {e}")

# ═══════════════════════════════════════════════════════════════
#  MIRROR DASHBOARD (polls /api/metrics + /api/log every 2s)
# ═══════════════════════════════════════════════════════════════

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Aura — Mirror Dashboard</title>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap');

        * { margin: 0; padding: 0; box-sizing: border-box; }

        :root {
            --bg-primary: #0a0a0f;
            --bg-secondary: #12121a;
            --bg-card: #1a1a28;
            --bg-card-hover: #222236;
            --accent-green: #1db954;
            --accent-blue: #4c8bf5;
            --accent-purple: #a855f7;
            --accent-red: #ef4444;
            --accent-amber: #f59e0b;
            --text-primary: #e8e8f0;
            --text-secondary: #8888a0;
            --text-muted: #555570;
            --border: #2a2a3e;
            --glow-green: rgba(29, 185, 84, 0.15);
        }

        body {
            font-family: 'Inter', -apple-system, sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            min-height: 100vh;
            overflow-x: hidden;
        }

        .header {
            display: flex; align-items: center; justify-content: space-between;
            padding: 20px 32px;
            border-bottom: 1px solid var(--border);
            background: linear-gradient(180deg, var(--bg-secondary) 0%, transparent 100%);
        }

        .logo { display: flex; align-items: center; gap: 12px; }

        .logo-icon {
            width: 36px; height: 36px; border-radius: 10px;
            background: linear-gradient(135deg, var(--accent-green), var(--accent-blue));
            display: flex; align-items: center; justify-content: center; font-size: 18px;
        }

        .logo-text { font-size: 20px; font-weight: 700; letter-spacing: 2px; }
        .logo-sub { font-size: 11px; color: var(--text-muted); letter-spacing: 1px; }

        .status-badge {
            display: flex; align-items: center; gap: 8px;
            padding: 6px 16px; border-radius: 20px;
            background: var(--glow-green);
            border: 1px solid rgba(29, 185, 84, 0.3);
            font-size: 12px; color: var(--accent-green); font-weight: 500;
        }

        .status-dot {
            width: 8px; height: 8px; border-radius: 50%;
            background: var(--accent-green);
            animation: pulse 2s ease-in-out infinite;
        }

        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.5; transform: scale(0.8); }
        }

        .metrics {
            display: grid; grid-template-columns: repeat(4, 1fr);
            gap: 16px; padding: 24px 32px;
        }

        .metric-card {
            background: var(--bg-card); border: 1px solid var(--border);
            border-radius: 16px; padding: 20px; transition: all 0.3s ease;
        }

        .metric-card:hover {
            background: var(--bg-card-hover); transform: translateY(-2px);
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
        }

        .metric-label {
            font-size: 11px; color: var(--text-muted);
            text-transform: uppercase; letter-spacing: 1px; margin-bottom: 8px;
        }

        .metric-value {
            font-size: 32px; font-weight: 700;
            font-family: 'JetBrains Mono', monospace;
        }

        .metric-value.green { color: var(--accent-green); }
        .metric-value.blue  { color: var(--accent-blue); }
        .metric-value.purple { color: var(--accent-purple); }
        .metric-value.amber  { color: var(--accent-amber); }
        .metric-unit { font-size: 14px; color: var(--text-secondary); font-weight: 400; }

        .content {
            display: grid; grid-template-columns: 1fr 1fr;
            gap: 16px; padding: 0 32px 32px;
        }

        .panel {
            background: var(--bg-card); border: 1px solid var(--border);
            border-radius: 16px; overflow: hidden;
        }

        .panel-header {
            display: flex; align-items: center; justify-content: space-between;
            padding: 16px 20px; border-bottom: 1px solid var(--border);
        }

        .panel-title {
            font-size: 13px; font-weight: 600;
            text-transform: uppercase; letter-spacing: 1px; color: var(--text-secondary);
        }

        .log-container { height: 400px; overflow-y: auto; scroll-behavior: smooth; }
        .log-container::-webkit-scrollbar { width: 6px; }
        .log-container::-webkit-scrollbar-track { background: var(--bg-secondary); }
        .log-container::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }

        .log-entry {
            display: flex; align-items: flex-start; gap: 12px;
            padding: 12px 20px;
            border-bottom: 1px solid rgba(42,42,62,0.5);
            animation: slideIn 0.3s ease-out; font-size: 13px;
        }

        @keyframes slideIn {
            from { opacity: 0; transform: translateX(-20px); }
            to   { opacity: 1; transform: translateX(0); }
        }

        .log-time {
            font-family: 'JetBrains Mono', monospace;
            font-size: 11px; color: var(--text-muted); min-width: 70px;
        }

        .log-icon { font-size: 14px; }
        .log-message { color: var(--text-primary); line-height: 1.5; }

        .state-item {
            display: flex; justify-content: space-between; align-items: center;
            padding: 14px 20px; border-bottom: 1px solid rgba(42,42,62,0.5);
        }

        .state-key { font-size: 12px; color: var(--text-secondary); }

        .state-value {
            font-family: 'JetBrains Mono', monospace;
            font-size: 12px; color: var(--text-primary);
        }

        .footer {
            text-align: center; padding: 16px;
            color: var(--text-muted); font-size: 11px;
            border-top: 1px solid var(--border);
        }

        @media (max-width: 768px) {
            .metrics { grid-template-columns: repeat(2, 1fr); }
            .content { grid-template-columns: 1fr; }
        }
    </style>
</head>
<body>

    <div class="header">
        <div class="logo">
            <div class="logo-icon">⚡</div>
            <div>
                <div class="logo-text">AURA</div>
                <div class="logo-sub">Mirror Dashboard — Cloud Run + Firestore</div>
            </div>
        </div>
        <div class="status-badge">
            <div class="status-dot"></div>
            <span id="connectionStatus">Connecting...</span>
        </div>
    </div>

    <div class="metrics">
        <div class="metric-card">
            <div class="metric-label">Total Taps</div>
            <div class="metric-value green" id="metricTaps">0</div>
        </div>
        <div class="metric-card">
            <div class="metric-label">Avg Latency</div>
            <div class="metric-value blue" id="metricLatency">0<span class="metric-unit">ms</span></div>
        </div>
        <div class="metric-card">
            <div class="metric-label">Ghost Taps Prevented</div>
            <div class="metric-value purple" id="metricGhost">0</div>
        </div>
        <div class="metric-card">
            <div class="metric-label">Active App</div>
            <div class="metric-value amber" id="metricApp" style="font-size:16px">—</div>
        </div>
    </div>

    <div class="content">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">📋 Live Action Log</div>
                <div style="font-size:11px;color:var(--text-muted)" id="logCount">0 events</div>
            </div>
            <div class="log-container" id="logContainer">
                <div class="log-entry">
                    <span class="log-time">--:--:--</span>
                    <span class="log-icon">🔌</span>
                    <span class="log-message">Waiting for Aura to connect...</span>
                </div>
            </div>
        </div>

        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">📊 Session State</div>
            </div>
            <div id="statePanel">
                <div class="state-item">
                    <span class="state-key">Device ID</span>
                    <span class="state-value" id="stateDeviceId">—</span>
                </div>
                <div class="state-item">
                    <span class="state-key">Current App</span>
                    <span class="state-value" id="stateApp">—</span>
                </div>
                <div class="state-item">
                    <span class="state-key">Total Taps</span>
                    <span class="state-value" id="stateTaps">0</span>
                </div>
                <div class="state-item">
                    <span class="state-key">Ghost Taps Blocked</span>
                    <span class="state-value" id="stateGhost">0</span>
                </div>
                <div class="state-item">
                    <span class="state-key">Session Duration</span>
                    <span class="state-value" id="stateDuration">—</span>
                </div>
                <div class="state-item">
                    <span class="state-key">Firestore</span>
                    <span class="state-value" id="stateFirestore">checking...</span>
                </div>
                <div class="state-item">
                    <span class="state-key">Guardrail API</span>
                    <span class="state-value" style="color:var(--accent-green)">✅ Online</span>
                </div>
            </div>
        </div>
    </div>

    <div class="footer">
        Project Aura v4.0 — Gemini Live Agent Challenge — Powered by Google Cloud Platform
    </div>

    <script>
        // ══════════════════════════════════════════════════════════════
        //  LIVE DASHBOARD — polls /api/metrics and /api/log every 2s
        //  Real data from Android device → Cloud Run → Firestore
        // ══════════════════════════════════════════════════════════════

        const startTime = Date.now();
        let lastLogCount = 0;
        let initialized = false;

        setInterval(() => {
            const elapsed = Math.floor((Date.now() - startTime) / 1000);
            const mins = Math.floor(elapsed / 60);
            const secs = elapsed % 60;
            document.getElementById('stateDuration').textContent =
                `${mins}m ${secs.toString().padStart(2, '0')}s`;
        }, 1000);

        async function fetchMetrics() {
            try {
                const res = await fetch('/api/metrics');
                const data = await res.json();

                document.getElementById('metricTaps').textContent = data.total_taps;
                document.getElementById('metricLatency').innerHTML =
                    `${data.avg_latency_ms}<span class="metric-unit">ms</span>`;
                document.getElementById('metricGhost').textContent = data.ghost_taps_prevented;

                const pkg = data.active_package || '—';
                document.getElementById('metricApp').textContent = pkg.split('.').pop() || pkg;
                document.getElementById('stateApp').textContent = pkg;
                document.getElementById('stateDeviceId').textContent = data.active_device || '—';
                document.getElementById('stateTaps').textContent = data.total_taps;
                document.getElementById('stateGhost').textContent = data.ghost_taps_prevented;

                document.getElementById('stateFirestore').innerHTML = data.firestore_live
                    ? '<span style="color:var(--accent-green)">✅ Connected</span>'
                    : '<span style="color:var(--accent-amber)">⚠️ In-memory only</span>';

                if (!initialized) {
                    document.getElementById('connectionStatus').textContent = 'Live';
                    initialized = true;
                }
            } catch (e) {
                document.getElementById('connectionStatus').textContent = 'Reconnecting...';
            }
        }

        async function fetchLog() {
            try {
                const res = await fetch('/api/log?limit=50');
                const data = await res.json();
                const events = data.events || [];
                if (events.length === lastLogCount) return;

                const container = document.getElementById('logContainer');
                const newEvents = events.slice(lastLogCount);
                lastLogCount = events.length;

                if (newEvents.length > 0 && lastLogCount === newEvents.length) {
                    container.innerHTML = '';
                }

                newEvents.forEach(evt => {
                    const entry = document.createElement('div');
                    entry.className = 'log-entry';
                    entry.innerHTML = `
                        <span class="log-time">${evt.time}</span>
                        <span class="log-icon">${evt.icon}</span>
                        <span class="log-message">${evt.message}</span>
                    `;
                    container.appendChild(entry);
                });

                container.scrollTop = container.scrollHeight;
                document.getElementById('logCount').textContent = `${events.length} events`;
            } catch (e) { /* silently retry */ }
        }

        fetchMetrics();
        fetchLog();
        setInterval(fetchMetrics, 2000);
        setInterval(fetchLog, 2000);
    </script>

</body>
</html>"""


@app.get("/", response_class=HTMLResponse)
async def dashboard():
    """Serves the Mirror Dashboard for the hackathon demo video."""
    return DASHBOARD_HTML


# ═══════════════════════════════════════════════════════════════
#  ENTRYPOINT
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    uvicorn.run(app, host="0.0.0.0", port=port)
