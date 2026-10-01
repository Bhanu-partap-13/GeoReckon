"""
GeoReckon — Kotlin ↔ Python Connection Guide PDF Generator
SIH26168 — ISRO Smart India Hackathon
"""

from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm, mm
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    HRFlowable, KeepTogether, PageBreak
)
from reportlab.platypus.flowables import Flowable
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor
import os

# ── Color Palette ──────────────────────────────────────────────
C_BG        = HexColor("#0a0f1a")
C_GREEN     = HexColor("#00c96e")
C_BLUE      = HexColor("#0a84ff")
C_ORANGE    = HexColor("#ff9f0a")
C_RED       = HexColor("#ff453a")
C_DARK_GRAY = HexColor("#1c2535")
C_MID_GRAY  = HexColor("#374151")
C_LIGHT     = HexColor("#e5e7eb")
C_WHITE     = HexColor("#ffffff")
C_CODE_BG   = HexColor("#111827")
C_HEADING   = HexColor("#00d97e")
C_SUBHEAD   = HexColor("#60a5fa")

OUTPUT_PATH = os.path.join(os.path.dirname(__file__), "GeoReckon_Kotlin_Connection_Guide.pdf")

# ── Page Template ──────────────────────────────────────────────
def draw_page_background(canvas_obj, doc):
    canvas_obj.saveState()
    w, h = A4
    # Full dark background
    canvas_obj.setFillColor(C_BG)
    canvas_obj.rect(0, 0, w, h, fill=1, stroke=0)

    # Top accent bar
    canvas_obj.setFillColor(C_GREEN)
    canvas_obj.rect(0, h - 6*mm, w, 6*mm, fill=1, stroke=0)

    # Bottom bar
    canvas_obj.setFillColor(C_DARK_GRAY)
    canvas_obj.rect(0, 0, w, 12*mm, fill=1, stroke=0)

    # Footer text
    canvas_obj.setFillColor(C_MID_GRAY)
    canvas_obj.setFont("Helvetica", 7)
    canvas_obj.drawString(2*cm, 4*mm, "GeoReckon Production Engine v3.2 — SIH26168 — ISRO Smart India Hackathon 2026")
    canvas_obj.drawRightString(w - 2*cm, 4*mm, f"Page {doc.page}")

    canvas_obj.restoreState()


def draw_cover_page(canvas_obj, doc):
    draw_page_background(canvas_obj, doc)
    canvas_obj.saveState()
    w, h = A4

    # Big green top bar override for cover
    canvas_obj.setFillColor(C_GREEN)
    canvas_obj.rect(0, h - 45*mm, w, 45*mm, fill=1, stroke=0)

    # Cover title
    canvas_obj.setFillColor(C_BG)
    canvas_obj.setFont("Helvetica-Bold", 26)
    canvas_obj.drawCentredString(w/2, h - 22*mm, "GEORECKON")
    canvas_obj.setFont("Helvetica", 13)
    canvas_obj.drawCentredString(w/2, h - 33*mm, "Intelligent Dead Reckoning Engine — SIH26168")

    # Subtitle box
    canvas_obj.setFillColor(C_DARK_GRAY)
    canvas_obj.rect(2*cm, h - 75*mm, w - 4*cm, 22*mm, fill=1, stroke=0)
    canvas_obj.setFillColor(C_GREEN)
    canvas_obj.setFont("Helvetica-Bold", 14)
    canvas_obj.drawCentredString(w/2, h - 61*mm, "Kotlin Android App ↔ Python Backend")
    canvas_obj.setFillColor(C_LIGHT)
    canvas_obj.setFont("Helvetica", 10)
    canvas_obj.drawCentredString(w/2, h - 69*mm, "Complete Integration Guide — All Methods, Pros & Cons, Code Examples")

    # Info boxes
    boxes = [
        ("Document Type",  "Technical Integration Guide"),
        ("Project",        "GeoReckon v3.2 Production Engine"),
        ("Hackathon",      "ISRO SIH 2026 — Problem SIH26168"),
        ("Audience",       "Android Kotlin Developers"),
    ]
    y = h - 105*mm
    for label, value in boxes:
        canvas_obj.setFillColor(C_DARK_GRAY)
        canvas_obj.rect(2*cm, y - 5*mm, w - 4*cm, 9*mm, fill=1, stroke=0)
        canvas_obj.setFillColor(C_GREEN)
        canvas_obj.setFont("Helvetica-Bold", 8)
        canvas_obj.drawString(2.5*cm, y, label)
        canvas_obj.setFillColor(C_LIGHT)
        canvas_obj.setFont("Helvetica", 8)
        canvas_obj.drawRightString(w - 2.5*cm, y, value)
        y -= 12*mm

    # Quick summary
    canvas_obj.setFillColor(C_DARK_GRAY)
    canvas_obj.rect(2*cm, y - 10*mm, w - 4*cm, 30*mm, fill=1, stroke=0)
    canvas_obj.setFillColor(C_SUBHEAD)
    canvas_obj.setFont("Helvetica-Bold", 9)
    canvas_obj.drawString(2.5*cm, y + 14*mm, "RECOMMENDED METHOD AT A GLANCE")
    canvas_obj.setFillColor(C_LIGHT)
    canvas_obj.setFont("Helvetica", 8)
    lines = [
        "• Run GeoReckon as a Flask/SocketIO WebSocket server on your laptop or cloud",
        "• Kotlin app reads phone sensors (Accel, Gyro, Gravity) at 10 Hz",
        "• Kotlin sends sensor JSON to server via OkHttp WebSocket every 100ms",
        "• Server processes data through all 6 pipeline stages and returns position",
        "• Kotlin receives { lat, lon, speed, confidence } and updates the app map",
    ]
    for i, line in enumerate(lines):
        canvas_obj.drawString(2.5*cm, y + 6*mm - i*5*mm, line)

    canvas_obj.restoreState()

