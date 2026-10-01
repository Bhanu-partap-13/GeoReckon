// DRISHTI Intelligent Dead Reckoning Web Application
// SMART INDIA HACKATHON 2026 - Problem Statement SIH26168 by ISRO

let demoData = null;
let samples = [];
let events = [];
let buildings = [];
let metadata = {};

// Application State
const state = {
  currentTimeSec: 0.0,
  isPlaying: true,
  playbackSpeed: 1,
  currentIndex: 0,
  isJudgeDemo: false,
  ablation: {
    aiSpeed: true,
    nhc: true,
    map: true
  },
  dataSource: 'SIMULATION',
  camera: {
    x: 0,
    y: 0,
    zoom: 2.2,
    pitch: 0.45, // Isometric slant
    yaw: 0.0
  },
  packetsRx: 12480,
  loggedEventIds: new Set()
};

// DOM Elements
const canvas = document.getElementById('navCanvas');
const ctx = canvas.getContext('2d');

const headerClock = document.getElementById('headerClock');
const filterStateBanner = document.getElementById('filterStateBanner');
const filterStateText = document.getElementById('filterStateText');

const toggleAiSpeed = document.getElementById('toggleAiSpeed');
const toggleNhc = document.getElementById('toggleNhc');
const toggleMap = document.getElementById('toggleMap');
const drishtiErrVal = document.getElementById('drishtiErrVal');
const baselineErrVal = document.getElementById('baselineErrVal');

const overallUncertainty = document.getElementById('overallUncertainty');
const alongVal = document.getElementById('alongVal');
const alongBar = document.getElementById('alongBar');
const crossVal = document.getElementById('crossVal');
const crossBar = document.getElementById('crossBar');
const headingUncertVal = document.getElementById('headingUncertVal');
const headingBar = document.getElementById('headingBar');
const axisRatioVal = document.getElementById('axisRatioVal');

const gnssDeniedOverlay = document.getElementById('gnssDeniedOverlay');
const deniedTimer = document.getElementById('deniedTimer');
const deniedDistance = document.getElementById('deniedDistance');
const naiveInsAlert = document.getElementById('naiveInsAlert');
const naiveDistanceText = document.getElementById('naiveDistanceText');
const integrityStateText = document.getElementById('integrityStateText');

const eventLogContainer = document.getElementById('eventLogContainer');
const eventCountBadge = document.getElementById('eventCountBadge');

const kinSpeedKmh = document.getElementById('kinSpeedKmh');
const kinSpeedMps = document.getElementById('kinSpeedMps');
const kinHeadingDeg = document.getElementById('kinHeadingDeg');
const kinAccelX = document.getElementById('kinAccelX');
const gyroZVal = document.getElementById('gyroZVal');

const btnRunJudgeDemo = document.getElementById('btnRunJudgeDemo');
const btnPlayPause = document.getElementById('btnPlayPause');
const btnReset = document.getElementById('btnReset');
const btnReload = document.getElementById('btnReload');
const timeScrubber = document.getElementById('timeScrubber');
const scrubCurrentTime = document.getElementById('scrubCurrentTime');

const phaseNormal = document.getElementById('phaseNormal');
const phaseBlackout = document.getElementById('phaseBlackout');
const phasePothole = document.getElementById('phasePothole');
const phaseSpoof = document.getElementById('phaseSpoof');
const phaseRecovery = document.getElementById('phaseRecovery');

// Load Data
async function loadDemoData() {
  try {
    const res = await fetch('/src/data/demo_trip.json');
    demoData = await res.json();
    samples = demoData.samples || [];
    events = demoData.events || [];
    buildings = demoData.buildings || [];
    metadata = demoData.metadata || {};

    console.log(`Loaded ${samples.length} samples, ${events.length} events, ${buildings.length} buildings.`);
    initEventsList();
    resetPlayback();
    requestAnimationFrame(renderLoop);
  } catch (err) {
    console.error('Failed to load demo_trip.json:', err);
  }
}

// Resize Canvas
function resizeCanvas() {
  const rect = canvas.parentElement.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);
}
window.addEventListener('resize', resizeCanvas);

