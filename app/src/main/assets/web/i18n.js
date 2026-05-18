/* ====================================================================
 * r1 // remote — i18n
 *
 * Flat key/string map per locale + a t(key, ...args) helper. Locale is
 * pulled from state.snapshot.locale and applied with applyI18n() on
 * every snapshot — DOM elements opt in via data-i18n="key" (textContent)
 * and data-i18n-placeholder="key" (input/textarea placeholder).
 *
 * Adding a language: drop a new STRINGS["xx"] block + ensure a font
 * fallback is available in style.css for non-Latin scripts.
 * ==================================================================== */

(() => {
'use strict';

const STRINGS = {
    en: {
        // topbar
        'topbar.title': 'r1 // remote',
        'topbar.signal': 'signal',
        'conn.connecting': 'connecting',
        'conn.live': 'live',
        'conn.offline': 'offline',
        'hero.connecting': 'connecting to r1',
        'hero.connected': 'connected to r1',
        'hero.lost': 'lost connection — retrying',
        // unlock overlay
        'unlock.prompt': 'enter your 4-digit passcode',
        'unlock.hint': 'set the passcode on the r1 at settings → network',
        // home grid
        'apps.label': 'apps',
        'tile.terminal.label': 'terminal',
        'tile.terminal.sub': 'root shell',
        'tile.sms.label': 'messages',
        'tile.sms.sub': 'sms threads',
        'tile.send.label': 'send text',
        'tile.send.sub': 'paste into r1',
        'tile.system.label': 'system',
        'tile.system.sub': 'toggles + sliders',
        'back.home': '< home',
        // view titles
        'view.terminal': 'terminal',
        'view.sms': 'messages',
        'view.send': 'send text',
        'view.system': 'system',
        // Auth flow — only shown until creds exist on the device.
        // terminal
        'terminal.banner': 'remote terminal is off. enable it on the r1 at settings → network → remote terminal.',
        'terminal.cwd': 'cwd',
        'terminal.placeholder': 'ls /sdcard',
        'terminal.run': 'run',
        'terminal.clr': 'clr',
        // sms
        'sms.refresh': 'refresh',
        'sms.empty': 'no messages yet',
        'sms.threadEmpty': 'no messages in this thread',
        'sms.threadHeader': 'select a conversation',
        'sms.loading': 'loading…',
        // send text
        'send.target': 'target',
        'send.payload': 'payload',
        'send.targetVoice': 'elevenlabs voice key',
        'send.targetVoiceCustomId': 'custom voice id (clone / pro)',
        'send.targetChat': 'openclaw chat (live session)',
        'send.placeholder': 'paste or type text…',
        'send.button': 'send to r1',
        'send.hintEmpty': 'paste an elevenlabs key (sk_… or 32-char hex)',
        'send.hintValid': 'looks valid',
        'send.hintInvalid': 'must be sk_<29+ chars> or 32-char hex',
        'send.hintCustomIdEmpty': 'paste a voice_id from elevenlabs.io — empty clears the override',
        'send.hintCustomIdInvalid': 'voice_id is usually 20 alphanumeric chars (no dashes / spaces)',
        'send.hintTypeMsg': 'type a message',
        'send.hintChars': '%d chars',
        'send.sent': 'sent ✓',
        'send.sentToR1': 'sent to r1',
        // system
        'system.device': 'device',
        'system.battery': 'battery',
        'system.charging': 'charging',
        'system.operator': 'operator',
        'system.radio': 'radio',
        'system.signal': 'signal',
        'system.panelIp': 'panel ip',
        'system.toggles': 'toggles',
        'system.wifi': 'wifi',
        'system.cellular': 'cellular',
        'system.bluetooth': 'bluetooth',
        'system.hotspot': 'hotspot',
        'system.sliders': 'sliders',
        'system.brightness': 'brightness',
        'system.volume': 'volume',
        'system.openclaw': 'openclaw',
        'system.status': 'status',
        'system.voiceKey': 'voice key',
        'system.notSet': 'not set',
        'system.yes': 'yes',
        'system.no': 'no',
    },
    ar: {
        'topbar.title': 'r1 // عن بُعد',
        'topbar.signal': 'الإشارة',
        'conn.connecting': 'جارٍ الاتصال',
        'conn.live': 'متصل',
        'conn.offline': 'غير متصل',
        'hero.connecting': 'جارٍ الاتصال بـ r1',
        'hero.connected': 'متصل بـ r1',
        'hero.lost': 'فقد الاتصال — جارٍ إعادة المحاولة',
        'unlock.prompt': 'أدخل رمز الدخول المكوّن من 4 أرقام',
        'unlock.hint': 'اضبط الرمز على الـ r1 من الإعدادات ← الشبكة',
        'apps.label': 'التطبيقات',
        'tile.terminal.label': 'الطرفية',
        'tile.terminal.sub': 'صدفة الجذر',
        'tile.sms.label': 'الرسائل',
        'tile.sms.sub': 'محادثات SMS',
        'tile.send.label': 'إرسال نص',
        'tile.send.sub': 'لصق إلى r1',
        'tile.system.label': 'النظام',
        'tile.system.sub': 'المفاتيح والمنزلقات',
        'back.home': 'الرئيسية >',
        'view.terminal': 'الطرفية',
        'view.sms': 'الرسائل',
        'view.send': 'إرسال نص',
        'view.system': 'النظام',
        'terminal.banner': 'الطرفية عن بُعد متوقفة. فعّلها على r1 من الإعدادات ← الشبكة ← الطرفية عن بُعد.',
        'terminal.cwd': 'المسار',
        'terminal.placeholder': 'ls /sdcard',
        'terminal.run': 'تشغيل',
        'terminal.clr': 'مسح',
        'sms.refresh': 'تحديث',
        'sms.empty': 'لا توجد رسائل بعد',
        'sms.threadEmpty': 'لا توجد رسائل في هذه المحادثة',
        'sms.threadHeader': 'اختر محادثة',
        'sms.loading': 'جارٍ التحميل…',
        'send.target': 'الهدف',
        'send.payload': 'المحتوى',
        'send.targetVoice': 'مفتاح ElevenLabs',
        'send.targetVoiceCustomId': 'معرّف صوت مخصص (مستنسخ / احترافي)',
        'send.targetChat': 'محادثة OpenClaw (جلسة حية)',
        'send.placeholder': 'ألصق أو اكتب نصاً…',
        'send.button': 'إرسال إلى r1',
        'send.hintEmpty': 'ألصق مفتاح ElevenLabs (sk_… أو 32 رقم سداسي)',
        'send.hintValid': 'يبدو صحيحاً',
        'send.hintInvalid': 'يجب أن يبدأ بـ sk_<29+ حرفاً> أو 32 رقم سداسي',
        'send.hintCustomIdEmpty': 'ألصق voice_id من elevenlabs.io — يمسح الإلغاء عند تركه فارغاً',
        'send.hintCustomIdInvalid': 'voice_id عادةً 20 حرفاً أبجدياً رقمياً (بدون شَرَط أو فراغ)',
        'send.hintTypeMsg': 'اكتب رسالة',
        'send.hintChars': '%d حرفاً',
        'send.sent': 'تم الإرسال ✓',
        'send.sentToR1': 'أُرسل إلى r1',
        'system.device': 'الجهاز',
        'system.battery': 'البطارية',
        'system.charging': 'الشحن',
        'system.operator': 'المشغّل',
        'system.radio': 'الشبكة',
        'system.signal': 'الإشارة',
        'system.panelIp': 'عنوان اللوحة',
        'system.toggles': 'المفاتيح',
        'system.wifi': 'Wi-Fi',
        'system.cellular': 'الجوّال',
        'system.bluetooth': 'Bluetooth',
        'system.hotspot': 'نقطة الاتصال',
        'system.sliders': 'المنزلقات',
        'system.brightness': 'السطوع',
        'system.volume': 'الصوت',
        'system.openclaw': 'OpenClaw',
        'system.status': 'الحالة',
        'system.voiceKey': 'المفتاح الصوتي',
        'system.notSet': 'غير محدد',
        'system.yes': 'نعم',
        'system.no': 'لا',
    },
};

// RTL languages — drives <html dir="..."> + Noto Sans Arabic font choice.
const RTL_LANGS = new Set(['ar']);

let currentLocale = 'en';

/** Look up a key; supports printf-style %s/%d/%% interpolation against ...args. */
function t(key, ...args) {
    const dict = STRINGS[currentLocale] || STRINGS.en;
    let s = dict[key];
    if (s == null) s = STRINGS.en[key];
    if (s == null) return key;
    let i = 0;
    return String(s).replace(/%[sd%]/g, (m) => {
        if (m === '%%') return '%';
        const v = args[i++];
        return v == null ? '' : String(v);
    });
}

/** Set the active locale and re-render every [data-i18n] node + flip <html dir>. */
function setLocale(code) {
    if (!STRINGS[code]) code = 'en';
    if (currentLocale === code) {
        // First-call init: still need to apply once.
        if (!document.documentElement.hasAttribute('data-i18n-applied')) {
            applyI18n();
            document.documentElement.setAttribute('data-i18n-applied', '1');
        }
        return;
    }
    currentLocale = code;
    document.documentElement.lang = code;
    document.documentElement.dir = RTL_LANGS.has(code) ? 'rtl' : 'ltr';
    applyI18n();
}

/** Walk all [data-i18n] / [data-i18n-placeholder] nodes and refresh text. */
function applyI18n(root = document) {
    root.querySelectorAll('[data-i18n]').forEach((el) => {
        el.textContent = t(el.dataset.i18n);
    });
    root.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
        el.placeholder = t(el.dataset.i18nPlaceholder);
    });
    document.title = t('topbar.title');
}

window.R1I18n = { t, setLocale, applyI18n };
})();
