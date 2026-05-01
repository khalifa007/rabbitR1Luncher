(() => {
'use strict';

// --- WS RPC ---
let ws = null;
let reconnectDelay = 500;
const pending = new Map(); // id -> {resolve, reject}
let reqSeq = 0;

const statusEl = document.getElementById('conn-status');
function setStatus(text, cls) {
    statusEl.textContent = text;
    statusEl.className = 'status' + (cls ? ' ' + cls : '');
}

function connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${location.host}/api/rpc`;
    setStatus('connecting…');
    ws = new WebSocket(url);
    ws.addEventListener('open', () => {
        setStatus('live', 'live');
        reconnectDelay = 500;
    });
    ws.addEventListener('close', () => {
        setStatus('lost connection', 'error');
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

// --- event handlers ---
function handleEvent(event, payload) {
    if (event === 'state.snapshot') applySnapshot(payload);
}

function applySnapshot(s) {
    if (!s) return;
    // Topbar
    if (s.system) {
        document.getElementById('info-clock').textContent = s.system.clockText || '--:--';
        document.getElementById('info-battery').textContent =
            'battery ' + Math.round((s.system.battery || 0) * 100) + '%';
        document.getElementById('sys-battery').textContent = Math.round((s.system.battery || 0) * 100) + '%';
        document.getElementById('sys-charging').textContent = s.system.charging ? 'yes' : 'no';
        document.getElementById('sys-ip').textContent = (s.system.ip || '?') + ':' + (s.system.port || '?');
    }
    if (s.network) {
        document.getElementById('info-signal').textContent = 'signal ' + s.network.signal;
        document.getElementById('info-operator').textContent = s.network.operator || '—';
        document.getElementById('sys-operator').textContent = s.network.operator || '—';
        document.getElementById('sys-radio').textContent = s.network.networkType || '—';
        document.getElementById('sys-signal').textContent = '●'.repeat(s.network.signal) + '○'.repeat(4 - s.network.signal);
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
        document.getElementById('oc-key').textContent = s.openclaw.hasOpenAiKey ? ('sk-…' + (s.openclaw.openAiKeyTail || '')) : 'not set';
    }
}

function setToggle(id, v) {
    const el = document.getElementById(id);
    if (el && document.activeElement !== el) el.checked = !!v;
}

// --- tabs ---
document.querySelectorAll('#tabs button').forEach((b) => {
    b.addEventListener('click', () => {
        document.querySelectorAll('#tabs button').forEach((x) => x.classList.remove('active'));
        document.querySelectorAll('.tab').forEach((x) => x.classList.remove('active'));
        b.classList.add('active');
        document.getElementById('tab-' + b.dataset.tab).classList.add('active');
        if (b.dataset.tab === 'sms') refreshSmsList();
    });
});

function flash(el, text, error) {
    const tag = document.createElement('div');
    tag.textContent = text;
    tag.style.cssText = `position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:${error ? '#ff4040' : '#35d26f'};color:#000;padding:8px 16px;border-radius:6px;z-index:99;font-size:12px`;
    document.body.appendChild(tag);
    setTimeout(() => tag.remove(), 1400);
}

// --- sms ---
let smsActiveAddr = null;
async function refreshSmsList() {
    try {
        const list = await rpc('sms.list');
        const ul = document.getElementById('sms-threads');
        ul.innerHTML = '';
        if (!Array.isArray(list) || list.length === 0) {
            ul.innerHTML = '<li style="cursor:default;color:#888">no messages</li>';
            return;
        }
        list.forEach((c) => {
            const li = document.createElement('li');
            const date = new Date(c.latestTimestampMs);
            li.innerHTML = `
                <div class="name">${escapeHtml(c.name || c.address)}</div>
                <div class="preview">${escapeHtml((c.latestBody || '').slice(0, 64))}</div>
                <div class="meta">
                    <span>${escapeHtml(date.toLocaleString())}</span>
                    <span class="${c.unreadCount > 0 ? 'unread' : ''}">${c.unreadCount > 0 ? '•' + c.unreadCount : ''}</span>
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
    body.innerHTML = '<span style="color:#888">loading…</span>';
    try {
        const items = await rpc('sms.thread', { address });
        body.innerHTML = '';
        items.forEach((m) => {
            const div = document.createElement('div');
            div.className = 'bubble ' + (m.incoming ? 'in' : 'out');
            const time = new Date(m.timestampMs).toLocaleString();
            div.innerHTML = `
                <div>${escapeHtml(m.body)}</div>
                <div class="bubble-time">${time} <span class="bubble-actions" data-act="copy">copy</span></div>
            `;
            div.querySelector('[data-act=copy]').addEventListener('click', (e) => {
                e.stopPropagation();
                navigator.clipboard?.writeText(m.body);
                flash(div, 'copied');
            });
            body.appendChild(div);
        });
    } catch (e) { body.textContent = 'failed: ' + e.message; }
}
document.getElementById('sms-refresh').addEventListener('click', refreshSmsList);

// --- send text ---
const sendText = document.getElementById('send-text');
const sendTarget = document.getElementById('send-target');
const sendHint = document.getElementById('send-hint');
const sendBtn = document.getElementById('send-button');

function updateSendHint() {
    const t = sendText.value;
    if (sendTarget.value === 'openai_key') {
        if (!t) sendHint.textContent = 'paste a sk- key';
        else if (t.startsWith('sk-') && t.length >= 20) {
            sendHint.textContent = 'looks valid'; sendHint.className = 'hint ok';
            return;
        } else { sendHint.textContent = 'must start with sk- and be at least 20 chars'; sendHint.className = 'hint err'; return; }
    } else {
        sendHint.textContent = t ? `${t.length} chars` : 'type a message';
    }
    sendHint.className = 'hint';
}
sendText.addEventListener('input', updateSendHint);
sendTarget.addEventListener('change', updateSendHint);
sendBtn.addEventListener('click', async () => {
    const text = sendText.value;
    if (!text) return;
    sendBtn.disabled = true;
    try {
        await rpc('text.send', { target: sendTarget.value, text });
        sendHint.textContent = 'sent';
        sendHint.className = 'hint ok';
        sendText.value = '';
    } catch (e) {
        sendHint.textContent = 'failed: ' + e.message;
        sendHint.className = 'hint err';
    } finally {
        sendBtn.disabled = false;
    }
});

// --- toggles & sliders ---
document.getElementById('toggle-wifi').addEventListener('change', (e) => rpc('wifi.toggle', { on: e.target.checked }).catch(showErr));
document.getElementById('toggle-cellular').addEventListener('change', (e) => rpc('cellular.toggle', { on: e.target.checked }).catch(showErr));
document.getElementById('toggle-bt').addEventListener('change', (e) => rpc('bt.toggle', { on: e.target.checked }).catch(showErr));
document.getElementById('toggle-hotspot').addEventListener('change', (e) => rpc('hotspot.toggle', { on: e.target.checked }).catch(showErr));

let brightnessTimer = null;
document.getElementById('brightness').addEventListener('input', (e) => {
    clearTimeout(brightnessTimer);
    brightnessTimer = setTimeout(() => rpc('brightness.set', { level: parseInt(e.target.value, 10) }).catch(showErr), 120);
});
let volumeTimer = null;
document.getElementById('volume').addEventListener('input', (e) => {
    clearTimeout(volumeTimer);
    volumeTimer = setTimeout(() => rpc('volume.set', { level: parseInt(e.target.value, 10) }).catch(showErr), 120);
});

function showErr(e) { flash(document.body, e.message || 'error', true); }
function escapeHtml(s) { return String(s ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]); }

connect();
})();
