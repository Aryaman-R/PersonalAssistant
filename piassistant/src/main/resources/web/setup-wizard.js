/* ═══════════════════════════════════════════════════════════════════
   Sentient Assistant — Setup Wizard
   Loaded after app.js. Handles first-run onboarding entirely in the UI.
   ═══════════════════════════════════════════════════════════════════ */

(function () {
    'use strict';

    const STATE_KEY = 'sentient_wizardState';
    const DONE_KEY = 'sentient_wizardDone';

    // ── State ─────────────────────────────────────────────
    const state = loadState();
    function loadState() {
        try {
            const s = JSON.parse(localStorage.getItem(STATE_KEY) || '{}');
            return Object.assign({
                stepIndex: 0,
                hostMode: '',     // 'solo' | 'lan' | 'tailnet' | 'funnel'
                engine: '',       // 'groq' | 'openclaw'
                os: '',           // 'macos' | 'linux' | 'windows' | 'other'
                deviceName: '',
                summary: {},      // map of step-id → 'ok'|'skip'|'pending'
            }, s);
        } catch (e) { return { stepIndex: 0, summary: {} }; }
    }
    function saveState() {
        localStorage.setItem(STATE_KEY, JSON.stringify(state));
    }

    // ── DOM refs ──────────────────────────────────────────
    const root      = document.getElementById('setupWizard');
    const body      = document.getElementById('setupBody');
    const progress  = document.getElementById('setupProgressFill');
    const stepLabel = document.getElementById('setupStepLabel');
    const backBtn   = document.getElementById('setupBackBtn');
    const nextBtn   = document.getElementById('setupNextBtn');
    const skipBtn   = document.getElementById('setupSkipAllBtn');

    if (!root) return; // wizard markup missing — bail

    // ── Step definitions ──────────────────────────────────
    const STEPS = [
        { id: 'welcome',      label: 'WELCOME',          visible: () => true,                              render: stepWelcome },
        { id: 'host-mode',    label: 'HOSTING METHOD',   visible: () => true,                              render: stepHostMode },
        { id: 'prereqs',      label: 'HEALTH CHECK',     visible: () => true,                              render: stepPrereqs },
        { id: 'engine',       label: 'CHAT ENGINE',      visible: () => true,                              render: stepEngine },
        { id: 'openclaw',     label: 'OPENCLAW INSTALL', visible: () => state.engine === 'openclaw',       render: stepOpenClaw },
        { id: 'provider',     label: 'AI PROVIDER',      visible: () => state.engine === 'openclaw',       render: stepProvider },
        { id: 'groq-key',     label: 'GROQ KEY',         visible: () => state.engine === 'groq',           render: stepGroqKey },
        { id: 'password',     label: 'DEVICE PASSWORD',  visible: () => state.hostMode !== 'solo',         render: stepPassword },
        { id: 'env',          label: 'OPTIONAL KEYS',    visible: () => true,                              render: stepEnv },
        { id: 'integrations', label: 'INTEGRATIONS',     visible: () => true,                              render: stepIntegrations },
        { id: 'remote',       label: 'REMOTE ACCESS',    visible: () => state.hostMode === 'tailnet' || state.hostMode === 'funnel', render: stepRemote },
        { id: 'voice',        label: 'VOICE',            visible: () => true,                              render: stepVoice },
        { id: 'helper',       label: 'NATIVE HELPER',    visible: () => state.os === 'macos' || state.os === 'windows', render: stepHelper },
        { id: 'device-name',  label: 'NAME THIS DEVICE', visible: () => true,                              render: stepDeviceName },
        { id: 'done',         label: 'DONE',             visible: () => true,                              render: stepDone },
    ];

    function visibleSteps() { return STEPS.filter(s => s.visible()); }
    function currentStep() {
        const vis = visibleSteps();
        return vis[Math.min(state.stepIndex, vis.length - 1)];
    }

    // ── Lifecycle ─────────────────────────────────────────
    async function boot() {
        let serverState;
        try {
            const r = await fetch(api('/api/setup/state'));
            serverState = await r.json();
        } catch (e) { serverState = { firstRun: false }; }

        state.os = serverState.os || state.os || 'other';
        saveState();

        const userDismissed = localStorage.getItem(DONE_KEY) === 'true';
        if (serverState.firstRun && !userDismissed) {
            open();
        }
    }

    function open() {
        root.style.display = 'flex';
        // Clamp stepIndex if previously-visible steps disappeared.
        state.stepIndex = Math.min(state.stepIndex, visibleSteps().length - 1);
        render();
    }
    function close() {
        root.style.display = 'none';
    }

    backBtn.addEventListener('click', () => {
        if (state.stepIndex > 0) {
            state.stepIndex--;
            saveState();
            render();
        }
    });
    nextBtn.addEventListener('click', () => {
        const vis = visibleSteps();
        if (state.stepIndex < vis.length - 1) {
            state.stepIndex++;
            saveState();
            render();
        } else {
            // last step → close
            finish(true);
        }
    });
    skipBtn.addEventListener('click', () => {
        if (!confirm('Close the setup wizard? You can re-open it from Settings → SETUP WIZARD.')) return;
        finish(false);
    });

    async function finish(completed) {
        if (completed) {
            try { await fetch(api('/api/setup/finish'), { method: 'POST' }); } catch (e) {}
        }
        localStorage.setItem(DONE_KEY, 'true');
        close();
    }

    // ── Render loop ───────────────────────────────────────
    async function render() {
        const vis = visibleSteps();
        const step = vis[state.stepIndex];
        if (!step) return;
        stepLabel.textContent = `STEP ${state.stepIndex + 1} OF ${vis.length} · ${step.label}`;
        progress.style.width = (((state.stepIndex + 1) / vis.length) * 100) + '%';
        backBtn.disabled = state.stepIndex === 0;
        nextBtn.textContent = (state.stepIndex === vis.length - 1) ? 'CLOSE ✓' : 'NEXT ▸';
        body.innerHTML = '<div class="setup-lede">Loading…</div>';
        try {
            await step.render(body);
        } catch (e) {
            body.innerHTML = `<div class="setup-h1">Step error</div>
                <div class="setup-lede">${escapeHtml(e.message || String(e))}</div>`;
        }
    }

    // ── Step renderers ────────────────────────────────────

    function stepWelcome(el) {
        el.innerHTML = `
            <div class="setup-h2">WELCOME</div>
            <div class="setup-h1">Let's get Sentient running.</div>
            <div class="setup-lede">
                This wizard will set up everything in about 5 minutes — picking how you want to host
                the assistant, installing the AI engine, setting a password, connecting integrations
                (Spotify, Google, Composio), and optionally enabling voice and remote access.
                <br><br>
                Every step is optional. You can skip anything and come back to it later from
                <strong>Settings → SETUP WIZARD</strong>.
            </div>
        `;
    }

    function stepHostMode(el) {
        const opts = [
            { id: 'solo',    icon: '◆', title: 'Just my computer',               desc: 'The master server and the browser both run on this device. Easiest. Skip remote-access steps.', best: 'Best for: trying it out, single-user laptop.' },
            { id: 'lan',     icon: '◰', title: 'One device on my home network',  desc: 'Always-on server (Pi/Mac mini/desktop). Any device on the same Wi-Fi opens http://<your-ip>:7070.', best: 'Best for: households, multi-room setups.' },
            { id: 'tailnet', icon: '◯', title: 'My private network (Tailscale)', desc: 'Reachable from anywhere via your Tailscale tailnet. Stays private — only your devices.', best: 'Best for: travelers, multi-location use.' },
            { id: 'funnel',  icon: '◑', title: 'Public URL (Tailscale Funnel)',  desc: 'Permanent HTTPS URL anyone with the password can reach. Requires shared-password login.', best: 'Best for: sharing access from off-network with no VPN.' },
        ];
        el.innerHTML = `
            <div class="setup-h2">STEP 1 — HOSTING METHOD</div>
            <div class="setup-h1">How do you want to host this?</div>
            <div class="setup-lede">Pick the option that matches your situation. You can change this later.</div>
            <div class="setup-cards">${opts.map(o => `
                <button class="setup-card-option ${state.hostMode === o.id ? 'selected' : ''}" data-host="${o.id}">
                    <span class="opt-icon">${o.icon}</span>
                    <span class="opt-title">${o.title}</span>
                    <span class="opt-desc">${o.desc}</span>
                    <span class="opt-best">${o.best}</span>
                </button>`).join('')}
            </div>
        `;
        el.querySelectorAll('.setup-card-option').forEach(card => {
            card.addEventListener('click', () => {
                state.hostMode = card.dataset.host;
                state.summary['host-mode'] = 'ok';
                // Auto-advance — feels more direct for first-time users than
                // forcing them to hunt for NEXT after picking.
                if (state.stepIndex < visibleSteps().length - 1) state.stepIndex++;
                saveState();
                render();
            });
        });
    }

    async function stepPrereqs(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP 2 — HEALTH CHECK</div>
            <div class="setup-h1">Checking your machine…</div>
            <div class="setup-lede">We verify Java is installed and detect anything else already in place. Nothing is changed yet.</div>
            <div class="setup-rows" id="prereqRows"><div class="setup-lede">probing…</div></div>
        `;
        const rows = el.querySelector('#prereqRows');
        try {
            const r = await fetch(api('/api/setup/prereqs'));
            const data = await r.json();
            const items = [
                { key: 'java', label: 'Java 17+', need: true },
                { key: 'maven', label: 'Maven (rebuild only)', need: false },
                { key: 'openclaw', label: 'OpenClaw binary', need: false },
                { key: 'vosk', label: 'Vosk wake-word model', need: false },
                { key: 'tailscale', label: 'Tailscale', need: state.hostMode === 'tailnet' || state.hostMode === 'funnel' },
                { key: 'helper', label: 'Native helper (this OS)', need: false },
            ];
            rows.innerHTML = items.map(it => {
                const v = data[it.key] || {};
                let status = 'busy', text = 'CHECKING';
                if (v.installed && v.ok)                       { status = 'ok'; text = v.version ? ('FOUND · ' + truncate(v.version, 28)) : 'FOUND'; }
                else if (v.installed && !v.ok)                  { status = 'warn'; text = 'PRESENT BUT OUTDATED'; }
                else if (it.need)                               { status = 'err'; text = 'MISSING'; }
                else                                            { status = 'warn'; text = 'NOT INSTALLED'; }
                return `
                    <div class="setup-row">
                        <div class="setup-row-label">${escapeHtml(it.label)}
                            ${v.note ? `<small>${escapeHtml(v.note)}</small>` : ''}
                            ${v.path ? `<small>${escapeHtml(v.path)}</small>` : ''}
                        </div>
                        <span class="setup-row-status ${status}">${text}</span>
                    </div>`;
            }).join('');
        } catch (e) {
            rows.innerHTML = `<div class="setup-lede" style="color:var(--accent-red)">Could not reach the server. Is it running?</div>`;
        }
    }

    function stepEngine(el) {
        const opts = [
            { id: 'groq',     icon: '⚡', title: 'Groq (built-in)', desc: 'Fastest path. One API key from console.groq.com and you\'re done. Llama / Qwen models.', best: 'Best for: simplest setup.' },
            { id: 'openclaw', icon: '⌬', title: 'OpenClaw (multi-provider)', desc: 'Local agent harness. Pick any provider — Anthropic, OpenAI, Google, Groq, OpenRouter, xAI, DeepSeek… Adds MCP tools (GitHub, Gmail, etc.) via Composio.', best: 'Best for: power users, switching providers, tool use.' },
        ];
        el.innerHTML = `
            <div class="setup-h2">STEP 3 — CHAT ENGINE</div>
            <div class="setup-h1">Pick how the assistant talks to an LLM.</div>
            <div class="setup-lede">You can change this any time from Settings → Chat Engine. Both work with the rest of the app identically.</div>
            <div class="setup-cards">${opts.map(o => `
                <button class="setup-card-option ${state.engine === o.id ? 'selected' : ''}" data-engine="${o.id}">
                    <span class="opt-icon">${o.icon}</span>
                    <span class="opt-title">${o.title}</span>
                    <span class="opt-desc">${o.desc}</span>
                    <span class="opt-best">${o.best}</span>
                </button>`).join('')}
            </div>
        `;
        el.querySelectorAll('.setup-card-option').forEach(c => {
            c.addEventListener('click', () => {
                state.engine = c.dataset.engine;
                if (state.stepIndex < visibleSteps().length - 1) state.stepIndex++;
                saveState();
                render();
            });
        });
    }

    async function stepOpenClaw(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP 4 — OPENCLAW</div>
            <div class="setup-h1">Install the OpenClaw gateway.</div>
            <div class="setup-lede">
                OpenClaw runs on this machine and routes your chats to whichever provider you pick.
                Click <strong>INSTALL</strong> below — the server will run the install script and stream
                progress here. No terminal needed.
                <br><br>
                If you'd rather inspect first, the script is at
                <a href="https://openclaw.ai/install.sh" target="_blank" rel="noopener">openclaw.ai/install.sh</a>.
            </div>
            <div class="setup-row">
                <div class="setup-row-label">OpenClaw binary <small id="ocwHint">Checking…</small></div>
                <button class="setup-row-action primary" id="ocwInstallBtn">INSTALL OPENCLAW</button>
                <button class="setup-row-action" id="ocwRefreshBtn">↻ RE-CHECK</button>
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Gateway daemon <small>Starts after install. Click START if it isn't running.</small></div>
                <button class="setup-row-action" id="ocwStartBtn">START GATEWAY</button>
            </div>
            <div class="setup-log" id="ocwLog" aria-live="polite"></div>
        `;
        const hint = el.querySelector('#ocwHint');
        const installBtn = el.querySelector('#ocwInstallBtn');
        const refreshBtn = el.querySelector('#ocwRefreshBtn');
        const startBtn   = el.querySelector('#ocwStartBtn');
        const log        = el.querySelector('#ocwLog');

        async function refresh() {
            hint.textContent = 'checking…';
            try {
                const r = await fetch(api('/api/openclaw/status'));
                const d = await r.json();
                if (d.installed) {
                    hint.textContent = (d.binary || '/usr/local/bin/openclaw') + (d.gatewayUp ? ' · gateway ONLINE' : ' · gateway OFFLINE');
                    installBtn.disabled = true;
                    installBtn.textContent = '✓ INSTALLED';
                } else {
                    hint.textContent = 'Not installed.';
                    installBtn.disabled = false;
                    installBtn.textContent = 'INSTALL OPENCLAW';
                }
            } catch (e) { hint.textContent = 'Server unreachable.'; }
        }
        refreshBtn.addEventListener('click', refresh);
        refresh();

        installBtn.addEventListener('click', async () => {
            log.innerHTML = '';
            installBtn.disabled = true;
            try {
                await fetch(api('/api/setup/install/openclaw'), { method: 'POST' });
                appendLog(log, 'info', 'Install requested. Streaming progress…');
                listenSetupProgress(log, () => { refresh(); installBtn.disabled = false; });
            } catch (e) {
                appendLog(log, 'err', 'Install request failed: ' + e.message);
                installBtn.disabled = false;
            }
        });
        startBtn.addEventListener('click', async () => {
            log.innerHTML = '';
            try {
                await fetch(api('/api/setup/install/openclaw/start'), { method: 'POST' });
                appendLog(log, 'info', 'Starting gateway…');
                listenSetupProgress(log, () => refresh());
            } catch (e) { appendLog(log, 'err', e.message); }
        });
    }

    async function stepProvider(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP 5 — PROVIDER</div>
            <div class="setup-h1">Pick a provider and paste its API key.</div>
            <div class="setup-lede">
                The key never leaves this machine — it's saved to <code>~/.config/openclaw/openclaw.json5</code>.
                Don't have one yet? Each provider's "Get key" link opens their dashboard in a new tab.
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Provider</div>
                <select class="setup-row-input" id="provPick">
                    <option value="anthropic">Anthropic (Claude)</option>
                    <option value="openai">OpenAI (GPT)</option>
                    <option value="google">Google (Gemini)</option>
                    <option value="groq">Groq</option>
                    <option value="openrouter">OpenRouter</option>
                    <option value="xai">xAI (Grok)</option>
                    <option value="deepseek">DeepSeek</option>
                    <option value="moonshot">Moonshot</option>
                </select>
                <a class="setup-row-action" id="provLink" target="_blank" rel="noopener">GET KEY →</a>
            </div>
            <div class="setup-row">
                <div class="setup-row-label">API Key</div>
                <input class="setup-row-input" type="password" id="provKey" placeholder="sk-…" autocomplete="off">
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Model <small>Leave blank for default.</small></div>
                <input class="setup-row-input" type="text" id="provModel" placeholder="(default)">
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Save the config and restart the gateway.</div>
                <button class="setup-row-action primary" id="provSaveBtn">SAVE & START</button>
            </div>
            <div class="setup-log" id="provLog"></div>
        `;
        const KEY_LINKS = {
            anthropic:  'https://console.anthropic.com/settings/keys',
            openai:     'https://platform.openai.com/api-keys',
            google:     'https://aistudio.google.com/app/apikey',
            groq:       'https://console.groq.com/keys',
            openrouter: 'https://openrouter.ai/keys',
            xai:        'https://console.x.ai/',
            deepseek:   'https://platform.deepseek.com/api_keys',
            moonshot:   'https://platform.moonshot.cn/console/api-keys',
        };
        const MODEL_DEFAULTS = {
            anthropic: 'claude-sonnet-4-5',
            openai: 'gpt-4o',
            google: 'gemini-2.5-flash',
            groq: 'llama-3.3-70b-versatile',
            openrouter: 'openrouter/auto',
            xai: 'grok-2',
            deepseek: 'deepseek-chat',
            moonshot: 'kimi-k2',
        };
        const provPick = el.querySelector('#provPick');
        const provLink = el.querySelector('#provLink');
        const provKey  = el.querySelector('#provKey');
        const provModel= el.querySelector('#provModel');
        const log      = el.querySelector('#provLog');
        function syncLink() {
            provLink.href = KEY_LINKS[provPick.value] || '#';
            provModel.placeholder = MODEL_DEFAULTS[provPick.value] || '(default)';
        }
        provPick.addEventListener('change', syncLink); syncLink();

        el.querySelector('#provSaveBtn').addEventListener('click', async () => {
            log.innerHTML = '';
            const provider = provPick.value;
            const apiKey = provKey.value.trim();
            const model = provModel.value.trim() || MODEL_DEFAULTS[provider] || '';
            if (!apiKey) { appendLog(log, 'err', 'API key is required.'); return; }
            appendLog(log, 'info', `Saving ${provider} key + restarting gateway…`);
            try {
                const r = await fetch(api('/api/openclaw/provider'), {
                    method: 'POST', headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({ provider, apiKey, model, gatewayToken: '' }),
                });
                const d = await r.json();
                if (!r.ok || d.error) { appendLog(log, 'err', d.error || 'Save failed'); return; }
                appendLog(log, 'ok', d.message || 'Saved. Gateway restarting.');
                // Set engine in localStorage so the rest of the app uses OpenClaw.
                localStorage.setItem('sentient_engine', 'openclaw');
                state.summary.engine = 'ok';
                saveState();
            } catch (e) { appendLog(log, 'err', e.message); }
        });
    }

    function stepGroqKey(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP 4 — GROQ KEY</div>
            <div class="setup-h1">Paste your Groq API key.</div>
            <div class="setup-lede">
                Get a free key from <a href="https://console.groq.com/keys" target="_blank" rel="noopener">console.groq.com/keys</a>.
                It's saved into <code>.env</code> on the server. Skip with <strong>NEXT</strong> if you'll add it later.
            </div>
            <div class="setup-row">
                <div class="setup-row-label">GROQ_API_KEY</div>
                <input class="setup-row-input" type="password" id="groqKey" placeholder="gsk_…" autocomplete="off">
                <button class="setup-row-action primary" id="groqSaveBtn">SAVE</button>
            </div>
            <div class="setup-row" id="groqStatus" style="display:none"></div>
        `;
        el.querySelector('#groqSaveBtn').addEventListener('click', async () => {
            const v = el.querySelector('#groqKey').value.trim();
            if (!v) return;
            const status = el.querySelector('#groqStatus');
            status.style.display = '';
            status.innerHTML = `<div class="setup-row-label">Saving…</div>`;
            try {
                const r = await fetch(api('/api/setup/env'), {
                    method: 'POST', headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({ name: 'GROQ_API_KEY', value: v }),
                });
                const d = await r.json();
                if (!r.ok || d.error) {
                    status.innerHTML = `<div class="setup-row-label" style="color:var(--accent-red)">${escapeHtml(d.error || 'Save failed')}</div>`;
                } else {
                    status.innerHTML = `<div class="setup-row-label">Saved. <small>Restart the server to apply.</small></div>
                        <span class="setup-row-status ok">SAVED</span>`;
                    state.summary.engine = 'ok';
                    saveState();
                }
            } catch (e) {
                status.innerHTML = `<div class="setup-row-label" style="color:var(--accent-red)">${escapeHtml(e.message)}</div>`;
            }
        });
    }

    async function stepPassword(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP — DEVICE PASSWORD</div>
            <div class="setup-h1">Set a password to gate access.</div>
            <div class="setup-lede">
                Every browser opening this app will be asked once. The password is salted + hashed and
                stored only on this machine. ${state.hostMode === 'funnel' ? '<strong>Required</strong> before enabling public Funnel.' : 'Strongly recommended for any LAN-or-wider setup.'}
                <br><br>
                <small>Leave blank and click <strong>SAVE</strong> to disable auth (LAN-trust mode).</small>
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Password <small>min 6 characters</small></div>
                <input class="setup-row-input" type="password" id="pwInput" placeholder="" autocomplete="new-password">
                <button class="setup-row-action primary" id="pwSaveBtn">SAVE</button>
            </div>
            <div class="setup-row" id="pwStatus" style="display:none"></div>
        `;
        el.querySelector('#pwSaveBtn').addEventListener('click', async () => {
            const v = el.querySelector('#pwInput').value;
            const status = el.querySelector('#pwStatus');
            status.style.display = '';
            if (v && v.length < 6) {
                status.innerHTML = `<div class="setup-row-label" style="color:var(--accent-red)">Password must be 6+ characters.</div>`;
                return;
            }
            status.innerHTML = `<div class="setup-row-label">Saving…</div>`;
            try {
                const r = await fetch(api('/api/auth/password'), {
                    method: 'POST', headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({ password: v }),
                });
                const d = await r.json();
                if (!r.ok || d.error) {
                    status.innerHTML = `<div class="setup-row-label" style="color:var(--accent-red)">${escapeHtml(d.error || 'Save failed')}</div>`;
                    return;
                }
                if (v) {
                    // Login on this device with the new password to get a fresh token.
                    const li = await fetch(api('/api/auth/login'), {
                        method: 'POST', headers: {'Content-Type':'application/json'},
                        body: JSON.stringify({ password: v }),
                    }).then(x => x.json());
                    if (li && li.token && typeof setSentientToken === 'function') {
                        setSentientToken(li.token);
                    }
                    status.innerHTML = `<div class="setup-row-label">Password saved. This device is logged in.</div>
                        <span class="setup-row-status ok">SET</span>`;
                    state.summary.password = 'ok';
                } else {
                    if (typeof clearSentientToken === 'function') clearSentientToken();
                    status.innerHTML = `<div class="setup-row-label">Auth disabled. Anyone on this network can reach the app.</div>
                        <span class="setup-row-status warn">OFF</span>`;
                    state.summary.password = 'skip';
                }
                saveState();
            } catch (e) {
                status.innerHTML = `<div class="setup-row-label" style="color:var(--accent-red)">${escapeHtml(e.message)}</div>`;
            }
        });
    }

    async function stepEnv(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP — OPTIONAL API KEYS</div>
            <div class="setup-h1">Add keys for the integrations you want.</div>
            <div class="setup-lede">
                Every key is independently optional — the matching feature just degrades if it's missing.
                Each row links to the dashboard where you create the key. <strong>Skip</strong> any you'll add later.
            </div>
            <div class="setup-rows" id="envRows"><div class="setup-lede">Loading current state…</div></div>
        `;
        // Each entry surfaces both where to get the key AND any redirect URI
        // the user must register against their app. Missing redirect URIs are
        // the #1 cause of OAuth failures, so we make them obvious here.
        const SPOTIFY_REDIRECT = location.protocol + '//' + (location.hostname || '127.0.0.1') + ':7070/api/spotify/callback';
        const GOOGLE_REDIRECT  = location.protocol + '//' + (location.hostname || '127.0.0.1') + ':7070/api/tasks/google/callback';
        const ENV_META = [
            { key: 'GROQ_API_KEY',          label: 'Groq API key',           desc: 'Built-in chat path (free tier available).', link: 'https://console.groq.com/keys', placeholder: 'gsk_…' },
            { key: 'OPENAI_API_KEY',        label: 'OpenAI API key',         desc: 'Optional vision / transcription fallback.', link: 'https://platform.openai.com/api-keys', placeholder: 'sk-…' },
            { key: 'GEMINI_API_KEY',        label: 'Google Gemini key',      desc: 'Camera vision analysis.', link: 'https://aistudio.google.com/app/apikey', placeholder: 'AIza…' },
            { key: 'SPOTIFY_CLIENT_ID',     label: 'Spotify Client ID',      desc: 'OAuth app credentials. Add redirect URI <code>' + SPOTIFY_REDIRECT + '</code> in your Spotify app settings.', link: 'https://developer.spotify.com/dashboard', placeholder: '…' },
            { key: 'SPOTIFY_CLIENT_SECRET', label: 'Spotify Client Secret',  desc: 'Same Spotify app.', link: 'https://developer.spotify.com/dashboard', placeholder: '…' },
            { key: 'GOOGLE_CLIENT_ID',      label: 'Google OAuth Client ID', desc: 'Tasks + Calendar share this client. Enable Google Tasks API + Google Calendar API. Add redirect URI <code>' + GOOGLE_REDIRECT + '</code>.', link: 'https://console.cloud.google.com/apis/credentials', placeholder: '…apps.googleusercontent.com' },
            { key: 'GOOGLE_CLIENT_SECRET',  label: 'Google OAuth Secret',    desc: 'Same Google client as above.', link: 'https://console.cloud.google.com/apis/credentials', placeholder: '…' },
        ];
        const rows = el.querySelector('#envRows');
        let envState;
        try {
            envState = await fetch(api('/api/setup/env')).then(x => x.json());
        } catch (e) {
            rows.innerHTML = `<div class="setup-lede" style="color:var(--accent-red)">Server unreachable.</div>`;
            return;
        }
        const present = new Set((envState.keys || []).filter(k => k.present).map(k => k.name));
        rows.innerHTML = ENV_META.map(m => `
            <div class="setup-row" data-key="${m.key}">
                <div class="setup-row-label">${escapeHtml(m.label)}
                    <!-- m.desc is hardcoded above, safe to render as HTML so we can include <code> for redirect URIs. -->
                    <small>${m.desc} · <a href="${m.link}" target="_blank" rel="noopener">Get key</a></small>
                </div>
                <input class="setup-row-input" type="password" placeholder="${m.placeholder}"
                    autocomplete="off" data-key-input="${m.key}">
                <button class="setup-row-action" data-key-save="${m.key}">SAVE</button>
                <span class="setup-row-status ${present.has(m.key) ? 'ok' : 'warn'}">${present.has(m.key) ? 'SET' : 'EMPTY'}</span>
            </div>
        `).join('');
        rows.querySelectorAll('[data-key-save]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const key = btn.dataset.keySave;
                const input = rows.querySelector(`[data-key-input="${key}"]`);
                const status = btn.nextElementSibling;
                const val = input.value.trim();
                if (!val) {
                    status.className = 'setup-row-status warn';
                    status.textContent = 'EMPTY';
                    return;
                }
                status.className = 'setup-row-status busy';
                status.textContent = 'SAVING…';
                try {
                    const r = await fetch(api('/api/setup/env'), {
                        method: 'POST', headers: {'Content-Type':'application/json'},
                        body: JSON.stringify({ name: key, value: val }),
                    });
                    const d = await r.json();
                    if (!r.ok || d.error) {
                        status.className = 'setup-row-status err';
                        status.textContent = 'FAIL';
                    } else {
                        status.className = 'setup-row-status ok';
                        status.textContent = 'SAVED';
                        input.value = '';
                    }
                } catch (e) {
                    status.className = 'setup-row-status err';
                    status.textContent = 'FAIL';
                }
            });
        });
    }

    function stepIntegrations(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP — INTEGRATIONS</div>
            <div class="setup-h1">Connect the services you use.</div>
            <div class="setup-lede">
                Each one opens a popup OAuth window against this server. You can skip any of them.
                After consent, the panel switches to its normal view automatically.
            </div>
            <div class="setup-rows">
                <div class="setup-row">
                    <div class="setup-row-label">Google Tasks &amp; Calendar
                        <small>OAuth · same client for both</small></div>
                    <button class="setup-row-action primary" id="intGoogleBtn">CONNECT GOOGLE</button>
                    <span class="setup-row-status warn" id="intGoogleStatus">CHECK</span>
                </div>
                <div class="setup-row">
                    <div class="setup-row-label">Spotify
                        <small>Playlists, AI DJ, playback control</small></div>
                    <button class="setup-row-action primary" id="intSpotifyBtn">CONNECT SPOTIFY</button>
                    <span class="setup-row-status warn" id="intSpotifyStatus">CHECK</span>
                </div>
                <div class="setup-row">
                    <div class="setup-row-label">Composio (tools)
                        <small>Adds GitHub, Gmail, Slack, Notion, Linear, etc. via MCP. Paste your consumer key in
                        <strong>Settings → SKILLS · COMPOSIO</strong> after this.</small></div>
                    <a class="setup-row-action" href="https://dashboard.composio.dev/" target="_blank" rel="noopener">GET KEY →</a>
                </div>
            </div>
        `;
        el.querySelector('#intGoogleBtn').addEventListener('click', () => {
            window.open(api('/api/tasks/google/auth'), '_blank', 'width=500,height=700');
            setTimeout(() => probe('intGoogleStatus', '/api/tasks/google/status', 'authenticated'), 3000);
        });
        el.querySelector('#intSpotifyBtn').addEventListener('click', () => {
            window.open(api('/api/spotify/auth'), '_blank', 'width=500,height=700');
            setTimeout(() => probe('intSpotifyStatus', '/api/spotify/status', 'authenticated'), 3000);
        });
        async function probe(badgeId, url, key) {
            try {
                const r = await fetch(api(url));
                const d = await r.json();
                const badge = el.querySelector('#' + badgeId);
                if (d[key]) { badge.className = 'setup-row-status ok'; badge.textContent = 'CONNECTED'; }
                else { badge.className = 'setup-row-status warn'; badge.textContent = 'NOT CONNECTED'; }
            } catch (e) {}
        }
        probe('intGoogleStatus', '/api/tasks/google/status', 'authenticated');
        probe('intSpotifyStatus', '/api/spotify/status', 'authenticated');
    }

    async function stepRemote(el) {
        if (state.hostMode === 'tailnet' || state.hostMode === 'funnel') {
            el.innerHTML = `
                <div class="setup-h2">STEP — REMOTE ACCESS</div>
                <div class="setup-h1">Set up Tailscale${state.hostMode === 'funnel' ? ' + Funnel' : ''}.</div>
                <div class="setup-lede">
                    Tailscale gives every device on your tailnet a private DNS name to reach this server.
                    ${state.hostMode === 'funnel'
                        ? 'Funnel additionally exposes a public HTTPS URL — make sure the password step succeeded before enabling.'
                        : ''}
                </div>
                <div class="setup-row">
                    <div class="setup-row-label">Tailscale installed <small id="tsInstHint">checking…</small></div>
                    <button class="setup-row-action primary" id="tsInstallBtn">INSTALL</button>
                </div>
                <div class="setup-row">
                    <div class="setup-row-label">Logged in <small>Click LOGIN — a browser tab opens against tailscale.com.</small></div>
                    <button class="setup-row-action" id="tsLoginBtn">LOGIN</button>
                </div>
                ${state.hostMode === 'funnel' ? `
                <div class="setup-row">
                    <div class="setup-row-label">Funnel (public URL) <small id="tsFunnelHint"></small></div>
                    <button class="setup-row-action primary" id="tsFunnelBtn">ENABLE FUNNEL</button>
                </div>` : ''}
                <div class="setup-log" id="tsLog"></div>
            `;
            const log = el.querySelector('#tsLog');
            const hint = el.querySelector('#tsInstHint');
            const tsInstall = el.querySelector('#tsInstallBtn');
            const tsLogin = el.querySelector('#tsLoginBtn');

            async function probe() {
                try {
                    const r = await fetch(api('/api/tailscale/status'));
                    const d = await r.json();
                    if (!d.installed) {
                        hint.textContent = 'Not installed.';
                        tsInstall.disabled = false;
                    } else {
                        hint.textContent = d.loggedIn ? ('Installed · ' + (d.dnsName || 'logged in')) : 'Installed · not logged in';
                        tsInstall.disabled = true;
                        tsInstall.textContent = '✓ INSTALLED';
                    }
                    if (state.hostMode === 'funnel') {
                        const fhint = el.querySelector('#tsFunnelHint');
                        const fbtn = el.querySelector('#tsFunnelBtn');
                        if (d.funnelEnabled && d.funnelUrl) {
                            fhint.innerHTML = `<a href="${d.funnelUrl}" target="_blank" rel="noopener">${d.funnelUrl}</a>`;
                            fbtn.textContent = '✓ FUNNEL ON';
                        } else {
                            fhint.textContent = 'Funnel off.';
                        }
                    }
                } catch (e) { hint.textContent = 'Server unreachable.'; }
            }
            probe();

            tsInstall.addEventListener('click', async () => {
                log.innerHTML = '';
                await fetch(api('/api/setup/install/tailscale'), { method: 'POST' });
                listenSetupProgress(log, () => probe());
            });
            tsLogin.addEventListener('click', async () => {
                log.innerHTML = '';
                await fetch(api('/api/setup/install/tailscale/up'), { method: 'POST' });
                listenSetupProgress(log, () => probe());
            });
            if (state.hostMode === 'funnel') {
                el.querySelector('#tsFunnelBtn').addEventListener('click', async () => {
                    log.innerHTML = '';
                    appendLog(log, 'info', 'Enabling Funnel…');
                    try {
                        const r = await fetch(api('/api/tailscale/funnel'), {
                            method: 'POST', headers: {'Content-Type':'application/json'},
                            body: JSON.stringify({ enable: true }),
                        });
                        const d = await r.json();
                        if (d.error) appendLog(log, 'err', d.error);
                        else appendLog(log, 'ok', d.funnelUrl ? ('Funnel up: ' + d.funnelUrl) : 'Funnel enabled.');
                        probe();
                    } catch (e) { appendLog(log, 'err', e.message); }
                });
            }
        } else {
            el.innerHTML = `
                <div class="setup-h2">STEP — REMOTE ACCESS</div>
                <div class="setup-h1">Use the URLs below from your other devices.</div>
                <div class="setup-lede">
                    <div class="setup-row">
                        <div class="setup-row-label">On this device: <small>${location.origin}</small></div>
                    </div>
                </div>`;
        }
    }

    async function stepVoice(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP — VOICE (OPTIONAL)</div>
            <div class="setup-h1">Enable wake-word + voice transcription.</div>
            <div class="setup-lede">
                Sentient supports two voice paths: browser-based <strong>SpeechRecognition</strong> (Chromium only)
                and a server-side <strong>Vosk</strong> listener (any browser, more reliable).
                <br><br>
                Click <strong>DOWNLOAD VOSK MODEL</strong> to fetch the ~40 MB small English model
                and wire it up automatically. Skip if you don't use voice or prefer browser-only.
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Vosk wake-word model <small id="voskHint">checking…</small></div>
                <button class="setup-row-action primary" id="voskBtn">DOWNLOAD MODEL</button>
            </div>
            <div class="setup-log" id="voskLog"></div>
        `;
        const hint = el.querySelector('#voskHint');
        const btn = el.querySelector('#voskBtn');
        const log = el.querySelector('#voskLog');

        async function probe() {
            try {
                const r = await fetch(api('/api/setup/prereqs'));
                const d = await r.json();
                if (d.vosk && d.vosk.installed) {
                    hint.textContent = 'Installed · ' + (d.vosk.path || 'configured');
                    btn.textContent = '✓ DOWNLOADED';
                    btn.disabled = true;
                } else {
                    hint.textContent = 'Not yet downloaded.';
                    btn.disabled = false;
                    btn.textContent = 'DOWNLOAD MODEL';
                }
            } catch (e) {}
        }
        probe();
        btn.addEventListener('click', async () => {
            log.innerHTML = '';
            btn.disabled = true;
            await fetch(api('/api/setup/install/vosk'), { method: 'POST' });
            appendLog(log, 'info', 'Downloading + unzipping… this can take a minute.');
            listenSetupProgress(log, () => probe());
        });
    }

    async function stepHelper(el) {
        const supported = state.os === 'macos' || state.os === 'windows';
        if (!supported) {
            el.innerHTML = `
                <div class="setup-h2">STEP — NATIVE HELPER</div>
                <div class="setup-h1">Not needed on ${escapeHtml(state.os)}.</div>
                <div class="setup-lede">The native helper exists only for macOS and Windows.</div>`;
            return;
        }
        el.innerHTML = `
            <div class="setup-h2">STEP — NATIVE HELPER (${state.os.toUpperCase()})</div>
            <div class="setup-h1">Let the AI control this ${state.os === 'macos' ? 'Mac' : 'PC'}.</div>
            <div class="setup-lede">
                The browser sandbox can't inject keystrokes or move the mouse — that's what the native
                helper is for. Install it once and the assistant can type, click, launch apps, and send
                key combos on this machine. <strong>Optional — skip if you don't want OS-level remote control.</strong>
                ${state.os === 'macos' ? '<br><br>After install, you\'ll be guided to grant Accessibility permission once.' : ''}
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Native helper <small id="helperHint">checking…</small></div>
                <button class="setup-row-action primary" id="helperBtn">BUILD & INSTALL</button>
            </div>
            ${state.os === 'macos' ? `
            <div class="setup-row">
                <div class="setup-row-label">Accessibility permission
                    <small>Open System Settings to add the helper. macOS only.</small></div>
                <a class="setup-row-action" href="x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility">OPEN SYSTEM SETTINGS</a>
            </div>` : ''}
            <div class="setup-log" id="helperLog"></div>
        `;
        const hint = el.querySelector('#helperHint');
        const btn = el.querySelector('#helperBtn');
        const log = el.querySelector('#helperLog');

        async function probe() {
            try {
                const r = await fetch(api('/api/setup/prereqs'));
                const d = await r.json();
                if (d.helper && d.helper.installed) {
                    hint.textContent = 'Installed · ' + (d.helper.path || '/usr/local/bin/sentient-helper');
                    btn.disabled = true;
                    btn.textContent = '✓ INSTALLED';
                } else {
                    hint.textContent = 'Not installed.';
                    btn.disabled = false;
                    btn.textContent = 'BUILD & INSTALL';
                }
            } catch (e) {}
        }
        probe();
        btn.addEventListener('click', async () => {
            log.innerHTML = '';
            btn.disabled = true;
            await fetch(api('/api/setup/install/helper'), { method: 'POST' });
            appendLog(log, 'info', 'Building helper…');
            listenSetupProgress(log, () => probe());
        });
    }

    function stepDeviceName(el) {
        el.innerHTML = `
            <div class="setup-h2">STEP — NAME THIS DEVICE</div>
            <div class="setup-h1">What should we call this browser tab?</div>
            <div class="setup-lede">
                The AI uses this name to address you ("show me my <strong>Laptop</strong> screen"). It also appears
                in every other tab's device list. You can change it any time in <strong>Settings → DEVICES</strong>.
            </div>
            <div class="setup-row">
                <div class="setup-row-label">Device name</div>
                <input class="setup-row-input" type="text" id="devNameInput" placeholder="e.g. My Laptop"
                    value="${escapeHtml(state.deviceName || localStorage.getItem('sentient_deviceName') || '')}">
                <button class="setup-row-action primary" id="devNameSaveBtn">SAVE</button>
            </div>
            <div class="setup-row" id="devNameStatus" style="display:none"></div>
        `;
        el.querySelector('#devNameSaveBtn').addEventListener('click', () => {
            const v = el.querySelector('#devNameInput').value.trim();
            if (!v) return;
            localStorage.setItem('sentient_deviceName', v);
            state.deviceName = v;
            saveState();
            // Re-register with the server immediately.
            try {
                if (typeof ws !== 'undefined' && ws && ws.readyState === 1) {
                    ws.send(JSON.stringify({
                        type: 'register_device',
                        name: v,
                        platform: navigator.platform || '',
                    }));
                }
            } catch (e) {}
            const s = el.querySelector('#devNameStatus');
            s.style.display = '';
            s.innerHTML = `<div class="setup-row-label">Saved as "${escapeHtml(v)}".</div>
                <span class="setup-row-status ok">SAVED</span>`;
        });
    }

    async function stepDone(el) {
        const items = [
            { id: 'host-mode',   label: 'Hosting method',           done: !!state.hostMode },
            { id: 'engine',      label: 'Chat engine',              done: state.summary.engine === 'ok' || !!state.engine },
            { id: 'password',    label: 'Device password',          done: state.summary.password === 'ok' },
            { id: 'env',         label: 'Optional API keys',        done: true /* always reachable; treat as ok */ },
            { id: 'integrations',label: 'Integrations',             done: true },
            { id: 'remote',      label: 'Remote access',            done: state.hostMode === 'solo' || state.hostMode === 'lan' || state.summary.remote === 'ok' },
            { id: 'voice',       label: 'Voice (Vosk)',             done: true },
            { id: 'helper',      label: 'Native helper',            done: true },
            { id: 'device-name', label: 'Device name',              done: !!state.deviceName },
        ];
        const reachUrls = [];
        try {
            const r = await fetch(api('/api/tailscale/status'));
            const d = await r.json();
            if (d.dnsName) reachUrls.push('Tailnet: http://' + d.dnsName + ':7070');
            if (d.funnelUrl) reachUrls.push('Funnel:  ' + d.funnelUrl);
        } catch (e) {}
        const localHost = location.host;
        if (localHost) reachUrls.unshift('This device: ' + location.protocol + '//' + localHost);

        el.innerHTML = `
            <div class="setup-h2">DONE</div>
            <div class="setup-h1">Sentient is ready.</div>
            <div class="setup-lede">
                Here's a quick recap. Anything marked <span class="setup-row-status warn">SKIPPED</span> can be
                completed any time from Settings.
            </div>
            <div class="setup-summary">
                ${items.map(it => `
                    <div class="setup-summary-item ${it.done ? 'ok' : 'skip'}">
                        <span class="si-icon">${it.done ? '✓' : '◌'}</span>
                        <span>${escapeHtml(it.label)}</span>
                    </div>`).join('')}
            </div>
            <div class="setup-lede" style="margin-top:18px">
                <strong>Reach this device:</strong><br>
                <code style="display:block; padding:8px 12px; background:var(--bg-input); border-radius:4px; margin-top:6px;">${reachUrls.map(escapeHtml).join('<br>')}</code>
            </div>
            <div class="setup-lede">
                Click <strong>CLOSE ✓</strong> below to start using the assistant.
            </div>
        `;
    }

    // ── Helpers ───────────────────────────────────────────
    function appendLog(log, level, line) {
        const span = document.createElement('div');
        span.className = 'll-' + (level || 'info');
        span.textContent = line;
        log.appendChild(span);
        log.scrollTop = log.scrollHeight;
    }

    /**
     * Subscribe to the WS for setup_progress messages. Disconnects after a `done`
     * message (or 10 minutes), then calls {@code onDone}.
     */
    function listenSetupProgress(log, onDone) {
        if (typeof ws === 'undefined' || !ws || ws.readyState !== 1) {
            appendLog(log, 'warn', 'WebSocket not open — progress will not stream. Click RE-CHECK after the install finishes.');
            return;
        }
        const handler = (event) => {
            try {
                const data = JSON.parse(event.data);
                if (data.type !== 'setup_progress') return;
                appendLog(log, data.level || 'info', data.line || '');
                if (data.done) {
                    ws.removeEventListener('message', handler);
                    if (typeof onDone === 'function') onDone(data);
                }
            } catch (e) {}
        };
        ws.addEventListener('message', handler);
        // Safety timeout — long installs shouldn't leak forever.
        setTimeout(() => ws.removeEventListener('message', handler), 10 * 60_000);
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function truncate(s, n) { return s.length <= n ? s : s.slice(0, n - 1) + '…'; }

    // ── Settings → Re-run wizard button ───────────────────
    document.addEventListener('DOMContentLoaded', () => {
        const reRun = document.getElementById('settingsRunWizardBtn');
        if (reRun) reRun.addEventListener('click', () => {
            localStorage.removeItem(DONE_KEY);
            // Reset to first step so the user gets the whole flow again,
            // but keep their previous choices in state.
            state.stepIndex = 0;
            saveState();
            open();
        });
    });

    // ── Auto-show on first launch ─────────────────────────
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
