/* ====================================================================
 * r1 // remote — companion web app
 *
 * Single-page launcher-style UI: home view shows clock + apps grid;
 * tapping a tile reveals one of the sub-views (terminal, sms, send
 * text, system, meetings). Back-pill returns to home. RPC over the
 * existing WebSocket at /api/rpc; topbar reflects live state snapshots.
 * ==================================================================== */

(() => {
'use strict';

// Shorthand for the i18n module loaded from /i18n.js. Falls back to identity
// lookups if the script ever fails to load so the page still renders.
const t = (k, ...a) => (window.R1I18n ? window.R1I18n.t(k, ...a) : k);
const applyI18n = (root) => window.R1I18n && window.R1I18n.applyI18n(root);
const setLocale = (code) => window.R1I18n && window.R1I18n.setLocale(code);
// Apply once with default English so static markup is consistent before the
// first state.snapshot lands.
setLocale('en');

// ============== panel token ==============
// The long-lived bearer that gates WS upgrades + sensitive HTTP endpoints
// (/api/state, /api/transcriber/*). We obtain it one of two ways:
//   1. Legacy path: a `?t=<token>` query parameter on the URL (still
//      supported so older QR codes / typed URLs keep working).
//   2. Current path: the user types a 4-digit passcode in the unlock
//      overlay; we POST to /api/auth and store the returned token in
//      sessionStorage. This is the recommended UX — typing 32 hex chars
//      on a phone is awful.
// `panelToken` is `let` (not `const`) because it may be set after init by
// the unlock flow.
let panelToken = (() => {
    const fromUrl = new URLSearchParams(location.search).get('t');
    if (fromUrl) {
        try { sessionStorage.setItem('r1.panelToken', fromUrl); } catch (_) {}
        return fromUrl;
    }
    try { return sessionStorage.getItem('r1.panelToken') || ''; } catch (_) { return ''; }
})();
// Append `?t=TOKEN` (or `&t=TOKEN`) to URLs that the server requires it on.
// Skip when token is empty — server returns 401 and the SPA surfaces it as
// offline, which is the correct visible state.
function withToken(url) {
    if (!panelToken) return url;
    return url + (url.includes('?') ? '&' : '?') + 't=' + encodeURIComponent(panelToken);
}

// ============== unlock overlay ==============
// Shown when we don't have a token; hidden once authentication completes.
// Drives a tiny state machine: typing digits builds `unlockBuffer`, the
// 4th digit triggers an auto-submit, on success we save the token and let
// the rest of the SPA come online. On failure we clear the buffer and
// either show "X tries left" or kick into a lockout countdown.
const unlockOverlay = document.getElementById('unlock');
const unlockMsg = document.getElementById('unlock-msg');
const unlockDots = Array.from(document.querySelectorAll('#unlock-dots .unlock-dot'));
const unlockKeys = Array.from(document.querySelectorAll('.unlock-key[data-digit]'));
const unlockBack = document.querySelector('.unlock-key[data-back]');
let unlockBuffer = '';
let unlockBusy = false;
let unlockLockoutUntil = 0;

function renderUnlockDots() {
    unlockDots.forEach((dot, i) => {
        dot.classList.toggle('filled', i < unlockBuffer.length);
    });
}

function setUnlockMsg(text, ok = false) {
    if (!unlockMsg) return;
    unlockMsg.textContent = text || '';
    unlockMsg.classList.toggle('ok', !!ok);
}

function lockUnlockKeys(disabled) {
    unlockKeys.forEach((k) => { k.disabled = disabled; });
    if (unlockBack) unlockBack.disabled = disabled;
}

function showUnlock(reason) {
    unlockOverlay.classList.remove('hidden');
    unlockOverlay.setAttribute('aria-hidden', 'false');
    unlockBuffer = '';
    renderUnlockDots();
    if (reason) setUnlockMsg(reason);
}

function hideUnlock() {
    unlockOverlay.classList.add('hidden');
    unlockOverlay.setAttribute('aria-hidden', 'true');
    setUnlockMsg('');
}

async function submitPasscode(code) {
    if (unlockBusy) return;
    unlockBusy = true;
    lockUnlockKeys(true);
    setUnlockMsg('checking…');
    try {
        const r = await fetch('/api/auth', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ passcode: code }),
        });
        const body = await r.json().catch(() => ({}));
        if (r.status === 200 && body.ok && body.token) {
            panelToken = body.token;
            try { sessionStorage.setItem('r1.panelToken', panelToken); } catch (_) {}
            setUnlockMsg('unlocked', true);
            setTimeout(() => { hideUnlock(); connect(); }, 250);
            return;
        }
        if (r.status === 429 && typeof body.retry_after_ms === 'number') {
            unlockLockoutUntil = Date.now() + body.retry_after_ms;
            startLockoutCountdown();
            return;
        }
        const left = (body && typeof body.attempts_left === 'number') ? body.attempts_left : null;
        setUnlockMsg(
            left === null ? 'wrong passcode' :
            left === 1 ? 'wrong — 1 try left' :
            'wrong — ' + left + ' tries left'
        );
        unlockBuffer = '';
        renderUnlockDots();
    } catch (e) {
        setUnlockMsg('network error');
        unlockBuffer = '';
        renderUnlockDots();
    } finally {
        unlockBusy = false;
        if (Date.now() >= unlockLockoutUntil) lockUnlockKeys(false);
    }
}

