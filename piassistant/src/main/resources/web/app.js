/* ═══════════════════════════════════════════════════
   Sentient Assistant — Frontend Application
   ═══════════════════════════════════════════════════ */

// ── Auth: bootstrap (runs before anything else) ─────────
// If a shared password is set on the master, every fetch + WS must carry the
// device token. We probe /api/auth/status synchronously-ish: if auth is required
// and we don't have a valid token, we bounce to /login.
(function bootstrapAuth() {
    const TOKEN_KEY = 'sentient_token';
    function getToken() { return localStorage.getItem(TOKEN_KEY) || ''; }
    window.getSentientToken = getToken;
    window.setSentientToken = (t) => localStorage.setItem(TOKEN_KEY, t);
    window.clearSentientToken = () => localStorage.removeItem(TOKEN_KEY);

    // Intercept fetch to attach the auth header on every /api/* call.
    const origFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
        init = init || {};
        const headers = new Headers(init.headers || {});
        const url = typeof input === 'string' ? input : (input && input.url) || '';
        const isApi = url.includes('/api/');
        if (isApi && !headers.has('X-Sentient-Token')) {
            const t = getToken();
            if (t) headers.set('X-Sentient-Token', t);
        }
        init.headers = headers;
        return origFetch(input, init);
    };

    // On boot, ask the server if we need to log in.
    const masterHost = (localStorage.getItem('sentient_masterHost') || '').trim() || location.host;
    const base = masterHost === location.host ? '' : location.protocol + '//' + masterHost;
    origFetch(base + '/api/auth/status', {
        headers: getToken() ? { 'X-Sentient-Token': getToken() } : {}
    }).then(r => r.json()).then(data => {
        if (data.required && !data.loggedIn) {
            // Save where we wanted to be, then go to the login page.
            location.replace(base + '/login');
        }
    }).catch(() => { /* server unreachable, let normal UI surface that */ });
})();

// ── Partition Manager (Smart 2D Grid + Drag & Drop) ─

const activePanels = new Set();
let panelSlots = []; // ordered list of panel names in their grid positions

function togglePanel(name) {
    if (activePanels.has(name)) {
        closePanel(name);
    } else {
        openPanel(name);
    }
}

function openPanel(name) {
    activePanels.add(name);
    // Add to end of slots
    if (!panelSlots.includes(name)) panelSlots.push(name);
    const panel = document.getElementById('panel-' + name);
    const btn = document.querySelector(`.nav-btn[data-panel="${name}"]`);
    if (panel) panel.style.display = 'flex';
    if (btn) btn.classList.add('active');
    updateGrid();
    setupDraggable(panel);

    // Refresh data when opening specific panels
    if (name === 'tasks') { refreshTasks(); refreshCommitments(); }
    if (name === 'study') refreshStudyTasks();
    if (name === 'sleep') startSleepClock();
    if (name === 'spotify') initSpotifyPanel();
}

function closePanel(name) {
    activePanels.delete(name);
    panelSlots = panelSlots.filter(n => n !== name);
    const panel = document.getElementById('panel-' + name);
    const btn = document.querySelector(`.nav-btn[data-panel="${name}"]`);
    if (panel) panel.style.display = 'none';
    if (btn) btn.classList.remove('active');
    updateGrid();

    if (name === 'sleep') stopSleepClock();
    if (name === 'spotify') stopSpotifyPolling();
}

/**
 * Smart 2D grid layouts:
 *   1 panel  → full screen
 *   2 panels → 2 columns
 *   3 panels → 2 top, 1 spanning bottom
 *   4 panels → 2×2 grid
 *   5 panels → 3 top, 2 bottom (centered)
 */
function updateGrid() {
    const area = document.getElementById('contentArea');
    const empty = document.getElementById('emptyState');
    const n = panelSlots.length;

    // Clean up old grid area assignments
    document.querySelectorAll('.panel').forEach(p => {
        p.style.gridArea = '';
    });

    if (n === 0) {
        area.style.gridTemplateColumns = '1fr';
        area.style.gridTemplateRows = '1fr';
        area.style.gridTemplateAreas = '';
        empty.style.display = 'flex';
        return;
    }

    empty.style.display = 'none';

    // Reorder DOM so grid areas match
    panelSlots.forEach(name => {
        const panel = document.getElementById('panel-' + name);
        if (panel) area.appendChild(panel);
    });

    // Assign grid-area names
    panelSlots.forEach((name, i) => {
        const panel = document.getElementById('panel-' + name);
        if (panel) panel.style.gridArea = 'p' + i;
    });

    // Build grid template based on count
    switch (n) {
        case 1:
            area.style.gridTemplateColumns = '1fr';
            area.style.gridTemplateRows = '1fr';
            area.style.gridTemplateAreas = '"p0"';
            break;
        case 2:
            area.style.gridTemplateColumns = '1fr 1fr';
            area.style.gridTemplateRows = '1fr';
            area.style.gridTemplateAreas = '"p0 p1"';
            break;
        case 3:
            area.style.gridTemplateColumns = '1fr 1fr';
            area.style.gridTemplateRows = '1fr 1fr';
            area.style.gridTemplateAreas = '"p0 p1" "p2 p2"';
            break;
        case 4:
            area.style.gridTemplateColumns = '1fr 1fr';
            area.style.gridTemplateRows = '1fr 1fr';
            area.style.gridTemplateAreas = '"p0 p1" "p2 p3"';
            break;
        case 5:
            area.style.gridTemplateColumns = '1fr 1fr 1fr';
            area.style.gridTemplateRows = '1fr 1fr';
            area.style.gridTemplateAreas = '"p0 p1 p2" "p3 p3 p4"';
            break;
        default: { // 6+
            const cols = Math.ceil(Math.sqrt(n));
            const rows = Math.ceil(n / cols);
            area.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
            area.style.gridTemplateRows = `repeat(${rows}, 1fr)`;
            // Simple sequential assignment, last item spans remaining
            let areas = [];
            let idx = 0;
            for (let r = 0; r < rows; r++) {
                let row = [];
                for (let c = 0; c < cols; c++) {
                    if (idx < n) {
                        row.push('p' + idx);
                        idx++;
                    } else {
                        // Fill remaining with last panel
                        row.push('p' + (n - 1));
                    }
                }
                areas.push('"' + row.join(' ') + '"');
            }
            area.style.gridTemplateAreas = areas.join(' ');
        }
    }
}

// ── Drag & Drop Panel Reordering ────────────────────

let dragSrcName = null;

function setupDraggable(panel) {
    const header = panel.querySelector('.panel-header');
    if (!header || header.dataset.dragReady) return;
    header.dataset.dragReady = 'true';
    header.draggable = true;
    header.style.cursor = 'grab';

    header.addEventListener('dragstart', (e) => {
        dragSrcName = panel.id.replace('panel-', '');
        panel.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', dragSrcName);
    });

    header.addEventListener('dragend', () => {
        panel.classList.remove('dragging');
        document.querySelectorAll('.panel').forEach(p => p.classList.remove('drag-over'));
        dragSrcName = null;
    });

    // The whole panel is the drop target
    panel.addEventListener('dragover', (e) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        const targetName = panel.id.replace('panel-', '');
        if (targetName !== dragSrcName) {
            panel.classList.add('drag-over');
        }
    });

    panel.addEventListener('dragleave', () => {
        panel.classList.remove('drag-over');
    });

    panel.addEventListener('drop', (e) => {
        e.preventDefault();
        panel.classList.remove('drag-over');
        const targetName = panel.id.replace('panel-', '');
        if (dragSrcName && targetName !== dragSrcName) {
            swapPanels(dragSrcName, targetName);
        }
    });
}

function swapPanels(a, b) {
    const idxA = panelSlots.indexOf(a);
    const idxB = panelSlots.indexOf(b);
    if (idxA === -1 || idxB === -1) return;
    [panelSlots[idxA], panelSlots[idxB]] = [panelSlots[idxB], panelSlots[idxA]];
    updateGrid();
}

// Initialize drag on any panels already in the DOM
document.querySelectorAll('.panel').forEach(setupDraggable);

// ── Sidebar buttons ─────────────────────────────────

document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const panel = btn.dataset.panel;
        if (panel) togglePanel(panel);
    });
});

// Close buttons inside panels
document.querySelectorAll('.panel-close').forEach(btn => {
    btn.addEventListener('click', () => {
        const panel = btn.dataset.panel;
        if (panel) closePanel(panel);
    });
});

// ── WebSocket ───────────────────────────────────────

let ws = null;
let reconnectTimer = null;
let modelOverride = 'AUTO';
let convMode = true;
let ttsEnabled = true;
let micSetting = 'enabled';
let voiceWakeMode = false; // toggled by the VOICE tab — gates auto-mic + wake detection
let currentImageBase64 = null;
let currentFileName = null;
let currentFileType = null;
const tabSessionId = Date.now().toString() + Math.random().toString();
// Active WebRTC peer connections, keyed by the other peer's sessionId.
const webrtcPeerConnections = {};

// ── Master-server URL (for remote clients) ────────────
// If the user pinned a master device address in Settings, route every
// WS + REST call through it. Defaults to whatever host is serving this page.
function getMasterHost() {
    return (localStorage.getItem('sentient_masterHost') || '').trim() || location.host;
}
function getMasterHttpBase() {
    const host = getMasterHost();
    if (host === location.host) return ''; // same-origin — relative URLs are fine
    return `${location.protocol}//${host}`;
}
// `api(path)` returns the absolute URL for a backend call. Use this instead of
// fetch('/api/...') whenever the master server may live on a different host.
window.api = function api(path) { return getMasterHttpBase() + path; };

function connectWS() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const tok = (typeof getSentientToken === 'function') ? getSentientToken() : '';
    const tokQuery = tok ? ('?token=' + encodeURIComponent(tok)) : '';
    ws = new WebSocket(`${protocol}//${getMasterHost()}/ws${tokQuery}`);

    ws.onopen = () => {
        console.log('[WS] Connected');
        setConnectionStatus(true);

        ws.send(JSON.stringify({ type: 'init', sessionId: tabSessionId }));
        // Announce this device so other clients can target it for screen capture / control.
        try { registerDevice(); } catch (e) { console.warn('[devices] register failed', e); }
        
        // Keep-alive for Tailscale Funnel / Proxies
        if (ws.pingInterval) clearInterval(ws.pingInterval);
        ws.pingInterval = setInterval(() => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'ping' }));
            }
        }, 30000);
    };

    ws.onclose = () => {
        console.log('[WS] Disconnected');
        setConnectionStatus(false);
        if (ws.pingInterval) clearInterval(ws.pingInterval);
        if (reconnectTimer) clearTimeout(reconnectTimer);
        // Auto-reconnect
        reconnectTimer = setTimeout(connectWS, 3000);
    };

    ws.onerror = (e) => {
        console.error('[WS] Error', e);
    };

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        handleWSMessage(msg);
    };
}

function setConnectionStatus(connected) {
    const el = document.getElementById('connectionStatus');
    const text = el.querySelector('.status-text');
    if (connected) {
        el.classList.add('connected');
        text.textContent = 'ONLINE';
    } else {
        el.classList.remove('connected');
        text.textContent = 'OFFLINE';
    }
}

// ── Cross-device screen access (limited: browser-side getDisplayMedia) ─
// Each browser tab announces itself as a "device". Another tab can ask the
// master to forward a screen-capture request; this tab pops the browser's
// screen-share consent dialog, grabs one frame, sends back JPEG base64.
// True remote-control of the OS is out of scope — see DEVICE_CONTROL.md.
function deviceDisplayName() {
    const saved = localStorage.getItem('sentient_deviceName');
    if (saved) return saved;
    const ua = navigator.userAgent || '';
    let name = 'Browser';
    if (/Macintosh|Mac OS X/.test(ua)) name = 'Mac';
    else if (/Windows/.test(ua)) name = 'Windows PC';
    else if (/Android/.test(ua)) name = 'Android';
    else if (/iPhone|iPad/.test(ua)) name = 'iOS';
    else if (/Linux/.test(ua)) name = 'Linux';
    return `${name} · ${tabSessionId.slice(-5)}`;
}
function registerDevice() {
    sendWS({
        type: 'register_device',
        name: deviceDisplayName(),
        platform: navigator.platform || '',
        capabilities: ['screen-capture-getDisplayMedia']
    });
}
let knownDevices = [];
function updateDeviceList(list) {
    knownDevices = list || [];
    renderDeviceList();
}
function renderDeviceList() {
    const el = document.getElementById('devicesList');
    if (!el) return;
    if (!knownDevices.length) {
        el.innerHTML = '<div class="setting-hint">No other devices connected.</div>';
        return;
    }
    el.innerHTML = knownDevices.map(d => `
        <div class="device-row">
            <span class="device-name">${escapeHtml(d.name || 'Device')}</span>
            <span class="device-meta">${escapeHtml(d.platform || '')}</span>
            <button class="timer-btn play-btn device-snap-btn" data-session="${escapeHtml(d.sessionId)}">VIEW SCREEN</button>
            <button class="timer-btn device-live-btn" data-session="${escapeHtml(d.sessionId)}" data-name="${escapeHtml(d.name || 'Device')}">LIVE</button>
        </div>`).join('');
    el.querySelectorAll('.device-snap-btn').forEach(b => {
        b.addEventListener('click', () => requestScreenSnapshot(b.dataset.session));
    });
    el.querySelectorAll('.device-live-btn').forEach(b => {
        b.addEventListener('click', () => startWebRTCStream(b.dataset.session, b.dataset.name));
    });
}
async function requestScreenSnapshot(targetSessionId) {
    const requestId = 'r' + Date.now() + Math.random().toString(16).slice(2, 8);
    pendingScreenRequests[requestId] = (frameBase64, deviceName) => {
        showSnapshot(frameBase64, deviceName);
    };
    sendWS({ type: 'request_screen', targetSessionId, requestId });
}
const pendingScreenRequests = {};
async function handleCaptureScreen(msg) {
    // We were asked to capture our own screen and send it back.
    let frameBase64 = '';
    try {
        const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
        const track = stream.getVideoTracks()[0];
        // Wait one tick so the frame is available.
        await new Promise(r => setTimeout(r, 200));
        const settings = track.getSettings();
        const canvas = document.createElement('canvas');
        canvas.width = settings.width || 1280;
        canvas.height = settings.height || 720;
        const video = document.createElement('video');
        video.srcObject = stream;
        await video.play();
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
        frameBase64 = canvas.toDataURL('image/jpeg', 0.6);
        track.stop();
    } catch (e) {
        console.warn('[devices] capture denied or failed', e);
    }
    sendWS({
        type: 'screen_frame',
        requestId: msg.requestId,
        requesterSessionId: msg.requesterSessionId,
        frame: frameBase64,
        deviceName: deviceDisplayName(),
        ok: !!frameBase64
    });
}
function showSnapshot(dataUrl, deviceName) {
    if (!dataUrl) {
        alert('Remote device did not return a frame (user may have denied the screen-share prompt).');
        return;
    }
    const w = window.open('', '_blank', 'width=900,height=700');
    if (w) {
        w.document.write(`<title>${escapeHtml(deviceName || 'remote screen')}</title>
            <body style="margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh">
            <img src="${dataUrl}" style="max-width:100%;max-height:100%"></body>`);
    }
}

// ── Per-action consent store (Track 5) ────────────────
// Records "always allow <action> from <fromDevice>" choices in localStorage
// so the receiving user is only prompted on the first occurrence of a new
// (sender, action) pair. The Settings → Permissions section lists every grant
// so it can be revoked.
function consentKey(action, fromDevice) {
    return `sentient_consent_${action}_${(fromDevice || 'master').toLowerCase()}`;
}
function hasConsent(action, fromDevice) {
    return localStorage.getItem(consentKey(action, fromDevice)) === 'granted';
}
function grantConsent(action, fromDevice) {
    localStorage.setItem(consentKey(action, fromDevice), 'granted');
    if (typeof refreshConsentList === 'function') refreshConsentList();
}
function revokeConsent(action, fromDevice) {
    localStorage.removeItem(consentKey(action, fromDevice));
    if (typeof refreshConsentList === 'function') refreshConsentList();
}
function listConsentGrants() {
    const out = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key || !key.startsWith('sentient_consent_')) continue;
        if (localStorage.getItem(key) !== 'granted') continue;
        // Key shape: sentient_consent_<ACTION>_<fromDevice-lowercased>
        const rest = key.slice('sentient_consent_'.length);
        const underscore = rest.indexOf('_');
        if (underscore < 0) continue;
        const action = rest.slice(0, underscore);
        const fromDevice = rest.slice(underscore + 1);
        out.push({ key, action, fromDevice });
    }
    return out.sort((a, b) => a.action.localeCompare(b.action) || a.fromDevice.localeCompare(b.fromDevice));
}
function showConsentPrompt(action, fromDevice, detail, onAllow, onDeny) {
    const modal = document.createElement('div');
    modal.className = 'consent-modal';
    modal.innerHTML = `
        <div class="consent-box">
            <h3>Permission Request</h3>
            <p><strong>${escapeHtml(fromDevice || 'master')}</strong> wants to: <em>${escapeHtml(action)}</em></p>
            <p class="consent-detail">${escapeHtml(detail || '')}</p>
            <label class="consent-remember">
                <input type="checkbox" id="consentRemember">
                Always allow ${escapeHtml(action)} from ${escapeHtml(fromDevice || 'master')}
            </label>
            <div class="consent-buttons">
                <button id="consentAllow" class="timer-btn play-btn">ALLOW</button>
                <button id="consentDeny" class="timer-btn cancel-btn">DENY</button>
            </div>
        </div>`;
    document.body.appendChild(modal);
    const allowBtn = modal.querySelector('#consentAllow');
    const denyBtn = modal.querySelector('#consentDeny');
    allowBtn.addEventListener('click', () => {
        const remember = modal.querySelector('#consentRemember').checked;
        if (remember) grantConsent(action, fromDevice);
        modal.remove();
        try { onAllow && onAllow(); } catch (e) { console.error('[consent] onAllow error', e); }
    });
    denyBtn.addEventListener('click', () => {
        modal.remove();
        try { onDeny && onDeny(); } catch (e) { /* swallow */ }
    });
    // Esc cancels.
    modal.addEventListener('keydown', e => { if (e.key === 'Escape') denyBtn.click(); });
    allowBtn.focus();
}