// Projection: 2.5D Isometric World -> Screen
function worldToScreen(wx, wy, wz = 0) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.width / dpr;
  const h = canvas.height / dpr;

  // Camera center offset
  const dx = wx - state.camera.x;
  const dy = wy - state.camera.y;

  // Isometric oblique projection with slant
  const isoX = w / 2 + (dx - dy * 0.4) * state.camera.zoom;
  const isoY = h / 2 - (dx * 0.15 + dy * 0.75 + wz * 0.9) * state.camera.zoom;

  return { x: isoX, y: isoY };
}

// Main Render Loop
let lastFrameTime = performance.now();
function renderLoop(now) {
  const dt = (now - lastFrameTime) / 1000;
  lastFrameTime = now;

  if (state.isPlaying && samples.length > 0) {
    state.currentTimeSec += dt * state.playbackSpeed;
    if (state.currentTimeSec > metadata.durationSec) {
      state.currentTimeSec = 0;
      state.loggedEventIds.clear();
      initEventsList();
    }
    updateStateFromTime(state.currentTimeSec);
  }

  draw3DScene();
  requestAnimationFrame(renderLoop);
}

// Update State & UI
function updateStateFromTime(tSec) {
  const sampleRate = metadata.sampleRateHz || 10;
  state.currentIndex = Math.min(samples.length - 1, Math.floor(tSec * sampleRate));
  const cur = samples[state.currentIndex];
  if (!cur) return;

  // Smooth camera following vehicle
  const targetX = cur.estX;
  const targetY = cur.estY;
  state.camera.x += (targetX - state.camera.x) * 0.12;
  state.camera.y += (targetY - state.camera.y) * 0.12;

  // Scrubber & Header Clock
  timeScrubber.value = tSec.toFixed(1);
  const min = Math.floor(tSec / 60);
  const sec = (tSec % 60).toFixed(1);
  const timeStr = `${String(min).padStart(2, '0')}:${String(sec).padStart(4, '0')}`;
  scrubCurrentTime.textContent = timeStr;
  headerClock.textContent = `T+${timeStr}`;

  // Mode & State Machine
  const isOutage = tSec >= metadata.outageStartSec && tSec < metadata.outageEndSec;
  const isRecovery = tSec >= metadata.outageEndSec && tSec < metadata.outageEndSec + 4.0;

  if (isOutage) {
    filterStateBanner.className = 'filter-state-large state-dr-active';
    filterStateText.textContent = 'DR ACTIVE';
    integrityStateText.textContent = 'DR COASTING';
    gnssDeniedOverlay.classList.remove('hidden');

    const outageElapsed = (tSec - metadata.outageStartSec).toFixed(1);
    deniedTimer.textContent = `00:${String(outageElapsed).padStart(4, '0')}`;
    const distTraveled = ((cur.estX - samples[Math.floor(metadata.outageStartSec * sampleRate)].estX)).toFixed(0);
    deniedDistance.textContent = `DISTANCE ${Math.max(0, distTraveled)} m`;

    // Naive INS Failure Alert
    if (cur.naiveErrorM > 35) {
      naiveInsAlert.classList.remove('hidden');
      naiveDistanceText.textContent = `NAIVE INS - OFF-MAP · ${cur.naiveErrorM.toFixed(0)} m`;
    } else {
      naiveInsAlert.classList.add('hidden');
    }
  } else if (isRecovery) {
    filterStateBanner.className = 'filter-state-large state-recovering';
    filterStateText.textContent = 'SOFT HANDOVER';
    integrityStateText.textContent = 'FUSION BLENDING';
    gnssDeniedOverlay.classList.add('hidden');
    naiveInsAlert.classList.add('hidden');
  } else {
    filterStateBanner.className = 'filter-state-large state-gnss-locked';
    filterStateText.textContent = 'GNSS + DR';
    integrityStateText.textContent = 'GNSS LOCKED';
    gnssDeniedOverlay.classList.add('hidden');
    naiveInsAlert.classList.add('hidden');
  }

  // Active Ablation Error Calculation
  let activeDrishtiError = cur.errorM;
  if (!state.ablation.aiSpeed) activeDrishtiError = cur.noAiErrorM;
  else if (!state.ablation.nhc) activeDrishtiError = cur.noNhcErrorM;
  else if (!state.ablation.map) activeDrishtiError = cur.noMapErrorM;

  drishtiErrVal.textContent = `${activeDrishtiError.toFixed(1)} m`;
  baselineErrVal.textContent = `${cur.naiveErrorM.toFixed(1)} m`;

  // Uncertainty Bars
  overallUncertainty.textContent = `± ${cur.uncertaintyM.toFixed(2)} m`;
  alongVal.textContent = `${cur.alongUncertM.toFixed(2)} m`;
  alongBar.style.width = `${Math.min(100, (cur.alongUncertM / 6.0) * 100)}%`;

  crossVal.textContent = `${cur.crossUncertM.toFixed(2)} m`;
  crossBar.style.width = `${Math.min(100, (cur.crossUncertM / 3.0) * 100)}%`;

  headingUncertVal.textContent = `${cur.headingUncertDeg.toFixed(2)}°`;
  headingBar.style.width = `${Math.min(100, (cur.headingUncertDeg / 3.5) * 100)}%`;

  axisRatioVal.textContent = `${cur.axisRatio.toFixed(2)} : 1`;

  // Kinematics HUD
  kinSpeedKmh.textContent = `${cur.speedKmh.toFixed(1)} km/h`;
  kinSpeedMps.textContent = `${cur.speedMps.toFixed(2)} m/s`;
  kinHeadingDeg.textContent = `${cur.headingDeg.toFixed(1)}°`;
  kinAccelX.textContent = `${cur.accelX.toFixed(2)} m/s²`;
  gyroZVal.textContent = `GYRO Z: ${cur.gyroZ.toFixed(3)} rad/s`;

  // Timeline HUD Phase Highlight
  updateTimelinePhases(tSec);

  // Trigger Forensic Events
  checkAndTriggerEvents(tSec * 1000);
}