function startLockoutCountdown() {
    lockUnlockKeys(true);
    unlockBuffer = '';
    renderUnlockDots();
    const tick = () => {
        const remaining = Math.max(0, Math.ceil((unlockLockoutUntil - Date.now()) / 1000));
        if (remaining <= 0) {
            setUnlockMsg('try again');
            lockUnlockKeys(false);
            return;
        }
        setUnlockMsg('locked out — ' + remaining + 's');
        setTimeout(tick, 1000);
    };
    tick();
}

unlockKeys.forEach((btn) => {
    btn.addEventListener('click', () => {
        if (unlockBusy || Date.now() < unlockLockoutUntil) return;
        if (unlockBuffer.length >= 4) return;
        unlockBuffer += btn.dataset.digit;
        renderUnlockDots();
        if (unlockBuffer.length === 4) submitPasscode(unlockBuffer);
    });
});
if (unlockBack) {
    unlockBack.addEventListener('click', () => {
        if (unlockBusy || Date.now() < unlockLockoutUntil) return;
        unlockBuffer = unlockBuffer.slice(0, -1);
        renderUnlockDots();
        setUnlockMsg('');
    });
}
// Hardware keyboard fallback — handy on desktop browsers during dev.
window.addEventListener('keydown', (e) => {
    if (unlockOverlay.classList.contains('hidden')) return;
    if (unlockBusy || Date.now() < unlockLockoutUntil) return;
    if (e.key >= '0' && e.key <= '9') {
        if (unlockBuffer.length < 4) {
            unlockBuffer += e.key;
            renderUnlockDots();
            if (unlockBuffer.length === 4) submitPasscode(unlockBuffer);
        }
    } else if (e.key === 'Backspace') {
        unlockBuffer = unlockBuffer.slice(0, -1);
        renderUnlockDots();
        setUnlockMsg('');
    }
});

// ============== WebSocket RPC ==============
let ws = null;
let reconnectDelay = 500;
const pending = new Map();
let reqSeq = 0;

const connStatus = document.getElementById('conn-status');
const heroDot = document.getElementById('hero-conn-dot');
const heroStatusText = document.getElementById('hero-status-text');

function setConn(state) {
    // state: 'connecting' | 'live' | 'error'
    connStatus.className = 'conn-pill ' + (state === 'live' ? 'live' : state === 'error' ? 'error' : '');
    connStatus.textContent = state === 'live' ? t('conn.live') :
        state === 'error' ? t('conn.offline') : t('conn.connecting');
    heroDot.className = 'dot ' + (state === 'live' ? 'live' : state === 'error' ? 'bad' : 'warn');
    heroStatusText.textContent = state === 'live' ? t('hero.connected') :
        state === 'error' ? t('hero.lost') : t('hero.connecting');
}

function connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = withToken(`${proto}://${location.host}/api/rpc`);
    setConn('connecting');
    ws = new WebSocket(url);
    ws.addEventListener('open', () => {
        setConn('live');
        reconnectDelay = 500;
    });
    ws.addEventListener('close', (e) => {
        setConn('error');
        ws = null;
        // 1008 PolicyViolation = server rejected the WS handshake on auth.
        // The token in sessionStorage is stale (rotated on the device, or
        // we never had one). Kick straight to the unlock overlay rather
        // than burning CPU on reconnect attempts that will all fail.
        if (e && e.code === 1008) {
            panelToken = '';
            try { sessionStorage.removeItem('r1.panelToken'); } catch (_) {}
            showUnlock('session expired');
            return;
        }
        setTimeout(connect, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, 10000);
    });
    ws.addEventListener('error', () => {});
    ws.addEventListener('message', (e) => {
        let frame;
        try { frame = JSON.parse(e.data); } catch (_) { return; }
        if (frame.type === 'res') {
            const p = pending.get(frame.id);
            if (!p) return;
            pending.delete(frame.id);
            if (frame.ok) p.resolve(frame.payload);
            else p.reject(new Error(frame.error?.message || 'rpc failed'));
        } else if (frame.type === 'event') {
            handleEvent(frame.event, frame.payload);
        }
    });
}

function rpc(method, params = null) {
    if (!ws || ws.readyState !== 1) return Promise.reject(new Error('not connected'));
    const id = String(++reqSeq);
    const frame = { type: 'req', id, method, params };
    return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify(frame));
        setTimeout(() => {
            if (pending.has(id)) {
                pending.delete(id);
                reject(new Error('timeout'));
            }
        }, 8000);
    });
}

// ============== view navigation ==============
const tplAppHeader = document.getElementById('tpl-app-header');

