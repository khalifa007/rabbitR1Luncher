/* ====================================================================
 * r1 // remote — companion web app
 *
 * Single-page launcher-style UI: home view shows clock + apps grid;
 * tapping a tile reveals one of the sub-views (claude code, terminal,
 * sms, send text, system). Back-pill returns to home. RPC over the
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
    const url = `${proto}://${location.host}/api/rpc`;
    setConn('connecting');
    ws = new WebSocket(url);
    ws.addEventListener('open', () => {
        setConn('live');
        reconnectDelay = 500;
    });
    ws.addEventListener('close', () => {
        setConn('error');
        ws = null;
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
    if (name === 'sms') refreshSmsList();
    if (name === 'terminal') refreshTerminalHistory();
    if (name === 'claude') {
        refreshClaudeHistory();
        // Auth status decides whether the login banner shows above the
        // chat — refresh on every entry so creds dropped via adb show up
        // immediately the next time the view opens.
        if (typeof refreshClaudeAuth === 'function') refreshClaudeAuth();
        // Drop focus into the textarea so the user can start typing immediately.
        setTimeout(() => document.getElementById('claude-input')?.focus(), 220);
    }
    if (name === 'meetings') refreshMeetings();
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
                + '<a href="/api/transcriber/audio/' + m.uuid + '.m4a" class="primary-btn" download>audio</a>'
                + '<a href="/api/transcriber/transcript/' + m.uuid + '.txt" class="primary-btn" download>transcript</a>'
                + '<a href="/api/transcriber/transcript/' + m.uuid + '.json" class="primary-btn" download>json</a>'
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
    else if (event === 'claude.message') appendClaudeMessage(payload);
    else if (event === 'claude.streaming') applyClaudeStreaming(payload);
    else if (event === 'claude.busy') applyClaudeBusy(payload);
    else if (event === 'claude.cleared') clearClaudeMessages();
    else if (event === 'claude.setup.progress') appendSetupLog(payload.line || '');
    else if (event === 'claude.setup.done') applyClaudeSetupDone(payload);
}

function applyClaudeSetupDone(payload) {
    const ok = !!(payload && payload.ok);
    if (setupStartBtn) setupStartBtn.disabled = false;
    setSetupStatus(
        ok ? t('claude.setup.success') : t('claude.setup.failed'),
        ok ? 'ok' : 'err',
    );
    // Re-pull auth status so the UI flips from setup → login pane.
    if (ok) setTimeout(() => refreshClaudeAuth(), 600);
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

// ============== sms ==============
let smsActiveAddr = null;
async function refreshSmsList() {
    try {
        const list = await rpc('sms.list');
        const ul = document.getElementById('sms-threads');
        ul.innerHTML = '';
        if (!Array.isArray(list) || list.length === 0) {
            const li = document.createElement('li');
            li.style.cssText = 'cursor:default;color:var(--muted)';
            li.textContent = t('sms.empty');
            ul.appendChild(li);
            return;
        }
        list.forEach((c) => {
            const li = document.createElement('li');
            const date = new Date(c.latestTimestampMs);
            li.innerHTML = `
                <div class="sms-from">${escapeHtml(c.name || c.address)}</div>
                <div class="sms-preview">${escapeHtml((c.latestBody || '').slice(0, 80))}</div>
                <div class="sms-preview" style="margin-top:4px">
                    ${escapeHtml(date.toLocaleString())}
                    ${c.unreadCount > 0 ? `· <span style="color:var(--accent)">●${c.unreadCount}</span>` : ''}
                </div>
            `;
            li.addEventListener('click', () => {
                document.querySelectorAll('#sms-threads li').forEach((x) => x.classList.remove('active'));
                li.classList.add('active');
                openSmsThread(c.address, c.name);
            });
            ul.appendChild(li);
        });
    } catch (e) { console.warn(e); }
}
async function openSmsThread(address, name) {
    smsActiveAddr = address;
    document.getElementById('sms-thread-header').textContent = name || address;
    const body = document.getElementById('sms-thread-body');
    body.innerHTML = '';
    const loadingSpan = document.createElement('span');
    loadingSpan.style.color = 'var(--muted)';
    loadingSpan.textContent = t('sms.loading');
    body.appendChild(loadingSpan);
    try {
        const items = await rpc('sms.thread', { address });
        body.innerHTML = '';
        if (!items.length) {
            const empty = document.createElement('span');
            empty.style.color = 'var(--muted)';
            empty.textContent = t('sms.threadEmpty');
            body.appendChild(empty);
            return;
        }
        items.forEach((m) => {
            const div = document.createElement('div');
            div.className = 'sms-msg ' + (m.incoming ? 'in' : 'out');
            const time = new Date(m.timestampMs).toLocaleString();
            div.innerHTML = `
                <div>${escapeHtml(m.body)}</div>
                <div class="sms-time">${escapeHtml(time)}</div>
            `;
            body.appendChild(div);
        });
        body.scrollTop = body.scrollHeight;
    } catch (e) { body.textContent = 'failed: ' + e.message; }
}
document.getElementById('sms-refresh').addEventListener('click', refreshSmsList);

// ============== send text ==============
const sendText = document.getElementById('send-text');
const sendTarget = document.getElementById('send-target');
const sendHint = document.getElementById('send-hint');
const sendBtn = document.getElementById('send-button');

function updateSendHint() {
    const text = sendText.value;
    if (sendTarget.value === 'voice_key') {
        const v = text.trim();
        if (!v) sendHint.textContent = t('send.hintEmpty');
        else if (v.startsWith('sk_') && v.length >= 32) sendHint.textContent = t('send.hintValid');
        else if (/^[0-9a-fA-F]{32}$/.test(v)) sendHint.textContent = t('send.hintValid');
        else sendHint.textContent = t('send.hintInvalid');
    } else if (sendTarget.value === 'voice_custom_id') {
        const v = text.trim();
        if (!v) sendHint.textContent = t('send.hintCustomIdEmpty');
        // ElevenLabs voice_id is a 20-char alphanumeric token. Accept anything
        // close to that shape; let the server reject the rest at synth time.
        else if (/^[A-Za-z0-9]{16,32}$/.test(v)) sendHint.textContent = t('send.hintValid');
        else sendHint.textContent = t('send.hintCustomIdInvalid');
    } else {
        sendHint.textContent = text ? t('send.hintChars', text.length) : t('send.hintTypeMsg');
    }
}
sendText.addEventListener('input', updateSendHint);
sendTarget.addEventListener('change', updateSendHint);
sendBtn.addEventListener('click', async () => {
    const text = sendText.value;
    if (!text) return;
    sendBtn.disabled = true;
    try {
        await rpc('text.send', { target: sendTarget.value, text });
        sendHint.textContent = t('send.sent');
        sendText.value = '';
        flash(t('send.sentToR1'));
    } catch (e) {
        sendHint.textContent = 'failed: ' + e.message;
        showErr(e);
    } finally {
        sendBtn.disabled = false;
    }
});
updateSendHint();

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

// ============== claude code ==============
const claudeMessages = document.getElementById('claude-messages');
const claudeForm = document.getElementById('claude-form');
const claudeInput = document.getElementById('claude-input');
const claudeSendBtn = document.getElementById('claude-send');
const claudeClearBtn = document.getElementById('claude-clear');
let claudeStreamingEl = null;

function escapeHtmlMd(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function renderMarkdown(src) {
    if (!src) return '';
    const codeBlocks = [];
    let s = src.replace(/```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)```/g, (_, lang, code) => {
        const id = codeBlocks.length;
        codeBlocks.push({ lang: lang || '', code: code.replace(/\n+$/, '') });
        return ` CODEBLOCK${id} `;
    });
    s = escapeHtmlMd(s);
    s = s.replace(/`([^`\n]+)`/g, (_, c) => `<code class="md-icode">${c}</code>`);
    s = s.replace(/^###### (.+)$/gm, '<h6 class="md-h">$1</h6>');
    s = s.replace(/^##### (.+)$/gm, '<h5 class="md-h">$1</h5>');
    s = s.replace(/^#### (.+)$/gm, '<h4 class="md-h">$1</h4>');
    s = s.replace(/^### (.+)$/gm, '<h3 class="md-h">$1</h3>');
    s = s.replace(/^## (.+)$/gm, '<h2 class="md-h">$1</h2>');
    s = s.replace(/^# (.+)$/gm, '<h1 class="md-h">$1</h1>');
    s = s.replace(/(^|\n)((?:&gt; .*(?:\n|$))+)/g, (_, lead, block) => {
        const inner = block.replace(/^&gt; ?/gm, '').replace(/\n$/, '');
        return `${lead}<blockquote class="md-quote">${inner}</blockquote>\n`;
    });
    {
        const lines = s.split('\n');
        const out = [];
        let listType = null;
        const flush = () => { if (listType) { out.push(`</${listType}>`); listType = null; } };
        for (const line of lines) {
            const ulMatch = /^[ \t]*[-*] (.+)$/.exec(line);
            const olMatch = /^[ \t]*\d+\. (.+)$/.exec(line);
            if (ulMatch) {
                if (listType !== 'ul') { flush(); out.push('<ul class="md-list">'); listType = 'ul'; }
                out.push(`<li>${ulMatch[1]}</li>`);
            } else if (olMatch) {
                if (listType !== 'ol') { flush(); out.push('<ol class="md-list">'); listType = 'ol'; }
                out.push(`<li>${olMatch[1]}</li>`);
            } else {
                flush();
                out.push(line);
            }
        }
        flush();
        s = out.join('\n');
    }
    s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[\s(])\*([^*\n]+)\*(?=[\s).,!?:;]|$)/g, '$1<em>$2</em>');
    s = s.replace(/__([^_\n]+)__/g, '<strong>$1</strong>');
    s = s.replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, (_, text, url) => {
        const safe = /^(https?:|mailto:|\/|#)/i.test(url) ? url : '#';
        return `<a href="${safe}" target="_blank" rel="noopener noreferrer">${text}</a>`;
    });
    s = s.split(/\n{2,}/).map((chunk) => {
        const trimmed = chunk.trim();
        if (!trimmed) return '';
        if (/^<(h\d|ul|ol|blockquote|pre| CODEBLOCK)/.test(trimmed)) return trimmed;
        return `<p class="md-p">${trimmed.replace(/\n/g, '<br>')}</p>`;
    }).join('\n');
    s = s.replace(/ CODEBLOCK(\d+) /g, (_, id) => {
        const b = codeBlocks[+id];
        const langAttr = b.lang ? ` data-lang="${escapeHtmlMd(b.lang)}"` : '';
        const langLabel = b.lang ? `<span class="md-code-lang">${escapeHtmlMd(b.lang)}</span>` : '';
        return `<div class="md-code-wrap">${langLabel}<pre class="md-code"${langAttr}>${escapeHtmlMd(b.code)}</pre></div>`;
    });
    return s;
}

function bubbleEl(role, text, error) {
    const el = document.createElement('div');
    el.className = 'claude-bubble ' + role + (error ? ' error' : '');
    if (role.startsWith('assistant') && !error) el.innerHTML = renderMarkdown(text);
    else el.textContent = text;
    return el;
}

function appendClaudeMessage(payload) {
    if (!payload || typeof payload.text !== 'string') return;
    if (payload.role === 'assistant' && claudeStreamingEl) {
        claudeStreamingEl.remove();
        claudeStreamingEl = null;
    }
    claudeMessages.appendChild(bubbleEl(payload.role, payload.text, payload.error));
    while (claudeMessages.childElementCount > 200) {
        claudeMessages.removeChild(claudeMessages.firstChild);
    }
    claudeMessages.scrollTop = claudeMessages.scrollHeight;
    // The CLI ships its own "not logged in" message even when a stale
    // .credentials.json exists on disk. claude.auth.status keys off file
    // presence, so it reports authed=true and the banner stays hidden, leaving
    // the user with no recourse. Snap the banner back open whenever the CLI
    // surfaces this so the reset/relogin actions are reachable.
    if (payload.role === 'assistant' && /not logged in|please run \/login/i.test(payload.text || '')) {
        if (authBanner) authBanner.classList.remove('hidden');
        if (setupBanner) setupBanner.classList.add('hidden');
        setAuthStatus(t('claude.auth.reset.done'), 'err');
    }
}

function applyClaudeStreaming(payload) {
    if (!payload) return;
    const text = typeof payload.text === 'string' ? payload.text : '';
    if (!text) {
        if (claudeStreamingEl) { claudeStreamingEl.remove(); claudeStreamingEl = null; }
        return;
    }
    if (!claudeStreamingEl) {
        claudeStreamingEl = bubbleEl('assistant streaming', text, false);
        claudeMessages.appendChild(claudeStreamingEl);
    } else {
        claudeStreamingEl.innerHTML = renderMarkdown(text);
    }
    claudeMessages.scrollTop = claudeMessages.scrollHeight;
}

function applyClaudeBusy(payload) {
    const busy = !!(payload && payload.busy);
    claudeSendBtn.disabled = busy;
    claudeSendBtn.textContent = busy ? '…' : t('claude.send');
    // Update the per-app status text in the claude header.
    const status = document.querySelector('#view-claude .app-status');
    if (status) status.textContent = busy ? t('claude.thinking') : '';
}

function clearClaudeMessages() {
    claudeMessages.innerHTML = '';
    claudeStreamingEl = null;
}

async function refreshClaudeHistory() {
    try {
        const h = await rpc('claude.history');
        clearClaudeMessages();
        if (h && Array.isArray(h.messages)) {
            h.messages.forEach((m) => {
                claudeMessages.appendChild(bubbleEl(m.role, m.text, m.error));
            });
        }
        if (h && h.streaming) applyClaudeStreaming({ text: h.streaming });
        applyClaudeBusy({ busy: !!(h && h.busy) });
        claudeMessages.scrollTop = claudeMessages.scrollHeight;
    } catch (err) { showErr(err); }
}

claudeForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = claudeInput.value.trim();
    if (!text) return;
    claudeInput.value = '';
    try { await rpc('claude.send', { text }); } catch (err) { showErr(err); }
});

claudeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        claudeForm.requestSubmit();
    }
});

claudeClearBtn.addEventListener('click', async () => {
    try { await rpc('claude.clear'); clearClaudeMessages(); } catch (err) { showErr(err); }
});

// ============== claude auth ==============
// The banner is hidden until refreshClaudeAuth() decides we need it. Claude
// is usable as long as either OAuth or API-key creds are present.
const authBanner    = document.getElementById('claude-auth-banner');
const authStartBtn  = document.getElementById('claude-auth-start');
const authUrlRow    = document.getElementById('claude-auth-url-row');
const authUrlLink   = document.getElementById('claude-auth-url');
const authCodeInput = document.getElementById('claude-auth-code');
const authSubmitBtn = document.getElementById('claude-auth-submit');
const authKeyInput  = document.getElementById('claude-auth-key');
const authKeyBtn    = document.getElementById('claude-auth-key-submit');
const authStatusEl  = document.getElementById('claude-auth-status');
const authResetBtn  = document.getElementById('claude-auth-reset');
const authVerifyBtn = document.getElementById('claude-auth-verify');
const authVerifyLog = document.getElementById('claude-auth-verify-log');

function setAuthStatus(msg, kind) {
    if (!authStatusEl) return;
    authStatusEl.textContent = msg || '';
    authStatusEl.classList.remove('ok', 'err');
    if (kind) authStatusEl.classList.add(kind);
}

// Tab switching between OAuth + API-key panes.
document.querySelectorAll('.claude-auth-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
        const target = tab.dataset.authTab;
        document.querySelectorAll('.claude-auth-tab').forEach(
            (t) => t.classList.toggle('active', t === tab),
        );
        document.querySelectorAll('.claude-auth-pane').forEach(
            (p) => p.classList.toggle('active', p.dataset.authPane === target),
        );
        setAuthStatus('');
    });
});

// Setup banner refs — populated below; auth refresh keys off them too.
const setupBanner   = document.getElementById('claude-setup-banner');
const setupStartBtn = document.getElementById('claude-setup-start');
const setupLogEl    = document.getElementById('claude-setup-log');
const setupStatusEl = document.getElementById('claude-setup-status');

function setSetupStatus(msg, kind) {
    if (!setupStatusEl) return;
    setupStatusEl.textContent = msg || '';
    setupStatusEl.classList.remove('ok', 'err');
    if (kind) setupStatusEl.classList.add(kind);
}

function appendSetupLog(line) {
    if (!setupLogEl) return;
    setupLogEl.classList.remove('hidden');
    // Each line is its own <span> so we can color phase headers, failures,
    // and the final DONE marker without spending a CSS rule per pattern.
    // textContent on the span guards against any weird control bytes the
    // chroot's tar/apk output might emit.
    const span = document.createElement('span');
    span.className = 'log-line ' + classifyLogLine(line);
    span.textContent = (setupLogEl.childElementCount ? '\n' : '') + line;
    setupLogEl.appendChild(span);
    // Cap at 400 lines so a verbose install (apk index, npm) doesn't grow
    // the DOM unbounded — drop oldest spans, not by truncating textContent
    // (which would strip our class colorization).
    while (setupLogEl.childElementCount > 400) {
        setupLogEl.removeChild(setupLogEl.firstChild);
    }
    setupLogEl.scrollTop = setupLogEl.scrollHeight;
}

function classifyLogLine(line) {
    if (!line) return '';
    if (line.startsWith('--- ')) return 'log-phase';
    if (line.startsWith('[FAIL]') || /\b(error|failed|fatal)\b/i.test(line)) return 'log-fail';
    if (/\b(DONE|installed|success|OK)\b/i.test(line)) return 'log-ok';
    if (line.startsWith('[r1-')) return 'log-info';
    return '';
}

async function refreshClaudeAuth() {
    if (!authBanner) return;
    try {
        const s = await rpc('claude.auth.status');
        const setupRunning = await rpc('claude.setup.status').catch(() => ({ running: false }));
        if (s && !s.chrootReady) {
            // Chroot missing — show the setup banner instead of the auth one.
            // The login flow has nothing to talk to until alpine is in place.
            authBanner.classList.add('hidden');
            setupBanner.classList.remove('hidden');
            if (setupStartBtn) {
                setupStartBtn.disabled = !!setupRunning.running;
                if (setupRunning.running) setSetupStatus(t('claude.setup.running'));
            }
        } else {
            // Chroot is ready — hide setup, decide whether to show login.
            setupBanner.classList.add('hidden');
            const authed = !!(s && (s.hasOAuth || s.hasApiKey));
            authBanner.classList.toggle('hidden', authed);
            if (authStartBtn) authStartBtn.disabled = false;
            if (authKeyBtn) authKeyBtn.disabled = false;
        }
    } catch (err) {
        // Don't blow up the whole Claude view if status fails — just leave
        // the banner in its current state and surface the error.
        showErr(err);
    }
}

setupStartBtn?.addEventListener('click', async () => {
    setupStartBtn.disabled = true;
    setupLogEl.textContent = '';
    setupLogEl.classList.remove('hidden');
    setSetupStatus(t('claude.setup.running'));
    try {
        await rpc('claude.setup.start');
        // Server now streams `claude.setup.progress` + `claude.setup.done`
        // events; appendSetupLog handles them. Don't re-enable the button
        // here — the done event flips state.
    } catch (err) {
        setSetupStatus(String(err?.message || err), 'err');
        setupStartBtn.disabled = false;
    }
});

authStartBtn?.addEventListener('click', async () => {
    setAuthStatus(t('claude.auth.oauth.starting'));
    authStartBtn.disabled = true;
    try {
        const r = await rpc('claude.auth.start');
        if (r && r.url) {
            authUrlLink.href = r.url;
            authUrlLink.textContent = r.url;
            authUrlRow.classList.remove('hidden');
            setAuthStatus('');
            authCodeInput.focus();
        } else {
            setAuthStatus(r?.error || 'no url returned', 'err');
        }
    } catch (err) {
        setAuthStatus(String(err?.message || err), 'err');
    } finally {
        authStartBtn.disabled = false;
    }
});

authSubmitBtn?.addEventListener('click', async () => {
    const code = (authCodeInput.value || '').trim();
    if (!code) {
        setAuthStatus(t('claude.auth.oauth.empty'), 'err');
        authCodeInput.focus();
        return;
    }
    setAuthStatus(t('claude.auth.oauth.submitting'));
    authSubmitBtn.disabled = true;
    try {
        const r = await rpc('claude.auth.finish', { code });
        if (r && r.ok) {
            setAuthStatus(t('claude.auth.success'), 'ok');
            // Hide the banner; claude.send is now usable.
            setTimeout(() => {
                authBanner.classList.add('hidden');
                authCodeInput.value = '';
            }, 800);
        } else {
            setAuthStatus(r?.error || 'finish failed', 'err');
        }
    } catch (err) {
        setAuthStatus(String(err?.message || err), 'err');
    } finally {
        authSubmitBtn.disabled = false;
    }
});

authVerifyBtn?.addEventListener('click', async () => {
    authVerifyBtn.disabled = true;
    authVerifyLog.classList.remove('hidden');
    authVerifyLog.textContent = '';
    setAuthStatus(t('claude.auth.verify.running'));
    try {
        const r = await rpc('claude.auth.verify');
        authVerifyLog.textContent = (r && r.log ? r.log : '').trim() || '(no output)';
        if (r && r.ok) {
            setAuthStatus(t('claude.auth.verify.ok'), 'ok');
        } else {
            setAuthStatus(r?.error || t('claude.auth.verify.fail'), 'err');
        }
    } catch (err) {
        setAuthStatus(String(err?.message || err), 'err');
    } finally {
        authVerifyBtn.disabled = false;
    }
});

authResetBtn?.addEventListener('click', async () => {
    // Two-step confirm so a stray click doesn't blow away a working session.
    if (authResetBtn.dataset.confirm !== '1') {
        authResetBtn.dataset.confirm = '1';
        const original = authResetBtn.textContent;
        authResetBtn.textContent = t('claude.auth.reset.confirm');
        setTimeout(() => {
            if (authResetBtn.dataset.confirm === '1') {
                authResetBtn.dataset.confirm = '';
                authResetBtn.textContent = original;
            }
        }, 4000);
        return;
    }
    authResetBtn.dataset.confirm = '';
    authResetBtn.disabled = true;
    setAuthStatus(t('claude.auth.reset.running'));
    try {
        const r = await rpc('claude.auth.reset');
        if (r && r.ok) {
            setAuthStatus(t('claude.auth.reset.done'), 'ok');
            authUrlRow.classList.add('hidden');
            authUrlLink.href = '#';
            authUrlLink.textContent = '—';
            authCodeInput.value = '';
            authKeyInput.value = '';
            // Force the banner open even if the status RPC race lags behind.
            authBanner.classList.remove('hidden');
            await refreshClaudeAuth();
        } else {
            setAuthStatus(t('claude.auth.reset.failed'), 'err');
        }
    } catch (err) {
        setAuthStatus(String(err?.message || err), 'err');
    } finally {
        authResetBtn.disabled = false;
        authResetBtn.textContent = t('claude.auth.reset');
    }
});

authKeyBtn?.addEventListener('click', async () => {
    const key = (authKeyInput.value || '').trim();
    if (!key) {
        setAuthStatus(t('claude.auth.key.empty'), 'err');
        authKeyInput.focus();
        return;
    }
    if (!key.startsWith('sk-ant-')) {
        setAuthStatus(t('claude.auth.key.bad'), 'err');
        return;
    }
    authKeyBtn.disabled = true;
    try {
        const r = await rpc('claude.auth.api_key', { key });
        if (r && r.ok) {
            setAuthStatus(t('claude.auth.success'), 'ok');
            setTimeout(() => {
                authBanner.classList.add('hidden');
                authKeyInput.value = '';
            }, 800);
        } else {
            setAuthStatus('save failed', 'err');
        }
    } catch (err) {
        setAuthStatus(String(err?.message || err), 'err');
    } finally {
        authKeyBtn.disabled = false;
    }
});

// ============== surveyor ==============
const sv = {
    surveys: [],
    campaigns: [],
    calls: [],
    settings: null,
    detailRecord: null,
};

const Q_TYPES = [
    { id: 'open', label: 'open' },
    { id: 'multi_choice', label: 'multi choice' },
    { id: 'yes_no', label: 'yes / no' },
    { id: 'number', label: 'number' },
    { id: 'rating_1_5', label: 'rating 1–5' },
];

function svTab(name) {
    document.querySelectorAll('.surveyor-tab').forEach((t) =>
        t.classList.toggle('active', t.dataset.surveyTab === name));
    document.querySelectorAll('.surveyor-pane').forEach((p) =>
        p.classList.toggle('active', p.dataset.surveyPane === name));
    if (name === 'surveys') { refreshSurveys(); }
    else if (name === 'campaigns') { refreshSurveys().then(refreshCampaigns); }
    else if (name === 'calls') { refreshCalls(); refreshLiveCall(); }
    else if (name === 'settings') { refreshSurveySettings(); }
}

document.querySelectorAll('.surveyor-tab').forEach((tab) => {
    tab.addEventListener('click', () => svTab(tab.dataset.surveyTab));
});

async function refreshSurveys() {
    try {
        sv.surveys = await rpc('survey.list');
    } catch (e) { console.warn('survey.list', e); sv.surveys = []; }
    // Render survey list panel.
    const list = document.getElementById('sv-survey-list');
    if (list) {
        if (sv.surveys.length === 0) {
            list.innerHTML = '<p style="color:#888;font-size:11px;">no surveys yet — author one above.</p>';
        } else {
            list.innerHTML = sv.surveys.map((s) =>
                '<div class="sv-row">'
                + '<span class="sv-row-title">' + escapeHtml(s.name) + '</span>'
                + '<span class="sv-row-sub">' + s.questionCount + ' questions · '
                    + fmtTimestamp(s.updatedAtMs) + '</span>'
                + '<div class="sv-row-actions">'
                + '<button class="primary-btn" type="button" data-sv-edit="' + s.id + '">edit</button>'
                + '<button class="primary-btn" type="button" data-sv-delete="' + s.id + '">delete</button>'
                + '</div></div>'
            ).join('');
        }
    }
    // Update campaign-create dropdown.
    const dd = document.getElementById('sv-camp-survey');
    if (dd) {
        const cur = dd.value;
        dd.innerHTML = sv.surveys.map((s) =>
            '<option value="' + escapeHtml(s.id) + '">' + escapeHtml(s.name) + '</option>'
        ).join('') || '<option value="">(no surveys — author one first)</option>';
        if (cur) dd.value = cur;
    }
}

async function refreshCampaigns() {
    try { sv.campaigns = await rpc('campaign.list'); }
    catch (e) { console.warn('campaign.list', e); sv.campaigns = []; }
    const list = document.getElementById('sv-camp-list');
    if (!list) return;
    if (sv.campaigns.length === 0) {
        list.innerHTML = '<p style="color:#888;font-size:11px;">no campaigns yet.</p>';
        return;
    }
    list.innerHTML = sv.campaigns.map((c) => {
        const progress = c.nextContactIdx + '/' + c.contactCount;
        return '<div class="sv-row">'
            + '<span class="sv-row-title">' + escapeHtml(c.surveyName) + ' — '
                + c.status + '</span>'
            + '<span class="sv-row-sub">' + progress + ' contacts · '
                + fmtTimestamp(c.createdAtMs) + '</span>'
            + '<div class="sv-row-actions">'
            + (c.status === 'pending'
                ? '<button class="primary-btn" type="button" data-sv-camp-start="' + c.id + '">start</button>'
                : '')
            + (c.status === 'running' || c.status === 'pending'
                ? '<button class="primary-btn" type="button" data-sv-camp-cancel="' + c.id + '">cancel</button>'
                : '')
            + '<button class="primary-btn" type="button" data-sv-camp-calls="' + c.id + '">view calls</button>'
            + '</div></div>';
    }).join('');
}

async function refreshCalls(campaignFilter) {
    try {
        sv.calls = await rpc('call.list',
            campaignFilter ? { campaignId: campaignFilter } : null);
    } catch (e) { console.warn('call.list', e); sv.calls = []; }
    const list = document.getElementById('sv-calls-list');
    if (!list) return;
    if (sv.calls.length === 0) {
        list.innerHTML = '<p style="color:#888;font-size:11px;">no calls yet.</p>';
        return;
    }
    list.innerHTML = sv.calls.map((c) =>
        '<div class="sv-row">'
        + '<span class="sv-row-title">'
            + escapeHtml(c.contactName || c.contactPhone) + ' · ' + c.status + '</span>'
        + '<span class="sv-row-sub">' + fmtTimestamp(c.createdAtMs)
            + ' · ' + fmtMmSs(c.durationMs) + '</span>'
        + '<div class="sv-row-actions">'
        + '<button class="primary-btn" type="button" data-sv-call-open="' + c.id + '">open</button>'
        + '<button class="primary-btn" type="button" data-sv-call-email="' + c.id + '">email</button>'
        + '<button class="primary-btn" type="button" data-sv-call-summary="' + c.id + '">re-summary</button>'
        + '<a class="primary-btn" href="/api/survey/audio/' + c.campaignId + '/' + c.id + '.wav" download>audio</a>'
        + '<button class="primary-btn" type="button" data-sv-call-delete="' + c.id + '">delete</button>'
        + '</div></div>'
    ).join('');
}

async function refreshLiveCall() {
    let live = null;
    try { live = await rpc('survey.live.state'); } catch (e) { return; }
    const pane = document.getElementById('sv-live-pane');
    if (!pane) return;
    if (!live || !live.active) {
        pane.innerHTML = 'no call in progress.';
        return;
    }
    pane.innerHTML =
        '<div class="sv-live-status">' + escapeHtml(live.status || '—') + '</div>'
        + 'contact: <strong>' + escapeHtml(live.contactName || '(unknown)') + '</strong><br>'
        + 'elapsed: ' + fmtMmSs(live.elapsedMs) + '<br>'
        + (live.currentQuestion
            ? 'current question: ' + escapeHtml(live.currentQuestion)
            : '');
}

async function openCallDetail(callId) {
    let rec;
    try { rec = await rpc('call.get', { id: callId }); }
    catch (e) { return alert('load failed: ' + e.message); }
    if (!rec) return;
    sv.detailRecord = rec;
    const card = document.getElementById('sv-call-detail');
    const body = document.getElementById('sv-call-detail-body');
    if (!card || !body) return;
    card.classList.remove('hidden');
    const answersHtml = Object.keys(rec.answers || {}).length === 0
        ? '<p style="color:#888;font-size:11px;">no answers captured.</p>'
        : Object.entries(rec.answers).map(([k, v]) =>
            '<div style="font-size:11px;color:#aaa;">'
            + '<span style="color:#FF6A00;">' + escapeHtml(k) + '</span> → '
            + escapeHtml(String(v)) + '</div>'
        ).join('');
    body.innerHTML =
        '<div style="font-size:12px;color:#aaa;">'
        + 'contact: <strong>' + escapeHtml(rec.contact?.name || rec.contact?.phone || '?') + '</strong> · '
        + rec.status + (rec.endReason ? ' · ' + rec.endReason : '')
        + ' · ' + fmtMmSs(rec.durationMs) + '</div>'
        + (rec.summary ? '<p style="color:#e8e8eb;font-size:12px;margin:6px 0;">' + escapeHtml(rec.summary) + '</p>' : '')
        + (rec.sentiment ? '<div style="font-size:11px;color:#888;">sentiment: ' + escapeHtml(rec.sentiment)
            + ' · completeness: ' + Math.round((rec.completeness || 0) * 100) + '%</div>' : '')
        + '<h4 style="color:#FF6A00;font-size:13px;margin:8px 0 4px;">answers</h4>' + answersHtml
        + (rec.transcript
            ? '<h4 style="color:#FF6A00;font-size:13px;margin:8px 0 4px;">transcript</h4>'
              + '<pre class="sv-transcript">' + escapeHtml(rec.transcript) + '</pre>'
            : '');
}

// ---- survey editor ----
function addQuestionRow(q) {
    const wrap = document.getElementById('sv-questions');
    if (!wrap) return;
    const idx = wrap.children.length;
    const qid = q?.id || ('q_' + (idx + 1));
    const node = document.createElement('div');
    node.className = 'sv-q';
    const optHtml = Q_TYPES.map((t) =>
        '<option value="' + t.id + '"' + (q?.type === t.id ? ' selected' : '') + '>' + t.label + '</option>'
    ).join('');
    node.innerHTML =
        '<div class="sv-q-row">'
        + '<span class="sv-q-id">' + escapeHtml(qid) + '</span>'
        + '<input type="text" class="sv-q-prompt" placeholder="prompt — e.g. how did you hear about us?" value="'
            + escapeHtml(q?.prompt || '') + '">'
        + '<select class="sv-q-type">' + optHtml + '</select>'
        + '<button class="sv-q-rm" type="button">remove</button>'
        + '</div>'
        + '<input type="text" class="sv-q-choices" placeholder="choices (comma-separated · multi-choice only)" value="'
            + escapeHtml((q?.choices || []).join(', ')) + '">'
        + '<input type="text" class="sv-q-hint" placeholder="follow-up hint (optional)" value="'
            + escapeHtml(q?.followUpHint || '') + '">';
    node.dataset.qid = qid;
    node.querySelector('.sv-q-rm').addEventListener('click', () => node.remove());
    wrap.appendChild(node);
}

function readSurveyForm() {
    const id = document.getElementById('sv-survey-id').value;
    const name = document.getElementById('sv-survey-name').value.trim();
    const consentText = document.getElementById('sv-survey-consent').value.trim();
    const systemInstructions = document.getElementById('sv-survey-sysinst').value.trim();
    const questions = Array.from(document.querySelectorAll('#sv-questions .sv-q'))
        .map((row) => ({
            id: row.dataset.qid,
            prompt: row.querySelector('.sv-q-prompt').value.trim(),
            type: row.querySelector('.sv-q-type').value,
            choices: row.querySelector('.sv-q-choices').value
                .split(',').map((s) => s.trim()).filter(Boolean),
            followUpHint: row.querySelector('.sv-q-hint').value.trim() || null,
        }))
        .filter((q) => q.prompt);
    return {
        id, name,
        consentText: consentText || null,
        systemInstructions: systemInstructions || null,
        questions,
    };
}

function resetSurveyForm() {
    document.getElementById('sv-survey-id').value = '';
    document.getElementById('sv-survey-name').value = '';
    document.getElementById('sv-survey-consent').value = '';
    document.getElementById('sv-survey-sysinst').value = '';
    document.getElementById('sv-questions').innerHTML = '';
    document.getElementById('sv-survey-form-title').textContent = 'new survey';
    addQuestionRow();
}

async function loadSurveyIntoForm(id) {
    let s;
    try { s = await rpc('survey.get', { id }); }
    catch (e) { return alert('load failed: ' + e.message); }
    if (!s) return;
    document.getElementById('sv-survey-id').value = s.id;
    document.getElementById('sv-survey-name').value = s.name || '';
    document.getElementById('sv-survey-consent').value = s.consentText || '';
    document.getElementById('sv-survey-sysinst').value = s.systemInstructions || '';
    document.getElementById('sv-questions').innerHTML = '';
    (s.questions || []).forEach(addQuestionRow);
    document.getElementById('sv-survey-form-title').textContent = 'edit survey · ' + s.name;
    svTab('surveys');
    document.getElementById('sv-survey-name').focus();
}

// ---- campaign authoring ----
function parseContactsTextarea(text) {
    return text.split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line.length > 0)
        .map((line) => {
            // Allow either "name, +phone" or just "+phone".
            const m = line.match(/^(.*?),\s*(.+)$/);
            if (m) return { name: m[1].trim(), phone: m[2].trim(), locale: 'en' };
            return { name: '', phone: line, locale: 'en' };
        })
        .filter((c) => c.phone.length > 0);
}

async function saveCampaign(start) {
    const surveyId = document.getElementById('sv-camp-survey').value;
    if (!surveyId) return alert('pick a survey first.');
    const contacts = parseContactsTextarea(
        document.getElementById('sv-camp-contacts').value);
    if (contacts.length === 0) return alert('no contacts parsed — expected one per line as "name, +phone".');
    try {
        const r = await rpc('campaign.create', { surveyId, contacts });
        if (start && r?.id) await rpc('campaign.start', { id: r.id });
        document.getElementById('sv-camp-contacts').value = '';
        await refreshCampaigns();
        alert(start ? 'campaign created + start signalled.' : 'campaign saved as pending.');
    } catch (e) { alert('save failed: ' + e.message); }
}

// ---- settings ----
async function refreshSurveySettings() {
    let s;
    try { s = await rpc('survey.settings.get'); } catch (e) { return; }
    sv.settings = s;
    const setVal = (id, v) => {
        const el = document.getElementById(id);
        if (el && document.activeElement !== el) el.value = v || '';
    };
    setVal('sv-set-openai', s.hasOpenAiKey ? '••••' + s.openAiKeyTail : '');
    setVal('sv-set-claude', '');
    setVal('sv-set-sip-host', s.sipHost);
    setVal('sv-set-sip-user', s.sipUser);
    setVal('sv-set-sip-pass', s.hasSipCreds ? '••••••••' : '');
    setVal('sv-set-sip-from', s.sipFrom);
    setVal('sv-set-consent', s.consentText);
    setVal('sv-set-recipient', s.emailRecipient);
    // Voice picker
    const voiceSel = document.getElementById('sv-set-voice');
    if (voiceSel) {
        voiceSel.innerHTML = (s.voiceCatalog || []).map((v) =>
            '<option value="' + escapeHtml(v) + '"' + (v === s.voice ? ' selected' : '') + '>' + escapeHtml(v) + '</option>'
        ).join('');
    }
    // Summarizer picker
    const sumSel = document.getElementById('sv-set-summarizer');
    if (sumSel) {
        sumSel.innerHTML = (s.summarizerCatalog || []).map((m) =>
            '<option value="' + escapeHtml(m.id) + '"' + (m.id === s.summarizerModel ? ' selected' : '') + '>'
            + escapeHtml(m.label) + '</option>'
        ).join('');
    }
}

function bindSettingsField(elId, field, opts) {
    const el = document.getElementById(elId);
    if (!el) return;
    el.addEventListener('change', async () => {
        // Don't blindly re-send the masked placeholders — only send if user typed.
        let v = el.value;
        if ((field === 'openai_key' || field === 'claude_key' || field === 'sip_password')
            && v.startsWith('••')) return;
        try { await rpc('survey.settings.set', { field, value: v }); }
        catch (e) { alert('save ' + field + ' failed: ' + e.message); return; }
        // Refresh masked display.
        if (opts?.silent !== true) refreshSurveySettings();
    });
}

bindSettingsField('sv-set-openai', 'openai_key');
bindSettingsField('sv-set-claude', 'claude_key');
bindSettingsField('sv-set-sip-host', 'sip_host');
bindSettingsField('sv-set-sip-user', 'sip_user');
bindSettingsField('sv-set-sip-pass', 'sip_password');
bindSettingsField('sv-set-sip-from', 'sip_from');
bindSettingsField('sv-set-consent', 'consent_text');
bindSettingsField('sv-set-recipient', 'email_recipient');
bindSettingsField('sv-set-voice', 'voice');
bindSettingsField('sv-set-summarizer', 'summarizer_model');

// ---- click delegation for surveyor view ----
document.addEventListener('click', (e) => {
    const t = e.target;
    if (!t || !t.closest) return;
    const editBtn = t.closest('[data-sv-edit]');
    if (editBtn) { loadSurveyIntoForm(editBtn.dataset.svEdit); return; }
    const delSurveyBtn = t.closest('[data-sv-delete]');
    if (delSurveyBtn) {
        if (!confirm('delete this survey? campaigns + past calls keep their copy.')) return;
        rpc('survey.delete', { id: delSurveyBtn.dataset.svDelete }).then(refreshSurveys);
        return;
    }
    const startBtn = t.closest('[data-sv-camp-start]');
    if (startBtn) {
        rpc('campaign.start', { id: startBtn.dataset.svCampStart })
            .then(refreshCampaigns)
            .catch((err) => alert('start failed: ' + err.message));
        return;
    }
    const cancelBtn = t.closest('[data-sv-camp-cancel]');
    if (cancelBtn) {
        rpc('campaign.cancel', { id: cancelBtn.dataset.svCampCancel })
            .then(refreshCampaigns);
        return;
    }
    const callsBtn = t.closest('[data-sv-camp-calls]');
    if (callsBtn) {
        svTab('calls');
        refreshCalls(callsBtn.dataset.svCampCalls);
        return;
    }
    const openCall = t.closest('[data-sv-call-open]');
    if (openCall) { openCallDetail(openCall.dataset.svCallOpen); return; }
    const emailCall = t.closest('[data-sv-call-email]');
    if (emailCall) {
        rpc('call.email', { id: emailCall.dataset.svCallEmail })
            .then(() => alert('queued — check device for delivery status.'))
            .catch((err) => alert('email failed: ' + err.message));
        return;
    }
    const summaryCall = t.closest('[data-sv-call-summary]');
    if (summaryCall) {
        rpc('call.retry_summary', { id: summaryCall.dataset.svCallSummary })
            .then(() => alert('summary requested — check device for status.'))
            .catch((err) => alert('summary failed: ' + err.message));
        return;
    }
    const delCall = t.closest('[data-sv-call-delete]');
    if (delCall) {
        if (!confirm('delete this call record + audio?')) return;
        rpc('call.delete', { id: delCall.dataset.svCallDelete }).then(() => refreshCalls());
        return;
    }
});

document.getElementById('sv-add-q')?.addEventListener('click', () => addQuestionRow());
document.getElementById('sv-reset-survey')?.addEventListener('click', resetSurveyForm);
document.getElementById('sv-survey-refresh')?.addEventListener('click', refreshSurveys);
document.getElementById('sv-camp-refresh')?.addEventListener('click', refreshCampaigns);
document.getElementById('sv-calls-refresh')?.addEventListener('click', () => refreshCalls());

document.getElementById('sv-save-survey')?.addEventListener('click', async () => {
    const body = readSurveyForm();
    if (!body.name) return alert('survey needs a name.');
    if (body.questions.length === 0) return alert('add at least one question.');
    try {
        const method = body.id ? 'survey.update' : 'survey.create';
        const r = await rpc(method, body);
        if (r?.id) {
            document.getElementById('sv-survey-id').value = r.id;
            document.getElementById('sv-survey-form-title').textContent = 'edit survey · ' + body.name;
        }
        await refreshSurveys();
        alert('survey saved.');
    } catch (e) { alert('save failed: ' + e.message); }
});
document.getElementById('sv-camp-save')?.addEventListener('click', () => saveCampaign(false));
document.getElementById('sv-camp-start')?.addEventListener('click', () => saveCampaign(true));

// Initial empty question row when the survey editor first loads.
addQuestionRow();

// Lazy-load survey data when entering the view.
const originalSetView = setView;
setView = function patchedSetView(name) {
    originalSetView(name);
    if (name === 'surveyor') {
        // Default to the campaigns tab on entry.
        svTab('campaigns');
    }
};

// Live events from the SipDialer / orchestrator (task #10 wires these).
const originalHandleEvent = handleEvent;
handleEvent = function patchedHandleEvent(event, payload) {
    originalHandleEvent(event, payload);
    if (event === 'survey.call.state') {
        refreshLiveCall();
    } else if (event === 'survey.call.done') {
        // New finished call — refresh the list if the user is on the calls tab.
        refreshCalls();
    }
};

// ============== boot ==============
setView('home');
connect();
})();