// Timeline HUD Phase updater
function updateTimelinePhases(t) {
  phaseNormal.classList.remove('active');
  phaseBlackout.classList.remove('active');
  phasePothole.classList.remove('active');
  phaseSpoof.classList.remove('active');
  phaseRecovery.classList.remove('active');

  if (t < 35.0) {
    phaseNormal.classList.add('active');
  } else if (t >= 35.0 && t < 44.0) {
    phaseBlackout.classList.add('active');
  } else if (t >= 44.0 && t < 55.0) {
    phasePothole.classList.add('active');
  } else if (t >= 65.0 && t < 74.0) {
    phaseSpoof.classList.add('active');
  } else if (t >= 74.0 && t < 80.0) {
    phaseRecovery.classList.add('active');
  } else {
    phaseNormal.classList.add('active');
  }
}

// Event Log Management
function initEventsList() {
  eventLogContainer.innerHTML = '';
  eventCountBadge.textContent = `${events.length} EVENTS`;
}

function checkAndTriggerEvents(currentMs) {
  events.forEach((ev, idx) => {
    if (currentMs >= ev.timestampMs && !state.loggedEventIds.has(idx)) {
      state.loggedEventIds.add(idx);
      appendEventLog(ev);
    }
  });
}

function appendEventLog(ev) {
  const entry = document.createElement('div');
  entry.className = `event-entry badge-${ev.badge}`;

  const tSec = (ev.timestampMs / 1000).toFixed(1);
  const min = Math.floor(tSec / 60);
  const sec = (tSec % 60).toFixed(1);
  const timeStr = `${String(min).padStart(2, '0')}:${String(sec).padStart(4, '0')}`;

  entry.innerHTML = `
    <div class="event-meta">
      <span class="event-time">${timeStr}</span>
      <span class="event-tag tag-${ev.badge}">[${ev.type}]</span>
    </div>
    <div class="event-text">${ev.text}</div>
  `;

  eventLogContainer.prepend(entry);
}