// Inject the per-app header (back pill + title + status) into each .view-app's
// .app-mount slot. Title comes from the section's data-title-key attribute
// (i18n lookup) with data-title as the English fallback for static markup.
document.querySelectorAll('.view-app').forEach((view) => {
    const mount = view.querySelector('.app-mount');
    if (!mount) return;
    const node = tplAppHeader.content.cloneNode(true);
    const titleEl = node.querySelector('.app-title');
    if (view.dataset.titleKey) {
        titleEl.dataset.i18n = view.dataset.titleKey;
        titleEl.textContent = t(view.dataset.titleKey);
    } else {
        titleEl.textContent = view.dataset.title || '';
    }
    mount.appendChild(node);
});
// First i18n pass after the cloned headers exist.
applyI18n();

function setView(name) {
    document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + name));
    document.body.className = 'view-' + name;
    // Lazy-load when opening certain panels.
    if (name === 'terminal') refreshTerminalHistory();
    if (name === 'meetings') refreshMeetings();
    if (name === 'media') Media.refresh();
}

// ============== meetings ==============
function fmtMmSs(ms) {
    if (!ms || ms <= 0) return '—';
    const sec = Math.floor(ms / 1000);
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
}
function fmtTimestamp(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    return d.toLocaleString();
}
async function refreshMeetings() {
    try {
        const data = await rpc('transcriber.list', {});
        const status = document.getElementById('mt-status');
        if (status) {
            status.textContent = data.recording
                ? 'recording'
                : (data.transcribing ? 'transcribing' : 'idle');
        }
        const list = document.getElementById('mt-list');
        if (!list) return;
        if (!data.meetings || data.meetings.length === 0) {
            list.innerHTML = '<p style="color:#888;font-size:12px;">no recordings yet.</p>';
            return;
        }
        list.innerHTML = data.meetings.map((m) => {
            const sub = fmtTimestamp(m.createdAtMs) + ' · ' + fmtMmSs(m.durationMs)
                + ' · ' + m.status + (m.speakerCount ? ' · ' + m.speakerCount + 'sp' : '');
            return '<div class="card-row" style="flex-direction:column;align-items:flex-start;gap:4px;border-top:1px solid #222;padding:6px 0;">'
                + '<span style="color:#FF6A00;font-size:14px;">' + escapeHtml(m.title) + '</span>'
                + '<span style="color:#888;font-size:11px;">' + escapeHtml(sub) + '</span>'
                + '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:4px;">'
                + '<a href="' + withToken('/api/transcriber/audio/' + m.uuid + '.m4a') + '" class="primary-btn" download>audio</a>'
                + '<a href="' + withToken('/api/transcriber/transcript/' + m.uuid + '.txt') + '" class="primary-btn" download>transcript</a>'
                + '<a href="' + withToken('/api/transcriber/transcript/' + m.uuid + '.json') + '" class="primary-btn" download>json</a>'
                + (data.hasSmtp
                    ? '<button class="primary-btn" type="button" data-mt-email="' + m.uuid + '">email</button>'
                    : '<span style="color:#888;font-size:11px;align-self:center;">smtp not set on device</span>')
                + '<button class="primary-btn" type="button" data-mt-delete="' + m.uuid + '">delete</button>'
                + '</div></div>';
        }).join('');
    } catch (e) {
        console.warn('meetings refresh failed', e);
    }
}
function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
document.addEventListener('click', (e) => {
    const startBtn = e.target.closest && e.target.closest('#mt-start');
    if (startBtn) { rpc('transcriber.start', {}).then(() => setTimeout(refreshMeetings, 500)); return; }
    const stopBtn = e.target.closest && e.target.closest('#mt-stop');
    if (stopBtn) { rpc('transcriber.stop', {}).then(() => setTimeout(refreshMeetings, 1000)); return; }
    const refreshBtn = e.target.closest && e.target.closest('#mt-refresh');
    if (refreshBtn) { refreshMeetings(); return; }
    const emailBtn = e.target.closest && e.target.closest('[data-mt-email]');
    if (emailBtn) {
        const uuid = emailBtn.getAttribute('data-mt-email');
        const recipient = window.prompt('email transcript + audio to:', '');
        if (recipient) rpc('transcriber.email', { uuid, recipient })
            .then(() => alert('queued — check device for status'))
            .catch((err) => alert('failed: ' + err.message));
        return;
    }
    const delBtn = e.target.closest && e.target.closest('[data-mt-delete]');
    if (delBtn) {
        const uuid = delBtn.getAttribute('data-mt-delete');
        if (window.confirm('delete this meeting? cannot be undone.')) {
            rpc('transcriber.delete', { uuid }).then(refreshMeetings);
        }
    }
});

document.querySelectorAll('.app-tile').forEach((tile) => {
    tile.addEventListener('click', () => {
        const app = tile.dataset.app;
        if (app) setView(app);
    });
});

// Event delegation for back pills (rendered from the template so they don't
// exist at parse time).
document.addEventListener('click', (e) => {
    if (e.target.matches('[data-back]')) setView('home');
});

// Browser back-button → go home (only when not already on home).
window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !document.getElementById('view-home').classList.contains('active')) {
        setView('home');
    }
});