# ── Style Definitions ──────────────────────────────────────────
def make_styles():
    base = getSampleStyleSheet()

    def S(name, **kwargs):
        return ParagraphStyle(name, **kwargs)

    styles = {
        "h1": S("h1",
            fontName="Helvetica-Bold", fontSize=16, textColor=C_HEADING,
            spaceAfter=6, spaceBefore=14, leading=20),
        "h2": S("h2",
            fontName="Helvetica-Bold", fontSize=12, textColor=C_SUBHEAD,
            spaceAfter=4, spaceBefore=10, leading=16),
        "h3": S("h3",
            fontName="Helvetica-Bold", fontSize=10, textColor=C_ORANGE,
            spaceAfter=3, spaceBefore=8, leading=14),
        "body": S("body",
            fontName="Helvetica", fontSize=8.5, textColor=C_LIGHT,
            spaceAfter=4, leading=13, alignment=TA_JUSTIFY),
        "code": S("code",
            fontName="Courier", fontSize=7.5, textColor=HexColor("#a5f3c0"),
            backColor=C_CODE_BG, spaceAfter=2, leading=11,
            leftIndent=8, rightIndent=8),
        "code_label": S("code_label",
            fontName="Helvetica-Bold", fontSize=7, textColor=C_MID_GRAY,
            spaceAfter=0, spaceBefore=6),
        "bullet": S("bullet",
            fontName="Helvetica", fontSize=8.5, textColor=C_LIGHT,
            spaceAfter=2, leading=13, leftIndent=12),
        "note": S("note",
            fontName="Helvetica-Oblique", fontSize=8, textColor=C_ORANGE,
            spaceAfter=4, leading=12, leftIndent=8),
        "pro": S("pro",
            fontName="Helvetica", fontSize=8.5, textColor=HexColor("#4ade80"),
            spaceAfter=2, leading=13, leftIndent=12),
        "con": S("con",
            fontName="Helvetica", fontSize=8.5, textColor=HexColor("#f87171"),
            spaceAfter=2, leading=13, leftIndent=12),
        "center": S("center",
            fontName="Helvetica", fontSize=8.5, textColor=C_LIGHT,
            spaceAfter=4, leading=13, alignment=TA_CENTER),
        "tag_green": S("tag_green",
            fontName="Helvetica-Bold", fontSize=8, textColor=C_BG,
            backColor=C_GREEN, spaceAfter=4, leading=12),
        "tag_red": S("tag_red",
            fontName="Helvetica-Bold", fontSize=8, textColor=C_WHITE,
            backColor=C_RED, spaceAfter=4, leading=12),
        "tag_orange": S("tag_orange",
            fontName="Helvetica-Bold", fontSize=8, textColor=C_BG,
            backColor=C_ORANGE, spaceAfter=4, leading=12),
    }
    return styles

# ── Helper Flowables ───────────────────────────────────────────
def HR(color=C_MID_GRAY, thickness=0.5):
    return HRFlowable(width="100%", thickness=thickness, color=color, spaceAfter=4, spaceBefore=4)

def SP(h=6):
    return Spacer(1, h)

def section_header(text, styles):
    return [
        SP(8),
        Paragraph(text, styles["h1"]),
        HR(C_GREEN, 1),
    ]

def code_block(label, code_lines, styles):
    blocks = [Paragraph(f"// {label}", styles["code_label"])]
    for line in code_lines:
        blocks.append(Paragraph(line.replace(" ", "&nbsp;").replace("<", "&lt;").replace(">", "&gt;"), styles["code"]))
    return blocks

def method_tag(text, method_type, styles):
    style_key = {"recommended": "tag_green", "hard": "tag_red", "medium": "tag_orange"}.get(method_type, "tag_green")
    return Paragraph(f"  {text}  ", styles[style_key])

