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
        // home grid
        'apps.label': 'apps',
        'tile.claude.label': 'claude code',
        'tile.claude.sub': 'chat with claude',
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
        'view.claude': 'claude code',
        'view.terminal': 'terminal',
        'view.sms': 'messages',
        'view.send': 'send text',
        'view.system': 'system',
        // claude
        'claude.placeholder': 'ask claude code anything (Enter to send · Shift+Enter for newline)',
        'claude.clear': 'clear',
        'claude.send': 'send',
        'claude.thinking': 'thinking…',
        // Auth flow — only shown until creds exist on the device.
        'claude.auth.title': 'log in to claude',
        'claude.auth.sub': 'choose a sign-in method to use claude code on the r1.',
        'claude.auth.tab.oauth': 'sign in with claude.ai',
        'claude.auth.tab.key': 'use api key',
        'claude.auth.oauth.step1': 'step 1 — generate the sign-in url',
        'claude.auth.oauth.start': 'start sign-in',
        'claude.auth.oauth.starting': 'generating sign-in url…',
        'claude.auth.oauth.step2': 'step 2 — open this url and approve',
        'claude.auth.oauth.step3': 'step 3 — paste the code from the redirect page',
        'claude.auth.oauth.codePh': 'paste <code>#<state>',
        'claude.auth.oauth.submit': 'submit code',
        'claude.auth.oauth.submitting': 'verifying…',
        'claude.auth.oauth.empty': 'paste the code first',
        'claude.auth.key.label': 'paste an anthropic api key',
        'claude.auth.key.ph': 'sk-ant-...',
        'claude.auth.key.submit': 'save key',
        'claude.auth.key.empty': 'paste a key first',
        'claude.auth.key.bad': 'key looks invalid (must start with sk-ant-)',
        'claude.auth.success': 'logged in ✓ — reloading…',
        'claude.auth.noChroot': 'alpine chroot missing on this device — bootstrap it first',
        'claude.auth.reset': 'reset credentials',
        'claude.auth.reset.confirm': 'tap again to confirm',
        'claude.auth.reset.running': 'wiping credentials…',
        'claude.auth.reset.done': 'cleared — log in again',
        'claude.auth.reset.failed': 'reset failed — check carroot',
        'claude.auth.verify': 'test login',
        'claude.auth.verify.running': 'asking claude to say PONG…',
        'claude.auth.verify.ok': 'login works ✓ — you can chat now',
        'claude.auth.verify.fail': 'claude refused — try reset + relogin',
        // Setup (one-time bootstrap of alpine + claude binary on this device).
        'claude.setup.title': 'set up claude code',
        'claude.setup.sub': 'first run on this device — installs an alpine chroot + the claude binary. ~85 mb download.',
        'claude.setup.start': 'install (~85 mb)',
        'claude.setup.running': 'installing… this takes 5-10 minutes over wifi',
        'claude.setup.success': 'installed ✓ — log in next',
        'claude.setup.failed': 'install failed — check the log above',
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
        'apps.label': 'التطبيقات',
        'tile.claude.label': 'Claude Code',
        'tile.claude.sub': 'محادثة مع Claude',
        'tile.terminal.label': 'الطرفية',
        'tile.terminal.sub': 'صدفة الجذر',
        'tile.sms.label': 'الرسائل',
        'tile.sms.sub': 'محادثات SMS',
        'tile.send.label': 'إرسال نص',
        'tile.send.sub': 'لصق إلى r1',
        'tile.system.label': 'النظام',
        'tile.system.sub': 'المفاتيح والمنزلقات',
        'back.home': 'الرئيسية >',
        'view.claude': 'Claude Code',
        'view.terminal': 'الطرفية',
        'view.sms': 'الرسائل',
        'view.send': 'إرسال نص',
        'view.system': 'النظام',
        'claude.placeholder': 'اسأل Claude Code أي شيء (Enter للإرسال · Shift+Enter لسطر جديد)',
        'claude.clear': 'مسح',
        'claude.send': 'إرسال',
        'claude.thinking': 'يفكّر…',
        'claude.auth.title': 'تسجيل الدخول إلى Claude',
        'claude.auth.sub': 'اختر طريقة تسجيل الدخول لاستخدام Claude Code على r1.',
        'claude.auth.tab.oauth': 'تسجيل عبر claude.ai',
        'claude.auth.tab.key': 'استخدام مفتاح API',
        'claude.auth.oauth.step1': 'الخطوة 1 — إنشاء رابط تسجيل الدخول',
        'claude.auth.oauth.start': 'بدء تسجيل الدخول',
        'claude.auth.oauth.starting': 'جارٍ إنشاء الرابط…',
        'claude.auth.oauth.step2': 'الخطوة 2 — افتح الرابط ووافق',
        'claude.auth.oauth.step3': 'الخطوة 3 — ألصق الرمز من صفحة التحويل',
        'claude.auth.oauth.codePh': 'ألصق <code>#<state>',
        'claude.auth.oauth.submit': 'إرسال الرمز',
        'claude.auth.oauth.submitting': 'جارٍ التحقق…',
        'claude.auth.oauth.empty': 'ألصق الرمز أولاً',
        'claude.auth.key.label': 'ألصق مفتاح Anthropic API',
        'claude.auth.key.ph': 'sk-ant-...',
        'claude.auth.key.submit': 'حفظ المفتاح',
        'claude.auth.key.empty': 'ألصق المفتاح أولاً',
        'claude.auth.key.bad': 'المفتاح غير صحيح (يجب أن يبدأ بـ sk-ant-)',
        'claude.auth.success': 'تم تسجيل الدخول ✓ — جارٍ التحديث…',
        'claude.auth.noChroot': 'بيئة Alpine غير مهيأة على هذا الجهاز — قم بالتهيئة أولاً',
        'claude.auth.reset': 'إعادة ضبط بيانات الاعتماد',
        'claude.auth.reset.confirm': 'اضغط مرة أخرى للتأكيد',
        'claude.auth.reset.running': 'جارٍ مسح بيانات الاعتماد…',
        'claude.auth.reset.done': 'تم المسح — سجّل الدخول مجددًا',
        'claude.auth.reset.failed': 'فشل الإعادة — تحقق من carroot',
        'claude.auth.verify': 'اختبار تسجيل الدخول',
        'claude.auth.verify.running': 'يطلب من Claude الرد بـ PONG…',
        'claude.auth.verify.ok': 'تسجيل الدخول يعمل ✓ — يمكنك الدردشة',
        'claude.auth.verify.fail': 'رفض Claude — أعد الضبط وسجّل من جديد',
        'claude.setup.title': 'تهيئة Claude Code',
        'claude.setup.sub': 'أول تشغيل على هذا الجهاز — يثبّت بيئة Alpine وبرنامج Claude. تحميل ~85 ميغابايت.',
        'claude.setup.start': 'تثبيت (~85 ميغابايت)',
        'claude.setup.running': 'جارٍ التثبيت… يستغرق 5-10 دقائق عبر Wi-Fi',
        'claude.setup.success': 'تم التثبيت ✓ — سجّل الدخول الآن',
        'claude.setup.failed': 'فشل التثبيت — راجع السجل أعلاه',
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