// ============== events ==============
function handleEvent(event, payload) {
    if (event === 'state.snapshot') applySnapshot(payload);
    else if (event === 'terminal.output') appendTerminalLine(payload);
    else if (event === 'capture.added') Media.onCaptureAdded(payload);
    else if (event === 'capture.recording') Media.onCaptureRecording(payload);
}

function setToggle(id, v) {
    const el = document.getElementById(id);
    if (el && document.activeElement !== el) el.checked = !!v;
}

function bars(level) {
    const filled = Math.max(0, Math.min(4, level | 0));
    return '●'.repeat(filled) + '○'.repeat(4 - filled);
}

function applySnapshot(s) {
    if (!s) return;
    // Locale: drives every t() lookup + <html dir>. Snapshot lands on every
    // open and at 1 Hz, so the web companion picks up device-side language
    // changes within a second.
    if (s.locale) setLocale(s.locale);
    if (s.system) {
        const clk = s.system.clockText || '--:--';
        document.getElementById('topbar-clock').textContent = clk;
        document.getElementById('hero-clock').textContent = clk;
        document.getElementById('hero-date').textContent = s.system.dateText || '—';
        const pct = Math.round((s.system.battery || 0) * 100);
        const bat = document.getElementById('topbar-battery');
        bat.textContent = pct + '%';
        bat.style.color = s.system.charging ? 'var(--good)' : pct > 20 ? 'var(--fg)' : 'var(--bad)';
        document.getElementById('sys-battery').textContent = pct + '%';
        document.getElementById('sys-charging').textContent = s.system.charging ? t('system.yes') : t('system.no');
        document.getElementById('sys-ip').textContent = (s.system.ip || '?') + ':' + (s.system.port || '?');
    }
    if (s.network) {
        document.getElementById('topbar-operator').textContent = s.network.operator || '—';
        document.getElementById('topbar-radio').textContent = s.network.networkType || '—';
        document.getElementById('topbar-signal').textContent = bars(s.network.signal);
        document.getElementById('sys-operator').textContent = s.network.operator || '—';
        document.getElementById('sys-radio').textContent = s.network.networkType || '—';
        document.getElementById('sys-signal').textContent = bars(s.network.signal);
        setToggle('toggle-wifi', s.network.wifi);
        setToggle('toggle-cellular', s.network.cellular);
        setToggle('toggle-bt', s.network.bt);
        setToggle('toggle-hotspot', s.network.hotspot);
    }
    if (typeof s.brightness === 'number') {
        const b = document.getElementById('brightness');
        if (document.activeElement !== b) b.value = s.brightness;
    }
    if (typeof s.volume === 'number') {
        const v = document.getElementById('volume');
        if (document.activeElement !== v) {
            v.value = s.volume;
            if (s.volumeMax) v.max = s.volumeMax;
        }
    }
    if (s.openclaw) {
        document.getElementById('oc-status').textContent = s.openclaw.status || '—';
        document.getElementById('oc-key').textContent = s.openclaw.hasVoiceKey
            ? ('sk_…' + (s.openclaw.voiceKeyTail || ''))
            : t('system.notSet');
    }
    if (s.terminal) applyTerminalSnapshot(s.terminal);
    if (s.credentials) applyCredentialsSnapshot(s.credentials);
    if (s.media) Media.onSnapshot(s.media);
}

// ============== utilities ==============
function flash(text, error) {
    const tag = document.createElement('div');
    tag.className = 'flash ' + (error ? 'error' : 'ok');
    tag.textContent = text;
    document.body.appendChild(tag);
    setTimeout(() => tag.remove(), 1600);
}
function showErr(e) { flash(e.message || 'error', true); }
function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}

// ============== system toggles + sliders ==============
document.getElementById('toggle-wifi').addEventListener('change', (e) =>
    rpc('wifi.toggle', { on: e.target.checked }).catch(showErr));
document.getElementById('toggle-cellular').addEventListener('change', (e) =>
    rpc('cellular.toggle', { on: e.target.checked }).catch(showErr));
document.getElementById('toggle-bt').addEventListener('change', (e) =>
    rpc('bt.toggle', { on: e.target.checked }).catch(showErr));
document.getElementById('toggle-hotspot').addEventListener('change', (e) =>
    rpc('hotspot.toggle', { on: e.target.checked }).catch(showErr));

let brightnessTimer = null;
document.getElementById('brightness').addEventListener('input', (e) => {
    clearTimeout(brightnessTimer);
    brightnessTimer = setTimeout(
        () => rpc('brightness.set', { level: parseInt(e.target.value, 10) }).catch(showErr),
        120,
    );
});
let volumeTimer = null;
document.getElementById('volume').addEventListener('input', (e) => {
    clearTimeout(volumeTimer);
    volumeTimer = setTimeout(
        () => rpc('volume.set', { level: parseInt(e.target.value, 10) }).catch(showErr),
        120,
    );
});

// ============== terminal ==============
const termOutput = document.getElementById('term-output');
const termCwd = document.getElementById('term-cwd');
const termInput = document.getElementById('term-input');
const termRun = document.getElementById('term-run');
const termClear = document.getElementById('term-clear');
const termForm = document.getElementById('term-form');
const termBanner = document.getElementById('term-banner');

