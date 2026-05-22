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
        'tile.credentials.label': 'credentials',
        'tile.credentials.sub': 'api keys + topics',
        'tile.system.label': 'system',
        'tile.system.sub': 'toggles + sliders',
        'tile.media.label': 'media',
        'tile.media.sub': 'screenshots + video',
        'back.home': '< home',
        // view titles
        'view.terminal': 'terminal',
        'view.credentials': 'credentials',
        'view.system': 'system',
        'view.media': 'media',
        // media capture
        'media.snap': 'snap',
        'media.record': 'record',
        'media.stop': 'stop',
        'media.recording': 'recording',
        'media.autoStop': 'auto in',
        'media.clearAll': 'clear all',
        'media.empty': 'no captures yet. tap snap or record.',
        'media.statsItems': 'items',
        'media.download': 'download',
        'media.delete': 'delete',
        'media.confirmDelete': 'delete?',
        'media.confirmClear': 'wipe all?',
        'media.failedCapture': 'capture failed',
        'media.failedRecord': 'record failed',
        'media.lowStorage': 'low storage',
        // Auth flow — only shown until creds exist on the device.
        // terminal
        'terminal.banner': 'remote terminal is off. enable it on the r1 at settings → network → remote terminal.',
        'terminal.cwd': 'cwd',
        'terminal.placeholder': 'ls /sdcard',
        'terminal.run': 'run',
        'terminal.clr': 'clr',
        // credentials
        'cred.save': 'save',
        'cred.eleven.title': 'elevenlabs',
        'cred.eleven.key': 'api key',
        'cred.eleven.keyPh': 'sk_... or 32-char hex',
        'cred.eleven.voice': 'catalog voice',
        'cred.eleven.custom': 'custom voice id (optional)',
        'cred.eleven.customPh': 'overrides catalog when set',
        'cred.hermes.title': 'hermes',
        'cred.hermes.url': 'server url',
        'cred.hermes.bearer': 'bearer token',
        'cred.hermes.add': 'add connection',
        'cred.hermes.activate': 'activate',
        'cred.hermes.edit': 'edit',
        'cred.hermes.delete': 'delete',
        'cred.hermes.cancel': 'cancel',
        'cred.hermes.activeMark': 'active',
        'cred.hermes.capReached': 'connection cap reached (5)',
        'cred.ntfy.title': 'ntfy.sh',
        'cred.ntfy.topic': 'topic',
        'cred.status.saved': 'saved ✓',
        'cred.status.failed': 'failed: %s',
        'cred.status.tail': 'current: %s',
        'cred.status.unset': 'not set',
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
        'tile.credentials.label': 'بيانات الاعتماد',
        'tile.credentials.sub': 'مفاتيح API ومواضيع',
        'tile.system.label': 'النظام',
        'tile.system.sub': 'المفاتيح والمنزلقات',
        'tile.media.label': 'الوسائط',
        'tile.media.sub': 'لقطات وفيديو',
        'back.home': 'الرئيسية >',
        'view.terminal': 'الطرفية',
        'view.credentials': 'بيانات الاعتماد',
        'view.system': 'النظام',
        'view.media': 'الوسائط',
        'media.snap': 'لقطة',
        'media.record': 'تسجيل',
        'media.stop': 'إيقاف',
        'media.recording': 'يسجل',
        'media.autoStop': 'تلقائي خلال',
        'media.clearAll': 'مسح الكل',
        'media.empty': 'لا توجد لقطات بعد. اضغط لقطة أو تسجيل.',
        'media.statsItems': 'عنصر',
        'media.download': 'تنزيل',
        'media.delete': 'حذف',
        'media.confirmDelete': 'حذف؟',
        'media.confirmClear': 'مسح الكل؟',
        'media.failedCapture': 'فشلت اللقطة',
        'media.failedRecord': 'فشل التسجيل',
        'media.lowStorage': 'سعة منخفضة',
        'terminal.banner': 'الطرفية عن بُعد متوقفة. فعّلها على r1 من الإعدادات ← الشبكة ← الطرفية عن بُعد.',
        'terminal.cwd': 'المسار',
        'terminal.placeholder': 'ls /sdcard',
        'terminal.run': 'تشغيل',
        'terminal.clr': 'مسح',
        'cred.save': 'حفظ',
        'cred.eleven.title': 'ElevenLabs',
        'cred.eleven.key': 'مفتاح API',
        'cred.eleven.keyPh': 'sk_... أو 32 رقم سداسي',
        'cred.eleven.voice': 'الصوت من الكتالوج',
        'cred.eleven.custom': 'معرّف صوت مخصص (اختياري)',
        'cred.eleven.customPh': 'يتجاوز الكتالوج عند ضبطه',
        'cred.hermes.title': 'Hermes',
        'cred.hermes.url': 'عنوان الخادم',
        'cred.hermes.bearer': 'رمز الحامل',
        'cred.hermes.add': 'إضافة اتصال',
        'cred.hermes.activate': 'تفعيل',
        'cred.hermes.edit': 'تعديل',
        'cred.hermes.delete': 'حذف',
        'cred.hermes.cancel': 'إلغاء',
        'cred.hermes.activeMark': 'نشط',
        'cred.hermes.capReached': 'الحد الأقصى للاتصالات (5)',
        'cred.ntfy.title': 'ntfy.sh',
        'cred.ntfy.topic': 'الموضوع',
        'cred.status.saved': 'تم الحفظ ✓',
        'cred.status.failed': 'فشل: %s',
        'cred.status.tail': 'الحالي: %s',
        'cred.status.unset': 'غير محدد',
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