// 3D Scene Drawing
function draw3DScene() {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.width / dpr;
  const h = canvas.height / dpr;

  ctx.clearRect(0, 0, w, h);

  // 1. Draw Grid Ground
  drawGroundGrid();

  // 2. Draw Tunnel Zone Shading
  drawTunnelZone();

  // 3. Draw Road Corridor
  drawRoadCorridor();

  // 4. Draw 3D Buildings
  draw3DBuildings();

  // 5. Draw Trajectories
  drawTrajectories();

  // 6. Draw Vehicle & Glowing Cyan Laser Pillar
  drawVehicleBeacon();
}

// 1. Grid
function drawGroundGrid() {
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.04)';
  ctx.lineWidth = 1;

  const step = 40;
  const cx = Math.floor(state.camera.x / step) * step;
  const cy = Math.floor(state.camera.y / step) * step;

  for (let x = cx - 400; x <= cx + 400; x += step) {
    const p1 = worldToScreen(x, cy - 400, 0);
    const p2 = worldToScreen(x, cy + 400, 0);
    ctx.beginPath();
    ctx.moveTo(p1.x, p1.y);
    ctx.lineTo(p2.x, p2.y);
    ctx.stroke();
  }

  for (let y = cy - 400; y <= cy + 400; y += step) {
    const p1 = worldToScreen(cx - 400, y, 0);
    const p2 = worldToScreen(cx + 400, y, 0);
    ctx.beginPath();
    ctx.moveTo(p1.x, p1.y);
    ctx.lineTo(p2.x, p2.y);
    ctx.stroke();
  }
}

// 2. Tunnel Zone
function drawTunnelZone() {
  if (samples.length === 0) return;
  const sampleRate = metadata.sampleRateHz || 10;
  const sStart = Math.floor(metadata.outageStartSec * sampleRate);
  const sEnd = Math.min(samples.length - 1, Math.floor(metadata.outageEndSec * sampleRate));

  const startPt = samples[sStart];
  const endPt = samples[sEnd];
  if (!startPt || !endPt) return;

  const sp1 = worldToScreen(startPt.trueX, startPt.trueY, 0);
  const sp2 = worldToScreen(endPt.trueX, endPt.trueY, 0);

  // Translucent tunnel envelope
  ctx.save();
  ctx.fillStyle = 'rgba(239, 68, 68, 0.05)';
  ctx.strokeStyle = 'rgba(239, 68, 68, 0.3)';
  ctx.setLineDash([8, 8]);
  ctx.lineWidth = 2;

  ctx.beginPath();
  const w1 = worldToScreen(startPt.trueX, startPt.trueY - 24, 0);
  const w2 = worldToScreen(endPt.trueX, endPt.trueY - 24, 0);
  const w3 = worldToScreen(endPt.trueX, endPt.trueY + 24, 0);
  const w4 = worldToScreen(startPt.trueX, startPt.trueY + 24, 0);

  ctx.moveTo(w1.x, w1.y);
  ctx.lineTo(w2.x, w2.y);
  ctx.lineTo(w3.x, w3.y);
  ctx.lineTo(w4.x, w4.y);
  ctx.closePath();
  ctx.fill();
  ctx.stroke();

  // Tunnel Portals
  drawTunnelPortal(startPt.trueX, startPt.trueY, 'PRAGATI TUNNEL ENTRANCE [GNSS CUT]');
  drawTunnelPortal(endPt.trueX, endPt.trueY, 'TUNNEL EXIT [GNSS RECOVERY]');
  ctx.restore();
}

function drawTunnelPortal(wx, wy, label) {
  const pBase = worldToScreen(wx, wy, 0);
  const pTop = worldToScreen(wx, wy, 35);

  ctx.strokeStyle = '#ef4444';
  ctx.lineWidth = 3;
  ctx.setLineDash([]);
  ctx.beginPath();
  ctx.moveTo(pBase.x - 20, pBase.y);
  ctx.lineTo(pTop.x - 20, pTop.y);
  ctx.lineTo(pTop.x + 20, pTop.y);
  ctx.lineTo(pBase.x + 20, pBase.y);
  ctx.stroke();

  ctx.fillStyle = '#ef4444';
  ctx.font = '9px JetBrains Mono';
  ctx.textAlign = 'center';
  ctx.fillText(label, pTop.x, pTop.y - 8);
}