function compactCwd(cwd) {
    if (!cwd) return '~';
    if (cwd === '/sdcard') return '~';
    if (cwd.startsWith('/sdcard/')) return '~' + cwd.slice('/sdcard'.length);
    return cwd;
}

function applyTerminalSnapshot(t) {
    if (!t) return;
    termCwd.textContent = compactCwd(t.cwd);
    if (t.enabled) {
        termBanner.classList.add('hidden');
        termInput.disabled = false;
        termRun.disabled = false;
    } else {
        termBanner.classList.remove('hidden');
        termInput.disabled = true;
        termRun.disabled = true;
    }
}

function appendTerminalLine(payload) {
    if (!payload || typeof payload.line !== 'string') return;
    const max = 2000;
    const lines = (termOutput.textContent || '').split('\n');
    lines.push(payload.line);
    while (lines.length > max) lines.shift();
    termOutput.textContent = lines.join('\n');
    termOutput.scrollTop = termOutput.scrollHeight;
    if (payload.cwd) termCwd.textContent = compactCwd(payload.cwd);
}

async function refreshTerminalHistory() {
    try {
        const h = await rpc('terminal.history');
        if (h && Array.isArray(h.lines)) {
            termOutput.textContent = h.lines.join('\n');
            termOutput.scrollTop = termOutput.scrollHeight;
        }
        if (h && h.cwd) termCwd.textContent = compactCwd(h.cwd);
    } catch (_) { /* probably 'disabled' — banner explains */ }
}

termForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const cmd = termInput.value;
    if (!cmd.trim()) return;
    termInput.value = '';
    try { await rpc('terminal.run', { cmd }); } catch (err) { showErr(err); }
});

termClear.addEventListener('click', async () => {
    try {
        await rpc('terminal.clear');
        termOutput.textContent = '';
    } catch (err) { showErr(err); }
});


// ============== credentials ==============
// Renders three blocks from snapshot.credentials (eleven / hermes / ntfy)
// and wires each block's save / add / edit / delete / activate to the
// corresponding credentials.* RPC. Secrets come back as tails from the
// server; we never store full values client-side beyond the moment they
// leave a save button.

const credElevenKey     = document.getElementById('cred-eleven-key');
const credElevenKeyTail = document.getElementById('cred-eleven-key-tail');
const credElevenVoice   = document.getElementById('cred-eleven-voice');
const credElevenCustom  = document.getElementById('cred-eleven-custom');
const credElevenSave    = document.getElementById('cred-eleven-save');
const credElevenStatus  = document.getElementById('cred-eleven-status');

const credHermesList    = document.getElementById('cred-hermes-list');
const credHermesUrl     = document.getElementById('cred-hermes-url');
const credHermesBearer  = document.getElementById('cred-hermes-bearer');
const credHermesAdd     = document.getElementById('cred-hermes-add');
const credHermesStatus  = document.getElementById('cred-hermes-status');

const credNtfyTopic     = document.getElementById('cred-ntfy-topic');
const credNtfySave      = document.getElementById('cred-ntfy-save');
const credNtfyStatus    = document.getElementById('cred-ntfy-status');

// Track which Hermes row (if any) is in edit mode so the user's in-flight
// edits aren't blown away when a 1 Hz snapshot triggers re-render.
let credHermesEditingId = null;
let credHermesEditDraft = { url: '', bearer: '' };

function credFieldFocused(el) {
    return document.activeElement === el;
}

function applyCredentialsSnapshot(cred) {
    if (!cred) return;

    // --- ElevenLabs ---
    const eleven = cred.elevenlabs || {};
    if (!credFieldFocused(credElevenKey)) {
        credElevenKey.value = '';
        credElevenKey.placeholder = eleven.hasApiKey ? (eleven.apiKeyTail || 'sk_...') : 'sk_... or 32-char hex';
    }
    credElevenKeyTail.textContent = eleven.hasApiKey
        ? t('cred.status.tail', eleven.apiKeyTail || '')
        : t('cred.status.unset');

    if (!credFieldFocused(credElevenVoice)) {
        const wanted = eleven.voiceId;
        const opts = Array.from(credElevenVoice.options).map((o) => o.value);
        credElevenVoice.value = opts.includes(wanted) ? wanted : opts[0];
    }

    if (!credFieldFocused(credElevenCustom)) {
        credElevenCustom.value = eleven.voiceCustomId || '';
    }

    // --- Hermes ---
    const hermes = cred.hermes || { connections: [], maxConnections: 5, activeId: '' };
    renderHermesList(hermes);

    // --- Ntfy ---
    const ntfy = cred.ntfy || {};
    if (!credFieldFocused(credNtfyTopic)) {
        credNtfyTopic.value = ntfy.topic || '';
    }
}