// ── Remote action handling (Track 3) ──────────────────
// A remote_action message asks this tab to do something on behalf of another
// device (or the master/AI). OPEN_URL pops a tab, SWITCH_PANEL flips the UI.
function handleRemoteAction(data) {
    const action = data.action;
    const fromDevice = data.fromDevice || data.requesterSessionId || 'master';
    if (action === 'OPEN_URL' && data.url) {
        const url = String(data.url);
        const go = () => window.open(url, '_blank', 'noopener,noreferrer');
        if (hasConsent('OPEN_URL', fromDevice)) {
            go();
        } else {
            showConsentPrompt('OPEN_URL', fromDevice, url, go);
        }
        return;
    }
    if (action === 'SWITCH_PANEL' && data.panel) {
        const panel = String(data.panel);
        const go = () => { try { openPanel(panel); } catch (e) { console.warn('openPanel failed for', panel, e); } };
        if (hasConsent('SWITCH_PANEL', fromDevice)) {
            go();
        } else {
            showConsentPrompt('SWITCH_PANEL', fromDevice, 'Open the "' + panel + '" panel', go);
        }
        return;
    }
    console.log('[remote_action] ignoring unknown action', action, data);
}

// ── WebRTC live screen mirror (Track 2) ───────────────
// Two-tab peer-to-peer video. The watcher (this device) calls startWebRTCStream;
// the watched device responds in handleWebRTCOffer with getDisplayMedia + an
// SDP answer. We rely on Tailnet for routing — no TURN servers needed.
async function startWebRTCStream(targetSessionId, targetName) {
    if (!navigator.mediaDevices || !window.RTCPeerConnection) {
        alert('Live mirror requires a modern browser with WebRTC support.');
        return;
    }
    const existing = webrtcPeerConnections[targetSessionId];
    if (existing) { try { existing.close(); } catch (e) {} }
    const pc = new RTCPeerConnection({ iceServers: [] });
    webrtcPeerConnections[targetSessionId] = pc;

    // Pop a viewer window for the remote stream.
    const w = window.open('', '_blank', 'width=960,height=600');
    if (!w) {
        alert('Pop-up blocked — allow pop-ups for this site to view the live stream.');
        try { pc.close(); } catch (e) {}
        delete webrtcPeerConnections[targetSessionId];
        return;
    }
    w.document.title = (targetName || 'Live') + ' · live mirror';
    w.document.body.style.cssText = 'margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh';
    const video = w.document.createElement('video');
    video.autoplay = true;
    video.playsInline = true;
    video.muted = true;
    video.style.cssText = 'width:100%;height:100%;background:#000;object-fit:contain';
    w.document.body.appendChild(video);
    w.addEventListener('beforeunload', () => {
        try { pc.close(); } catch (e) {}
        delete webrtcPeerConnections[targetSessionId];
    });

    pc.ontrack = (e) => { video.srcObject = e.streams[0]; };
    pc.onicecandidate = (e) => {
        if (!e.candidate) return;
        sendWS({
            type: 'ice_candidate',
            targetSessionId,
            candidate: e.candidate.toJSON(),
            fromSessionId: tabSessionId
        });
    };
    pc.onconnectionstatechange = () => {
        if (['failed', 'closed', 'disconnected'].includes(pc.connectionState)) {
            delete webrtcPeerConnections[targetSessionId];
        }
    };
    // Watcher must declare it wants to receive a video track.
    try { pc.addTransceiver('video', { direction: 'recvonly' }); } catch (e) { /* older browsers */ }

    const offer = await pc.createOffer({ offerToReceiveVideo: true });
    await pc.setLocalDescription(offer);
    sendWS({
        type: 'webrtc_offer',
        targetSessionId,
        sdp: offer.sdp,
        fromSessionId: tabSessionId
    });
}

async function handleWebRTCOffer(msg) {
    const from = msg.fromSessionId;
    if (!from) return;
    // Receiver: prompt for screen-share consent the same way as the snapshot path.
    const fromDeviceName = (knownDevices.find(d => d.sessionId === from) || {}).name || from;
    const proceed = () => doWebRTCAnswer(msg, fromDeviceName);
    if (hasConsent('LIVE_STREAM', fromDeviceName)) {
        proceed();
    } else {
        showConsentPrompt(
            'LIVE_STREAM',
            fromDeviceName,
            'Share your screen live with ' + fromDeviceName,
            proceed,
            () => {
                // Tell the offerer we declined, so they can clean up their PC.
                sendWS({
                    type: 'webrtc_answer',
                    targetSessionId: from,
                    sdp: '',
                    declined: true,
                    fromSessionId: tabSessionId
                });
            }
        );
    }
}

async function doWebRTCAnswer(msg, fromDeviceName) {
    const from = msg.fromSessionId;
    let stream;
    try {
        stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
    } catch (e) {
        console.warn('[webrtc] getDisplayMedia denied', e);
        sendWS({
            type: 'webrtc_answer',
            targetSessionId: from,
            sdp: '',
            declined: true,
            fromSessionId: tabSessionId
        });
        return;
    }
    const existing = webrtcPeerConnections[from];
    if (existing) { try { existing.close(); } catch (e) {} }
    const pc = new RTCPeerConnection({ iceServers: [] });
    webrtcPeerConnections[from] = pc;
    stream.getTracks().forEach(track => pc.addTrack(track, stream));
    // When the user clicks the browser's "Stop sharing" button, kill the PC too.
    stream.getVideoTracks().forEach(t => t.addEventListener('ended', () => {
        try { pc.close(); } catch (e) {}
        delete webrtcPeerConnections[from];
    }));
    pc.onicecandidate = (e) => {
        if (!e.candidate) return;
        sendWS({
            type: 'ice_candidate',
            targetSessionId: from,
            candidate: e.candidate.toJSON(),
            fromSessionId: tabSessionId
        });
    };
    pc.onconnectionstatechange = () => {
        if (['failed', 'closed', 'disconnected'].includes(pc.connectionState)) {
            delete webrtcPeerConnections[from];
        }
    };
    await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    sendWS({
        type: 'webrtc_answer',
        targetSessionId: from,
        sdp: answer.sdp,
        fromSessionId: tabSessionId
    });
}

async function handleWebRTCAnswer(msg) {
    const pc = webrtcPeerConnections[msg.fromSessionId];
    if (!pc) return;
    if (msg.declined || !msg.sdp) {
        try { pc.close(); } catch (e) {}
        delete webrtcPeerConnections[msg.fromSessionId];
        appendChat('system', 'System', 'The other device declined the live stream.');
        return;
    }
    try {
        await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
    } catch (e) {
        console.error('[webrtc] setRemoteDescription failed', e);
    }
}

async function handleICECandidate(msg) {
    const pc = webrtcPeerConnections[msg.fromSessionId];
    if (!pc || !msg.candidate) return;
    try {
        await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
    } catch (e) {
        console.warn('[webrtc] addIceCandidate failed', e);
    }
}

function handleWSMessage(msg) {
    switch (msg.type) {
        case 'system':
            appendChat('system', 'System', msg.text);
            break;
        case 'chat_word':
            appendStreamWord(msg.word);
            break;
        case 'chat_done':
            finishStream();
            break;
        case 'voice_partial':
            updateVoiceInput(msg.text);
            break;
        case 'voice_final':
            updateVoiceInput(msg.text);
            appendChat('user', 'You', msg.text);
            clearVoiceInput();
            break;
        case 'voice_state':
            setRecordingState(msg.listening);
            break;
        case 'tts_audio':
            if (ttsEnabled) {
                playBase64Audio(msg.audioData);
            }
            break;
        case 'command':
            executeCommand(msg.action, msg.param);
            break;
        case 'web_record':
            // Only honor auto-mic continuation if the user is on the VOICE tab. On HOME
            // (push-to-talk) and other tabs we want full silence between turns.
            if (convMode && recognition && activePanels.has('voice') && voiceWakeMode) {
                (typeof claimChosenMic === 'function' ? claimChosenMic() : Promise.resolve())
                    .finally(() => { try { recognition.start(); } catch (e) {} });
            }
            break;
        case 'device_list':
            // Filter out ourselves; render the rest.
            updateDeviceList((msg.devices || []).filter(d => d.sessionId !== tabSessionId));
            break;
        case 'capture_screen':
            handleCaptureScreen(msg);
            break;
        case 'screen_frame': {
            const cb = pendingScreenRequests[msg.requestId];
            if (cb) {
                cb(msg.frame, msg.deviceName);
                delete pendingScreenRequests[msg.requestId];
            }
            break;
        }
        case 'vault_updated':
            // Some other device mutated the vault — re-fetch so this tab stays in sync.
            if (typeof refreshVaultCredentials === 'function') refreshVaultCredentials();
            if (typeof refreshVaultEnvVars === 'function') refreshVaultEnvVars();
            break;
        case 'autofill_request':
            if (typeof showAutofillOverlay === 'function') showAutofillOverlay(msg);
            break;
        case 'remote_action':
            handleRemoteAction(msg);
            break;
        case 'webrtc_offer':
            handleWebRTCOffer(msg);
            break;
        case 'webrtc_answer':
            handleWebRTCAnswer(msg);
            break;
        case 'ice_candidate':
            handleICECandidate(msg);
            break;
    }
}

function sendWS(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

// ── Chat ────────────────────────────────────────────

const chatArea = document.getElementById('chatArea');
const chatInput = document.getElementById('chatInput');
const sendBtn = document.getElementById('sendBtn');
const recordBtn = document.getElementById('recordBtn');
const stopBtn = document.getElementById('stopBtn');

let currentStreamEl = null;
let isRecording = false;

function appendChat(type, sender, text, attachmentHtml = '') {
    const div = document.createElement('div');
    div.className = 'chat-msg';
    const htmlContent = (typeof marked !== 'undefined') ? marked.parse(text) : escapeHtml(text);
    div.innerHTML = `<span class="sender ${type}">${sender}:</span><div class="text markdown-body" style="display:inline-block; vertical-align:top; margin-top:-2px">${attachmentHtml}${htmlContent}</div>`;
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
}

let accumulatedStreamText = "";

function startStream() {
    accumulatedStreamText = "";
    const div = document.createElement('div');
    div.className = 'chat-msg streaming';
    div.innerHTML = `<span class="sender assistant">Assistant:</span><div class="text markdown-body" style="display:inline-block; vertical-align:top; margin-top:-2px"></div>`;
    chatArea.appendChild(div);
    currentStreamEl = div.querySelector('.text');
    chatArea.scrollTop = chatArea.scrollHeight;
}

function appendStreamWord(word) {
    if (!currentStreamEl) startStream();
    accumulatedStreamText += word + ' ';
    currentStreamEl.innerHTML = (typeof marked !== 'undefined') ? marked.parse(accumulatedStreamText) : escapeHtml(accumulatedStreamText);
    chatArea.scrollTop = chatArea.scrollHeight;
}

function finishStream() {
    if (currentStreamEl) {
        currentStreamEl.closest('.chat-msg').classList.remove('streaming');
        currentStreamEl = null;
    }
}

function handleSend() {
    const text = chatInput.value.trim();
    if (!text && !currentImageBase64) return;

    // Interrupt ongoing response
    sendWS({ type: 'stop' });

    let labelHtml = '';
    if (currentFileName) {
        const safeName = currentFileName.replace(/</g, "&lt;").replace(/>/g, "&gt;");
        labelHtml = `<div class="attachment-label">📎 ${safeName}</div>`;
    }

    appendChat('user', 'You', text || '[File attached]', labelHtml);
    
    const payload = { type: 'chat', text: text, source: 'web', modelOverride: modelOverride, engine: (typeof chatEngine !== 'undefined' ? chatEngine : 'groq') };
    if (currentImageBase64) {
        payload.image = currentImageBase64;
        payload.fileName = currentFileName;
        payload.fileType = currentFileType;
    }
    
    sendWS(payload);
    chatInput.value = '';
    if (typeof removeAttachedFile === 'function') removeAttachedFile();

    // Auto-open home panel if not already open
    if (!activePanels.has('home')) openPanel('home');
}

sendBtn.addEventListener('click', handleSend);
chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') handleSend();
});

// Add speech recognition
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
let recognition = null;
if (SpeechRecognition) {
    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    
    recognition.onstart = function() {
        setRecordingState(true);
    };
    
    recognition.onresult = function(event) {
        let interimTranscript = '';
        let finalTranscript = '';

        for (let i = event.resultIndex; i < event.results.length; ++i) {
            if (event.results[i].isFinal) {
                finalTranscript += event.results[i][0].transcript;
            } else {
                interimTranscript += event.results[i][0].transcript;
            }
        }
        
        if (finalTranscript) {
            updateVoiceInput(finalTranscript);
            // Interrupt ongoing response
            sendWS({ type: 'stop' });

            appendChat('user', 'You', finalTranscript);
            sendWS({ type: 'chat', text: finalTranscript, source: 'web', modelOverride: modelOverride, engine: (typeof chatEngine !== 'undefined' ? chatEngine : 'groq') });
            clearVoiceInput();
        } else {
            updateVoiceInput(interimTranscript);
        }
    };
    
    recognition.onerror = function(event) {
        console.error("Speech recognition error", event.error);
        setRecordingState(false);
    };
    
    recognition.onend = function() {
        setRecordingState(false);
    };
}

let currentAudio = null;
function playBase64Audio(base64) {
    if (currentAudio) {
        currentAudio.pause();
    }
    currentAudio = new Audio("data:audio/wav;base64," + base64);
    
    const deviceId = localStorage.getItem('sentient_speaker');
    if (deviceId && deviceId !== 'default' && currentAudio.setSinkId) {
        currentAudio.setSinkId(deviceId).catch(e => console.error("setSinkId fail:", e));
    }
    
    currentAudio.play().catch(e => console.error("Audio playback error:", e));
}

// ── Push-to-talk for HOME tab ──────────────────────────
// Hold the mic button → mic on. Release → mic off. No click-toggle here.
// (The VOICE tab uses a different model: continuous wake-word + barge-in.)
let pttActive = false;

function startPushToTalk() {
    if (pttActive) return;
    if (micSetting === 'muted') {
        alert("Microphone is completely disconnected in Settings.");
        return;
    }
    pttActive = true;
    if (recognition) {
        claimChosenMic().finally(() => {
            try { recognition.start(); } catch (e) { console.error(e); }
        });
    } else {
        sendWS({ type: 'record' });
    }
}

function stopPushToTalk() {
    if (!pttActive) return;
    pttActive = false;
    if (recognition) {
        try { recognition.stop(); } catch (e) {}
    }
    // We don't send `stop` here — that would cancel any in-flight reply too.
    setRecordingState(false);
}

recordBtn.addEventListener('mousedown', startPushToTalk);
recordBtn.addEventListener('mouseup', stopPushToTalk);
recordBtn.addEventListener('mouseleave', stopPushToTalk);
recordBtn.addEventListener('touchstart', (e) => { e.preventDefault(); startPushToTalk(); }, { passive: false });
recordBtn.addEventListener('touchend',   (e) => { e.preventDefault(); stopPushToTalk();  }, { passive: false });
recordBtn.addEventListener('touchcancel',(e) => { e.preventDefault(); stopPushToTalk();  }, { passive: false });
// Keep title hint accurate
recordBtn.title = "HOLD TO TALK";

stopBtn.addEventListener('click', () => {
    if (recognition) recognition.stop();
    if (currentAudio) currentAudio.pause();
    sendWS({ type: 'stop' });
    setRecordingState(false);
});

function updateVoiceInput(text) {
    chatInput.value = text;
}

function clearVoiceInput() {
    chatInput.value = '';
}