// 3. Road Corridor
function drawRoadCorridor() {
  if (samples.length < 2) return;

  // Road asphalt bed
  ctx.save();
  ctx.lineWidth = 26 * state.camera.zoom;
  ctx.strokeStyle = '#0f172a';
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();

  const firstPt = worldToScreen(samples[0].trueX, samples[0].trueY, 0);
  ctx.moveTo(firstPt.x, firstPt.y);
  for (let i = 1; i < samples.length; i += 2) {
    const pt = worldToScreen(samples[i].trueX, samples[i].trueY, 0);
    ctx.lineTo(pt.x, pt.y);
  }
  ctx.stroke();

  // Road shoulders
  ctx.lineWidth = 28 * state.camera.zoom;
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.08)';
  ctx.stroke();

  // Center dashed white lane
  ctx.lineWidth = 2;
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.35)';
  ctx.setLineDash([12, 12]);
  ctx.stroke();
  ctx.restore();
}

// 4. 3D Extruded Buildings
function draw3DBuildings() {
  if (!buildings || buildings.length === 0) return;

  // Sort by Y for correct isometric depth occlusion
  const sorted = [...buildings].sort((a, b) => a.y - b.y);

  sorted.forEach(b => {
    // 4 base corners
    const hw = b.w / 2;
    const hl = b.l / 2;

    const baseCorners = [
      worldToScreen(b.x - hw, b.y - hl, 0),
      worldToScreen(b.x + hw, b.y - hl, 0),
      worldToScreen(b.x + hw, b.y + hl, 0),
      worldToScreen(b.x - hw, b.y + hl, 0)
    ];

    const roofCorners = [
      worldToScreen(b.x - hw, b.y - hl, b.h),
      worldToScreen(b.x + hw, b.y - hl, b.h),
      worldToScreen(b.x + hw, b.y + hl, b.h),
      worldToScreen(b.x - hw, b.y + hl, b.h)
    ];

    // Front/Side wall shading
    ctx.fillStyle = '#090e1a';
    ctx.strokeStyle = 'rgba(56, 189, 248, 0.12)';
    ctx.lineWidth = 1;

    // Wall 1
    ctx.beginPath();
    ctx.moveTo(baseCorners[0].x, baseCorners[0].y);
    ctx.lineTo(baseCorners[1].x, baseCorners[1].y);
    ctx.lineTo(roofCorners[1].x, roofCorners[1].y);
    ctx.lineTo(roofCorners[0].x, roofCorners[0].y);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    // Wall 2 (illuminated with slight cyan tint)
    ctx.fillStyle = '#0d1527';
    ctx.beginPath();
    ctx.moveTo(baseCorners[1].x, baseCorners[1].y);
    ctx.lineTo(baseCorners[2].x, baseCorners[2].y);
    ctx.lineTo(roofCorners[2].x, roofCorners[2].y);
    ctx.lineTo(roofCorners[1].x, roofCorners[1].y);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    // Roof (Glass tint with glowing edge)
    ctx.fillStyle = '#142038';
    ctx.beginPath();
    ctx.moveTo(roofCorners[0].x, roofCorners[0].y);
    ctx.lineTo(roofCorners[1].x, roofCorners[1].y);
    ctx.lineTo(roofCorners[2].x, roofCorners[2].y);
    ctx.lineTo(roofCorners[3].x, roofCorners[3].y);
    ctx.closePath();
    ctx.fill();

    ctx.strokeStyle = 'rgba(0, 242, 254, 0.3)';
    ctx.stroke();
  });
}