function renderHermesList(hermes) {
    const conns = Array.isArray(hermes.connections) ? hermes.connections : [];
    const activeId = hermes.activeId || '';
    const cap = hermes.maxConnections || 5;

    credHermesList.innerHTML = '';
    conns.forEach((c) => {
        const li = document.createElement('li');
        if (c.id === activeId) li.classList.add('cred-hermes-active');

        const row = document.createElement('div');
        row.className = 'cred-hermes-row';

        const host = document.createElement('div');
        host.className = 'cred-hermes-host';
        host.textContent = c.hostLabel || c.url || '';
        row.appendChild(host);

        if (c.id === activeId) {
            const mark = document.createElement('span');
            mark.className = 'cred-hermes-active-mark';
            mark.textContent = t('cred.hermes.activeMark');
            row.appendChild(mark);
        }

        const actions = document.createElement('div');
        actions.className = 'cred-hermes-actions';
        if (c.id !== activeId) {
            const btnActivate = document.createElement('button');
            btnActivate.type = 'button';
            btnActivate.textContent = t('cred.hermes.activate');
            btnActivate.addEventListener('click', () => activateHermesConn(c.id));
            actions.appendChild(btnActivate);
        }
        const btnEdit = document.createElement('button');
        btnEdit.type = 'button';
        btnEdit.textContent = t('cred.hermes.edit');
        btnEdit.addEventListener('click', () => beginEditHermesConn(c));
        actions.appendChild(btnEdit);

        const btnDelete = document.createElement('button');
        btnDelete.type = 'button';
        btnDelete.textContent = t('cred.hermes.delete');
        btnDelete.addEventListener('click', () => deleteHermesConn(c.id));
        actions.appendChild(btnDelete);

        row.appendChild(actions);
        li.appendChild(row);

        const bearer = document.createElement('div');
        bearer.className = 'cred-hermes-bearer';
        bearer.textContent = c.hasBearer
            ? t('cred.status.tail', c.bearerTail || '')
            : t('cred.status.unset');
        li.appendChild(bearer);

        if (credHermesEditingId === c.id) {
            li.appendChild(buildHermesEditForm(c));
        }

        credHermesList.appendChild(li);
    });

    const atCap = conns.length >= cap;
    credHermesAdd.disabled = atCap;
    credHermesStatus.textContent = atCap ? t('cred.hermes.capReached') : '';
}

function buildHermesEditForm(c) {
    const form = document.createElement('div');
    form.className = 'cred-hermes-edit-form';

    const urlLabel = document.createElement('label');
    urlLabel.className = 'field';
    const urlSpan = document.createElement('span');
    urlSpan.className = 'field-label';
    urlSpan.textContent = t('cred.hermes.url');
    urlLabel.appendChild(urlSpan);
    const urlInput = document.createElement('input');
    urlInput.type = 'text';
    urlInput.autocomplete = 'off';
    urlInput.spellcheck = false;
    urlInput.value = credHermesEditDraft.url || c.url || '';
    urlInput.addEventListener('input', () => { credHermesEditDraft.url = urlInput.value; });
    urlLabel.appendChild(urlInput);
    form.appendChild(urlLabel);

    const bearerLabel = document.createElement('label');
    bearerLabel.className = 'field';
    const bearerSpan = document.createElement('span');
    bearerSpan.className = 'field-label';
    bearerSpan.textContent = t('cred.hermes.bearer');
    bearerLabel.appendChild(bearerSpan);
    const bearerInput = document.createElement('input');
    bearerInput.type = 'text';
    bearerInput.autocomplete = 'off';
    bearerInput.spellcheck = false;
    bearerInput.placeholder = c.hasBearer ? (c.bearerTail || '') : '';
    bearerInput.value = credHermesEditDraft.bearer || '';
    bearerInput.addEventListener('input', () => { credHermesEditDraft.bearer = bearerInput.value; });
    bearerLabel.appendChild(bearerInput);
    form.appendChild(bearerLabel);

    const actions = document.createElement('div');
    actions.className = 'cred-hermes-edit-form-actions';

    const saveBtn = document.createElement('button');
    saveBtn.type = 'button';
    saveBtn.className = 'primary-btn';
    saveBtn.textContent = t('cred.save');
    saveBtn.addEventListener('click', () => saveHermesEdit(c));
    actions.appendChild(saveBtn);

    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.textContent = t('cred.hermes.cancel');
    cancelBtn.addEventListener('click', () => cancelHermesEdit());
    actions.appendChild(cancelBtn);

    form.appendChild(actions);
    return form;
}

function beginEditHermesConn(c) {
    credHermesEditingId = c.id;
    credHermesEditDraft = { url: c.url || '', bearer: '' };
    rpc('credentials.get').then(applyCredentialsSnapshot).catch(() => {});
}

function cancelHermesEdit() {
    credHermesEditingId = null;
    credHermesEditDraft = { url: '', bearer: '' };
    rpc('credentials.get').then(applyCredentialsSnapshot).catch(() => {});
}