function setRecordingState(listening) {
    isRecording = listening;
    if (listening) {
        recordBtn.classList.add('recording');
        recordBtn.textContent = '⏹';
    } else {
        recordBtn.classList.remove('recording');
        recordBtn.textContent = '🎤';
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ── Command Execution ───────────────────────────────

function executeCommand(action, param) {
    switch (action) {
        case 'SWITCH_STUDY':
            if (!activePanels.has('study')) openPanel('study');
            appendChat('system', 'System', '⚡ Switched to Focus Mode');
            break;
        case 'SWITCH_HOME':
            if (!activePanels.has('home')) openPanel('home');
            appendChat('system', 'System', '⚡ Switched to Home');
            break;
        case 'SWITCH_SLEEP':
            if (!activePanels.has('sleep')) openPanel('sleep');
            appendChat('system', 'System', '⚡ Switched to Sleep Mode');
            break;
        case 'SWITCH_TASKS':
            if (!activePanels.has('tasks')) openPanel('tasks');
            appendChat('system', 'System', '⚡ Switched to Tasks');
            break;
        case 'SET_TIMER':
            if (param) {
                const mins = parseInt(param);
                if (!isNaN(mins)) {
                    setTimerMinutes(mins);
                    appendChat('system', 'System', `⏱ Timer set to ${mins} minutes`);
                }
            }
            break;
        case 'START_TIMER':
            if (!timerRunning) { startTimer(); appendChat('system', 'System', '▶ Timer started'); }
            break;
        case 'PAUSE_TIMER':
            if (timerRunning) { pauseTimer(); appendChat('system', 'System', '⏸ Timer paused'); }
            break;
        case 'CANCEL_TIMER':
            cancelTimer();
            appendChat('system', 'System', '■ Timer cancelled');
            break;
        case 'ADD_TASK':
        case 'REMOVE_TASK':
        case 'ADD_COMMITMENT':
        case 'REMOVE_COMMITMENT':
            // These are handled server-side by ProfileManager; just refresh UI
            setTimeout(() => { refreshTasks(); refreshCommitments(); refreshStudyTasks(); }, 500);
            break;
        case 'SWITCH_CALENDAR':
            if (!activePanels.has('calendar')) openPanel('calendar');
            break;
        case 'ADD_EVENT':
            // Handled server-side/calendar service, just open panel and refresh
            if (!activePanels.has('calendar')) openPanel('calendar');
            setTimeout(() => loadCalendarEvents(), 2000);
            appendChat('system', 'System', '📅 Event added');
            break;
        default:
            // Profile commands (SET_USERNAME, ADD_HABIT, etc.) handled server-side
            break;
    }
}

// ── Study Timer ─────────────────────────────────────

let totalSeconds = 25 * 60;
let remainingSeconds = totalSeconds;
let timerRunning = false;
let timerStarted = false;
let timerInterval = null;
let sessionCount = 0;

const FOCUS_QUOTES = [
    '"The secret of getting ahead is getting started." — Mark Twain',
    '"Focus is the art of knowing what to ignore." — James Clear',
    '"Deep work is the superpower of the 21st century." — Cal Newport',
    '"Silence is the language of God, all else is poor translation." — Rumi',
    '"Where focus goes, energy flows." — Tony Robbins',
    '"Do what you can, with what you have, where you are." — Theodore Roosevelt',
    '"The mind is everything. What you think you become." — Buddha',
    '"Discipline is the bridge between goals and accomplishment." — Jim Rohn',
    '"You will never reach your destination if you stop and throw stones at every dog that barks." — Winston Churchill',
    '"Concentrate all your thoughts upon the work at hand." — Alexander Graham Bell',
    '"It is during our darkest moments that we must focus to see the light." — Aristotle',
    '"The successful warrior is the average man, with laser-like focus." — Bruce Lee'
];

const timerLabel = document.getElementById('timerLabel');
const timerEdit = document.getElementById('timerEdit');
const progressBar = document.getElementById('progressBar');
const playPauseBtn = document.getElementById('playPauseBtn');
const cancelBtn = document.getElementById('cancelBtn');
const sessionLabel = document.getElementById('sessionLabel');
const quoteLabel = document.getElementById('quoteLabel');

function formatTime(secs) {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

function updateTimerDisplay() {
    timerLabel.textContent = formatTime(remainingSeconds);
    progressBar.style.width = ((remainingSeconds / totalSeconds) * 100) + '%';
}

function setTimerMinutes(mins) {
    if (timerRunning) cancelTimer();
    totalSeconds = mins * 60;
    remainingSeconds = totalSeconds;
    updateTimerDisplay();
}

// Double-click to edit
timerLabel.addEventListener('dblclick', () => {
    if (timerRunning) return;
    timerLabel.style.display = 'none';
    timerEdit.style.display = 'block';
    const mins = Math.floor(remainingSeconds / 60);
    const secs = remainingSeconds % 60;
    timerEdit.value = `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
    timerEdit.focus();
    timerEdit.select();
});

timerEdit.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') confirmTimerEdit();
    if (e.key === 'Escape') cancelTimerEdit();
});

timerEdit.addEventListener('blur', confirmTimerEdit);

function confirmTimerEdit() {
    const text = timerEdit.value.trim();
    timerEdit.style.display = 'none';
    timerLabel.style.display = 'block';

    try {
        if (text.includes(':')) {
            const [m, s] = text.split(':').map(Number);
            totalSeconds = m * 60 + (s || 0);
        } else {
            totalSeconds = parseInt(text) * 60;
        }
        if (totalSeconds <= 0 || isNaN(totalSeconds)) totalSeconds = 25 * 60;
    } catch (e) {
        // keep previous
    }

    remainingSeconds = totalSeconds;
    updateTimerDisplay();
}

function cancelTimerEdit() {
    timerEdit.style.display = 'none';
    timerLabel.style.display = 'block';
}

playPauseBtn.addEventListener('click', togglePlayPause);
cancelBtn.addEventListener('click', cancelTimer);

function togglePlayPause() {
    if (timerRunning) {
        pauseTimer();
    } else {
        startTimer();
    }
}

function startTimer() {
    if (!timerStarted) animateQuote();

    timerRunning = true;
    timerStarted = true;
    cancelBtn.disabled = false;

    playPauseBtn.textContent = '⏸  PAUSE';
    playPauseBtn.className = 'timer-btn pause-btn';

    timerInterval = setInterval(() => {
        remainingSeconds--;
        if (remainingSeconds <= 0) {
            remainingSeconds = 0;
            updateTimerDisplay();
            timerComplete();
            return;
        }
        updateTimerDisplay();
    }, 1000);
}

function pauseTimer() {
    timerRunning = false;
    clearInterval(timerInterval);

    playPauseBtn.textContent = '▶  RESUME';
    playPauseBtn.className = 'timer-btn play-btn';
}

function cancelTimer() {
    timerRunning = false;
    timerStarted = false;
    clearInterval(timerInterval);

    remainingSeconds = totalSeconds;
    updateTimerDisplay();

    cancelBtn.disabled = true;
    playPauseBtn.textContent = '▶  START';
    playPauseBtn.className = 'timer-btn play-btn';
}

function timerComplete() {
    timerRunning = false;
    timerStarted = false;
    clearInterval(timerInterval);

    sessionCount++;
    sessionLabel.textContent = `SESSIONS COMPLETED: ${sessionCount}`;
    cancelBtn.disabled = true;

    timerLabel.textContent = 'DONE!';
    timerLabel.classList.add('done');

    playPauseBtn.textContent = '▶  START';
    playPauseBtn.className = 'timer-btn play-btn';

    // Reset after 2 seconds
    setTimeout(() => {
        remainingSeconds = totalSeconds;
        timerLabel.classList.remove('done');
        updateTimerDisplay();
        animateQuote();
    }, 2000);
}

function animateQuote() {
    quoteLabel.style.opacity = '0';
    setTimeout(() => {
        quoteLabel.textContent = FOCUS_QUOTES[Math.floor(Math.random() * FOCUS_QUOTES.length)];
        quoteLabel.style.opacity = '1';
    }, 250);
}

// ── Tasks (Named Lists) ────────────────────────────

const commitmentList = document.getElementById('commitmentList');
const addCommitBtn = document.getElementById('addCommitBtn');
addCommitBtn.addEventListener('click', addCommitment);
document.getElementById('commitmentInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') addCommitment();
});

const taskListsRow = document.getElementById('taskListsRow');
const newListBtn = document.getElementById('newListBtn');
const newListInput = document.getElementById('newListInput');
const tasksGoogleConnectBtn = document.getElementById('tasksGoogleConnectBtn');
const tasksSyncBtn = document.getElementById('tasksSyncBtn');
const tasksGoogleText = document.getElementById('tasksGoogleText');

// Google Tasks status check
async function checkGoogleTasksStatus() {
    try {
        const res = await fetch('/api/tasks/google/status');
        const data = await res.json();
        if (data.authenticated) {
            tasksGoogleText.textContent = 'Connected to Google Tasks.';
            tasksGoogleConnectBtn.style.display = 'none';
            tasksSyncBtn.style.display = 'inline-flex';
        } else if (data.configured) {
            tasksGoogleText.textContent = 'Tasks stored locally.';
            tasksGoogleConnectBtn.style.display = 'inline-flex';
            tasksSyncBtn.style.display = 'none';
        } else {
            tasksGoogleText.textContent = 'Tasks stored locally.';
            tasksGoogleConnectBtn.style.display = 'none';
            tasksSyncBtn.style.display = 'none';
        }
    } catch (e) {
        tasksGoogleText.textContent = 'Tasks stored locally.';
    }
}

if (tasksGoogleConnectBtn) {
    tasksGoogleConnectBtn.addEventListener('click', () => {
        window.open(api('/api/tasks/google/auth'), '_blank', 'width=500,height=700');
        const poll = setInterval(async () => {
            try {
                const res = await fetch(api('/api/tasks/google/status'));
                const data = await res.json();
                if (data.authenticated) {
                    clearInterval(poll);
                    checkGoogleTasksStatus();
                }
            } catch (e) { }
        }, 2000);
    });
}

if (tasksSyncBtn) {
    tasksSyncBtn.addEventListener('click', async () => {
        tasksSyncBtn.disabled = true;
        tasksSyncBtn.textContent = 'SYNCING...';
        try {
            await fetch('/api/tasks/google/pull', { method: 'POST' });
            await refreshTaskLists();
            refreshStudyTasks();
        } catch (e) {
            console.error('Sync failed:', e);
        }
        tasksSyncBtn.disabled = false;
        tasksSyncBtn.textContent = '↻ SYNC';
    });
}

// New List
if (newListBtn) {
    newListBtn.addEventListener('click', () => {
        if (newListInput.style.display === 'none') {
            newListInput.style.display = 'inline-block';
            newListInput.focus();
        } else {
            const name = newListInput.value.trim();
            if (name) {
                createTaskList(name);
                newListInput.value = '';
            }
            newListInput.style.display = 'none';
        }
    });
}
if (newListInput) {
    newListInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            const name = newListInput.value.trim();
            if (name) {
                createTaskList(name);
                newListInput.value = '';
            }
            newListInput.style.display = 'none';
        } else if (e.key === 'Escape') {
            newListInput.style.display = 'none';
            newListInput.value = '';
        }
    });
}

async function createTaskList(name) {
    try {
        await fetch('/api/tasklists', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        refreshTaskLists();
    } catch (e) { console.error('Create list failed:', e); }
}

async function deleteTaskList(name) {
    try {
        await fetch('/api/tasklists/' + encodeURIComponent(name), { method: 'DELETE' });
        refreshTaskLists();
    } catch (e) { console.error('Delete list failed:', e); }
}

async function addTaskToList(listName, title, desc, dueDate) {
    try {
        await fetch('/api/tasklists/' + encodeURIComponent(listName) + '/tasks', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, description: desc, dueDate })
        });
        refreshTaskLists();
        refreshStudyTasks();
    } catch (e) { console.error('Add task failed:', e); }
}

async function removeTaskFromList(listName, title) {
    try {
        await fetch('/api/tasklists/' + encodeURIComponent(listName) + '/tasks/' + encodeURIComponent(title), { method: 'DELETE' });
        refreshTaskLists();
        refreshStudyTasks();
    } catch (e) { console.error('Remove task failed:', e); }
}

async function refreshTaskLists() {
    try {
        const res = await fetch('/api/tasklists');
        const lists = await res.json();
        renderTaskLists(lists);
    } catch (e) {
        console.error('Failed to load task lists:', e);
    }
}

function renderTaskLists(lists) {
    if (!taskListsRow) return;
    taskListsRow.innerHTML = '';
    lists.forEach(list => {
        const card = document.createElement('div');
        card.className = 'task-list-card';

        // Header
        const headerDiv = document.createElement('div');
        headerDiv.className = 'task-list-card-header';
        headerDiv.innerHTML = `
            <span class="task-list-card-name">${escapeHtml(list.name)}</span>
            <button class="task-list-menu-btn" title="Delete list">✕</button>
        `;
        headerDiv.querySelector('.task-list-menu-btn').addEventListener('click', () => {
            if (confirm(`Delete list "${list.name}" and all its tasks?`)) {
                deleteTaskList(list.name);
            }
        });
        card.appendChild(headerDiv);

        // Task items
        const itemsDiv = document.createElement('div');
        itemsDiv.className = 'task-list-card-items';
        if (list.items.length === 0) {
            itemsDiv.innerHTML = '<div class="status-hint">No tasks yet.</div>';
        } else {
            list.items.forEach(task => {
                const item = document.createElement('div');
                item.className = 'task-list-item';
                item.innerHTML = `
                    <span class="task-list-item-radio"></span>
                    <div class="task-list-item-info">
                        <span class="task-list-item-title">${escapeHtml(task.title)}</span>
                        ${task.description ? `<span class="task-list-item-desc">${escapeHtml(task.description)}</span>` : ''}
                        ${task.dueDate ? `<span class="task-list-item-date">📅 ${escapeHtml(task.dueDate)}</span>` : ''}
                    </div>
                    <button class="remove-btn" title="Remove">✕</button>
                `;
                item.querySelector('.remove-btn').addEventListener('click', () => removeTaskFromList(list.name, task.title));
                item.querySelector('.task-list-item-radio').addEventListener('click', () => removeTaskFromList(list.name, task.title));
                itemsDiv.appendChild(item);
            });
        }
        card.appendChild(itemsDiv);

        // Add task input
        const addDiv = document.createElement('div');
        addDiv.className = 'task-list-card-add';
        addDiv.innerHTML = `
            <input type="text" class="form-input task-list-add-input" placeholder="Add a task..." data-list="${escapeHtml(list.name)}">
        `;
        const addInput = addDiv.querySelector('input');
        addInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const title = addInput.value.trim();
                if (title) {
                    addTaskToList(list.name, title, '', '');
                    addInput.value = '';
                }
            }
        });
        card.appendChild(addDiv);

        taskListsRow.appendChild(card);
    });
}

// Legacy compatibility wrappers for AI commands
async function refreshTasks() { refreshTaskLists(); }
async function removeTask(title) {
    try {
        await fetch('/api/tasks/' + encodeURIComponent(title), { method: 'DELETE' });
        refreshTaskLists();
        refreshStudyTasks();
    } catch (e) { console.error('Failed to remove task:', e); }
}

// ── Commitments ─────────────────────────────────────

async function addCommitment() {
    const input = document.getElementById('commitmentInput');
    const text = input.value.trim();
    if (!text) return;

    try {
        const res = await fetch('/api/commitments', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ text })
        });
        if (res.ok) {
            input.value = '';
            refreshCommitments();
        }
    } catch (e) {
        console.error('Failed to add commitment:', e);
    }
}

async function removeCommitment(name) {
    try {
        await fetch('/api/commitments/' + encodeURIComponent(name), { method: 'DELETE' });
        refreshCommitments();
    } catch (e) {
        console.error('Failed to remove commitment:', e);
    }
}

async function refreshCommitments() {
    try {
        const res = await fetch('/api/commitments');
        const commitments = await res.json();
        renderCommitments(commitments);
    } catch (e) {
        console.error('Failed to load commitments:', e);
    }
}

function renderCommitments(commitments) {
    commitmentList.innerHTML = '';
    if (commitments.length === 0) {
        commitmentList.innerHTML = '<div class="status-hint">No commitments yet.</div>';
        return;
    }
    commitments.forEach(text => {
        const div = document.createElement('div');
        div.className = 'commitment-item';
        div.innerHTML = `
            <span class="commitment-text">${escapeHtml(text)}</span>
            <button class="remove-btn" title="Remove">✕</button>
        `;
        div.querySelector('.remove-btn').addEventListener('click', () => removeCommitment(text));
        commitmentList.appendChild(div);
    });
}

// ── Study Tasks ─────────────────────────────────────

const studyTaskListEl = document.getElementById('studyTaskList');
const sessionSelectedTasks = new Set();

async function refreshStudyTasks() {
    try {
        const res = await fetch('/api/tasks');
        const tasks = await res.json();
        renderStudyTasks(tasks);
    } catch (e) {
        console.error('Failed to load study tasks:', e);
    }
}

function renderStudyTasks(tasks) {
    studyTaskListEl.innerHTML = '';
    if (tasks.length === 0) {
        studyTaskListEl.innerHTML = '<div class="status-hint">No tasks. Add tasks in the TASKS tab.</div>';
        return;
    }
    tasks.forEach(task => {
        const div = document.createElement('div');
        div.className = 'study-task-item';
        const id = 'study-task-' + task.title.replace(/\s+/g, '-');
        const dateStr = task.dueDate ? `  📅 ${task.dueDate}` : '';
        div.innerHTML = `
            <input type="checkbox" id="${id}" ${sessionSelectedTasks.has(task.title) ? 'checked' : ''}>
            <label for="${id}">${escapeHtml(task.title)}${dateStr}</label>
        `;
        div.querySelector('input').addEventListener('change', (e) => {
            if (e.target.checked) {
                sessionSelectedTasks.add(task.title);
            } else {
                sessionSelectedTasks.delete(task.title);
            }
        });
        studyTaskListEl.appendChild(div);
    });
}

// Init tasks panel
checkGoogleTasksStatus();
refreshTaskLists();
refreshCommitments();

// ── Sleep Clock ─────────────────────────────────────

let sleepClockInterval = null;
const sleepClock = document.getElementById('sleepClock');

function startSleepClock() {
    updateSleepClock();
    sleepClockInterval = setInterval(updateSleepClock, 1000);
}

function stopSleepClock() {
    clearInterval(sleepClockInterval);
}

function updateSleepClock() {
    const now = new Date();
    let h = now.getHours();
    const m = String(now.getMinutes()).padStart(2, '0');
    const ampm = h >= 12 ? 'PM' : 'AM';
    h = h % 12 || 12;
    sleepClock.textContent = `${String(h).padStart(2, '0')}:${m} ${ampm}`;
}

// Wake button opens home panel
document.getElementById('wakeBtn').addEventListener('click', () => {
    if (!activePanels.has('home')) openPanel('home');
});

// ── Spotify ─────────────────────────────────────────

let spotifyPollingInterval = null;
let spotifyDeviceId = null;
let currentSpotifyTab = 'playlists';
let spotifyPreviousTab = 'playlists'; // for going back from track view

async function initSpotifyPanel() {
    try {
        const res = await fetch('/api/spotify/status');
        const data = JSON.parse(await res.text());
        // Show the connect screen if we don't even have a refresh token,
        // OR if the saved token has been rejected (working=false + authenticated=false).
        if (!data.authenticated) {
            showSpotifyAuth();
            return;
        }
        showSpotifyMain();
        // If we have a refresh token but the API isn't actually accepting it,
        // surface a banner inside the main panel. Don't go to the connect screen
        // because some endpoints may still work (different scopes).
        applySpotifyHealthBanner(data);
        loadSpotifyPlaylists();
        startSpotifyPolling();
        initSpotifyWebPlayer();
        refreshDeviceList();
    } catch (e) {
        console.error('[Spotify] Status check failed:', e);
        showSpotifyAuth();
    }
}

/**
 * Insert or remove a top-of-panel banner based on the health probe result.
 * data: {authenticated, working, statusCode?, error?, displayName?}
 */
function applySpotifyHealthBanner(data) {
    let banner = document.getElementById('spotifyHealthBanner');
    if (data && data.working) {
        if (banner) banner.remove();
        return;
    }
    if (!banner) {
        banner = document.createElement('div');
        banner.id = 'spotifyHealthBanner';
        banner.className = 'spotify-devmode-banner spotify-health-banner';
        const main = document.getElementById('spotifyMain');
        if (main) main.insertBefore(banner, main.firstChild);
    }
    const reconnectHtml = `<button class="spotify-connect-btn spotify-reconnect-btn" onclick="window.open(api('/api/spotify/auth'),'_blank','width=500,height=700')">RECONNECT SPOTIFY</button>`;
    const msg = (data && data.error) || 'Spotify API is unreachable.';
    banner.innerHTML = `
        <div class="spotify-devmode-title">⚠ SPOTIFY API NOT WORKING</div>
        <div class="spotify-devmode-text">${escapeHtml(msg)}</div>
        <div style="margin-top:8px">${reconnectHtml}</div>
    `;
}

function showSpotifyAuth() {
    document.getElementById('spotifyAuth').style.display = 'flex';
    document.getElementById('spotifyMain').style.display = 'none';
}

function showSpotifyMain() {
    document.getElementById('spotifyAuth').style.display = 'none';
    document.getElementById('spotifyMain').style.display = 'flex';
}

// Connect button
document.getElementById('spotifyConnectBtn').addEventListener('click', () => {
    window.open(api('/api/spotify/auth'), '_blank', 'width=500,height=700');
    // Poll for successful auth
    const authPoll = setInterval(async () => {
        try {
            const res = await fetch(api('/api/spotify/status'));
            const data = JSON.parse(await res.text());
            if (data.authenticated) {
                clearInterval(authPoll);
                showSpotifyMain();
                loadSpotifyPlaylists();
                startSpotifyPolling();
                initSpotifyWebPlayer();
                refreshDeviceList();
            }
        } catch (e) { /* keep polling */ }
    }, 2000);
    // Stop polling after 5 min
    setTimeout(() => clearInterval(authPoll), 300000);
});

// ── Spotify Tabs ────────────────────────────────────

document.querySelectorAll('.spotify-tab').forEach(tab => {
    tab.addEventListener('click', () => {
        const tabName = tab.dataset.tab;
        switchSpotifyTab(tabName);
    });
});

function switchSpotifyTab(tabName) {
    currentSpotifyTab = tabName;

    // Update tab active state
    document.querySelectorAll('.spotify-tab').forEach(t => t.classList.remove('active'));
    const activeTab = document.querySelector(`.spotify-tab[data-tab="${tabName}"]`);
    if (activeTab) activeTab.classList.add('active');

    // Show/hide content
    document.getElementById('spotifyPlaylistGrid').style.display = tabName === 'playlists' ? 'grid' : 'none';
    document.getElementById('spotifyFeaturedGrid').style.display = tabName === 'featured' ? 'grid' : 'none';
    document.getElementById('spotifySearchResults').style.display = tabName === 'search' ? 'flex' : 'none';
    document.getElementById('spotifyAiDj').style.display = tabName === 'aidj' ? 'flex' : 'none';
    document.getElementById('spotifyPlaylistTracks').style.display = tabName === 'tracks' ? 'flex' : 'none';
    const libEl = document.getElementById('spotifyLibrary');
    if (libEl) libEl.style.display = tabName === 'library' ? 'flex' : 'none';

    // Load data on first switch
    if (tabName === 'playlists') loadSpotifyPlaylists();
    if (tabName === 'featured') loadFeaturedPlaylists();
    if (tabName === 'library') loadSpotifyLibrary();
}

// ── Spotify LIBRARY (Saved + Recently Played) ────────
let savedLoaded = false;
let recentLoaded = false;
let currentLibSubTab = 'saved';

function loadSpotifyLibrary() {
    setLibSubTab(currentLibSubTab);
    if (currentLibSubTab === 'saved') loadSavedTracks();
    else loadRecentTracks();
}

function setLibSubTab(name) {
    currentLibSubTab = name;
    document.querySelectorAll('.spotify-sub-tab').forEach(t => t.classList.toggle('active', t.dataset.libtab === name));
    const saved = document.getElementById('spotifySavedList');
    const recent = document.getElementById('spotifyRecentList');
    if (saved) saved.style.display = name === 'saved' ? 'flex' : 'none';
    if (recent) recent.style.display = name === 'recent' ? 'flex' : 'none';
}

document.querySelectorAll('.spotify-sub-tab').forEach(t => {
    t.addEventListener('click', () => {
        setLibSubTab(t.dataset.libtab);
        if (t.dataset.libtab === 'saved') loadSavedTracks();
        else loadRecentTracks();
    });
});

async function loadSavedTracks() {
    if (savedLoaded) return;
    const container = document.getElementById('spotifySavedList');
    container.innerHTML = '<div class="status-hint">Loading saved tracks…</div>';
    try {
        const res = await fetch('/api/spotify/saved');
        const tracks = JSON.parse(await res.text());
        renderTrackList(container, tracks, null);
        savedLoaded = true;
    } catch (e) {
        container.innerHTML = '<div class="status-hint">Failed to load saved tracks.</div>';
    }
}

async function loadRecentTracks() {
    if (recentLoaded) return;
    const container = document.getElementById('spotifyRecentList');
    container.innerHTML = '<div class="status-hint">Loading recently played…</div>';
    try {
        const res = await fetch('/api/spotify/recent');
        const tracks = JSON.parse(await res.text());
        renderTrackList(container, tracks, null);
        recentLoaded = true;
    } catch (e) {
        container.innerHTML = '<div class="status-hint">Failed to load recently played.</div>';
    }
}

// ── Spotify Playlists ───────────────────────────────

let playlistsLoaded = false;
let featuredLoaded = false;

async function loadSpotifyPlaylists() {
    if (playlistsLoaded) return;
    const grid = document.getElementById('spotifyPlaylistGrid');
    grid.innerHTML = '<div class="status-hint">Loading playlists...</div>';

    try {
        const res = await fetch('/api/spotify/playlists');
        const playlists = JSON.parse(await res.text());
        renderPlaylistGrid(grid, playlists);
        playlistsLoaded = true;
    } catch (e) {
        grid.innerHTML = '<div class="status-hint">Failed to load playlists.</div>';
        console.error('[Spotify] Load playlists error:', e);
    }
}

async function loadFeaturedPlaylists() {
    if (featuredLoaded) return;
    const grid = document.getElementById('spotifyFeaturedGrid');
    grid.innerHTML = '<div class="status-hint">Loading featured playlists...</div>';

    try {
        const res = await fetch('/api/spotify/featured');
        const playlists = JSON.parse(await res.text());
        renderPlaylistGrid(grid, playlists);
        featuredLoaded = true;
    } catch (e) {
        grid.innerHTML = '<div class="status-hint">Failed to load featured playlists.</div>';
        console.error('[Spotify] Load featured error:', e);
    }
}

function renderPlaylistGrid(container, playlists) {
    container.innerHTML = '';
    if (!playlists || playlists.length === 0) {
        container.innerHTML = '<div class="status-hint">No playlists found.</div>';
        return;
    }
    playlists.forEach(p => {
        const card = document.createElement('div');
        card.className = 'playlist-card';
        card.innerHTML = `
            <div class="playlist-card-img">
                ${p.imageUrl ? `<img src="${p.imageUrl}" alt="${escapeHtml(p.name)}" loading="lazy">` : '♫'}
            </div>
            <div class="playlist-card-info">
                <div class="playlist-card-name">${escapeHtml(p.name)}</div>
                <div class="playlist-card-meta">${p.trackCount || 0} tracks${p.owner ? ' · ' + escapeHtml(p.owner) : ''}</div>
            </div>
        `;
        card.addEventListener('click', () => openPlaylistTracks(p.id, p.name, p.uri));
        container.appendChild(card);
    });
}

// ── Create Playlist ─────────────────────────────────

const createPlaylistBtn = document.getElementById('createPlaylistBtn');
const createPlaylistRow = document.getElementById('createPlaylistRow');
const createPlaylistInput = document.getElementById('createPlaylistInput');
const createPlaylistSubmit = document.getElementById('createPlaylistSubmit');
const createPlaylistCancel = document.getElementById('createPlaylistCancel');

createPlaylistBtn.addEventListener('click', () => {
    // Switch to playlists tab and show the form
    switchSpotifyTab('playlists');
    createPlaylistRow.style.display = 'flex';
    createPlaylistInput.value = '';
    createPlaylistInput.focus();
});

createPlaylistCancel.addEventListener('click', () => {
    createPlaylistRow.style.display = 'none';
    createPlaylistInput.value = '';
});

createPlaylistSubmit.addEventListener('click', doCreatePlaylist);
createPlaylistInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doCreatePlaylist();
    if (e.key === 'Escape') {
        createPlaylistRow.style.display = 'none';
        createPlaylistInput.value = '';
    }
});

async function doCreatePlaylist() {
    const name = createPlaylistInput.value.trim();
    if (!name) return;

    createPlaylistSubmit.disabled = true;
    createPlaylistSubmit.textContent = '...';

    try {
        const res = await fetch('/api/spotify/playlist/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description: '', public: false })
        });

        if (res.ok) {
            createPlaylistRow.style.display = 'none';
            createPlaylistInput.value = '';
            // Force reload playlists
            playlistsLoaded = false;
            loadSpotifyPlaylists();
            appendChat('system', 'System', `✅ Created playlist: ${name}`);
        } else {
            const err = await res.text();
            console.error('[Spotify] Create playlist failed:', err);
        }
    } catch (e) {
        console.error('[Spotify] Create playlist error:', e);
    } finally {
        createPlaylistSubmit.disabled = false;
        createPlaylistSubmit.textContent = 'CREATE';
    }
}

// ── Playlist Tracks View ────────────────────────────

async function openPlaylistTracks(playlistId, playlistName, playlistUri) {
    spotifyPreviousTab = currentSpotifyTab;
    switchSpotifyTab('tracks');
    document.getElementById('spotifyPlaylistName').textContent = playlistName;
    const container = document.getElementById('spotifyTrackItems');
    const banner = document.getElementById('spotifyDevModeBanner');
    if (banner) banner.style.display = 'none';
    container.innerHTML = '<div class="status-hint">Loading tracks...</div>';

    try {
        const res = await fetch(`/api/spotify/playlist/${playlistId}/tracks`);
        const tracks = JSON.parse(await res.text());
        renderTrackList(container, tracks, playlistUri);
        // If empty, probe whether Spotify is dev-mode-restricting this app's access
        // to /v1/playlists/{id}/tracks. Show banner if so.
        if (!tracks || tracks.length === 0) {
            try {
                const probe = await fetch('/api/spotify/access');
                const access = await probe.json();
                if (banner && access && access.extendedQuota === false) banner.style.display = '';
            } catch (e) { /* probe failure is non-fatal */ }
        }
    } catch (e) {
        container.innerHTML = '<div class="status-hint">Failed to load tracks.</div>';
    }
}

document.getElementById('spotifyBackBtn').addEventListener('click', () => {
    switchSpotifyTab(spotifyPreviousTab);
});

function renderTrackList(container, tracks, contextUri) {
    container.innerHTML = '';
    if (!tracks || tracks.length === 0) {
        container.innerHTML = '<div class="status-hint">No tracks found.</div>';
        return;
    }
    tracks.forEach((t, index) => {
        const div = document.createElement('div');
        div.className = 'track-item';
        const duration = formatDuration(t.duration_ms);
        div.innerHTML = `
            <div class="track-art">
                ${t.imageUrl ? `<img src="${t.imageUrl}" alt="" loading="lazy">` : '♫'}
            </div>
            <div class="track-info">
                <div class="track-name">${escapeHtml(t.name)}</div>
                <div class="track-artist">${escapeHtml(t.artists || '')}</div>
            </div>
            <div class="track-duration">${duration}</div>
            <button class="track-play-btn" title="Play">▶</button>
        `;
        div.querySelector('.track-play-btn').addEventListener('click', (e) => {
            e.stopPropagation();
            playTrack(t.uri);
        });
        div.addEventListener('click', () => playTrack(t.uri));
        container.appendChild(div);
    });
}

function formatDuration(ms) {
    if (!ms) return '--:--';
    const min = Math.floor(ms / 60000);
    const sec = Math.floor((ms % 60000) / 1000);
    return `${min}:${String(sec).padStart(2, '0')}`;
}

// ── Spotify Search ──────────────────────────────────

const spotifySearchInput = document.getElementById('spotifySearch');
const spotifySearchBtn = document.getElementById('spotifySearchBtn');

spotifySearchBtn.addEventListener('click', doSpotifySearch);
spotifySearchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doSpotifySearch();
});

async function doSpotifySearch() {
    const query = spotifySearchInput.value.trim();
    if (!query) return;

    // Show search tab
    document.getElementById('tabSearch').style.display = 'block';
    switchSpotifyTab('search');

    const container = document.getElementById('spotifySearchResults');
    container.innerHTML = '<div class="status-hint">Searching...</div>';

    try {
        const res = await fetch('/api/spotify/search?q=' + encodeURIComponent(query));
        const tracks = JSON.parse(await res.text());
        renderTrackList(container, tracks);
    } catch (e) {
        container.innerHTML = '<div class="status-hint">Search failed.</div>';
    }
}

// ── Spotify Playback ────────────────────────────────

async function playTrack(uri) {
    try {
        await fetch('/api/spotify/play', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ uri, device_id: spotifyDeviceId || '' })
        });
        // Immediately poll for playback state
        setTimeout(pollPlaybackState, 500);
    } catch (e) {
        console.error('[Spotify] Play failed:', e);
    }
}

let spotifyHealthInterval = null;
function startSpotifyPolling() {
    pollPlaybackState();
    spotifyPollingInterval = setInterval(pollPlaybackState, 3000);
    // Re-check auth health every 60s so an expired token gets surfaced.
    if (spotifyHealthInterval) clearInterval(spotifyHealthInterval);
    spotifyHealthInterval = setInterval(async () => {
        try {
            const res = await fetch('/api/spotify/status');
            const data = JSON.parse(await res.text());
            applySpotifyHealthBanner(data);
            if (!data.authenticated) showSpotifyAuth();
        } catch (e) { /* silent */ }
    }, 60000);
}

function stopSpotifyPolling() {
    if (spotifyPollingInterval) {
        clearInterval(spotifyPollingInterval);
        spotifyPollingInterval = null;
    }
    if (spotifyHealthInterval) {
        clearInterval(spotifyHealthInterval);
        spotifyHealthInterval = null;
    }
}

async function pollPlaybackState() {
    try {
        const res = await fetch('/api/spotify/playback');
        const state = JSON.parse(await res.text());
        updateNowPlaying(state);
    } catch (e) { /* silent */ }
}

function updateNowPlaying(state) {
    const bar = document.getElementById('spotifyNowPlaying');
    const titleEl = document.getElementById('nowPlayingTitle');
    const artistEl = document.getElementById('nowPlayingArtist');
    const artEl = document.getElementById('nowPlayingArt');
    const playBtn = document.getElementById('npPlayPause');
    const progressEl = document.getElementById('npProgressBar');

    if (!state || !state.track) {
        bar.style.display = 'none';
        return;
    }

    bar.style.display = 'flex';
    titleEl.textContent = state.track.name || '—';
    artistEl.textContent = state.track.artists || '—';

    if (state.track.imageUrl) {
        artEl.innerHTML = `<img src="${state.track.imageUrl}" alt="">`;
    } else {
        artEl.innerHTML = '♫';
    }

    playBtn.textContent = state.playing ? '⏸' : '▶';

    // Progress
    if (state.track.duration_ms && state.progress_ms != null) {
        const pct = (state.progress_ms / state.track.duration_ms) * 100;
        progressEl.style.width = pct + '%';
    }
}

// Now Playing controls
document.getElementById('npPlayPause').addEventListener('click', async () => {
    const btn = document.getElementById('npPlayPause');
    const isPlaying = btn.textContent === '⏸';
    try {
        await fetch(isPlaying ? '/api/spotify/pause' : '/api/spotify/resume', { method: 'POST' });
        btn.textContent = isPlaying ? '▶' : '⏸';
        setTimeout(pollPlaybackState, 300);
    } catch (e) { console.error('[Spotify] PlayPause error:', e); }
});

document.getElementById('npNext').addEventListener('click', async () => {
    try {
        await fetch('/api/spotify/skip', { method: 'POST' });
        setTimeout(pollPlaybackState, 500);
    } catch (e) { console.error('[Spotify] Skip error:', e); }
});

document.getElementById('npPrev').addEventListener('click', async () => {
    try {
        await fetch('/api/spotify/previous', { method: 'POST' });
        setTimeout(pollPlaybackState, 500);
    } catch (e) { console.error('[Spotify] Prev error:', e); }
});

// ── Spotify Web Playback SDK ────────────────────────

let spotifyWebPlayer = null;
let spotifyWebPlayerReady = false;

// Spotify SDK calls this global when it's ready
window.onSpotifyWebPlaybackSDKReady = () => {
    console.log('[Spotify] Web Playback SDK ready');
    // initSpotifyWebPlayer will be called when auth is confirmed
};

async function initSpotifyWebPlayer() {
    if (spotifyWebPlayer) return; // already initialized
    try {
        const res = await fetch('/api/spotify/token');
        const data = JSON.parse(await res.text());
        const token = data.token;
        if (!token) return;

        spotifyWebPlayer = new Spotify.Player({
            name: 'Sentient Assistant',
            getOAuthToken: async (cb) => {
                // Always fetch fresh token
                try {
                    const r = await fetch('/api/spotify/token');
                    const d = JSON.parse(await r.text());
                    cb(d.token);
                } catch (e) {
                    cb(token);
                }
            },
            volume: 0.5
        });

        spotifyWebPlayer.addListener('ready', ({ device_id }) => {
            console.log('[Spotify] Web Player ready, device_id:', device_id);
            spotifyDeviceId = device_id;
            spotifyWebPlayerReady = true;
            // Refresh device list to show this device
            setTimeout(refreshDeviceList, 1000);
        });

        spotifyWebPlayer.addListener('not_ready', ({ device_id }) => {
            console.log('[Spotify] Web Player went offline:', device_id);
            spotifyWebPlayerReady = false;
        });

        spotifyWebPlayer.addListener('player_state_changed', (state) => {
            if (state) {
                const track = state.track_window?.current_track;
                if (track) {
                    updateNowPlaying({
                        playing: !state.paused,
                        progress_ms: state.position,
                        track: {
                            name: track.name,
                            artists: track.artists.map(a => a.name).join(', '),
                            imageUrl: track.album?.images?.[0]?.url || '',
                            duration_ms: state.duration
                        }
                    });
                }
            }
        });

        spotifyWebPlayer.addListener('initialization_error', ({ message }) => {
            console.error('[Spotify] Init error:', message);
        });
        spotifyWebPlayer.addListener('authentication_error', ({ message }) => {
            console.error('[Spotify] Auth error:', message);
        });
        spotifyWebPlayer.addListener('account_error', ({ message }) => {
            console.error('[Spotify] Account error (Premium required):', message);
        });

        await spotifyWebPlayer.connect();
        console.log('[Spotify] Web Player connecting...');
    } catch (e) {
        console.error('[Spotify] Web Player init error:', e);
    }
}

// ── Device Selector ────────────────────────────────

const deviceSelect = document.getElementById('npDeviceSelect');

async function refreshDeviceList() {
    try {
        const res = await fetch('/api/spotify/devices');
        const devices = JSON.parse(await res.text());
        deviceSelect.innerHTML = '';

        if (!devices || devices.length === 0) {
            deviceSelect.innerHTML = '<option value="">No devices found</option>';
            return;
        }

        devices.forEach(d => {
            const opt = document.createElement('option');
            opt.value = d.id;
            const icon = d.type === 'Computer' ? '💻' : d.type === 'Smartphone' ? '📱' : d.type === 'Speaker' ? '🔊' : '🎵';
            opt.textContent = `${icon} ${d.name}`;
            if (d.active) opt.selected = true;
            deviceSelect.appendChild(opt);
        });
    } catch (e) {
        console.error('[Spotify] Load devices error:', e);
    }
}

deviceSelect.addEventListener('change', async () => {
    const deviceId = deviceSelect.value;
    if (!deviceId) return;
    spotifyDeviceId = deviceId;
    try {
        await fetch('/api/spotify/transfer', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ device_id: deviceId })
        });
        console.log('[Spotify] Transferred to device:', deviceId);
    } catch (e) {
        console.error('[Spotify] Transfer error:', e);
    }
});

// Refresh device list periodically when Spotify panel is open
setInterval(() => {
    if (activePanels.has('spotify')) refreshDeviceList();
}, 15000);

// ── AI DJ ───────────────────────────────────────────

const aiDjInput = document.getElementById('aiDjInput');
const aiDjBtn = document.getElementById('aiDjBtn');
const aiDjStatus = document.getElementById('aiDjStatus');
const aiDjResults = document.getElementById('aiDjResults');

aiDjBtn.addEventListener('click', runAiDj);
aiDjInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runAiDj();
});

async function runAiDj() {
    const mood = aiDjInput.value.trim();
    if (!mood) return;

    aiDjBtn.disabled = true;
    aiDjStatus.textContent = 'AI is picking songs for you';
    aiDjStatus.className = 'ai-dj-status loading';
    aiDjResults.innerHTML = '';

    try {
        const res = await fetch('/api/spotify/ai-dj', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ mood, device_id: spotifyDeviceId || '' })
        });
        const data = JSON.parse(await res.text());

        if (data.error) {
            aiDjStatus.textContent = 'Error: ' + data.error;
            aiDjStatus.className = 'ai-dj-status';
        } else {
            aiDjStatus.textContent = data.message || `Found ${data.tracks?.length || 0} tracks`;
            aiDjStatus.className = 'ai-dj-status';
            if (data.tracks) {
                renderTrackList(aiDjResults, data.tracks);
            }
            // Poll playback immediately
            setTimeout(pollPlaybackState, 1000);
        }
    } catch (e) {
        aiDjStatus.textContent = 'AI DJ request failed.';
        aiDjStatus.className = 'ai-dj-status';
        console.error('[Spotify] AI DJ error:', e);
    } finally {
        aiDjBtn.disabled = false;
    }
}

// ── Spotify Command Integration ─────────────────────
// Add Spotify commands to the executeCommand handler
const originalExecuteCommand = executeCommand;
executeCommand = function (action, param) {
    switch (action) {
        case 'SWITCH_SPOTIFY':
            if (!activePanels.has('spotify')) openPanel('spotify');
            appendChat('system', 'System', '⚡ Switched to Music');
            break;
        case 'PLAY_MUSIC':
            if (!activePanels.has('spotify')) openPanel('spotify');
            if (param) playTrack(param);
            break;
        case 'CREATE_PLAYLIST':
            // Playlist was already created server-side; refresh the list
            if (!activePanels.has('spotify')) openPanel('spotify');
            playlistsLoaded = false;
            loadSpotifyPlaylists();
            switchSpotifyTab('playlists');
            break;
        default:
            originalExecuteCommand(action, param);
    }
};

// ── Initialize ──────────────────────────────────────

// Connect WebSocket
connectWS();

// Open home panel by default
openPanel('home');

// ── Calendar ────────────────────────────────────────

const calendarAuth = document.getElementById('calendarAuth');
const calendarAuthText = document.getElementById('calendarAuthText');
const calendarAuthBtn = document.getElementById('calendarAuthBtn');
const calendarMain = document.getElementById('calendarMain');
const calendarGrid = document.getElementById('calendarGrid');
const calDateRange = document.getElementById('calDateRange');
const calPrevWeekBtn = document.getElementById('calPrevWeek');
const calNextWeekBtn = document.getElementById('calNextWeek');
const calTooltip = document.getElementById('calTooltip');

const calTitleInput = document.getElementById('calTitleInput');
const calDescInput = document.getElementById('calDescInput');
const calStartInput = document.getElementById('calStartInput');
const calEndInput = document.getElementById('calEndInput');
const addCalEventBtn = document.getElementById('addCalEventBtn');
const cancelEditBtn = document.getElementById('cancelEditBtn');
const deleteEditBtn = document.getElementById('deleteEditBtn');

const calAiBtn = document.getElementById('calAiBtn');
const aiSuggestionArea = document.getElementById('aiSuggestionArea');
const closeAiSuggestions = document.getElementById('closeAiSuggestions');
const aiSuggestionLoading = document.getElementById('aiSuggestionLoading');
const aiSuggestionList = document.getElementById('aiSuggestionList');

let calendarEvents = [];
let currentWeekOffset = 0;
let currentMonthOffset = 0;
let currentDayOffset = 0;
let calendarViewMode = 'week'; // 'week', 'month', 'day'
let editingEventId = null; // for click-to-edit

// View mode toggle
document.querySelectorAll('.cal-view-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.cal-view-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        calendarViewMode = btn.dataset.view;
        renderCalendarGrid();
    });
});

async function checkCalendarStatus() {
    try {
        const res = await fetch('/api/calendar/status');
        const status = await res.json();

        if (!status.configured) {
            if (calendarAuthText) calendarAuthText.textContent = "Google Calendar not configured. Using local calendar.";
            if (calendarAuthBtn) calendarAuthBtn.style.display = 'none';
        } else if (!status.authenticated) {
            if (calendarAuthText) calendarAuthText.textContent = "Connect Google Calendar to sync events. Currently using local calendar.";
            if (calendarAuthBtn) calendarAuthBtn.style.display = 'inline-flex';
        } else {
            if (calendarAuth) calendarAuth.style.display = 'none';
        }

        if (calendarMain) calendarMain.style.display = 'block';
        return true;
    } catch (e) {
        console.error("Calendar status error:", e);
        if (calendarAuthText) calendarAuthText.textContent = "Working locally.";
        if (calendarAuthBtn) calendarAuthBtn.style.display = 'none';
        if (calendarMain) calendarMain.style.display = 'block';
        return true;
    }
}

if (calendarAuthBtn) {
    calendarAuthBtn.addEventListener('click', () => {
        // Tasks and Calendar share a single OAuth token file + auth endpoint;
        // use api(...) so it works when sentient_masterHost points elsewhere.
        window.open(api('/api/tasks/google/auth'), '_blank', 'width=500,height=700');
        const pollInterval = setInterval(async () => {
            try {
                await fetch(api('/api/calendar/refresh-auth'), { method: 'POST' });
                if (await checkCalendarStatus()) {
                    clearInterval(pollInterval);
                    loadCalendarEvents();
                }
            } catch (e) { }
        }, 2000);
    });
}

async function loadCalendarEvents() {
    if (!await checkCalendarStatus()) return;
    try {
        const res = await fetch('/api/calendar/events?days=60');
        const data = await res.json();
        if (data.events) {
            calendarEvents = data.events;
            renderCalendarGrid();
        }
    } catch (e) {
        console.error("Failed to load events", e);
    }
}

// ── Calendar rendering ──────────────────────────────

function renderCalendarGrid() {
    if (!calendarGrid) return;

    if (calendarViewMode === 'week') renderWeekView();
    else if (calendarViewMode === 'month') renderMonthView();
    else if (calendarViewMode === 'day') renderDayView();
}

function getEventsForDate(date) {
    return calendarEvents.filter(ev => {
        if (!ev.start) return false;
        let evDate;
        if (ev.allDay) {
            const [y, m, d] = ev.start.split('-');
            evDate = new Date(y, m - 1, d);
        } else {
            evDate = new Date(ev.start);
        }
        evDate.setHours(0, 0, 0, 0);
        return evDate.getTime() === date.getTime();
    });
}

function makeEventCard(ev) {
    const card = document.createElement('div');
    card.className = 'cal-event-card';

    let timeStr = 'All Day';
    if (!ev.allDay && ev.start) {
        timeStr = new Date(ev.start).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    }

    card.innerHTML = `
        <div class="cal-event-title">${escapeHtml(ev.title)}</div>
        <div class="cal-event-time">${timeStr}</div>
    `;

    // Hover tooltip — follows mouse cursor
    card.addEventListener('mouseenter', (e) => {
        let endStr = '';
        if (ev.end) endStr = new Date(ev.end).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
        let startStr = '';
        if (ev.start) startStr = new Date(ev.start).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

        calTooltip.innerHTML = `
            <div class="cal-tooltip-title">${escapeHtml(ev.title)}</div>
            ${ev.description ? `<div class="cal-tooltip-desc">${escapeHtml(ev.description)}</div>` : ''}
            <div class="cal-tooltip-time">${ev.allDay ? 'All Day' : startStr + ' → ' + endStr}</div>
        `;
        calTooltip.style.display = 'block';
    });
    card.addEventListener('mousemove', (e) => {
        calTooltip.style.left = (e.pageX + 12) + 'px';
        calTooltip.style.top = (e.pageY + 12) + 'px';
    });
    card.addEventListener('mouseleave', () => {
        calTooltip.style.display = 'none';
    });

    // Click-to-edit
    card.addEventListener('click', () => {
        editingEventId = ev.id;
        calTitleInput.value = ev.title || '';
        calDescInput.value = ev.description || '';
        if (ev.start) calStartInput.value = toDateTimeLocal(ev.start);
        if (ev.end) calEndInput.value = toDateTimeLocal(ev.end);
        addCalEventBtn.textContent = 'UPDATE EVENT';
        cancelEditBtn.style.display = 'inline-flex';
        if (deleteEditBtn) deleteEditBtn.style.display = 'inline-flex';

        // Scroll form into view
        document.getElementById('calAddForm').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });

    return card;
}

// Convert ISO/datetime string to datetime-local value
function toDateTimeLocal(str) {
    if (!str) return '';
    const d = new Date(str);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// Format datetime-local value to local ISO offset string (prevents UTC conversion / timezone shift)
function toLocalISO(dtLocalValue) {
    if (!dtLocalValue) return '';
    // dtLocalValue is like "2026-03-25T10:00"
    const d = new Date(dtLocalValue);
    const off = -d.getTimezoneOffset();
    const sign = off >= 0 ? '+' : '-';
    const h = String(Math.floor(Math.abs(off) / 60)).padStart(2, '0');
    const m = String(Math.abs(off) % 60).padStart(2, '0');
    return dtLocalValue + ':00' + sign + h + ':' + m;
}

// ── WEEK VIEW ──────────────────────────────────────

function renderWeekView() {
    calendarGrid.innerHTML = '';
    calendarGrid.className = 'calendar-grid';

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const day = today.getDay() || 7;
    const baseMonday = new Date(today);
    baseMonday.setDate(today.getDate() - day + 1 + (currentWeekOffset * 7));

    const weekDays = [];
    for (let i = 0; i < 7; i++) {
        const d = new Date(baseMonday);
        d.setDate(baseMonday.getDate() + i);
        weekDays.push(d);
    }

    const firstDay = weekDays[0];
    const lastDay = weekDays[6];
    const fmt = { month: 'short', day: 'numeric' };
    if (calDateRange) calDateRange.textContent = `${firstDay.toLocaleDateString(undefined, fmt)} — ${lastDay.toLocaleDateString(undefined, fmt)}`;

    weekDays.forEach(date => {
        const col = document.createElement('div');
        col.className = 'cal-day-col';

        const isToday = date.getTime() === today.getTime();
        const header = document.createElement('div');
        header.className = `cal-day-header ${isToday ? 'today' : ''}`;
        header.innerHTML = `
            <div class="cal-day-name">${date.toLocaleDateString(undefined, { weekday: 'short' }).toUpperCase()}</div>
            <div class="cal-day-num">${date.getDate()}</div>
        `;
        col.appendChild(header);

        const dayEvents = getEventsForDate(date);
        const eventsContainer = document.createElement('div');
        eventsContainer.className = 'cal-events';

        dayEvents.forEach(ev => {
            eventsContainer.appendChild(makeEventCard(ev));
        });

        col.appendChild(eventsContainer);
        calendarGrid.appendChild(col);
    });
}

// ── MONTH VIEW ─────────────────────────────────────

function renderMonthView() {
    calendarGrid.innerHTML = '';
    calendarGrid.className = 'calendar-grid cal-month-grid';

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const viewDate = new Date(today.getFullYear(), today.getMonth() + currentMonthOffset, 1);
    const year = viewDate.getFullYear();
    const month = viewDate.getMonth();
    const firstDay = new Date(year, month, 1);
    const lastDay = new Date(year, month + 1, 0);

    if (calDateRange) calDateRange.textContent = viewDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });

    // Day-of-week headers
    const dayNames = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
    dayNames.forEach(name => {
        const hdr = document.createElement('div');
        hdr.className = 'cal-month-header';
        hdr.textContent = name;
        calendarGrid.appendChild(hdr);
    });

    // Offset for first day (Monday=0)
    let startOffset = (firstDay.getDay() + 6) % 7;

    // Empty cells before month start
    for (let i = 0; i < startOffset; i++) {
        const empty = document.createElement('div');
        empty.className = 'cal-month-cell empty';
        calendarGrid.appendChild(empty);
    }

    // Day cells
    for (let d = 1; d <= lastDay.getDate(); d++) {
        const dateObj = new Date(year, month, d);
        dateObj.setHours(0, 0, 0, 0);
        const cell = document.createElement('div');
        cell.className = 'cal-month-cell';
        if (dateObj.getTime() === today.getTime()) cell.classList.add('today');

        const dayEvents = getEventsForDate(dateObj);
        cell.innerHTML = `<span class="cal-month-day-num">${d}</span>`;

        if (dayEvents.length > 0) {
            const dotsDiv = document.createElement('div');
            dotsDiv.className = 'cal-month-events';
            dayEvents.slice(0, 3).forEach(ev => {
                const dot = document.createElement('div');
                dot.className = 'cal-month-event-dot';
                dot.title = ev.title;
                dotsDiv.appendChild(dot);
            });
            if (dayEvents.length > 3) {
                const more = document.createElement('span');
                more.className = 'cal-month-more';
                more.textContent = `+${dayEvents.length - 3}`;
                dotsDiv.appendChild(more);
            }
            cell.appendChild(dotsDiv);
        }

        // Click day to switch to day view
        cell.addEventListener('click', () => {
            const diff = Math.floor((dateObj - today) / (1000 * 60 * 60 * 24));
            currentDayOffset = diff;
            calendarViewMode = 'day';
            document.querySelectorAll('.cal-view-btn').forEach(b => b.classList.remove('active'));
            document.querySelector('.cal-view-btn[data-view="day"]').classList.add('active');
            renderCalendarGrid();
        });

        calendarGrid.appendChild(cell);
    }
}

// ── DAY VIEW ───────────────────────────────────────

function renderDayView() {
    calendarGrid.innerHTML = '';
    calendarGrid.className = 'calendar-grid cal-day-view';

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const viewDate = new Date(today);
    viewDate.setDate(today.getDate() + currentDayOffset);

    if (calDateRange) calDateRange.textContent = viewDate.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });

    const dayEvents = getEventsForDate(viewDate);

    // Hourly slots
    for (let hour = 0; hour < 24; hour++) {
        const slot = document.createElement('div');
        slot.className = 'cal-hour-slot';

        const label = document.createElement('div');
        label.className = 'cal-hour-label';
        const ampm = hour >= 12 ? 'PM' : 'AM';
        const displayHour = hour % 12 || 12;
        label.textContent = `${displayHour} ${ampm}`;
        slot.appendChild(label);

        const content = document.createElement('div');
        content.className = 'cal-hour-content';

        // Events that start at this hour
        const hourEvents = dayEvents.filter(ev => {
            if (ev.allDay) return hour === 0;
            const evHour = new Date(ev.start).getHours();
            return evHour === hour;
        });

        hourEvents.forEach(ev => {
            content.appendChild(makeEventCard(ev));
        });

        slot.appendChild(content);
        calendarGrid.appendChild(slot);
    }
}

// ── Navigation ──────────────────────────────────────

if (calPrevWeekBtn) calPrevWeekBtn.addEventListener('click', () => {
    if (calendarViewMode === 'week') currentWeekOffset--;
    else if (calendarViewMode === 'month') currentMonthOffset--;
    else if (calendarViewMode === 'day') currentDayOffset--;
    renderCalendarGrid();
});
if (calNextWeekBtn) calNextWeekBtn.addEventListener('click', () => {
    if (calendarViewMode === 'week') currentWeekOffset++;
    else if (calendarViewMode === 'month') currentMonthOffset++;
    else if (calendarViewMode === 'day') currentDayOffset++;
    renderCalendarGrid();
});

// ── Add / Update Event ──────────────────────────────

if (cancelEditBtn) {
    cancelEditBtn.addEventListener('click', () => {
        editingEventId = null;
        calTitleInput.value = '';
        calDescInput.value = '';
        calStartInput.value = '';
        calEndInput.value = '';
        addCalEventBtn.textContent = '+ ADD EVENT';
        cancelEditBtn.style.display = 'none';
        if (deleteEditBtn) deleteEditBtn.style.display = 'none';
    });
}

if (deleteEditBtn) {
    deleteEditBtn.addEventListener('click', async () => {
        if (!editingEventId) return;
        if (!confirm('Delete this event?')) return;
        await fetch('/api/calendar/events/' + editingEventId, { method: 'DELETE' });
        editingEventId = null;
        calTitleInput.value = '';
        calDescInput.value = '';
        calStartInput.value = '';
        calEndInput.value = '';
        addCalEventBtn.textContent = '+ ADD EVENT';
        cancelEditBtn.style.display = 'none';
        deleteEditBtn.style.display = 'none';
        loadCalendarEvents();
    });
}

if (addCalEventBtn) {
    addCalEventBtn.addEventListener('click', async () => {
        const payload = {
            title: calTitleInput.value,
            description: calDescInput.value,
            start: toLocalISO(calStartInput.value),
            end: toLocalISO(calEndInput.value)
        };
        if (!payload.title || !payload.start || !payload.end) return alert('Title, Start, and End are required.');

        if (editingEventId) {
            // UPDATE existing event
            await fetch('/api/calendar/events/' + editingEventId, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            editingEventId = null;
            addCalEventBtn.textContent = '+ ADD EVENT';
            cancelEditBtn.style.display = 'none';
        } else {
            // CREATE new event
            await fetch('/api/calendar/events', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
        }

        calTitleInput.value = '';
        calDescInput.value = '';
        calStartInput.value = '';
        calEndInput.value = '';

        loadCalendarEvents();
    });
}

// ── AI Suggestions ──────────────────────────────────

if (calAiBtn) {
    calAiBtn.addEventListener('click', async () => {
        calAiBtn.disabled = true;
        aiSuggestionArea.style.display = 'block';
        aiSuggestionLoading.style.display = 'block';
        aiSuggestionList.innerHTML = '';

        try {
            const res = await fetch('/api/calendar/ai-suggest', { method: 'POST' });
            const data = await res.json();

            aiSuggestionLoading.style.display = 'none';
            calAiBtn.disabled = false;

            if (data.suggestions && data.suggestions.length > 0) {
                data.suggestions.forEach(sug => {
                    const card = document.createElement('div');
                    card.className = 'ai-sug-card';
                    card.innerHTML = `
                        <div class="ai-sug-title">${sug.action.toUpperCase()}: ${sug.title}</div>
                        <div class="ai-sug-desc">${sug.description || ''}</div>
                        <div class="ai-sug-time">${sug.start ? new Date(sug.start).toLocaleString() : ''} - ${sug.end ? new Date(sug.end).toLocaleString() : ''}</div>
                    `;

                    const btnGrp = document.createElement('div');
                    btnGrp.className = 'ai-sug-actions';

                    const accept = document.createElement('button');
                    accept.className = 'timer-btn play-btn';
                    accept.textContent = 'APPROVE';
                    accept.onclick = async () => {
                        accept.disabled = true;
                        await fetch('/api/calendar/ai-apply', {
                            method: 'POST',
                            body: JSON.stringify(sug)
                        });
                        card.remove();
                        loadCalendarEvents();
                        refreshTasks();
                    };

                    const dismiss = document.createElement('button');
                    dismiss.className = 'timer-btn reset-btn';
                    dismiss.textContent = 'DISMISS';
                    dismiss.onclick = () => card.remove();

                    btnGrp.appendChild(accept);
                    btnGrp.appendChild(dismiss);
                    card.appendChild(btnGrp);

                    aiSuggestionList.appendChild(card);
                });
            } else {
                aiSuggestionList.innerHTML = '<div style="color:#a1a1aa">No suggestions at this time.</div>';
            }
        } catch (e) {
            aiSuggestionLoading.style.display = 'none';
            aiSuggestionList.innerHTML = '<div style="color:#f87171">Failed to get suggestions.</div>';
            calAiBtn.disabled = false;
        }
    });
}

if (closeAiSuggestions) {
    closeAiSuggestions.addEventListener('click', () => {
        aiSuggestionArea.style.display = 'none';
    });
}

// Initial status check
checkCalendarStatus().then(ready => {
    if (ready) loadCalendarEvents();
});

// ── File Attachments ──────────────────────────────────
const chatAttach = document.getElementById('chatAttach');
const filePreviewArea = document.getElementById('filePreviewArea');
const filePreviewName = document.getElementById('filePreviewName');
const removeFileBtn = document.getElementById('removeFileBtn');

if (chatAttach) {
    chatAttach.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (!file) return;
        filePreviewName.textContent = file.name;
        filePreviewArea.style.display = 'flex';
        
        currentFileName = file.name;
        currentFileType = file.type;
        
        const reader = new FileReader();
        reader.onload = function(evt) {
            currentImageBase64 = evt.target.result;
        };
        reader.readAsDataURL(file);
    });
}
if (removeFileBtn) {
    removeFileBtn.addEventListener('click', () => {
        removeAttachedFile();
    });
}
function removeAttachedFile() {
    if (chatAttach) chatAttach.value = '';
    currentImageBase64 = null;
    currentFileName = null;
    currentFileType = null;
    if (filePreviewArea) filePreviewArea.style.display = 'none';
}

// ── Settings Logic ────────────────────────────────────
const setConvMode = document.getElementById('settingConvMode');
const setModel = document.getElementById('settingModel');
const setTts = document.getElementById('settingTts');
const setSpeaker = document.getElementById('settingSpeaker');
const setMic = document.getElementById('settingMic');

function loadSettings() {
    convMode = localStorage.getItem('sentient_convMode') !== 'false';
    ttsEnabled = localStorage.getItem('sentient_ttsEnabled') !== 'false';
    modelOverride = localStorage.getItem('sentient_model') || 'AUTO';
    micSetting = localStorage.getItem('sentient_mic') || 'enabled';
    
    if (setConvMode) setConvMode.checked = convMode;
    if (setTts) setTts.checked = ttsEnabled;
    if (setModel) setModel.value = modelOverride;
    if (setMic) setMic.value = micSetting;
    
    populateAudioDevices();
}

if (setConvMode) {
    setConvMode.addEventListener('change', (e) => {
        convMode = e.target.checked;
        localStorage.setItem('sentient_convMode', convMode);
    });
}
if (setTts) {
    setTts.addEventListener('change', (e) => {
        ttsEnabled = e.target.checked;
        localStorage.setItem('sentient_ttsEnabled', ttsEnabled);
    });
}
if (setMic) {
    setMic.addEventListener('change', (e) => {
        micSetting = e.target.value;
        localStorage.setItem('sentient_mic', micSetting);
        if (micSetting === 'muted' && isRecording) {
            if (recognition) recognition.stop();
            setRecordingState(false);
        }
    });
}
if (setModel) {
    setModel.addEventListener('change', (e) => {
        modelOverride = e.target.value;
        localStorage.setItem('sentient_model', modelOverride);
    });
}

const setMicDevice = document.getElementById('settingMicDevice');
const settingsRefreshDevicesBtn = document.getElementById('settingsRefreshDevicesBtn');
let micDeviceId = localStorage.getItem('sentient_micDevice') || 'default';

async function populateAudioDevices() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
    try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        const speakers = devices.filter(d => d.kind === 'audiooutput');
        const mics = devices.filter(d => d.kind === 'audioinput');

        if (speakers.length > 0 && setSpeaker) {
            setSpeaker.innerHTML = '<option value="default">System Default</option>';
            speakers.forEach(s => {
                if (s.deviceId === 'default' || !s.deviceId) return;
                const opt = document.createElement('option');
                opt.value = s.deviceId;
                opt.textContent = s.label || `Speaker ${s.deviceId.substring(0, 5)}...`;
                setSpeaker.appendChild(opt);
            });
            const savedSpeaker = localStorage.getItem('sentient_speaker');
            if (savedSpeaker) setSpeaker.value = savedSpeaker;
        }

        if (mics.length > 0 && setMicDevice) {
            setMicDevice.innerHTML = '<option value="default">System Default</option>';
            mics.forEach(m => {
                if (m.deviceId === 'default' || !m.deviceId) return;
                const opt = document.createElement('option');
                opt.value = m.deviceId;
                opt.textContent = m.label || `Mic ${m.deviceId.substring(0, 5)}...`;
                setMicDevice.appendChild(opt);
            });
            const savedMic = localStorage.getItem('sentient_micDevice');
            if (savedMic) setMicDevice.value = savedMic;
        }
    } catch (e) {
        console.error("Failed to enumerate devices", e);
    }
}

/**
 * Browsers withhold device labels until at least one getUserMedia permission has been
 * granted in the session. This requests a brief stream so the picker can show real names.
 */
async function requestMicPermissionAndRepopulate() {
    try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach(t => t.stop());
        await populateAudioDevices();
    } catch (e) {
        console.error("Mic permission denied or unavailable", e);
    }
}

if (settingsRefreshDevicesBtn) {
    settingsRefreshDevicesBtn.addEventListener('click', requestMicPermissionAndRepopulate);
}

if (setSpeaker) {
    setSpeaker.addEventListener('change', (e) => {
        localStorage.setItem('sentient_speaker', e.target.value);
    });
}

if (setMicDevice) {
    setMicDevice.addEventListener('change', (e) => {
        micDeviceId = e.target.value;
        localStorage.setItem('sentient_micDevice', micDeviceId);
    });
}

/**
 * Claim the chosen mic device so the browser's SpeechRecognition uses it.
 * SpeechRecognition has no deviceId API, but most browsers pick whichever input was
 * most recently used by getUserMedia. We grab a one-shot track on the chosen device
 * and release it right before recognition.start().
 */
async function claimChosenMic() {
    if (!micDeviceId || micDeviceId === 'default') return;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return;
    try {
        const stream = await navigator.mediaDevices.getUserMedia({
            audio: { deviceId: { exact: micDeviceId } }
        });
        // Release immediately — we only needed the side effect of selecting the device.
        stream.getTracks().forEach(t => t.stop());
    } catch (e) {
        console.error("Could not claim mic device", micDeviceId, e);
    }
}

// ── Device Login (shared password) UI ───────────────────
const authStatusBadge = document.getElementById('authStatusBadge');
const authPasswordInput = document.getElementById('authPasswordInput');
const authSaveBtn = document.getElementById('authSaveBtn');
const authClearBtn = document.getElementById('authClearBtn');
const authLogoutBtn = document.getElementById('authLogoutBtn');
const authFeedback = document.getElementById('authFeedback');

function setAuthBadge(state, text) {
    if (!authStatusBadge) return;
    authStatusBadge.className = 'setting-status status-' + state;
    authStatusBadge.textContent = text;
}

async function refreshAuthStatus() {
    if (!authStatusBadge) return;
    setAuthBadge('checking', 'CHECKING…');
    try {
        const r = await fetch(api('/api/auth/status'));
        const data = await r.json();
        if (data.required) {
            setAuthBadge(data.loggedIn ? 'online' : 'error', data.loggedIn ? 'LOGGED IN' : 'NOT LOGGED IN');
        } else {
            setAuthBadge('offline', 'LOGIN DISABLED');
        }
    } catch (e) {
        setAuthBadge('error', 'SERVER UNREACHABLE');
    }
}
if (authSaveBtn) {
    authSaveBtn.addEventListener('click', async () => {
        const pw = (authPasswordInput && authPasswordInput.value || '').trim();
        if (pw.length < 6) {
            authFeedback.className = 'setting-feedback error';
            authFeedback.textContent = 'Password must be at least 6 characters.';
            return;
        }
        authFeedback.className = 'setting-feedback';
        authFeedback.textContent = 'Saving…';
        try {
            const r = await fetch(api('/api/auth/password'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: pw })
            });
            const data = await r.json();
            if (!r.ok || data.error) {
                authFeedback.className = 'setting-feedback error';
                authFeedback.textContent = data.error || ('Save failed (HTTP ' + r.status + ')');
                return;
            }
            // Setting/changing the password revokes all prior tokens — we need to log
            // back in on THIS device to keep using the app.
            const login = await fetch(api('/api/auth/login'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: pw })
            }).then(x => x.json());
            if (login && login.token) {
                if (typeof setSentientToken === 'function') setSentientToken(login.token);
            }
            authPasswordInput.value = '';
            authFeedback.className = 'setting-feedback success';
            authFeedback.textContent = 'Password set. All other devices must log in again.';
            refreshAuthStatus();
        } catch (e) {
            authFeedback.className = 'setting-feedback error';
            authFeedback.textContent = 'Backend unreachable.';
        }
    });
}
if (authClearBtn) {
    authClearBtn.addEventListener('click', async () => {
        if (!confirm('Disable login? Anyone with network access will be able to use this app.')) return;
        authFeedback.className = 'setting-feedback';
        authFeedback.textContent = 'Disabling…';
        try {
            const r = await fetch(api('/api/auth/password'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: '' })
            });
            if (!r.ok) {
                authFeedback.className = 'setting-feedback error';
                authFeedback.textContent = 'Failed to disable (HTTP ' + r.status + ')';
                return;
            }
            if (typeof clearSentientToken === 'function') clearSentientToken();
            authFeedback.className = 'setting-feedback success';
            authFeedback.textContent = 'Login disabled.';
            refreshAuthStatus();
        } catch (e) {
            authFeedback.className = 'setting-feedback error';
            authFeedback.textContent = 'Backend unreachable.';
        }
    });
}
if (authLogoutBtn) {
    authLogoutBtn.addEventListener('click', async () => {
        try { await fetch(api('/api/auth/logout'), { method: 'POST' }); } catch (e) {}
        if (typeof clearSentientToken === 'function') clearSentientToken();
        location.href = '/login';
    });
}
setTimeout(refreshAuthStatus, 1500);

// ── Tailscale Funnel UI ─────────────────────────────────
const tailscaleStatusBadge = document.getElementById('tailscaleStatusBadge');
const tailscaleEnableBtn = document.getElementById('tailscaleEnableBtn');
const tailscaleDisableBtn = document.getElementById('tailscaleDisableBtn');
const tailscaleRefreshBtn = document.getElementById('tailscaleRefreshBtn');
const tailscaleStatusEl = document.getElementById('tailscaleStatus');
const tailscaleUrlLine = document.getElementById('tailscaleUrlLine');

function setTailscaleBadge(state, text) {
    if (!tailscaleStatusBadge) return;
    tailscaleStatusBadge.className = `setting-status status-${state}`;
    tailscaleStatusBadge.textContent = text;
}

async function refreshTailscaleStatus() {
    if (!tailscaleStatusBadge) return;
    setTailscaleBadge('checking', 'CHECKING…');
    if (tailscaleUrlLine) tailscaleUrlLine.textContent = '';
    try {
        const r = await fetch(api('/api/tailscale/status'));
        const data = await r.json();
        if (!data.installed) {
            setTailscaleBadge('error', 'NOT INSTALLED');
            if (tailscaleUrlLine) tailscaleUrlLine.innerHTML = 'Install Tailscale → <a href="https://tailscale.com/download" target="_blank" rel="noopener">tailscale.com/download</a>';
            return;
        }
        if (!data.loggedIn) {
            setTailscaleBadge('offline', 'NOT LOGGED IN');
            if (tailscaleUrlLine) tailscaleUrlLine.textContent = 'Run `tailscale up` on the master device.';
            return;
        }
        if (data.funnelEnabled && data.funnelUrl) {
            setTailscaleBadge('online', 'FUNNEL ENABLED');
            tailscaleUrlLine.innerHTML = `Public URL: <a href="${data.funnelUrl}" target="_blank" rel="noopener">${data.funnelUrl}</a>`;
        } else {
            setTailscaleBadge('offline', 'FUNNEL OFF');
            if (data.hintUrl && tailscaleUrlLine) {
                tailscaleUrlLine.innerHTML = `Tailnet URL (Tailscale-only): <a href="${data.hintUrl}" target="_blank" rel="noopener">${data.hintUrl}</a>`;
            } else if (tailscaleUrlLine) {
                tailscaleUrlLine.textContent = data.error || '';
            }
        }
    } catch (e) {
        setTailscaleBadge('error', 'SERVER UNREACHABLE');
    }
}
if (tailscaleRefreshBtn) tailscaleRefreshBtn.addEventListener('click', refreshTailscaleStatus);
async function toggleFunnel(enable) {
    if (!tailscaleStatusEl) return;
    tailscaleStatusEl.textContent = enable ? 'Enabling funnel…' : 'Disabling funnel…';
    tailscaleStatusEl.className = 'setting-feedback';
    try {
        const r = await fetch(api('/api/tailscale/funnel'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enable })
        });
        const data = await r.json();
        if (!r.ok || data.error) {
            tailscaleStatusEl.className = 'setting-feedback error';
            tailscaleStatusEl.textContent = data.error || `Failed (HTTP ${r.status})`;
        } else {
            tailscaleStatusEl.className = 'setting-feedback success';
            tailscaleStatusEl.textContent = enable
                ? (data.funnelUrl ? `Funnel up: ${data.funnelUrl}` : 'Funnel enabled.')
                : 'Funnel disabled.';
        }
        refreshTailscaleStatus();
    } catch (e) {
        tailscaleStatusEl.className = 'setting-feedback error';
        tailscaleStatusEl.textContent = 'Backend unreachable.';
    }
}
if (tailscaleEnableBtn) tailscaleEnableBtn.addEventListener('click', () => toggleFunnel(true));
if (tailscaleDisableBtn) tailscaleDisableBtn.addEventListener('click', () => toggleFunnel(false));
// Initial probe (cheap GET; safe if backend isn't reachable)
setTimeout(refreshTailscaleStatus, 1500);

// ── Vault UI (credentials + env vars) ───────────────────
const vaultCredName = document.getElementById('vaultCredName');
const vaultCredUrl = document.getElementById('vaultCredUrl');
const vaultCredUser = document.getElementById('vaultCredUser');
const vaultCredPw = document.getElementById('vaultCredPw');
const vaultCredSaveBtn = document.getElementById('vaultCredSaveBtn');
const vaultCredList = document.getElementById('vaultCredList');
const vaultEnvName = document.getElementById('vaultEnvName');
const vaultEnvValue = document.getElementById('vaultEnvValue');
const vaultEnvSaveBtn = document.getElementById('vaultEnvSaveBtn');
const vaultEnvList = document.getElementById('vaultEnvList');

async function refreshVaultCredentials() {
    if (!vaultCredList) return;
    try {
        const r = await fetch(api('/api/vault/credentials'));
        const list = await r.json();
        if (!list.length) {
            vaultCredList.innerHTML = '<div class="setting-hint">No saved logins yet.</div>';
            return;
        }
        vaultCredList.innerHTML = list.map(c => `
            <div class="vault-row">
                <div class="vault-row-main">
                    <span class="vault-row-name">${escapeHtml(c.name)}</span>
                    <span class="vault-row-meta">${escapeHtml(c.username || '')} ${c.url ? '· ' + escapeHtml(c.url) : ''}</span>
                </div>
                <div class="vault-row-actions">
                    <button class="timer-btn play-btn" data-action="vault-use-cred" data-name="${escapeHtml(c.name)}">USE</button>
                    <button class="timer-btn cancel-btn" data-action="vault-delete-cred" data-name="${escapeHtml(c.name)}">DELETE</button>
                </div>
            </div>`).join('');
        vaultCredList.querySelectorAll('[data-action="vault-use-cred"]').forEach(b => {
            b.addEventListener('click', () => useCredential(b.dataset.name));
        });
        vaultCredList.querySelectorAll('[data-action="vault-delete-cred"]').forEach(b => {
            b.addEventListener('click', () => deleteCredential(b.dataset.name));
        });
    } catch (e) {
        vaultCredList.innerHTML = '<div class="setting-hint error">Could not load credentials.</div>';
    }
}
async function refreshVaultEnvVars() {
    if (!vaultEnvList) return;
    try {
        const r = await fetch(api('/api/vault/env'));
        const list = await r.json();
        if (!list.length) {
            vaultEnvList.innerHTML = '<div class="setting-hint">No env vars saved yet.</div>';
            return;
        }
        vaultEnvList.innerHTML = list.map(e => `
            <div class="vault-row">
                <div class="vault-row-main">
                    <span class="vault-row-name">${escapeHtml(e.name)}</span>
                    <span class="vault-row-meta">${e.value ? '••••' + escapeHtml(e.value.slice(-4)) : '(empty)'}</span>
                </div>
                <div class="vault-row-actions">
                    <button class="timer-btn cancel-btn" data-action="vault-delete-env" data-name="${escapeHtml(e.name)}">DELETE</button>
                </div>
            </div>`).join('');
        vaultEnvList.querySelectorAll('[data-action="vault-delete-env"]').forEach(b => {
            b.addEventListener('click', () => deleteEnvVar(b.dataset.name));
        });
    } catch (e) {
        vaultEnvList.innerHTML = '<div class="setting-hint error">Could not load env vars.</div>';
    }
}
async function saveCredentialFromForm() {
    const name = (vaultCredName.value || '').trim();
    if (!name) return;
    const body = {
        name,
        url: (vaultCredUrl.value || '').trim(),
        username: (vaultCredUser.value || '').trim(),
        password: vaultCredPw.value, // empty string → keep existing
    };
    await fetch(api('/api/vault/credentials'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    vaultCredName.value = vaultCredUrl.value = vaultCredUser.value = vaultCredPw.value = '';
    refreshVaultCredentials();
}
async function deleteCredential(name) {
    if (!confirm(`Delete credential "${name}"?`)) return;
    await fetch(api('/api/vault/credentials/' + encodeURIComponent(name)), { method: 'DELETE' });
    refreshVaultCredentials();
}
async function useCredential(name) {
    // Server will broadcast an autofill_request message to all logged-in devices.
    await fetch(api('/api/vault/use'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, targetSessionId: tabSessionId })
    });
}
async function saveEnvVarFromForm() {
    const name = (vaultEnvName.value || '').trim();
    if (!name) return;
    await fetch(api('/api/vault/env'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, value: vaultEnvValue.value })
    });
    vaultEnvName.value = vaultEnvValue.value = '';
    refreshVaultEnvVars();
}
async function deleteEnvVar(name) {
    if (!confirm(`Delete env var "${name}"?`)) return;
    await fetch(api('/api/vault/env/' + encodeURIComponent(name)), { method: 'DELETE' });
    refreshVaultEnvVars();
}
if (vaultCredSaveBtn) vaultCredSaveBtn.addEventListener('click', saveCredentialFromForm);
if (vaultEnvSaveBtn) vaultEnvSaveBtn.addEventListener('click', saveEnvVarFromForm);
setTimeout(() => { refreshVaultCredentials(); refreshVaultEnvVars(); refreshConsentList(); }, 1500);

// ── Permissions panel renderer (Settings → PERMISSIONS) ──
function refreshConsentList() {
    const el = document.getElementById('permissionsList');
    if (!el) return;
    const grants = listConsentGrants();
    if (!grants.length) {
        el.innerHTML = '<div class="permissions-empty">No permissions granted yet — you\'ll see entries here as you accept incoming remote actions.</div>';
        return;
    }
    el.innerHTML = grants.map(g => `
        <div class="permission-row">
            <span class="permission-action">${escapeHtml(g.action)}</span>
            <span class="permission-from">from <strong>${escapeHtml(g.fromDevice)}</strong></span>
            <button class="timer-btn cancel-btn permission-revoke-btn"
                    data-action="${escapeHtml(g.action)}"
                    data-from="${escapeHtml(g.fromDevice)}">REVOKE</button>
        </div>`).join('');
    el.querySelectorAll('.permission-revoke-btn').forEach(b => {
        b.addEventListener('click', () => {
            revokeConsent(b.dataset.action, b.dataset.from);
        });
    });
}

// Auto-fill overlay shown when an autofill_request lands on this tab.
function showAutofillOverlay(payload) {
    // Remove any existing overlay first.
    document.querySelectorAll('.autofill-overlay').forEach(n => n.remove());
    const el = document.createElement('div');
    el.className = 'autofill-overlay';
    el.innerHTML = `
        <div class="autofill-card">
            <div class="autofill-title">SIGN IN: ${escapeHtml(payload.name || '')}</div>
            <div class="autofill-url">${escapeHtml(payload.url || '')}</div>
            <div class="autofill-row"><span>username</span>
                <code id="afu">${escapeHtml(payload.username || '')}</code>
                <button data-act="copy-u">COPY</button></div>
            <div class="autofill-row"><span>password</span>
                <code id="afp">${'•'.repeat(Math.min(16, (payload.password || '').length || 8))}</code>
                <button data-act="copy-p">COPY</button>
                <button data-act="reveal-p">REVEAL</button></div>
            <div class="autofill-actions">
                <button class="timer-btn play-btn" data-act="open">OPEN URL</button>
                <button class="timer-btn cancel-btn" data-act="close">CLOSE</button>
            </div>
            <div class="autofill-hint">
                Cross-origin scripting is blocked — paste manually after clicking OPEN URL.
                Some browsers' password managers will offer to fill the saved entry directly.
            </div>
        </div>`;
    document.body.appendChild(el);
    let revealed = false;
    el.querySelector('[data-act="copy-u"]').addEventListener('click', () => {
        navigator.clipboard.writeText(payload.username || '');
    });
    el.querySelector('[data-act="copy-p"]').addEventListener('click', () => {
        navigator.clipboard.writeText(payload.password || '');
    });
    el.querySelector('[data-act="reveal-p"]').addEventListener('click', () => {
        const code = el.querySelector('#afp');
        revealed = !revealed;
        code.textContent = revealed ? (payload.password || '') : '•'.repeat(Math.min(16, (payload.password || '').length || 8));
    });
    el.querySelector('[data-act="open"]').addEventListener('click', () => {
        if (payload.url) window.open(payload.url, '_blank', 'noopener');
    });
    el.querySelector('[data-act="close"]').addEventListener('click', () => el.remove());
    // Auto-dismiss after 60s as a safety so passwords don't linger on screen.
    setTimeout(() => el.remove(), 60000);
}

// ── Connected devices settings UI ───────────────────────
const deviceNameInput = document.getElementById('deviceNameInput');
const deviceNameSaveBtn = document.getElementById('deviceNameSaveBtn');
if (deviceNameInput) deviceNameInput.value = localStorage.getItem('sentient_deviceName') || deviceDisplayName();
if (deviceNameSaveBtn) {
    deviceNameSaveBtn.addEventListener('click', () => {
        const v = (deviceNameInput.value || '').trim();
        if (v) localStorage.setItem('sentient_deviceName', v);
        else localStorage.removeItem('sentient_deviceName');
        // Re-register so other devices see the new name.
        try { registerDevice(); } catch (e) {}
    });
}

// Copy Google Buttons to Settings
const setGoogleTasks = document.getElementById('settingsGoogleTasksBtn');
const setGoogleCal = document.getElementById('settingsGoogleCalBtn');
if (setGoogleTasks) {
    setGoogleTasks.addEventListener('click', () => {
        window.open(api('/api/tasks/google/auth'), '_blank', 'width=500,height=700');
    });
}
if (setGoogleCal) {
    setGoogleCal.addEventListener('click', () => {
        // Google Calendar shares the same OAuth file as Google Tasks; reuse that route.
        window.open(api('/api/tasks/google/auth'), '_blank', 'width=500,height=700');
    });
}

// Add Markdown support CSS directly inside the JS to ensure it applies over `.text`
if (!document.getElementById('markdownStyles')) {
    const style = document.createElement('style');
    style.id = 'markdownStyles';
    style.innerHTML = `
        .markdown-body p { margin-bottom: 0.5em; line-height: 1.6; }
        .markdown-body p:last-child { margin-bottom: 0; }
        .markdown-body pre { background: var(--bg-darkest) !important; padding: 12px !important; border-radius: 6px; overflow-x: auto; margin-top: 8px; border: 1px solid var(--border-dark); }
        .markdown-body code { background: var(--bg-input); padding: 2px 4px; border-radius: 3px; font-family: var(--font-mono); }
        .markdown-body pre code { background: transparent; padding: 0; border-radius: 0; }
        .markdown-body ul, .markdown-body ol { padding-left: 20px; margin-bottom: 0.5em; }
        .markdown-body blockquote { border-left: 3px solid var(--border-light); padding-left: 10px; color: var(--text-muted); }
        .markdown-body a { color: var(--accent-blue); text-decoration: none; }
        .markdown-body a:hover { text-decoration: underline; }
    `;
    document.head.appendChild(style);
}

loadSettings();

// ── Chat Engine selector (Groq vs OpenClaw) ────────────
const setEngine = document.getElementById('settingEngine');
let chatEngine = localStorage.getItem('sentient_engine') || 'groq';
if (setEngine) {
    setEngine.value = chatEngine;
    setEngine.addEventListener('change', (e) => {
        chatEngine = e.target.value;
        localStorage.setItem('sentient_engine', chatEngine);
    });
}

// ── OpenClaw configuration UI ───────────────────────────
const openclawProvider = document.getElementById('openclawProvider');
const openclawApiKey = document.getElementById('openclawApiKey');
const openclawModel = document.getElementById('openclawModel');
const openclawBaseUrlRow = document.getElementById('openclawBaseUrlRow');
const openclawApiTypeRow = document.getElementById('openclawApiTypeRow');
const openclawBaseUrl = document.getElementById('openclawBaseUrl');
const openclawApiType = document.getElementById('openclawApiType');
const openclawGatewayToken = document.getElementById('openclawGatewayToken');
const openclawSaveBtn = document.getElementById('openclawSaveBtn');
const openclawTestBtn = document.getElementById('openclawTestBtn');
const openclawSaveStatus = document.getElementById('openclawSaveStatus');
const openclawStatusBadge = document.getElementById('openclawStatusBadge');
const openclawRefreshBtn = document.getElementById('openclawRefreshBtn');

// Sensible default models per provider (user can still type anything).
const OPENCLAW_DEFAULT_MODELS = {
    anthropic: 'claude-sonnet-4-5',
    openai: 'gpt-4o',
    google: 'gemini-2.5-flash',
    groq: 'llama-3.3-70b-versatile',
    openrouter: 'openrouter/auto',
    xai: 'grok-2',
    deepseek: 'deepseek-chat',
    moonshot: 'kimi-k2',
    custom: '',
};

function syncOpenClawProviderUI() {
    if (!openclawProvider) return;
    const isCustom = openclawProvider.value === 'custom';
    openclawBaseUrlRow.style.display = isCustom ? '' : 'none';
    openclawApiTypeRow.style.display = isCustom ? '' : 'none';
    // Suggest a model if the field is empty
    if (openclawModel && !openclawModel.value) {
        openclawModel.placeholder = OPENCLAW_DEFAULT_MODELS[openclawProvider.value] || '';
    }
}

function loadOpenClawConfig() {
    if (!openclawProvider) return;
    const cfg = JSON.parse(localStorage.getItem('sentient_openclaw') || '{}');
    if (cfg.provider) openclawProvider.value = cfg.provider;
    if (cfg.apiKey) openclawApiKey.value = cfg.apiKey;
    if (cfg.model) openclawModel.value = cfg.model;
    if (cfg.baseUrl) openclawBaseUrl.value = cfg.baseUrl;
    if (cfg.apiType) openclawApiType.value = cfg.apiType;
    if (cfg.gatewayToken) openclawGatewayToken.value = cfg.gatewayToken;
    syncOpenClawProviderUI();
}
if (openclawProvider) openclawProvider.addEventListener('change', syncOpenClawProviderUI);

function setOpenClawStatus(state, text) {
    if (!openclawStatusBadge) return;
    openclawStatusBadge.className = `setting-status status-${state}`;
    openclawStatusBadge.textContent = text;
}

async function refreshOpenClawStatus() {
    setOpenClawStatus('checking', 'CHECKING…');
    try {
        const r = await fetch(api('/api/openclaw/status'));
        if (!r.ok) { setOpenClawStatus('error', 'NOT INSTALLED'); return; }
        const data = await r.json();
        // Update remote-toggle UI from server truth (so two tabs see the same state).
        if (typeof openclawUseRemote !== 'undefined' && openclawUseRemote) {
            openclawUseRemote.checked = !!data.useRemote;
            if (data.remoteBaseUrl && openclawRemoteUrl && !openclawRemoteUrl.value) {
                openclawRemoteUrl.value = data.remoteBaseUrl;
            }
            syncOpenClawRemoteUI();
        }
        if (data.gatewayUp) {
            setOpenClawStatus('online', data.useRemote ? `ONLINE · REMOTE (${data.activeBaseUrl})` : 'ONLINE');
        } else if (data.useRemote) {
            setOpenClawStatus('error', `REMOTE UNREACHABLE (${data.activeBaseUrl || ''})`);
        } else if (data.installed) {
            setOpenClawStatus('offline', 'INSTALLED · OFFLINE');
        } else {
            setOpenClawStatus('error', 'NOT INSTALLED');
        }
    } catch (e) {
        setOpenClawStatus('error', 'SERVER UNREACHABLE');
    }
}
if (openclawRefreshBtn) openclawRefreshBtn.addEventListener('click', refreshOpenClawStatus);

// ── Remote-gateway toggle (local vs VPS/other-device OpenClaw) ──────────
const openclawUseRemote = document.getElementById('openclawUseRemote');
const openclawRemoteUrl = document.getElementById('openclawRemoteUrl');
const openclawRemoteToken = document.getElementById('openclawRemoteToken');
const openclawConnectionSaveBtn = document.getElementById('openclawConnectionSaveBtn');
const openclawRemoteOnlyEls = document.querySelectorAll('.openclaw-remote-only');

function syncOpenClawRemoteUI() {
    if (!openclawUseRemote) return;
    const on = openclawUseRemote.checked;
    openclawRemoteOnlyEls.forEach(el => { el.style.display = on ? '' : 'none'; });
}
if (openclawUseRemote) openclawUseRemote.addEventListener('change', syncOpenClawRemoteUI);
if (openclawConnectionSaveBtn) {
    openclawConnectionSaveBtn.addEventListener('click', async () => {
        const payload = {
            useRemote: !!(openclawUseRemote && openclawUseRemote.checked),
            remoteBaseUrl: (openclawRemoteUrl && openclawRemoteUrl.value || '').trim(),
            remoteAuthToken: (openclawRemoteToken && openclawRemoteToken.value || '').trim(),
        };
        openclawSaveStatus.textContent = 'Saving connection…';
        openclawSaveStatus.className = 'setting-feedback';
        try {
            const r = await fetch(api('/api/openclaw/connection'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            const data = await r.json();
            if (!r.ok || data.error) {
                openclawSaveStatus.className = 'setting-feedback error';
                openclawSaveStatus.textContent = data.error || `Save failed (HTTP ${r.status})`;
            } else {
                openclawSaveStatus.className = 'setting-feedback success';
                openclawSaveStatus.textContent = data.gatewayUp
                    ? `Connected to ${data.activeBaseUrl}.`
                    : `Saved. Gateway not reachable at ${data.activeBaseUrl}.`;
                refreshOpenClawStatus();
            }
        } catch (e) {
            openclawSaveStatus.className = 'setting-feedback error';
            openclawSaveStatus.textContent = 'Backend unreachable.';
        }
    });
}

if (openclawSaveBtn) {
    openclawSaveBtn.addEventListener('click', async () => {
        const cfg = {
            provider: openclawProvider.value,
            apiKey: openclawApiKey.value.trim(),
            model: openclawModel.value.trim() || OPENCLAW_DEFAULT_MODELS[openclawProvider.value] || '',
            baseUrl: openclawBaseUrl.value.trim(),
            apiType: openclawApiType.value,
            gatewayToken: openclawGatewayToken.value.trim(),
        };
        localStorage.setItem('sentient_openclaw', JSON.stringify(cfg));
        openclawSaveStatus.textContent = 'Saving…';
        openclawSaveStatus.className = 'setting-feedback';
        try {
            const r = await fetch(api('/api/openclaw/provider'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(cfg),
            });
            const data = await r.json();
            if (!r.ok || data.error) {
                openclawSaveStatus.className = 'setting-feedback error';
                openclawSaveStatus.textContent = data.error || `Save failed (HTTP ${r.status})`;
            } else {
                openclawSaveStatus.className = 'setting-feedback success';
                openclawSaveStatus.textContent = data.message || 'Saved. Gateway restarting.';
                refreshOpenClawStatus();
            }
        } catch (e) {
            openclawSaveStatus.className = 'setting-feedback error';
            openclawSaveStatus.textContent = 'Backend unreachable. Settings stored locally only.';
        }
    });
}

if (openclawTestBtn) {
    openclawTestBtn.addEventListener('click', async () => {
        openclawSaveStatus.textContent = 'Testing…';
        openclawSaveStatus.className = 'setting-feedback';
        try {
            const r = await fetch(api('/api/openclaw/test'), { method: 'POST' });
            const data = await r.json();
            if (!r.ok || data.error) {
                openclawSaveStatus.className = 'setting-feedback error';
                openclawSaveStatus.textContent = data.error || `Test failed (HTTP ${r.status})`;
            } else {
                openclawSaveStatus.className = 'setting-feedback success';
                openclawSaveStatus.textContent = data.reply ? `OK: "${data.reply.slice(0, 80)}"` : 'OK — gateway responded.';
            }
        } catch (e) {
            openclawSaveStatus.className = 'setting-feedback error';
            openclawSaveStatus.textContent = 'Backend unreachable.';
        }
    });
}

// ── Composio toolkit picker ─────────────────────────────
const composioKey = document.getElementById('composioKey');
const composioGrid = document.getElementById('composioGrid');
const composioAddInput = document.getElementById('composioAddInput');
const composioAddBtn = document.getElementById('composioAddBtn');
const composioSaveBtn = document.getElementById('composioSaveBtn');
const composioSaveStatus = document.getElementById('composioSaveStatus');

// Curated list — Composio doesn't document a public "list all" endpoint.
// Each entry mirrors a slug from composio.dev/toolkits. Add more via the input.
const COMPOSIO_DEFAULT_TOOLKITS = [
    { slug: 'github',           label: 'GitHub',           icon: '⌥' },
    { slug: 'gmail',            label: 'Gmail',            icon: '✉' },
    { slug: 'google_drive',     label: 'Google Drive',     icon: '📁' },
    { slug: 'google_calendar',  label: 'Google Calendar',  icon: '📅' },
    { slug: 'google_docs',      label: 'Google Docs',      icon: '📝' },
    { slug: 'google_sheets',    label: 'Google Sheets',    icon: '▦' },
    { slug: 'slack',            label: 'Slack',            icon: '#' },
    { slug: 'notion',           label: 'Notion',           icon: '◐' },
    { slug: 'linear',           label: 'Linear',           icon: '∠' },
    { slug: 'jira',             label: 'Jira',             icon: '✦' },
    { slug: 'discord',          label: 'Discord',          icon: '◈' },
    { slug: 'trello',           label: 'Trello',           icon: '☷' },
    { slug: 'asana',            label: 'Asana',            icon: '⬢' },
    { slug: 'hubspot',          label: 'HubSpot',          icon: '◉' },
    { slug: 'zoom',             label: 'Zoom',             icon: '📹' },
    { slug: 'twitter',          label: 'X / Twitter',      icon: '𝕏' },
    { slug: 'reddit',           label: 'Reddit',           icon: 'ⓡ' },
    { slug: 'browser_tool',     label: 'Browser',          icon: '⌬' },
];

function loadComposioConfig() {
    const cfg = JSON.parse(localStorage.getItem('sentient_composio') || '{}');
    if (composioKey && cfg.consumerKey) composioKey.value = cfg.consumerKey;
    const enabled = new Set(cfg.enabled || []);
    const extras = cfg.extras || [];
    renderComposioGrid(enabled, extras);
}

function renderComposioGrid(enabledSet, extras) {
    if (!composioGrid) return;
    composioGrid.innerHTML = '';
    const all = [...COMPOSIO_DEFAULT_TOOLKITS, ...extras.map(s => ({ slug: s, label: s, icon: '⚙' }))];
    const seen = new Set();
    for (const tk of all) {
        if (seen.has(tk.slug)) continue;
        seen.add(tk.slug);
        const card = document.createElement('label');
        card.className = 'composio-card';
        const isOn = enabledSet.has(tk.slug);
        if (isOn) card.classList.add('on');
        card.innerHTML = `
            <input type="checkbox" data-slug="${tk.slug}" ${isOn ? 'checked' : ''}>
            <span class="composio-icon">${tk.icon}</span>
            <span class="composio-label">${tk.label}</span>
        `;
        card.querySelector('input').addEventListener('change', (e) => {
            card.classList.toggle('on', e.target.checked);
        });
        composioGrid.appendChild(card);
    }
}

if (composioAddBtn) {
    composioAddBtn.addEventListener('click', () => {
        const slug = (composioAddInput.value || '').trim().toLowerCase().replace(/\s+/g, '_');
        if (!slug) return;
        const cfg = JSON.parse(localStorage.getItem('sentient_composio') || '{}');
        cfg.extras = cfg.extras || [];
        if (!cfg.extras.includes(slug) && !COMPOSIO_DEFAULT_TOOLKITS.some(t => t.slug === slug)) {
            cfg.extras.push(slug);
        }
        // mark the new one as enabled so the user sees it on
        cfg.enabled = Array.from(new Set([...(cfg.enabled || []), slug]));
        localStorage.setItem('sentient_composio', JSON.stringify(cfg));
        composioAddInput.value = '';
        loadComposioConfig();
    });
}

if (composioSaveBtn) {
    composioSaveBtn.addEventListener('click', async () => {
        const enabled = Array.from(composioGrid.querySelectorAll('input[type=checkbox]'))
            .filter(cb => cb.checked).map(cb => cb.dataset.slug);
        const existing = JSON.parse(localStorage.getItem('sentient_composio') || '{}');
        const cfg = {
            consumerKey: composioKey.value.trim(),
            enabled,
            extras: existing.extras || [],
        };
        localStorage.setItem('sentient_composio', JSON.stringify(cfg));
        composioSaveStatus.textContent = 'Saving…';
        composioSaveStatus.className = 'setting-feedback';
        try {
            const r = await fetch(api('/api/openclaw/composio'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(cfg),
            });
            const data = await r.json();
            if (!r.ok || data.error) {
                composioSaveStatus.className = 'setting-feedback error';
                composioSaveStatus.textContent = data.error || `Save failed (HTTP ${r.status})`;
            } else {
                composioSaveStatus.className = 'setting-feedback success';
                composioSaveStatus.textContent = data.message || 'Saved. Restart OpenClaw to activate.';
            }
        } catch (e) {
            composioSaveStatus.className = 'setting-feedback error';
            composioSaveStatus.textContent = 'Backend unreachable. Settings stored locally only.';
        }
    });
}

// ── Master server config ────────────────────────────────
const masterServerUrl = document.getElementById('masterServerUrl');
const masterServerSaveBtn = document.getElementById('masterServerSaveBtn');
const masterServerClearBtn = document.getElementById('masterServerClearBtn');
const masterServerStatus = document.getElementById('masterServerStatus');

function loadMasterServerConfig() {
    const saved = localStorage.getItem('sentient_masterHost') || '';
    if (masterServerUrl) masterServerUrl.value = saved;
    if (masterServerStatus) {
        masterServerStatus.className = 'setting-feedback';
        masterServerStatus.textContent = saved
            ? `Currently pointing at: ${saved}`
            : `Currently pointing at this host (${location.host})`;
    }
}

if (masterServerSaveBtn) {
    masterServerSaveBtn.addEventListener('click', () => {
        let v = (masterServerUrl.value || '').trim();
        // accept "192.168.1.42:7070" or "http://192.168.1.42:7070" — strip protocol
        v = v.replace(/^https?:\/\//, '').replace(/\/+$/, '');
        if (v) {
            localStorage.setItem('sentient_masterHost', v);
        } else {
            localStorage.removeItem('sentient_masterHost');
        }
        masterServerStatus.className = 'setting-feedback success';
        masterServerStatus.textContent = 'Saved. Reloading…';
        setTimeout(() => location.reload(), 600);
    });
}

if (masterServerClearBtn) {
    masterServerClearBtn.addEventListener('click', () => {
        localStorage.removeItem('sentient_masterHost');
        if (masterServerUrl) masterServerUrl.value = '';
        masterServerStatus.className = 'setting-feedback success';
        masterServerStatus.textContent = 'Cleared. Reloading…';
        setTimeout(() => location.reload(), 600);
    });
}

loadOpenClawConfig();
loadComposioConfig();
loadMasterServerConfig();
refreshOpenClawStatus();

// ═══════════════════════════════════════════════════════
// VOICE tab — wake word + barge-in conversational mode
// ═══════════════════════════════════════════════════════
//
// State machine:
//   off          → orb dim. Mic untouched.
//   listening    → mic on, waiting for the wake word ("jarvis")
//   awake        → wake word detected, briefly highlighted before transcribing
//   transcribing → real speech-to-text running (server Vosk or browser SR)
//   speaking     → bot's TTS is playing. Mic still active for barge-in.
//   thinking     → user finished talking, waiting for AI reply
//
// The server-side Vosk Listener and a browser-side SpeechRecognition both watch for
// "jarvis" — whichever fires first wins. While the bot is speaking, an AnalyserNode
// running on the user's mic measures volume; a spike triggers a "stop" + start of a
// new transcription session (barge-in).

const voiceStage = document.getElementById('voiceStage');
const voiceStateLabel = document.getElementById('voiceStateLabel');
const voiceTranscript = document.getElementById('voiceTranscript');
const voiceReply = document.getElementById('voiceReply');
const voiceToggleBtn = document.getElementById('voiceToggleBtn');

const WAKE_PATTERNS = /\b(jarvis|jervis|jarvi|harvis|charvis)\b/i;

let voiceState = 'off';
let voiceSR = null;          // continuous browser SpeechRecognition for wake detection
let voiceVADStream = null;   // MediaStream backing the barge-in analyser
let voiceVADCtx = null;      // AudioContext
let voiceVADRaf = 0;         // requestAnimationFrame handle
let voiceVADHotStart = 0;    // ms timestamp when sustained loud-mic crossing began
let voiceVADBaseline = 0.012;// adaptive noise floor (RMS, 0..1)
let voiceSawSpeakingThisTurn = false; // true once we've seen chat_word in this turn

function setVoiceState(state) {
    voiceState = state;
    if (!voiceStage) return;
    voiceStage.setAttribute('data-state', state);
    const labels = {
        off:          'DEACTIVATED',
        listening:    'LISTENING FOR "JARVIS"',
        awake:        'I HEARD YOU',
        transcribing: 'TRANSCRIBING…',
        thinking:     'THINKING…',
        speaking:     'SPEAKING',
    };
    if (voiceStateLabel) voiceStateLabel.textContent = labels[state] || state.toUpperCase();
}

function showVoiceTranscript(text) {
    if (voiceTranscript) voiceTranscript.textContent = text || 'Say "jarvis" to wake';
}

function appendVoiceReplyWord(word) {
    if (!voiceReply) return;
    if (!voiceSawSpeakingThisTurn) {
        voiceReply.textContent = '';
        voiceSawSpeakingThisTurn = true;
    }
    voiceReply.textContent += (voiceReply.textContent ? ' ' : '') + word;
    voiceReply.scrollTop = voiceReply.scrollHeight;
}

// ── Browser-side wake-word detection ──────────────────
// Continuous SR scans transcripts for the wake pattern. When it fires it starts a
// transcription session: stops the continuous listener, opens a fresh recognition
// that captures the user's actual command, then resumes on completion.
function startBrowserWakeListener() {
    if (voiceSR) return;
    if (!SpeechRecognition) return; // not supported — server Listener still runs
    const sr = new SpeechRecognition();
    sr.continuous = true;
    sr.interimResults = true;
    sr.lang = 'en-US';

    sr.onresult = (event) => {
        const last = event.results[event.results.length - 1];
        const text = (last && last[0] && last[0].transcript) || '';
        if (!text) return;
        // While transcribing or speaking, ignore wake-word detection in this background SR
        if (voiceState === 'transcribing') return;
        if (WAKE_PATTERNS.test(text)) {
            console.log('[Voice] Browser wake-word detected:', text);
            onWakeDetected();
        }
    };
    sr.onerror = (e) => console.warn('[Voice] wake SR error', e.error);
    sr.onend = () => {
        // Auto-restart while wake mode is on (some browsers stop SR after silence)
        if (voiceWakeMode && voiceState !== 'transcribing') {
            try { sr.start(); } catch (e) {}
        }
    };
    try { sr.start(); } catch (e) { console.warn('[Voice] wake SR start failed', e); }
    voiceSR = sr;
}

function stopBrowserWakeListener() {
    if (!voiceSR) return;
    try { voiceSR.onend = null; voiceSR.stop(); } catch (e) {}
    voiceSR = null;
}

function onWakeDetected() {
    // Visual cue, then start a real transcription session
    setVoiceState('awake');
    setTimeout(() => {
        if (!voiceWakeMode) return;
        beginTranscriptionTurn();
    }, 250);
}

function beginTranscriptionTurn() {
    if (currentAudio) { try { currentAudio.pause(); } catch (e) {} }
    sendWS({ type: 'stop' });           // cancel any in-flight bot reply
    setVoiceState('transcribing');
    showVoiceTranscript('');

    // The server-side Vosk listener may have triggered transcription already.
    // We also start a fresh browser SR so remote browsers can capture speech.
    if (recognition) {
        // Stop the background wake SR briefly to free the mic
        stopBrowserWakeListener();
        claimChosenMic().finally(() => {
            try { recognition.start(); } catch (e) {
                console.warn('[Voice] recognition.start failed', e);
                // restart wake listener if recognition couldn't start
                if (voiceWakeMode) startBrowserWakeListener();
            }
        });
    } else {
        sendWS({ type: 'record' });
    }
}

// ── Barge-in (VAD volume gate during TTS) ─────────────
// Browsers do AEC by default when getUserMedia is open; this monitors mic RMS and
// triggers a transcription turn if the user clearly starts talking over the bot.
async function startBargeInVAD() {
    if (!voiceWakeMode) return;
    if (voiceVADStream) return;
    try {
        const constraints = { audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
            deviceId: micDeviceId && micDeviceId !== 'default' ? { exact: micDeviceId } : undefined,
        }};
        voiceVADStream = await navigator.mediaDevices.getUserMedia(constraints);
        voiceVADCtx = new (window.AudioContext || window.webkitAudioContext)();
        const source = voiceVADCtx.createMediaStreamSource(voiceVADStream);
        const analyser = voiceVADCtx.createAnalyser();
        analyser.fftSize = 512;
        source.connect(analyser);
        const data = new Uint8Array(analyser.fftSize);
        voiceVADHotStart = 0;

        const tick = () => {
            if (!voiceVADStream) return;
            analyser.getByteTimeDomainData(data);
            // RMS in 0..1 — center byte is 128
            let sum = 0;
            for (let i = 0; i < data.length; i++) {
                const v = (data[i] - 128) / 128;
                sum += v * v;
            }
            const rms = Math.sqrt(sum / data.length);
            // Track a gentle baseline (only update when not in a hot streak)
            if (voiceVADHotStart === 0 && rms < voiceVADBaseline * 4) {
                voiceVADBaseline = voiceVADBaseline * 0.99 + rms * 0.01;
            }
            const threshold = Math.max(0.05, voiceVADBaseline * 6);
            if (voiceState === 'speaking') {
                if (rms > threshold) {
                    if (!voiceVADHotStart) voiceVADHotStart = performance.now();
                    if (performance.now() - voiceVADHotStart > 220) {
                        console.log('[Voice] Barge-in: user spoke through bot (rms=' + rms.toFixed(3) + ')');
                        voiceVADHotStart = 0;
                        beginTranscriptionTurn();
                    }
                } else {
                    voiceVADHotStart = 0;
                }
            } else {
                voiceVADHotStart = 0;
            }
            voiceVADRaf = requestAnimationFrame(tick);
        };
        tick();
    } catch (e) {
        console.warn('[Voice] Could not start VAD', e);
    }
}

function stopBargeInVAD() {
    if (voiceVADRaf) cancelAnimationFrame(voiceVADRaf);
    voiceVADRaf = 0;
    if (voiceVADStream) {
        voiceVADStream.getTracks().forEach(t => t.stop());
        voiceVADStream = null;
    }
    if (voiceVADCtx) {
        try { voiceVADCtx.close(); } catch (e) {}
        voiceVADCtx = null;
    }
}

// ── Wake-mode toggle (button on the VOICE panel) ──────
async function activateVoiceWakeMode() {
    if (voiceWakeMode) return;
    voiceWakeMode = true;
    voiceToggleBtn.classList.add('active');
    voiceToggleBtn.textContent = '◼ DEACTIVATE WAKE MODE';
    setVoiceState('listening');
    showVoiceTranscript('Say "jarvis" to wake');
    sendWS({ type: 'set_listener', paused: false });
    startBrowserWakeListener();
    startBargeInVAD();
}

function deactivateVoiceWakeMode() {
    if (!voiceWakeMode) return;
    voiceWakeMode = false;
    voiceToggleBtn.classList.remove('active');
    voiceToggleBtn.textContent = '▶ ACTIVATE WAKE MODE';
    setVoiceState('off');
    showVoiceTranscript('Wake mode off');

    // Hard-stop everything in-flight: cancel the bot's in-flight reply, kill TTS
    // audio that's already playing, abort any active speech recognition, and pause
    // the server-side wake-word loop.
    sendWS({ type: 'stop' });
    if (currentAudio) { try { currentAudio.pause(); currentAudio.currentTime = 0; } catch (e) {} }
    if (recognition) { try { recognition.stop(); } catch (e) {} }
    sendWS({ type: 'set_listener', paused: true });
    stopBrowserWakeListener();
    stopBargeInVAD();
    voiceSawSpeakingThisTurn = false;
}

if (voiceToggleBtn) {
    voiceToggleBtn.addEventListener('click', () => {
        if (voiceWakeMode) deactivateVoiceWakeMode();
        else activateVoiceWakeMode();
    });
}

// Auto-deactivate when the VOICE panel is closed. We monkey-patch closePanel so we
// don't have to weave panel-lifecycle hooks throughout the codebase.
const _origClosePanel = closePanel;
closePanel = function (name) {
    if (name === 'voice' && voiceWakeMode) deactivateVoiceWakeMode();
    return _origClosePanel(name);
};

// ── Hook into existing WS message stream to drive orb state ──
//
// We don't replace handleWSMessage — we wrap the relevant downstream functions.
// chat_word → speaking
// chat_done → back to listening (if wake mode still on)
// voice_partial / voice_final → transcribing then thinking
// voice_state (server) → enter transcribing/listening accordingly
(function instrumentVoiceStates() {
    const origAppendStreamWord = window.appendStreamWord || appendStreamWord;
    window.appendStreamWord = function (word) {
        if (voiceWakeMode) {
            setVoiceState('speaking');
            appendVoiceReplyWord(word);
        }
        return origAppendStreamWord.apply(this, arguments);
    };
    const origFinishStream = window.finishStream || finishStream;
    window.finishStream = function () {
        if (voiceWakeMode) {
            voiceSawSpeakingThisTurn = false;
            setVoiceState('listening');
            showVoiceTranscript('Say "jarvis" to wake');
            // Resume the background wake listener after a turn ends
            if (!voiceSR) startBrowserWakeListener();
        }
        return origFinishStream.apply(this, arguments);
    };

    const origUpdate = window.updateVoiceInput || updateVoiceInput;
    window.updateVoiceInput = function (text) {
        if (voiceWakeMode && voiceState !== 'speaking') {
            setVoiceState('transcribing');
            showVoiceTranscript(text);
        }
        return origUpdate.apply(this, arguments);
    };
    const origClear = window.clearVoiceInput || clearVoiceInput;
    window.clearVoiceInput = function () {
        if (voiceWakeMode) {
            setVoiceState('thinking');
            showVoiceTranscript('…');
        }
        return origClear.apply(this, arguments);
    };
})();

setVoiceState('off');
showVoiceTranscript('Wake mode off');