// 5. Draw 3 Trajectories: Ground Truth, DRISHTI, and Naive INS
function drawTrajectories() {
  if (samples.length < 2) return;
  const count = state.currentIndex;

  // Trajectory 1: Ground Truth (Green)
  ctx.save();
  ctx.strokeStyle = '#10b981';
  ctx.lineWidth = 3;
  ctx.shadowColor = 'rgba(16, 185, 129, 0.6)';
  ctx.shadowBlur = 8;
  ctx.beginPath();
  const g0 = worldToScreen(samples[0].trueX, samples[0].trueY, 0);
  ctx.moveTo(g0.x, g0.y);
  for (let i = 1; i <= count; i++) {
    const pt = worldToScreen(samples[i].trueX, samples[i].trueY, 0);
    ctx.lineTo(pt.x, pt.y);
  }
  ctx.stroke();
  ctx.restore();

  // Trajectory 2: DRISHTI (Cyan) or Active Ablation
  ctx.save();
  ctx.strokeStyle = '#00f2fe';
  ctx.lineWidth = 3.5;
  ctx.shadowColor = 'rgba(0, 242, 254, 0.8)';
  ctx.shadowBlur = 12;
  ctx.beginPath();
  const d0 = worldToScreen(samples[0].estX, samples[0].estY, 0);
  ctx.moveTo(d0.x, d0.y);

  for (let i = 1; i <= count; i++) {
    let px = samples[i].estX;
    let py = samples[i].estY;
    if (!state.ablation.aiSpeed) { px = samples[i].noAiX; py = samples[i].noAiY; }
    else if (!state.ablation.nhc) { px = samples[i].noNhcX; py = samples[i].noNhcY; }
    else if (!state.ablation.map) { px = samples[i].noMapX; py = samples[i].noMapY; }

    const pt = worldToScreen(px, py, 0);
    ctx.lineTo(pt.x, pt.y);
  }
  ctx.stroke();
  ctx.restore();

  // Trajectory 3: Conventional Naive INS (Red - diverging wildly into buildings)
  ctx.save();
  ctx.strokeStyle = '#ef4444';
  ctx.lineWidth = 2.5;
  ctx.setLineDash([6, 4]);
  ctx.shadowColor = 'rgba(239, 68, 68, 0.8)';
  ctx.shadowBlur = 10;
  ctx.beginPath();
  const n0 = worldToScreen(samples[0].naiveX, samples[0].naiveY, 0);
  ctx.moveTo(n0.x, n0.y);

  for (let i = 1; i <= count; i++) {
    const pt = worldToScreen(samples[i].naiveX, samples[i].naiveY, 0);
    ctx.lineTo(pt.x, pt.y);
  }
  ctx.stroke();
  ctx.restore();
}

// 6. Draw Vehicle & Glowing Cyan Laser Pillar Beacon
function drawVehicleBeacon() {
  if (samples.length === 0) return;
  const cur = samples[state.currentIndex];
  if (!cur) return;

  let vx = cur.estX;
  let vy = cur.estY;
  if (!state.ablation.aiSpeed) { vx = cur.noAiX; vy = cur.noAiY; }
  else if (!state.ablation.nhc) { vx = cur.noNhcX; vy = cur.noNhcY; }
  else if (!state.ablation.map) { vx = cur.noMapX; vy = cur.noMapY; }

  const pBase = worldToScreen(vx, vy, 0);
  const pBeamTop = worldToScreen(vx, vy, 60);

  ctx.save();

  // Vertical Laser Pillar (Cyan)
  const grad = ctx.createLinearGradient(pBase.x, pBase.y, pBeamTop.x, pBeamTop.y);
  grad.addColorStop(0, 'rgba(0, 242, 254, 0.9)');
  grad.addColorStop(0.7, 'rgba(0, 242, 254, 0.4)');
  grad.addColorStop(1, 'rgba(0, 242, 254, 0.0)');

  ctx.strokeStyle = grad;
  ctx.lineWidth = 4;
  ctx.shadowColor = 'rgba(0, 242, 254, 0.9)';
  ctx.shadowBlur = 16;
  ctx.beginPath();
  ctx.moveTo(pBase.x, pBase.y);
  ctx.lineTo(pBeamTop.x, pBeamTop.y);
  ctx.stroke();

  // Covariance Halo Ellipse on Ground
  ctx.fillStyle = 'rgba(0, 242, 254, 0.15)';
  ctx.strokeStyle = 'rgba(0, 242, 254, 0.7)';
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  const radius = Math.max(8, cur.uncertaintyM * 4 * state.camera.zoom);
  ctx.ellipse(pBase.x, pBase.y, radius, radius * 0.5, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();

  // Vehicle Icon Chevron
  ctx.fillStyle = '#ffffff';
  ctx.shadowColor = '#00f2fe';
  ctx.shadowBlur = 12;
  ctx.beginPath();
  ctx.arc(pBase.x, pBase.y, 6, 0, Math.PI * 2);
  ctx.fill();

  ctx.restore();
}

// Reset Playback
function resetPlayback() {
  state.currentTimeSec = 0;
  state.currentIndex = 0;
  state.loggedEventIds.clear();
  initEventsList();
  if (samples.length > 0) {
    state.camera.x = samples[0].estX;
    state.camera.y = samples[0].estY;
  }
}

// Event Listeners: Transport & Ablation
btnPlayPause.addEventListener('click', () => {
  state.isPlaying = !state.isPlaying;
  btnPlayPause.textContent = state.isPlaying ? '⏸' : '▶';
});

btnReset.addEventListener('click', () => {
  resetPlayback();
});

btnReload.addEventListener('click', () => {
  loadDemoData();
});

// Speed Multipliers
document.querySelectorAll('.speed-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.speed-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    state.playbackSpeed = parseFloat(btn.dataset.speed);
  });
});