async function saveHermesEdit(c) {
    // hermesUpdateConnection overwrites both url and bearer with whatever
    // we pass. Bearer field comes back masked, so an empty bearer field
    // means "I didn't retype" — we refuse rather than silently wipe the
    // saved bearer.
    const newUrl = (credHermesEditDraft.url || c.url || '').trim();
    const newBearer = credHermesEditDraft.bearer.trim();
    if (!newUrl) {
        credHermesStatus.textContent = t('cred.status.failed', 'url required');
        return;
    }
    if (!newBearer && c.hasBearer) {
        credHermesStatus.textContent = t('cred.status.failed', 'bearer required (retype to confirm)');
        return;
    }
    try {
        await rpc('credentials.hermes_update', { id: c.id, url: newUrl, bearer: newBearer });
        flash(t('cred.status.saved'));
        cancelHermesEdit();
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}

async function activateHermesConn(id) {
    try {
        await rpc('credentials.hermes_activate', { id });
        flash(t('cred.status.saved'));
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}

async function deleteHermesConn(id) {
    if (!window.confirm(t('cred.hermes.delete') + '?')) return;
    try {
        await rpc('credentials.hermes_delete', { id });
        if (credHermesEditingId === id) cancelHermesEdit();
        flash(t('cred.status.saved'));
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}

credHermesAdd.addEventListener('click', async () => {
    const url = (credHermesUrl.value || '').trim();
    const bearer = (credHermesBearer.value || '').trim();
    if (!url || !bearer) {
        credHermesStatus.textContent = t('cred.status.failed', 'url + bearer required');
        return;
    }
    credHermesAdd.disabled = true;
    try {
        await rpc('credentials.hermes_add', { url, bearer });
        credHermesUrl.value = '';
        credHermesBearer.value = '';
        flash(t('cred.status.saved'));
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    } finally {
        credHermesAdd.disabled = false;
    }
});

credElevenSave.addEventListener('click', async () => {
    const key = credElevenKey.value.trim();
    const voiceId = credElevenVoice.value;
    const customId = credElevenCustom.value.trim();
    credElevenSave.disabled = true;
    try {
        if (key) await rpc('credentials.set_voice_key', { key });
        await rpc('credentials.set_voice_id', { id: voiceId });
        await rpc('credentials.set_voice_custom_id', { id: customId });
        credElevenKey.value = '';
        flash(t('cred.status.saved'));
        credElevenStatus.textContent = t('cred.status.saved');
    } catch (e) {
        credElevenStatus.textContent = t('cred.status.failed', e.message);
    } finally {
        credElevenSave.disabled = false;
    }
});

credNtfySave.addEventListener('click', async () => {
    const topic = credNtfyTopic.value.trim();
    credNtfySave.disabled = true;
    try {
        await rpc('credentials.set_ntfy_topic', { topic });
        flash(t('cred.status.saved'));
        credNtfyStatus.textContent = t('cred.status.saved');
    } catch (e) {
        credNtfyStatus.textContent = t('cred.status.failed', e.message);
    } finally {
        credNtfySave.disabled = false;
    }
});


// ============== boot ==============
// ============== media capture view ==============

const Media = (() => {
    const grid = () => document.getElementById('media-grid');
    const empty = () => document.getElementById('media-empty');
    const statsText = () => document.getElementById('media-stats-text');
    const snapBtn = () => document.getElementById('media-snap');
    const recordBtn = () => document.getElementById('media-record');
    const recordLabel = () => recordBtn().querySelector('.rec-label');
    const clearBtn = () => document.getElementById('media-clear-all');

    let items = [];
    let recordingTicker = null;
    let clearConfirm = false;
    let clearConfirmTimer = null;
    let bound = false;

    function relTime(takenAt) {
        const diff = Date.now() - takenAt;
        if (diff < 60_000) return 'just now';
        if (diff < 3_600_000) return Math.floor(diff / 60_000) + 'm ago';
        if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + 'h ago';
        const d = new Date(takenAt);
        return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    }

    function formatBytes(n) {
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        return (n / 1024 / 1024).toFixed(1) + ' MB';
    }

    function renderStats(totalBytes) {
        statsText().textContent = `${items.length} ${t('media.statsItems')} · ${formatBytes(totalBytes)}`;
        clearBtn().style.display = items.length ? '' : 'none';
    }

    function renderGrid() {
        empty().style.display = items.length ? 'none' : '';
        grid().innerHTML = '';
        items.forEach((item) => {
            const tile = document.createElement('div');
            tile.className = 'media-tile';
            tile.dataset.name = item.name;

            const img = document.createElement('img');
            img.loading = 'lazy';
            img.src = item.thumbUrl;
            img.alt = item.name;
            tile.appendChild(img);

            const meta = document.createElement('div');
            meta.className = 'media-tile-meta';
            const kind = document.createElement('span');
            kind.className = 'media-tile-kind';
            kind.textContent = item.kind === 'video' ? 'MP4' : 'PNG';
            const when = document.createElement('span');
            when.textContent = relTime(item.takenAt);
            meta.appendChild(kind);
            meta.appendChild(when);
            tile.appendChild(meta);

            const del = document.createElement('button');
            del.className = 'media-tile-delete';
            del.textContent = '×';
            del.dataset.confirm = '0';
            del.addEventListener('click', (e) => {
                e.stopPropagation();
                if (del.dataset.confirm === '1') {
                    rpc('capture.delete', { name: item.name }).then(refresh).catch(showErr);
                } else {
                    del.dataset.confirm = '1';
                    del.classList.add('confirm');
                    setTimeout(() => {
                        del.dataset.confirm = '0';
                        del.classList.remove('confirm');
                    }, 2000);
                }
            });
            tile.appendChild(del);

            tile.addEventListener('click', () => Lightbox.open(item));
            grid().appendChild(tile);
        });
    }

    function refresh() {
        return rpc('capture.list', { limit: 100 }).then((payload) => {
            items = (payload && payload.items) || [];
            renderGrid();
            renderStats((payload && payload.totalBytes) || 0);
        }).catch((e) => console.warn('media refresh failed', e));
    }

    function setRecordingUi(recording, startedAt) {
        if (recording) {
            recordBtn().classList.add('recording');
            snapBtn().disabled = true;
            tickRecording(startedAt);
            if (recordingTicker) clearInterval(recordingTicker);
            recordingTicker = setInterval(() => tickRecording(startedAt), 1000);
        } else {
            recordBtn().classList.remove('recording');
            snapBtn().disabled = false;
            recordLabel().textContent = t('media.record');
            if (recordingTicker) { clearInterval(recordingTicker); recordingTicker = null; }
        }
    }

    function tickRecording(startedAt) {
        const elapsed = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
        const mm = String(Math.floor(elapsed / 60)).padStart(2, '0');
        const ss = String(elapsed % 60).padStart(2, '0');
        const remain = 180 - elapsed;
        let label = `${t('media.stop')} (${mm}:${ss})`;
        if (remain <= 5 && remain > 0) {
            label += ` — ${t('media.autoStop')} ${String(remain).padStart(2, '0')}s`;
        }
        recordLabel().textContent = label;
    }

    function onCaptureAdded(payload) {
        if (!payload) return;
        items = [payload, ...items.filter((it) => it.name !== payload.name)];
        renderGrid();
        // Stats refresh is best-effort; the snapshot tick will correct it.
        rpc('capture.list', { limit: 0 })
            .then((p) => renderStats((p && p.totalBytes) || 0))
            .catch(() => {});
    }

    function onCaptureRecording(payload) {
        if (!payload) return;
        setRecordingUi(!!payload.recording, payload.startedAt || 0);
    }

    function onSnapshot(media) {
        if (!media) return;
        const uiRecording = recordBtn().classList.contains('recording');
        if (!!media.recording !== uiRecording) {
            setRecordingUi(!!media.recording, media.startedAt || 0);
        }
    }

    function bind() {
        if (bound) return;
        bound = true;

        snapBtn().addEventListener('click', async () => {
            snapBtn().disabled = true;
            try {
                await rpc('capture.screenshot', {});
                // capture.added event prepends the tile.
            } catch (e) {
                flash(`${t('media.failedCapture')} — ${e.message || ''}`, true);
            } finally {
                snapBtn().disabled = false;
            }
        });

        recordBtn().addEventListener('click', async () => {
            const isRecording = recordBtn().classList.contains('recording');
            try {
                if (isRecording) {
                    await rpc('capture.stopVideo', {});
                } else {
                    await rpc('capture.startVideo', {});
                }
            } catch (e) {
                const msg = e.message || '';
                const tag = msg.startsWith('free=') ? t('media.lowStorage') : t('media.failedRecord');
                flash(`${tag} — ${msg}`, true);
            }
        });

        clearBtn().addEventListener('click', () => {
            if (clearConfirm) {
                rpc('capture.clear', {}).then(refresh).catch(showErr);
                clearConfirm = false;
                clearBtn().classList.remove('confirm');
                clearBtn().textContent = t('media.clearAll');
                if (clearConfirmTimer) { clearTimeout(clearConfirmTimer); clearConfirmTimer = null; }
            } else {
                clearConfirm = true;
                clearBtn().classList.add('confirm');
                clearBtn().textContent = t('media.confirmClear');
                if (clearConfirmTimer) clearTimeout(clearConfirmTimer);
                clearConfirmTimer = setTimeout(() => {
                    clearConfirm = false;
                    clearBtn().classList.remove('confirm');
                    clearBtn().textContent = t('media.clearAll');
                }, 2000);
            }
        });
    }

    return { bind, refresh, onCaptureAdded, onCaptureRecording, onSnapshot };
})();

// Lightbox stub — real impl in Task 11. Defined here so Media.renderGrid's
// tile click handler resolves at parse time even before Task 11 lands.
const Lightbox = (() => {
    return {
        open: () => {},
        close: () => {},
        bind: () => {},
    };
})();

setView('home');
renderUnlockDots();
Media.bind();
Lightbox.bind();
if (panelToken) {
    // We already have a token (sessionStorage or legacy `?t=` URL) — hide
    // the overlay and dive straight into the live UI. If the token turns
    // out to be stale, the WS close handler (code 1008) will re-surface
    // the unlock prompt automatically.
    hideUnlock();
    connect();
} else {
    showUnlock();
}
})();