def pros_cons_table(pros, cons):
    data = [
        [Paragraph("✅  PROS", ParagraphStyle("ph", fontName="Helvetica-Bold", fontSize=8, textColor=HexColor("#4ade80"))),
         Paragraph("❌  CONS", ParagraphStyle("ph", fontName="Helvetica-Bold", fontSize=8, textColor=HexColor("#f87171")))],
    ]
    max_len = max(len(pros), len(cons))
    for i in range(max_len):
        pro_text = f"• {pros[i]}" if i < len(pros) else ""
        con_text = f"• {cons[i]}" if i < len(cons) else ""
        data.append([
            Paragraph(pro_text, ParagraphStyle("p", fontName="Helvetica", fontSize=8, textColor=HexColor("#4ade80"), leading=11)),
            Paragraph(con_text, ParagraphStyle("p", fontName="Helvetica", fontSize=8, textColor=HexColor("#f87171"), leading=11)),
        ])

    t = Table(data, colWidths=[9.5*cm, 9.5*cm])
    t.setStyle(TableStyle([
        ("BACKGROUND",  (0, 0), (-1, 0), HexColor("#1a2535")),
        ("BACKGROUND",  (0, 1), (-1, -1), C_CODE_BG),
        ("GRID",        (0, 0), (-1, -1), 0.3, C_MID_GRAY),
        ("TOPPADDING",  (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING",(0, 0), (-1, -1), 4),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING",(0, 0), (-1, -1), 6),
        ("VALIGN",      (0, 0), (-1, -1), "TOP"),
    ]))
    return t

def comparison_table(rows):
    header = ["Method", "Difficulty", "Offline?", "No Port Needed?", "Best For"]
    all_data = [
        [Paragraph(h, ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)) for h in header]
    ] + [
        [Paragraph(str(c), ParagraphStyle("c", fontName="Helvetica", fontSize=8,
                   textColor=HexColor("#4ade80") if i == 0 else C_LIGHT, leading=11))
         for i, c in enumerate(row)]
        for row in rows
    ]
    t = Table(all_data, colWidths=[4.5*cm, 2.5*cm, 2*cm, 3.5*cm, 6.5*cm])
    t.setStyle(TableStyle([
        ("BACKGROUND",   (0, 0), (-1, 0), HexColor("#1a2535")),
        ("BACKGROUND",   (0, 1), (-1, 1), HexColor("#0d1f14")),  # highlight recommended
        ("BACKGROUND",   (0, 2), (-1, -1), C_CODE_BG),
        ("GRID",         (0, 0), (-1, -1), 0.3, C_MID_GRAY),
        ("TOPPADDING",   (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING",(0, 0), (-1, -1), 4),
        ("LEFTPADDING",  (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ]))
    return t

# ── Build Document ─────────────────────────────────────────────
def build_pdf():
    doc = SimpleDocTemplate(
        OUTPUT_PATH,
        pagesize=A4,
        leftMargin=2*cm, rightMargin=2*cm,
        topMargin=2.5*cm, bottomMargin=2*cm,
        title="GeoReckon Kotlin Connection Guide",
        author="GeoReckon Team — SIH26168",
    )

    S = make_styles()
    story = []

    # ── COVER PAGE ─────────────────────────────────────────────
    story.append(Spacer(1, 12*cm))  # placeholder; cover drawn via onFirstPage
    story.append(PageBreak())

    # ── PAGE 2: OVERVIEW ───────────────────────────────────────
    story += section_header("1. Overview — The Integration Problem", S)
    story.append(Paragraph(
        "GeoReckon is written in Python and runs on a laptop or cloud server. "
        "Your Android navigation app is written in Kotlin and runs on a smartphone. "
        "These two need to communicate so the phone's IMU sensors can feed the GeoReckon "
        "pipeline and the computed position can update the map in real time.",
        S["body"]))
    story.append(SP())

    story.append(Paragraph("The Data Flow", S["h2"]))
    flow_data = [
        [Paragraph("Step", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)),
         Paragraph("What Happens", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)),
         Paragraph("Who Does It", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("1", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_GREEN)),
         Paragraph("Phone reads Accelerometer + Gyroscope + Gravity at 10 Hz", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11)),
         Paragraph("Kotlin App", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("2", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_GREEN)),
         Paragraph("GPS is lost (tunnel/underpass) — dead reckoning begins", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11)),
         Paragraph("Kotlin detects", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("3", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_GREEN)),
         Paragraph("Kotlin sends {accel, gyro, gravity, dt} as JSON every 100ms", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11)),
         Paragraph("Kotlin → Server", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("4", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_GREEN)),
         Paragraph("Server runs 6-stage GeoReckon pipeline on received data", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11)),
         Paragraph("Python Server", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("5", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_GREEN)),
         Paragraph("Server returns {position_enu, speed_kmh, confidence} instantly", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11)),
         Paragraph("Server → Kotlin", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("6", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_GREEN)),
         Paragraph("Kotlin updates map dot with estimated position", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11)),
         Paragraph("Kotlin App", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT))],
    ]
    ft = Table(flow_data, colWidths=[1.5*cm, 11*cm, 3*cm])
    ft.setStyle(TableStyle([
        ("BACKGROUND",   (0, 0), (-1, 0), HexColor("#1a2535")),
        ("BACKGROUND",   (0, 1), (-1, -1), C_CODE_BG),
        ("GRID",         (0, 0), (-1, -1), 0.3, C_MID_GRAY),
        ("TOPPADDING",   (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING",(0, 0), (-1, -1), 5),
        ("LEFTPADDING",  (0, 0), (-1, -1), 6),
        ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ]))
    story.append(ft)
    story.append(SP(8))

    story.append(Paragraph("Methods Comparison at a Glance", S["h2"]))
    story.append(comparison_table([
        ["✅ WebSocket (Recommended)", "⭐ Easy", "No", "Yes", "Hackathon demo, real-time dashboard"],
        ["REST API (HTTP)", "⭐ Easy", "No", "Yes", "Simple polling, non-real-time"],
        ["Chaquopy (Python on Android)", "⭐⭐ Medium", "Yes", "Yes", "Offline prototype — but large APK"],
        ["Native Kotlin Port", "⭐⭐⭐ Hard", "Yes", "No", "Production app — requires full rewrite"],
        ["JNI / C++ NDK", "⭐⭐⭐⭐ Very Hard", "Yes", "No", "High-performance — not feasible for SIH"],
    ]))

    story.append(PageBreak())

    # ── PAGE 3: RECOMMENDED METHOD ─────────────────────────────
    story += section_header("2. Recommended Method — WebSocket Bridge  ✅", S)
    story.append(Paragraph(
        "WebSocket gives a persistent two-way connection between the Kotlin app and the Python server. "
        "Unlike HTTP (which opens a new connection for every request), WebSocket stays open continuously "
        "— perfect for streaming 10 sensor readings per second with minimal overhead.",
        S["body"]))
    story.append(SP(4))

    story.append(Paragraph("Why WebSocket over plain HTTP?", S["h3"]))
    for line in [
        "• HTTP adds 30–100ms overhead per request (TCP handshake + headers) → too slow for 10 Hz streaming",
        "• WebSocket adds only 2–5ms per message after the initial connection — fast enough for real-time DR",
        "• WebSocket is bidirectional — server can push position updates without the app polling",
        "• Flask-SocketIO library makes this trivial to set up in Python (4 lines of code)",
        "• OkHttp WebSocket client in Kotlin is stable, production-grade, already in most Android apps",
    ]:
        story.append(Paragraph(line, S["bullet"]))

    story.append(SP(6))
    story.append(Paragraph("Pros & Cons", S["h2"]))
    story.append(pros_cons_table(
        pros=["Real-time — sub 10ms latency per message",
              "Bidirectional — server pushes to app",
              "No phone restart needed — stays connected",
              "Works over WiFi and mobile data (4G/5G)",
              "Works with ngrok for instant public URL",
              "No Android code changes needed for pipeline updates",
              "Can connect multiple phones simultaneously"],
        cons=["Requires internet/WiFi (no offline in tunnel)",
              "Python server must be running",
              "If WiFi drops, must reconnect",
              "For production: needs a hosted cloud server"],
    ))

    story.append(SP(8))

    # ── Step-by-step setup ─────────────────────────────────────
    story.append(Paragraph("Step-by-Step Setup", S["h2"]))

    story.append(Paragraph("Step 1 — Install Python dependencies", S["h3"]))
    story += code_block("Terminal — run in your project folder", [
        "pip install flask flask-socketio flask-cors",
    ], S)

    story.append(Paragraph("Step 2 — Create server.py in your GeoReckon project", S["h3"]))
    server_code = [
        "# georeckon/web_server.py",
        "from flask import Flask",
        "from flask_socketio import SocketIO, emit",
        "from flask_cors import CORS",
        "import numpy as np, sys, os",
        "",
        "sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))",
        "from georeckon.src.virtual_alignment   import VirtualInVehicleAligner",
        "from georeckon.src.vibration_filter    import KinematicVibrationFilter",
        "from georeckon.src.kinematics_speed_estimator import KinematicSpeedEstimator",
        "",
        "app = Flask(__name__)",
        "CORS(app)",
        "socketio = SocketIO(app, cors_allowed_origins='*', async_mode='threading')",
        "",
        "aligner    = VirtualInVehicleAligner()",
        "vib_filter = KinematicVibrationFilter(sampling_rate=10.0)",
        "speed_est  = KinematicSpeedEstimator(dt=0.1)",
        "state      = {'pos': [0.0, 0.0], 'heading': 0.0, 'mode': 'GPS'}",
        "accel_buf, gyro_buf = [], []",
        "",
        "@socketio.on('calibrate')",
        "def on_calibrate(data):",
        "    R, health = aligner.compute_alignment_from_history(",
        "        np.array(data['gravity_h']), np.array(data['accel_h']),",
        "        np.array(data['speed_h']),  np.array(data['time_h']))",
        "    speed_est.sync_gnss_speed(float(data['entry_speed']))",
        "    emit('calibrate_done', {'health': float(health)})",
        "",
        "@socketio.on('sensor')",
        "def on_sensor(data):",
        "    global accel_buf, gyro_buf",
        "    acc = np.array(data['accel'])",
        "    gyr = np.array(data['gyro'])",
        "    grav = np.array(data.get('gravity', [0,0,9.8]))",
        "    dt  = float(data.get('dt', 0.1))",
        "    accel_buf.append(acc); gyro_buf.append(gyr)",
        "    if len(accel_buf) > 10: accel_buf.pop(0); gyro_buf.pop(0)",
        "    ca, cw, shock = vib_filter.update_sample(acc, gyr)",
        "    lv = aligner.R_b_to_v @ (ca - grav)",
        "    wv = aligner.R_b_to_v @ cw",
        "    spd, stopped = speed_est.estimate_step(",
        "        float(lv[0]), float(lv[1]), float(wv[2]),",
        "        np.array(accel_buf), np.array(gyro_buf), dt)",
        "    state['heading']  += float(wv[2]) * dt",
        "    state['pos'][0]   += spd * np.cos(state['heading']) * dt",
        "    state['pos'][1]   += spd * np.sin(state['heading']) * dt",
        "    emit('position', {",
        "        'pos_enu':   state['pos'],",
        "        'speed_kmh': round(spd * 3.6, 1),",
        "        'heading':   float(np.degrees(state['heading'])),",
        "        'mode':      'DR'",
        "    })",
        "",
        "if __name__ == '__main__':",
        "    print('GeoReckon server at http://0.0.0.0:5000')",
        "    socketio.run(app, host='0.0.0.0', port=5000)",
    ]
    story += code_block("georeckon/web_server.py — complete server file", server_code, S)

    story.append(Paragraph("Step 3 — Run the server", S["h3"]))
    story += code_block("Terminal", [
        "cd c:\\Users\\...\\sih_Bhanu",
        "python -m georeckon.web_server",
        "",
        "# You will see:",
        "# GeoReckon server at http://0.0.0.0:5000",
        "# Your IP address (run: ipconfig) e.g. 192.168.1.42",
    ], S)

    story.append(PageBreak())

    # ── PAGE 4: KOTLIN CODE ────────────────────────────────────
    story += section_header("3. Kotlin Android App — Connection Code", S)

    story.append(Paragraph("Step 4 — Add OkHttp dependency to build.gradle", S["h3"]))
    story += code_block("app/build.gradle", [
        "dependencies {",
        "    // OkHttp for WebSocket",
        "    implementation 'com.squareup.okhttp3:okhttp:4.12.0'",
        "",
        "    // Gson for JSON serialization",
        "    implementation 'com.google.code.gson:gson:2.10.1'",
        "}",
    ], S)

    story.append(Paragraph("Step 5 — Add Internet permission to AndroidManifest.xml", S["h3"]))
    story += code_block("AndroidManifest.xml", [
        "<uses-permission android:name='android.permission.INTERNET'/>",
        "<uses-permission android:name='android.permission.ACCESS_NETWORK_STATE'/>",
    ], S)

    story.append(Paragraph("Step 6 — Create GeoReckonClient.kt", S["h3"]))
    kotlin_code = [
        "// GeoReckonClient.kt",
        "import okhttp3.*",
        "import org.json.JSONObject",
        "import android.util.Log",
        "",
        "class GeoReckonClient(private val serverUrl: String) {",
        "",
        "    // serverUrl example: 'ws://192.168.1.42:5000/socket.io/?EIO=4&transport=websocket'",
        "",
        "    private var ws: WebSocket? = null",
        "    private val client = OkHttpClient()",
        "    var onPosition: ((Double, Double, Double, Double) -> Unit)? = null",
        "    // callback: (easting_m, northing_m, speed_kmh, heading_deg)",
        "",
        "    fun connect() {",
        "        val request = Request.Builder().url(serverUrl).build()",
        "        ws = client.newWebSocket(request, object : WebSocketListener() {",
        "",
        "            override fun onOpen(webSocket: WebSocket, response: Response) {",
        "                Log.d('GeoReckon', 'Connected to server')",
        "                // Send connect event (Socket.IO protocol)",
        "                webSocket.send('40')  // Socket.IO CONNECT packet",
        "            }",
        "",
        "            override fun onMessage(webSocket: WebSocket, text: String) {",
        "                handleMessage(text)",
        "            }",
        "",
        "            override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {",
        "                Log.e('GeoReckon', 'Connection failed: ${t.message}')",
        "            }",
        "        })",
        "    }",
        "",
        "    fun sendSensorData(accel: FloatArray, gyro: FloatArray,",
        "                        gravity: FloatArray, dt: Float) {",
        "        val json = JSONObject().apply {",
        "            put('accel',   accel.toList())",
        "            put('gyro',    gyro.toList())",
        "            put('gravity', gravity.toList())",
        "            put('dt',      dt)",
        "        }",
        "        // Socket.IO event format: 42[\"sensor\", {data}]",
        "        ws?.send('42[\"sensor\",$json]')",
        "    }",
        "",
        "    fun sendCalibration(gravityH: List<List<Float>>, accelH: List<List<Float>>,",
        "                         speedH: List<Float>, timeH: List<Float>,",
        "                         entrySpeed: Float) {",
        "        val json = JSONObject().apply {",
        "            put('gravity_h',   gravityH)",
        "            put('accel_h',     accelH)",
        "            put('speed_h',     speedH)",
        "            put('time_h',      timeH)",
        "            put('entry_speed', entrySpeed)",
        "        }",
        "        ws?.send('42[\"calibrate\",$json]')",
        "    }",
        "",
        "    private fun handleMessage(text: String) {",
        "        // Filter Socket.IO position events: 42[\"position\", {...}]",
        "        if (text.startsWith('42[\"position\"')) {",
        "            val payload = text.substringAfter(',').dropLast(1)",
        "            val data    = JSONObject(payload)",
        "            val posEnu  = data.getJSONArray('pos_enu')",
        "            onPosition?.invoke(",
        "                posEnu.getDouble(0),           // east meters",
        "                posEnu.getDouble(1),           // north meters",
        "                data.getDouble('speed_kmh'),   // km/h",
        "                data.getDouble('heading')      // degrees",
        "            )",
        "        }",
        "    }",
        "",
        "    fun disconnect() { ws?.close(1000, 'User closed'); client.dispatcher.executorService.shutdown() }",
        "}",
    ]
    story += code_block("GeoReckonClient.kt", kotlin_code, S)

    story.append(PageBreak())

    # ── PAGE 5: MainActivity Integration ───────────────────────
    story += section_header("4. Using It in Your MainActivity.kt", S)

    story.append(Paragraph("Step 7 — Wire sensors + GeoReckon client in your Activity", S["h3"]))
    main_code = [
        "// MainActivity.kt (relevant parts)",
        "class MainActivity : AppCompatActivity(), SensorEventListener {",
        "",
        "    // ─── 1. Setup ───────────────────────────────────",
        "    private val SERVER_URL = 'ws://192.168.1.42:5000/socket.io/?EIO=4&transport=websocket'",
        "    // ↑ Replace with your laptop IP after running: ipconfig",
        "",
        "    private lateinit var geoReckon: GeoReckonClient",
        "    private lateinit var sensorManager: SensorManager",
        "    private var isDRMode = false",
        "    private var lastAccel   = floatArrayOf(0f, 0f, 9.8f)",
        "    private var lastGyro    = floatArrayOf(0f, 0f, 0f)",
        "    private var lastGravity = floatArrayOf(0f, 0f, 9.8f)",
        "    private var lastTime    = 0L",
        "",
        "    // History buffers for calibration (last 60 seconds)",
        "    private val accelHistory   = ArrayDeque<FloatArray>()",
        "    private val gravityHistory = ArrayDeque<FloatArray>()",
        "    private val speedHistory   = ArrayDeque<Float>()",
        "    private val timeHistory    = ArrayDeque<Float>()",
        "",
        "    override fun onCreate(savedInstanceState: Bundle?) {",
        "        super.onCreate(savedInstanceState)",
        "",
        "        // ─── Connect to GeoReckon server ────────────",
        "        geoReckon = GeoReckonClient(SERVER_URL)",
        "        geoReckon.onPosition = { east, north, speed, heading ->",
        "            runOnUiThread {",
        "                updateMapMarker(east, north)   // your map update function",
        "                updateSpeedDisplay(speed)",
        "            }",
        "        }",
        "        geoReckon.connect()",
        "",
        "        // ─── Register sensors at 10 Hz (100,000 μs) ─",
        "        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager",
        "        sensorManager.registerListener(this,",
        "            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), 100_000)",
        "        sensorManager.registerListener(this,",
        "            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), 100_000)",
        "        sensorManager.registerListener(this,",
        "            sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), 100_000)",
        "    }",
        "",
        "    override fun onSensorChanged(event: SensorEvent) {",
        "        when (event.sensor.type) {",
        "            Sensor.TYPE_LINEAR_ACCELERATION -> lastAccel   = event.values.clone()",
        "            Sensor.TYPE_GYROSCOPE           -> lastGyro    = event.values.clone()",
        "            Sensor.TYPE_GRAVITY             -> lastGravity = event.values.clone()",
        "        }",
        "        // Store history for calibration (max 600 samples = 60s at 10Hz)",
        "        if (!isDRMode) {",
        "            accelHistory.addLast(lastAccel.clone())",
        "            gravityHistory.addLast(lastGravity.clone())",
        "            if (accelHistory.size > 600) { accelHistory.removeFirst(); gravityHistory.removeFirst() }",
        "        }",
        "        // Send to GeoReckon every 100ms when in DR mode",
        "        val now = System.currentTimeMillis()",
        "        if (isDRMode && now - lastTime >= 100) {",
        "            lastTime = now",
        "            geoReckon.sendSensorData(lastAccel, lastGyro, lastGravity, 0.1f)",
        "        }",
        "    }",
        "",
        "    // ─── Call this when GPS signal is lost ──────────",
        "    fun onGpsLost(lastKnownSpeedMs: Float) {",
        "        isDRMode = true",
        "        // Send calibration data to server",
        "        geoReckon.sendCalibration(",
        "            gravityHistory = gravityHistory.map { it.toList() },",
        "            accelH  = accelHistory.map { it.toList() },",
        "            speedH  = speedHistory.toList(),",
        "            timeH   = timeHistory.toList(),",
        "            entrySpeed = lastKnownSpeedMs",
        "        )",
        "    }",
        "",
        "    // ─── Call this when GPS comes back ───────────────",
        "    fun onGpsRestored() {",
        "        isDRMode = false",
        "    }",
        "",
        "    override fun onDestroy() {",
        "        super.onDestroy()",
        "        geoReckon.disconnect()",
        "        sensorManager.unregisterListener(this)",
        "    }",
        "    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}",
        "}",
    ]
    story += code_block("MainActivity.kt", main_code, S)

    story.append(PageBreak())

    # ── PAGE 6: HOSTING ────────────────────────────────────────
    story += section_header("5. Hosting — Making It Publicly Accessible", S)

    story.append(Paragraph("Option A — Demo Day (ngrok) — Easiest ✅", S["h2"]))
    story.append(Paragraph(
        "ngrok creates a public HTTPS/WSS tunnel to your laptop in one command. "
        "Perfect for demos where you need a public URL instantly. Free tier works fine.",
        S["body"]))
    story += code_block("Terminal — run after starting your server", [
        "# Install ngrok: https://ngrok.com/download (free account)",
        "ngrok http 5000",
        "",
        "# ngrok will print something like:",
        "# Forwarding  https://abc123.ngrok-free.app -> http://localhost:5000",
        "# Forwarding  wss://abc123.ngrok-free.app   -> ws://localhost:5000",
        "",
        "# In your Kotlin app, change SERVER_URL to:",
        "# wss://abc123.ngrok-free.app/socket.io/?EIO=4&transport=websocket",
        "",
        "# Now your phone can connect from ANYWHERE — demo on 4G, not just WiFi!",
    ], S)

    story.append(Paragraph("Option B — Same WiFi (Direct IP) — Fastest", S["h2"]))
    story += code_block("Terminal", [
        "# Find your laptop IP:",
        "ipconfig         # Windows",
        "ifconfig         # Mac/Linux",
        "# Look for IPv4 Address, e.g. 192.168.1.42",
        "",
        "# In Kotlin: SERVER_URL = 'ws://192.168.1.42:5000/socket.io/?...'",
        "# Phone and laptop must be on same WiFi",
    ], S)

    story.append(Paragraph("Option C — Cloud Server (Production) — Most Robust", S["h2"]))
    story.append(Paragraph(
        "For a production app or if you want the server always available without a laptop:",
        S["body"]))
    for line in [
        "• Railway.app (free tier) — Deploy with 'railway up' in one command",
        "• Render.com (free tier) — Connect GitHub repo, auto-deploy",
        "• Google Cloud Run — Scales to zero when not in use, free quota",
        "• DigitalOcean Droplet — $6/month, full control",
    ]:
        story.append(Paragraph(line, S["bullet"]))

    story += code_block("Railway deployment (cloud hosting in 3 commands)", [
        "npm install -g @railway/cli",
        "railway login",
        "railway up",
        "# Railway gives you a permanent URL like: https://georeckon.up.railway.app",
        "# Update SERVER_URL in Kotlin to this URL (wss://georeckon.up.railway.app/socket.io/...)",
    ], S)

    story.append(PageBreak())

    # ── PAGE 7: OTHER METHODS ──────────────────────────────────
    story += section_header("6. Other Methods — Why They Are Harder / Less Feasible", S)

    # Method 2: REST API
    story.append(Paragraph("Method 2 — REST API (HTTP Polling)", S["h2"]))
    story.append(Paragraph(
        "Instead of a persistent WebSocket, the Kotlin app makes an HTTP POST request every 100ms. "
        "This works but is significantly slower and more wasteful.",
        S["body"]))
    story.append(Paragraph("Why it is LESS FEASIBLE for real-time DR:", S["h3"]))
    for line in [
        "• Each HTTP request opens a new TCP connection → 30–80ms overhead every call",
        "• At 10 Hz you'd need 10 connections/second → 300–800ms latency overhead = useless",
        "• Headers sent every request waste bandwidth (500+ bytes vs 50 bytes for WebSocket frame)",
        "• Server cannot push to app — app must constantly poll even when nothing changed",
        "• HTTP 1.1 keep-alive partially helps, but still far slower than WebSocket",
        "→ VERDICT: Use HTTP only if you drop to 1 Hz or 2 Hz sampling (not suitable for DR accuracy)",
    ]:
        story.append(Paragraph(line, S["bullet"]))
    story.append(pros_cons_table(
        pros=["Simpler to implement — basic Retrofit calls",
              "No special library needed",
              "Works anywhere HTTP works"],
        cons=["30–80ms overhead per request (too slow for 10 Hz)",
              "Cannot push from server to app",
              "Much higher battery drain from repeated connections",
              "Not suitable for real-time dead reckoning"],
    ))
    story.append(SP(8))

    # Method 3: Chaquopy
    story.append(Paragraph("Method 3 — Chaquopy (Run Python On the Phone)", S["h2"]))
    story.append(Paragraph(
        "Chaquopy is a Gradle plugin that embeds a Python interpreter inside the Android APK. "
        "Your Python files literally run on the phone — no server needed.",
        S["body"]))
    story.append(Paragraph("Why it is LESS FEASIBLE for GeoReckon:", S["h3"]))
    for line in [
        "• APK size explodes to 80–150 MB (numpy alone is 40 MB) — users won't install this",
        "• PyTorch (used by KinoNet) is NOT supported by Chaquopy at all",
        "• scipy is not officially supported — the Butterworth filter won't work",
        "• Python runs 5–10x slower on Android compared to laptop → may miss 100ms deadline",
        "• Cannot update the pipeline without updating the entire APK through Play Store",
        "• Debugging is extremely difficult (no proper Android debugger for Python)",
        "→ VERDICT: Only feasible if you strip out KinoNet and scipy and only use numpy",
    ]:
        story.append(Paragraph(line, S["bullet"]))
    story.append(pros_cons_table(
        pros=["100% offline — no server, no internet",
              "No code changes to Python pipeline",
              "Works even in tunnel with no connectivity"],
        cons=["APK size 80–150 MB (too large for most users)",
              "PyTorch not supported — KinoNet breaks",
              "scipy not officially supported — filter breaks",
              "5–10x slower execution on phone",
              "Very hard to debug",
              "Cannot update pipeline without app update"],
    ))

    story.append(PageBreak())

    # Method 4: Native Kotlin Port
    story.append(Paragraph("Method 4 — Native Kotlin Port (Rewrite Everything)", S["h2"]))
    story.append(Paragraph(
        "Port every Python file (geometry.py, virtual_alignment.py, filter_inekf.py, etc.) "
        "to native Kotlin/Java. The algorithm runs entirely on-device with no server.",
        S["body"]))
    story.append(Paragraph("Why it is LESS FEASIBLE for SIH hackathon timeframe:", S["h3"]))
    for line in [
        "• 6 Python source files with complex math must be completely rewritten in Kotlin",
        "• The InEKF filter requires 15x15 matrix operations — no standard Kotlin matrix library",
        "• scipy.signal.filtfilt (zero-phase filter) must be implemented from scratch",
        "• numpy's eigh() (eigenvalue decomposition for PCA) must be manually implemented",
        "• Total estimated effort: 6–10 weeks for a single developer (vs 1 day for WebSocket)",
        "• Any bug in the Python pipeline must be fixed in TWO places (Python + Kotlin)",
        "• SIH judges cannot easily verify the algorithm — the Python version is already audited",
        "→ VERDICT: This is the right long-term production approach but NOT for a hackathon demo",
    ]:
        story.append(Paragraph(line, S["bullet"]))
    story.append(pros_cons_table(
        pros=["100% offline — works anywhere including tunnels",
              "Best performance — native JVM code",
              "No server dependency in production",
              "Small APK size"],
        cons=["6–10 weeks development time",
              "Full rewrite of all 6 pipeline modules",
              "No scipy/numpy — all math from scratch",
              "Bugs must be fixed in two codebases",
              "InEKF 15x15 matrix math is very hard in Kotlin",
              "NOT feasible within hackathon timeline"],
    ))
    story.append(SP(8))

    # Method 5: JNI/NDK
    story.append(Paragraph("Method 5 — JNI / C++ NDK (Compile for Android)", S["h2"]))
    story.append(Paragraph(
        "Rewrite the core GeoReckon math in C++, compile it to a shared library (.so), "
        "and call it from Kotlin via the Android NDK's Java Native Interface (JNI).",
        S["body"]))
    story.append(Paragraph("Why it is EXTREMELY HARD and not feasible:", S["h3"]))
    for line in [
        "• Requires expert-level knowledge of C++, CMake, Android NDK, and JNI bindings",
        "• Eigen (C++ matrix library) must be integrated — complex build configuration",
        "• Memory management is manual (no garbage collection) → risk of crashes/leaks",
        "• Debugging native crashes on Android requires specialized tools (LLDB, AddressSanitizer)",
        "• Estimated effort: 12–16 weeks for an experienced C++ Android developer",
        "• Cross-compilation toolchain setup alone can take days to get right",
        "→ VERDICT: Used by production navigation apps (Google Maps, HERE) after years of development",
        "→ VERDICT: Absolutely not feasible for a 36–48 hour hackathon",
    ]:
        story.append(Paragraph(line, S["bullet"]))
    story.append(pros_cons_table(
        pros=["Absolute maximum performance",
              "Works offline",
              "Used by production-grade navigation apps"],
        cons=["12–16 weeks development time minimum",
              "Requires expert C++ + NDK + JNI knowledge",
              "Manual memory management — risk of crashes",
              "Extremely complex debugging",
              "Not remotely feasible for hackathon",
              "Much harder than WebSocket for same result"],
    ))

    story.append(PageBreak())

    # ── PAGE 8: QUICK REFERENCE ────────────────────────────────
    story += section_header("7. Quick Reference — 30 Minute Setup", S)

    story.append(Paragraph("Everything you need to go from zero to connected in 30 minutes:", S["body"]))
    story.append(SP(4))

    steps = [
        ("1", "Install deps", "pip install flask flask-socketio flask-cors"),
        ("2", "Add server",   "Create georeckon/web_server.py (see Page 3)"),
        ("3", "Run server",   "python -m georeckon.web_server"),
        ("4", "Find IP",      "Run: ipconfig → note IPv4 e.g. 192.168.1.42"),
        ("5", "Kotlin dep",   "Add OkHttp: implementation 'com.squareup.okhttp3:okhttp:4.12.0'"),
        ("6", "Manifest",     "Add: <uses-permission android:name='android.permission.INTERNET'/>"),
        ("7", "Client code",  "Add GeoReckonClient.kt to your project (see Page 4)"),
        ("8", "Wire sensors", "Add sensor registration + geoReckon.sendSensorData() (see Page 5)"),
        ("9", "Set URL",      "SERVER_URL = 'ws://192.168.1.42:5000/socket.io/?EIO=4&transport=websocket'"),
        ("10", "Test!",       "Run app on phone (same WiFi as laptop) → GPS loss → position updates!"),
    ]
    step_data = [
        [Paragraph("#", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)),
         Paragraph("Action", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)),
         Paragraph("Command / File", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT))]
    ]
    for num, action, cmd in steps:
        step_data.append([
            Paragraph(num, ParagraphStyle("n", fontName="Helvetica-Bold", fontSize=9, textColor=C_GREEN)),
            Paragraph(action, ParagraphStyle("a", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)),
            Paragraph(cmd, ParagraphStyle("c", fontName="Courier", fontSize=7.5, textColor=HexColor("#a5f3c0"))),
        ])
    qt = Table(step_data, colWidths=[1*cm, 4*cm, 13*cm])
    qt.setStyle(TableStyle([
        ("BACKGROUND",   (0, 0), (-1, 0), HexColor("#1a2535")),
        ("BACKGROUND",   (0, 1), (-1, -1), C_CODE_BG),
        ("GRID",         (0, 0), (-1, -1), 0.3, C_MID_GRAY),
        ("TOPPADDING",   (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING",(0, 0), (-1, -1), 5),
        ("LEFTPADDING",  (0, 0), (-1, -1), 6),
        ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ]))
    story.append(qt)
    story.append(SP(8))

    story.append(Paragraph("Troubleshooting", S["h2"]))
    trouble_data = [
        [Paragraph("Problem", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT)),
         Paragraph("Fix", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=8, textColor=C_LIGHT))],
        [Paragraph("App can't connect to server", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=HexColor("#f87171"), leading=11)),
         Paragraph("Check: same WiFi? Correct IP? Firewall blocking port 5000? Try: curl http://YOUR_IP:5000/health", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11))],
        [Paragraph("No position updates received", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=HexColor("#f87171"), leading=11)),
         Paragraph("Check: isDRMode = true before sending? Calibrate event sent first? Check Python server terminal logs", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11))],
        [Paragraph("flask_socketio not found", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=HexColor("#f87171"), leading=11)),
         Paragraph("Run: pip install flask-socketio flask-cors eventlet", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11))],
        [Paragraph("CORS error in browser/app", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=HexColor("#f87171"), leading=11)),
         Paragraph("Make sure CORS(app) is called and cors_allowed_origins='*' is set in SocketIO()", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11))],
        [Paragraph("Want public URL without same WiFi", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=HexColor("#f87171"), leading=11)),
         Paragraph("Use ngrok: install ngrok, run 'ngrok http 5000', use the wss:// URL in Kotlin", ParagraphStyle("c", fontName="Helvetica", fontSize=8, textColor=C_LIGHT, leading=11))],
    ]
    tr = Table(trouble_data, colWidths=[6.5*cm, 12*cm])
    tr.setStyle(TableStyle([
        ("BACKGROUND",   (0, 0), (-1, 0), HexColor("#1a2535")),
        ("BACKGROUND",   (0, 1), (-1, -1), C_CODE_BG),
        ("GRID",         (0, 0), (-1, -1), 0.3, C_MID_GRAY),
        ("TOPPADDING",   (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING",(0, 0), (-1, -1), 5),
        ("LEFTPADDING",  (0, 0), (-1, -1), 6),
        ("VALIGN",       (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(tr)

    # ── BUILD ──────────────────────────────────────────────────
    def on_first_page(c, d):
        draw_cover_page(c, d)

    def on_later_pages(c, d):
        draw_page_background(c, d)

    doc.build(story, onFirstPage=on_first_page, onLaterPages=on_later_pages)
    print(f"\nPDF generated successfully: {OUTPUT_PATH}\n")


if __name__ == '__main__':
    build_pdf()