// Scrubber
timeScrubber.addEventListener('input', (e) => {
  state.currentTimeSec = parseFloat(e.target.value);
  updateStateFromTime(state.currentTimeSec);
});

// Ablation Toggles
toggleAiSpeed.addEventListener('click', () => {
  state.ablation.aiSpeed = !state.ablation.aiSpeed;
  toggleAiSpeed.classList.toggle('active', state.ablation.aiSpeed);
  toggleAiSpeed.textContent = state.ablation.aiSpeed ? 'ON' : 'OFF';
});

toggleNhc.addEventListener('click', () => {
  state.ablation.nhc = !state.ablation.nhc;
  toggleNhc.classList.toggle('active', state.ablation.nhc);
  toggleNhc.textContent = state.ablation.nhc ? 'ON' : 'OFF';
});

toggleMap.addEventListener('click', () => {
  state.ablation.map = !state.ablation.map;
  toggleMap.classList.toggle('active', state.ablation.map);
  toggleMap.textContent = state.ablation.map ? 'ON' : 'OFF';
});

// RUN JUDGE DEMO: Automated Presentation Showcase
btnRunJudgeDemo.addEventListener('click', () => {
  resetPlayback();
  state.isPlaying = true;
  state.playbackSpeed = 1.5;
  document.querySelectorAll('.speed-btn').forEach(b => b.classList.remove('active'));
  document.querySelector('.speed-btn[data-speed="1"]').classList.add('active');

  btnRunJudgeDemo.classList.add('active');
  btnRunJudgeDemo.innerHTML = '<span class="btn-icon">⚡</span> RUNNING JUDGE DEMO';

  setTimeout(() => {
    btnRunJudgeDemo.classList.remove('active');
    btnRunJudgeDemo.innerHTML = '<span class="btn-icon">▶</span> RUN JUDGE DEMO';
  }, 35000);
});

// WebSocket Server Mock / Field Unit Listener
let ws = null;
function initFieldUnitWebSocket() {
  try {
    ws = new WebSocket('ws://localhost:8080');
    ws.onopen = () => {
      console.log('Connected to Field Unit WebSocket');
      document.getElementById('fieldLinkText').textContent = 'ONLINE';
    };
    ws.onmessage = (event) => {
      const packet = JSON.parse(event.data);
      state.packetsRx++;
      document.getElementById('packetsRxVal').textContent = state.packetsRx.toLocaleString();
    };
    ws.onerror = () => {
      document.getElementById('fieldLinkText').textContent = 'SIMULATED';
    };
  } catch (e) {
    document.getElementById('fieldLinkText').textContent = 'SIMULATED';
  }
}

// Initial Boot
window.addEventListener('DOMContentLoaded', () => {
  resizeCanvas();
  loadDemoData();
  initFieldUnitWebSocket();
});
