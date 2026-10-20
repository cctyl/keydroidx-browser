/*
 * Keyphone reading mode - continuous text reading, extracted from the source page.
 *
 * Loaded through Mezzanine: src/main/js/KeyphoneReadingMode.js
 *   -> acr.browser.lightning.js.KeyphoneReadingMode.provideJs()
 *
 * Architecture (render isolation + continuous text):
 *   1. the host extracts the article of the CURRENT page read-only (extract());
 *   2. the host navigates the WebView to a clean shell whose baseUrl is the chapter URL
 *      and boots the reader with the extracted content (enterWithContent);
 *   3. the shell appends the next chapter's TEXT below the current one as the reader
 *      approaches the end - the page simply grows, scrolling never jumps, and nothing at
 *      the chapter boundary is skipped. Only the text is stitched (no site DOM, no
 *      scripts); the reader window keeps at most KEEP_CHAPTERS chapters and drops the
 *      oldest ones once they are TRIM_SAFE_SCREENS screens above the viewport.
 *
 * The file body is an IIFE that installs window.__keyphone_rm__. The host boots it in two
 * modes (see the boot dispatch at the bottom):
 *   __keyphone_rm_boot__     render the pre-extracted chapter (clean page path)
 *   __keyphone_rm_noenter__  API only, do not touch the page (source page extraction)
 *
 * Kotlin (KeyphoneBrowserActivity) keeps per-tab state and receives user feedback from
 * window.__keyphoneReadingBridge__.onEvent(type, payload), with types:
 *   "enter"  payload = extracted text length
 *   "turn"   payload = url of the chapter now shown
 *   "end"    no further chapter (payload = '')
 *   "fail"   chapter download failed (payload = reason)
 *   "link"   user tapped a link inside the article (payload = absolute url)
 *   "exit"   reader torn down
 *
 * Keys 1 / 3 (font size) and 7 (background theme) do NOT use the bridge: on this device a
 * post-load addJavascriptInterface() never becomes visible to the page, so the host calls
 * window.__keyphone_rm__.font(delta) / .theme() and reads the new value from the
 * evaluateJavascript() result instead (that is what lets it persist the choice).
 * The appearance is handed over the same way:
 *   window.__keyphone_rm_init__ = {theme: <int>, font: <px>};
 * evaluated together with this file, so a new chapter keeps the previous look.
 *
 * The visited chapters are reported over the same "host pulls, script returns" channel:
 *   window.__keyphone_rm__.progress() -> "url\ttitle\nurl\ttitle\n..."  (reading order)
 * The host mirrors every chapter into the URL history (so the reader behaves like plain
 * browsing) and, when reading mode is left, navigates the tab to the last chapter - which
 * also fixes both the address bar and the tab that is restored on the next launch.
 *
 * IMPORTANT: keep this file ASCII only. Mezzanine embeds the file content into a
 * generated string literal at build time and the build machine default charset is not
 * guaranteed to be UTF-8, so CJK keywords are written as \uXXXX escapes (they are
 * resolved by the JavaScript engine at run time).
 */
(function () {
    'use strict';

    /* ------------------------------------------------------------------ *
     * tunables
     * ------------------------------------------------------------------ */
    var MIN_CONTENT_CHARS = 200;     // minimum amount of text accepted as article body
    // Reader window: measured on the device, the sandboxed renderer is killed once the
    // stitched document grows past ~100k px (7-8 chapters of text), so the window is
    // bounded by DOCUMENT HEIGHT, not by chapter count. 40k px is ~2-3 chapters - far
    // below the death zone while still holding much more than one screen of read-ahead.
    var MAX_DOM_PX = 40000;          // trim the oldest chapter above this document height
    var MIN_CHAPTERS = 2;            // never trim below this many chapters
    var TRIM_SAFE_SCREENS = 2;       // only trim a chapter once it is this many screens above
    var PRE_READ_SCREENS = 1.5;      // keep this many screens of unread text below the viewport
    var MAX_LINK_TEXT_LEN = 12;      // max length of a "next page" link label
    var MAX_NAV_LABEL_LEN = 24;      // max length of a navigation / meta label
    var MAX_BARE_URL_LEN = 60;       // max length of a bare host / url text block
    var MAX_TITLE_LEN = 40;          // max length of the stitched chapter title
    var SCAN_LIMIT = 1500;           // max nodes scanned while picking the article body
    var LOAD_TIMEOUT_MS = 12000;     // give up waiting for a slow next page after this
    var FAIL_RETRY_GAP_MS = 2000;    // minimum gap before retrying a failed request
    var FAIL_RETRY_MAX_MS = 15000;   // backoff ceiling for repeated failures
    var PUMP_INTERVAL_MS = 800;      // heartbeat: WebView.scrollBy() often does NOT fire a
                                     // page scroll event, so the prefetch must not rely on
                                     // scroll listeners alone on keypad devices
    var PAGE_ATTR = 'data-krm-page';
    var SEP_ATTR = 'data-krm-sep';
    var SRC_ATTR = 'data-krm-src';
    var LOADING_ATTR = 'data-krm-loading';
    var ROOT_ID = '__keyrm_root__';
    var STYLE_ID = '__keyrm_style__';
    var LOADING_ID = '__krm_loading__';
    var END_ID = '__krm_end__';
    var FAIL_ID = '__krm_fail__';
    var BRIDGE_NAME = '__keyphoneReadingBridge__';
    // Reader viewport: without a viewport meta the WebView lays the page out ~980px wide and
    // then scales it down to the physical screen (on a 320px keypad screen that is ~0.33x),
    // so a 26px stylesheet size would render as ~8px and font steps would look identical.
    // Forcing device-width + scale 1 makes the reader font sizes real pixels.
    var READER_VIEWPORT = 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no';

    /* ------------------------------------------------------------------ *
     * patterns and selector tables
     * ------------------------------------------------------------------ */
    // \u4e0b\u4e00\u9875 = next page, \u4e0b\u4e00\u7ae0 = next chapter,
    // \u4e0b\u4e00\u8282 = next section, \u4e0b\u4e00\u5f20 = next sheet,
    // \u4e0b\u4e00\u7bc7 = next article, \u540e\u4e00\u9875 = following page,
    // \u6700\u540e\u4e00\u9875 = last page, \u4e0b\u4e00\u9801 = traditional "next page"
    var NEXT_TEXT_RE = new RegExp(
        '^(\\u4e0b\\u4e00\\u9875|\\u4e0b\\u9875|\\u4e0b\\u4e00\\u7ae0|\\u4e0b\\u4e00\\u8282' +
        '|\\u4e0b\\u4e00\\u5f20|\\u4e0b\\u4e00\\u7bc7|\\u4e0b\\u7bc7|\\u540e\\u4e00\\u9875' +
        '|\\u540e\\u7ae0|\\u6700\\u540e\\u4e00\\u9875|\\u4e0b\\u4e00\\u9801' +
        '|next|nextpage|nextchapter|older|olderposts' +
        '|>{1,3}|\\u00bb{1,3}|\\u203a{1,3})$',
        'i'
    );
    // \u4e0a\u4e00 = previous, \u524d\u4e00 = preceding
    var PREV_TEXT_RE = new RegExp(
        '(\\u4e0a\\u4e00|\\u4e0a\\u7ae0|\\u4e0a\\u9875|\\u524d\\u4e00|prev|previous|back)',
        'i'
    );

    var SEMANTIC_SELECTORS = [
        'article', 'main', '[role="main"]',
        '#content', '#article', '#main', '#chapter', '#chaptercontent', '#chapter-content',
        '#nr', '#nr1', '#content1', '.content', '.article', '.post', '.entry', '.chapter',
        '.read', '.read-content', '.reading', '.text', '.txt', '.novel', '.book-content'
    ];

    var NOISE_TAGS = [
        'script', 'style', 'noscript', 'iframe', 'frame', 'frameset', 'link', 'meta',
        'img', 'map', 'area', 'audio', 'video', 'canvas', 'svg', 'object', 'embed',
        'form', 'input', 'select', 'textarea', 'button', 'nav', 'header', 'footer',
        'aside', 'figure', 'picture', 'source', 'track'
    ];

    var NOISE_ATTR_RE = /(^|[^a-z])(ad|ads|adv|advert|adsense|advertisement|banner|popup|pop-up|promo|sponsor|gg)([^a-z]|$)/i;
    var DROP_ATTR_RE = /^(style|class|id|width|height|color|bgcolor|align|valign|border|cellpadding|cellspacing|data-.*|on.*)$/i;
    // chapter meta blocks: "2379\u5b57" (word count), "14\u4e2a\u6708\u524d" (age), "A" / "A-" / "A+"
    // font-size buttons (\u5b57 = char, \u4e07\u5b57 = 10k chars, \u5206\u949f/\u5c0f\u65f6/\u5929/\u4e2a\u6708/\u5e74 = minute/hour/day/month/year)
    var META_LABEL_RE = new RegExp(
        '^(\\d+\\s*(\\u5b57|\\u4e07\\u5b57)' +
        '|\\d+\\s*(\\u5206\\u949f|\\u5c0f\\u65f6|\\u5929|\\u4e2a\\u6708|\\u5e74)\\u524d' +
        '|A[-+]?)$',
        'i'
    );
    var BARE_URL_RE = /^(https?:\/\/)?(www\.)?[\w-]+(\.[\w-]+)+([\/?#]\S*)?$/;
    var TITLE_SPLIT_RE = /\s*[-_|\/]\s*/;
    var HOSTLIKE_RE = /^[\w-]+(\.[\w-]+)+$/;

    // Navigation labels that must never survive inside the article body: prev/next
    // chapter links, "report an error" buttons, bookmark/vote/donate prompts and the
    // book index. Written as \uXXXX escapes to keep this file ASCII only
    // (e.g. \u4e0a\u4e00\u7ae0 = previous chapter, \u4e0b\u4e00\u7ae0 = next chapter).
    var NAV_LABELS = [
        '\u4e0a\u4e00\u7ae0', '\u4e0b\u4e00\u7ae0', '\u4e0a\u4e00\u9875', '\u4e0b\u4e00\u9875',
        '\u4e0a\u4e00\u8282', '\u4e0b\u4e00\u8282', '\u4e0a\u4e00\u5f20', '\u4e0b\u4e00\u5f20',
        '\u4e0a\u9875', '\u4e0b\u9875', '\u76ee\u5f55', '\u7ae0\u8282\u5217\u8868',
        '\u4e3e\u62a5', '\u52a0\u5165\u4e66\u7b7e', '\u52a0\u5165\u4e66\u67b6',
        '\u6295\u63a8\u8350\u7968', '\u63a8\u8350\u7968', '\u6253\u8d4f',
        '\u624b\u673a\u9605\u8bfb', '\u6536\u85cf\u672c\u4e66', '\u5168\u6587\u9605\u8bfb',
        '\u6700\u65b0\u7ae0\u8282', '\u9996\u9875', '\u6392\u884c', '\u4e66\u67b6',
        '\u672c\u7ae0\u5b8c', '\u8fd4\u56de'
    ];

    /* ------------------------------------------------------------------ *
     * appearance: background themes (key 7) and font sizes (keys 1 / 3)
     * ------------------------------------------------------------------ */
    // Every theme carries both a background and a matching text colour, so a dark
    // background never ends up with dark text (and vice versa):
    //   bg = page background, text = body text, head = headings, link = anchors,
    //   rule = separator rules, ruleText = separator caption, dim = hint text,
    //   fail = "next page failed" text.
    var THEMES = [
        // cream (default, closest to plain paper)
        { bg: '#F4F4EE', text: '#222222', head: '#111111', link: '#0B62C4', rule: '#B9B9AF', ruleText: '#444444', dim: '#7A7A72', fail: '#B25A00' },
        // sepia / eye-care yellow
        { bg: '#EAE0C8', text: '#3E3626', head: '#2A2418', link: '#8A6A2F', rule: '#C6B994', ruleText: '#6B5F45', dim: '#877B60', fail: '#A2570B' },
        // eye-care green
        { bg: '#CCE8CF', text: '#22331E', head: '#16240F', link: '#2F6B3A', rule: '#9CBB9C', ruleText: '#2F4A2F', dim: '#6C8069', fail: '#8A5A00' },
        // night grey
        { bg: '#1B1B1B', text: '#BDBDBD', head: '#DCDCDC', link: '#7FB2E5', rule: '#4A4A4A', ruleText: '#A9B2BB', dim: '#8A8A8A', fail: '#E0A860' },
        // night black (OLED friendly)
        { bg: '#000000', text: '#B0B0B0', head: '#D2D2D2', link: '#6FA8DC', rule: '#333333', ruleText: '#8F8F8F', dim: '#7A7A7A', fail: '#D9A05B' }
    ];
    // 14 -> 40 px. Fine steps of 2 while the text is still small, coarser steps of 4 at the
    // top so the largest size stays a few key presses away (reading while the phone sits on
    // a desk, or for low vision). The host persists the px value (see module header).
    var FONT_SIZES = [14, 16, 18, 20, 22, 24, 28, 32, 36, 40];
    var DEFAULT_THEME_INDEX = 0;
    var DEFAULT_FONT_INDEX = 1;

    /** Stylesheet for the current theme + font size (rebuilt on every restyle). */
    function buildCss() {
        var t = THEMES[themeIndex] || THEMES[0];
        var fs = FONT_SIZES[fontIndex];
        var hs = fs + 3;                  // headings slightly larger than body text
        // captions / hints stay secondary and are capped: at 40px body text an uncapped
        // "loading next chapter" line would overflow the width and wrap awkwardly
        var ss = Math.min(Math.max(12, fs - 2), 28);   // separator caption
        var ls = Math.min(Math.max(11, fs - 3), 26);   // hint / loading / end text
        return [
            'html,body{background:' + t.bg + '!important;color:' + t.text + '!important;}',
            'body{overflow-x:hidden!important;}',
            '#' + ROOT_ID + '{box-sizing:border-box!important;max-width:100%!important;',
            'padding:10px 10px 30px 10px!important;font-family:sans-serif!important;',
            'font-size:' + fs + 'px!important;line-height:1.8!important;word-wrap:break-word!important;}',
            '#' + ROOT_ID + ' p{color:' + t.text + '!important;background:transparent!important;',
            'font-size:' + fs + 'px!important;line-height:1.8!important;margin:0 0 0.8em 0!important;',
            'text-indent:0!important;}',
            '#' + ROOT_ID + ' div,#' + ROOT_ID + ' td,#' + ROOT_ID + ' span,#' + ROOT_ID + ' li{',
            'color:' + t.text + '!important;background:transparent!important;',
            'font-size:' + fs + 'px!important;line-height:1.8!important;}',
            '#' + ROOT_ID + ' h1,#' + ROOT_ID + ' h2,#' + ROOT_ID + ' h3{',
            'color:' + t.head + '!important;font-size:' + hs + 'px!important;line-height:1.6!important;',
            'margin:0.7em 0!important;}',
            '#' + ROOT_ID + ' a{color:' + t.link + '!important;text-decoration:none!important;}',
            '#' + ROOT_ID + ' img,#' + ROOT_ID + ' video,#' + ROOT_ID + ' audio,',
            '#' + ROOT_ID + ' iframe,#' + ROOT_ID + ' canvas{display:none!important;}',
            // Hint boxes / separator are children of the reader root, so every rule below is
            // scoped with an extra #root (or the id) to outrank "#root div / #root span"
            // above - otherwise their colour and font size would be silently overridden.
            '#' + ROOT_ID + ' [' + SEP_ATTR + ']{text-align:center!important;',
            'color:' + t.ruleText + '!important;',
            'font-size:' + ss + 'px!important;font-weight:bold!important;',
            'margin:28px 0 18px 0!important;padding:10px 0!important;',
            'border-top:3px solid ' + t.rule + '!important;border-bottom:3px solid ' + t.rule + '!important;',
            'letter-spacing:1px!important;word-break:break-all!important;}',
            '#' + ROOT_ID + ' #' + LOADING_ID + ',#' + ROOT_ID + ' #' + LOADING_ID + ' span{',
            'text-align:center!important;color:' + t.dim + '!important;',
            'font-size:' + ls + 'px!important;margin:16px 0 24px 0!important;padding:6px 0!important;}',
            '#' + ROOT_ID + ' #' + END_ID + '{text-align:center!important;color:' + t.dim + '!important;',
            'font-size:' + ls + 'px!important;margin:22px 0 30px 0!important;}',
            '#' + ROOT_ID + ' #' + FAIL_ID + '{text-align:center!important;color:' + t.fail + '!important;',
            'font-size:' + ls + 'px!important;margin:16px 0 24px 0!important;padding:6px 0!important;}',
            '.krm-spin{display:inline-block!important;width:12px!important;height:12px!important;',
            'border:2px solid ' + t.rule + '!important;border-top-color:' + t.dim + '!important;',
            'border-radius:50%!important;vertical-align:middle!important;',
            'animation:krm-spin 0.9s linear infinite!important;}',
            '@keyframes krm-spin{from{transform:rotate(0deg);}to{transform:rotate(360deg);}}'
        ].join('');
    }

    /* ------------------------------------------------------------------ *
     * state
     * ------------------------------------------------------------------ */
    var active = false;
    var root = null;
    // One chapter per page: the DOM holds exactly one chapter; its neighbours live in
    // this bounded cache ({prev, current, next}, each {url, title, html, next}).
    var chapterCache = { prev: null, current: null, next: null };
    var currentEntry = null;
    // chapters currently in the DOM, oldest first: {header: node, wrap: node}
    var pages = [];
    // every chapter visited so far, in reading order: {url, title}. The DOM only keeps
    // KEEP_CHAPTERS of them, but the host mirrors the full trail into the URL history
    // and reopens the last chapter when the reader is left.
    var pageLog = [];
    var nextUrl = null;
    var loading = false;
    var ended = false;
    var seen = {};
    var turning = false;      // a page turn is in progress (guards double key presses)
    var fetching = false;     // a chapter download is in flight (guards prefetch/turn race)
    var lastTurnAt = 0;       // debounce: stale contentHeight after a turn must not skip a chapter
    var lastFailAt = 0;
    var failCount = 0;
    // Boot-time SSR self-check of the entry chapter's next URL (see selfCheckEntryNext):
    // while true, stitching is paused so a possibly wrong live-DOM next can never race in.
    var entryNextCheckPending = false;
    var loadTimer = null;
    var pumpTimer = null;
    var endNotified = false;
    var themeIndex = DEFAULT_THEME_INDEX;
    var fontIndex = DEFAULT_FONT_INDEX;
    // Diagnostics only (never changes behaviour): the host polls diag() on every key press
    // when the reader looks broken, so the last few stitching steps survive even though the
    // bridge is not available on this device.
    var diagNextFetches = 0;
    var diagLastEvent = '';
    var diagTrace = [];
    var DIAG_TRACE_MAX = 12;

    /* ------------------------------------------------------------------ *
     * helpers
     * ------------------------------------------------------------------ */
    /**
     * Diagnostics: keep the last DIAG_TRACE_MAX stitching steps (fetch / append / trim / exit),
     * which the host reads through diag() on the next key press - see module header for why the
     * bridge can not be relied on.
     */
    function trace(message) {
        try {
            diagTrace.push(shortUrl(String(message)));
            if (diagTrace.length > DIAG_TRACE_MAX) {
                diagTrace.shift();
            }
        } catch (e) {
            // diagnostics must never break stitching
        }
    }

    function shortUrl(text) {
        return String(text).replace(/^https?:\/\//i, '');
    }

    function notify(type, payload) {
        diagLastEvent = String(type) + (payload ? ':' + String(payload).substring(0, 120) : '');
        try {
            var bridge = window[BRIDGE_NAME];
            if (bridge && typeof bridge.onEvent === 'function') {
                bridge.onEvent(String(type), payload === undefined || payload === null ? '' : String(payload));
            }
        } catch (e) {
            // bridge detached after navigation - nothing to report
        }
    }

    function removeNode(node) {
        if (node && node.parentNode) {
            node.parentNode.removeChild(node);
        }
    }

    function textLen(el) {
        if (!el) {
            return 0;
        }
        return String(el.textContent || '').replace(/\s+/g, '').length;
    }

    function normalizeText(value) {
        return String(value === undefined || value === null ? '' : value).replace(/\s+/g, '').trim();
    }

    function toHttpUrl(href, base) {
        if (!href) {
            return null;
        }
        var raw = String(href).trim();
        if (!raw || raw.charAt(0) === '#' || /^javascript:/i.test(raw)) {
            return null;
        }
        try {
            var url = new URL(raw, base || location.href);
            if (url.protocol !== 'http:' && url.protocol !== 'https:') {
                return null;
            }
            url.hash = '';
            return url.href;
        } catch (e) {
            return null;
        }
    }

    function normalizeUrl(url) {
        return String(url || '').replace(/#.*$/, '');
    }

    /* ------------------------------------------------------------------ *
     * article extraction (lightweight readability)
     * ------------------------------------------------------------------ */
    function scoreCandidate(el) {
        var total = textLen(el);
        if (total <= 0) {
            return 0;
        }
        var linkChars = 0;
        var links = el.querySelectorAll('a');
        for (var i = 0; i < links.length; i++) {
            linkChars += textLen(links[i]);
        }
        var linkRatio = Math.min(linkChars / total, 0.95);
        var paragraphs = Math.min(el.querySelectorAll('p').length, 200);
        return total * (1 - linkRatio) + paragraphs * 12;
    }

    /** True for nodes inside our own reader DOM (never extract the reader itself). */
    function insideReader(node) {
        if (!node || !node.closest) {
            return false;
        }
        return node.closest('#' + ROOT_ID) !== null;
    }

    /** Numeric chapter id found at the end of a chapter URL (e.g. /chapter/1/12345/). */
    function chapterIdFromUrl(url) {
        if (!url) {
            return null;
        }
        var path = '';
        try {
            path = new URL(url).pathname.replace(/\/+$/, '');
        } catch (e) {
            return null;
        }
        var segments = path.split('/');
        for (var i = segments.length - 1; i >= 0; i--) {
            if (/^\d+$/.test(segments[i])) {
                return segments[i];
            }
        }
        return null;
    }

    /**
     * Best article container whose id is anchored to the chapter id of [baseUrl]
     * (matches `c-12345` / `chapter-12345` etc.) - see extractHtml. Returns null when
     * the URL has no numeric chapter id, no element matches, or nothing has enough text.
     */
    function findContainerByChapterId(doc, chapterId) {
        if (!chapterId || !doc) {
            return null;
        }
        var anchorRe = new RegExp('^(c|chapter|chp|content|article|read|reader)[-_]?' + chapterId + '$', 'i');
        var best = null;
        var bestScore = 0;
        var all = [];
        try {
            all = doc.querySelectorAll('[id]');
        } catch (e) {
            return null;
        }
        var cap = Math.min(all.length, SCAN_LIMIT);
        for (var i = 0; i < cap; i++) {
            var el = all[i];
            if (insideReader(el)) {
                continue;
            }
            var id = String(el.id || '').trim();
            if (!id || !anchorRe.test(id)) {
                continue;
            }
            var score = scoreCandidate(el);
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        return best && bestScore >= MIN_CONTENT_CHARS ? best : null;
    }

    function pickContainer(doc) {
        var best = null;
        var bestScore = 0;
        var i;
        var j;

        for (i = 0; i < SEMANTIC_SELECTORS.length; i++) {
            var nodes;
            try {
                nodes = doc.querySelectorAll(SEMANTIC_SELECTORS[i]);
            } catch (e) {
                continue;
            }
            for (j = 0; j < nodes.length; j++) {
                if (insideReader(nodes[j])) {
                    continue;
                }
                var score = scoreCandidate(nodes[j]);
                if (score > bestScore) {
                    bestScore = score;
                    best = nodes[j];
                }
            }
        }
        if (best && bestScore >= MIN_CONTENT_CHARS) {
            return best;
        }

        // semantic selectors were not enough: scan generic block containers
        var pool = doc.body ? doc.body.querySelectorAll('div,section,article,main,td') : [];
        var cap = Math.min(pool.length, SCAN_LIMIT);
        for (i = 0; i < cap; i++) {
            if (insideReader(pool[i])) {
                continue;
            }
            var genericScore = scoreCandidate(pool[i]);
            if (genericScore > bestScore) {
                bestScore = genericScore;
                best = pool[i];
            }
        }
        if (best && bestScore >= MIN_CONTENT_CHARS) {
            return best;
        }
        // never fall back to a body that only holds our own reader DOM
        return insideReader(doc.body) ? null : (doc.body || null);
    }

    /** Navigation label check: short labels like prev/next chapter, report, bookmark... */
    function looksLikeNavLabel(text) {
        for (var i = 0; i < NAV_LABELS.length; i++) {
            if (text.indexOf(NAV_LABELS[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** True for a text block that is just a host name / url (e.g. www.example.com). */
    function isBareUrlText(text) {
        return text.length <= MAX_BARE_URL_LEN && BARE_URL_RE.test(text);
    }

    /** True for chapter meta labels: word count, age, font-size buttons. */
    function isMetaLabel(text) {
        return text.length <= MAX_NAV_LABEL_LEN && META_LABEL_RE.test(text);
    }

    function stripNoise(el) {
        var i;
        var j;

        for (i = 0; i < NOISE_TAGS.length; i++) {
            var tags = el.querySelectorAll(NOISE_TAGS[i]);
            for (j = tags.length - 1; j >= 0; j--) {
                removeNode(tags[j]);
            }
        }

        var all = el.querySelectorAll('*');
        for (i = all.length - 1; i >= 0; i--) {
            var item = all[i];
            var marker = String(item.getAttribute('class') || '') + ' ' + String(item.id || '');
            if (marker.replace(/\s+/g, '').length > 0 && NOISE_ATTR_RE.test(marker)) {
                removeNode(item);
            }
        }

        // Reading mode offers no navigation, so every anchor inside the article body is
        // dropped. Book title / author links usually live in a tiny wrapper
        // ("<span><a>Book</a></span>"), so a holder left with nothing but a short label
        // is dropped too - that is what removes the "book / author / word count" line.
        var anchors = el.querySelectorAll('a');
        for (i = anchors.length - 1; i >= 0; i--) {
            var anchor = anchors[i];
            var holder = anchor.parentNode;
            removeNode(anchor);
            if (holder && holder !== el &&
                holder.tagName !== 'P' && holder.tagName !== 'TD' &&
                normalizeText(holder.textContent).length <= MAX_NAV_LABEL_LEN) {
                removeNode(holder);
            }
        }

        // navigation blocks, bare host text and chapter meta labels that survived the
        // anchor pass ("prev/next chapter", "www.example.com", "2379 chars", "A- A A+")
        var navNodes = el.querySelectorAll('div,span,li,em,strong,section');
        for (i = navNodes.length - 1; i >= 0; i--) {
            var node = navNodes[i];
            if (node.closest && node.closest('p,td')) {
                continue;
            }
            var label = normalizeText(node.textContent);
            if (!label) {
                continue;
            }
            var isNavLabel = label.length <= MAX_NAV_LABEL_LEN && looksLikeNavLabel(label);
            if (isNavLabel || isBareUrlText(label) || isMetaLabel(label)) {
                removeNode(node);
            }
        }
    }

    function prepareClone(clone, baseUrl) {
        var links = clone.querySelectorAll('a[href]');
        var i;
        for (i = 0; i < links.length; i++) {
            var url = toHttpUrl(links[i].getAttribute('href'), baseUrl);
            if (url) {
                links[i].setAttribute('href', url);
            } else {
                links[i].removeAttribute('href');
            }
        }
        var all = clone.querySelectorAll('*');
        for (i = 0; i < all.length; i++) {
            var el = all[i];
            var attrs = el.attributes;
            if (!attrs) {
                continue;
            }
            for (var a = attrs.length - 1; a >= 0; a--) {
                var name = attrs[a].name;
                if (DROP_ATTR_RE.test(name)) {
                    el.removeAttribute(name);
                }
            }
        }
    }

    /**
     * Source element for one chapter: prefer the container anchored to the chapter id in
     * [baseUrl], but never let a small id-matching widget replace a much larger body.
     */
    function pickSource(doc, baseUrl) {
        var fallback = pickContainer(doc);
        var anchored = findContainerByChapterId(doc, chapterIdFromUrl(baseUrl));
        if (!anchored) {
            return fallback;
        }
        if (!fallback || anchored === fallback || fallback === doc.body) {
            return anchored;
        }
        // chapters of the same book are of comparable length, so an anchored candidate less
        // than half the best-scoring one is an id-carrying widget, not the article body
        if (scoreCandidate(anchored) * 2 < scoreCandidate(fallback)) {
            trace('id-anchored container too small, keeping scored one');
            return fallback;
        }
        return anchored;
    }

    function extractHtml(doc, baseUrl) {
        // Prefer a container whose id is anchored to the chapter id in the URL. Sites that
        // stitch the next chapter into the live page while scrolling (Qidian's in-line
        // next-chapter load) leave several chapter containers in the DOM; the id-anchored
        // element is always the chapter the URL refers to, while naive scoring may pick the
        // appended one (which turns the entry itself into the next chapter). Falls back to
        // generic scoring when there is nothing to anchor to.
        var source = pickSource(doc, baseUrl);
        if (!source) {
            return null;
        }
        var clone = source.cloneNode(true);
        stripNoise(clone);
        prepareClone(clone, baseUrl);
        if (textLen(clone) < MIN_CONTENT_CHARS) {
            return null;
        }
        return clone.innerHTML || null;
    }

    function findNextUrl(doc, baseUrl) {
        try {
            var rel = doc.querySelector('link[rel~="next"][href], a[rel~="next"][href]');
            if (rel) {
                var relUrl = toHttpUrl(rel.getAttribute('href'), baseUrl);
                if (relUrl && normalizeUrl(relUrl) !== normalizeUrl(baseUrl)) {
                    return relUrl;
                }
            }
        } catch (e) {
            // selector unsupported - fall back to label matching
        }

        try {
            var links = doc.querySelectorAll('a[href]');
            var cap = Math.min(links.length, SCAN_LIMIT);
            for (var i = 0; i < cap; i++) {
                var label = normalizeText(links[i].textContent);
                if (!label || label.length > MAX_LINK_TEXT_LEN) {
                    continue;
                }
                if (PREV_TEXT_RE.test(label) || !NEXT_TEXT_RE.test(label)) {
                    continue;
                }
                var url = toHttpUrl(links[i].getAttribute('href'), baseUrl);
                if (url && normalizeUrl(url) !== normalizeUrl(baseUrl)) {
                    return url;
                }
            }
        } catch (e) {
            // treated as "no next page"
        }
        return null;
    }

    /* ------------------------------------------------------------------ *
     * reader DOM
     * ------------------------------------------------------------------ */
    function injectStyle() {
        if (document.getElementById(STYLE_ID)) {
            return;
        }
        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.type = 'text/css';
        style.textContent = buildCss();
        (document.head || document.documentElement).appendChild(style);
    }

    /**
     * Switch the page to the reader viewport (see READER_VIEWPORT). The existing meta is
     * REWRITTEN rather than replaced: Chromium re-applies the viewport when the content
     * changes, while a removed meta is simply forgotten and the layout would stay at the
     * reader's device width after leaving reading mode.
     */
    function applyViewport() {
        try {
            var head = document.head || document.documentElement;
            if (!head) {
                return;
            }
            var meta = head.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.setAttribute('name', 'viewport');
                head.appendChild(meta);
            }
            meta.setAttribute('content', READER_VIEWPORT);
        } catch (e) {
            // viewport rewrite failed: reader still works, just scaled by the page viewport
        }
    }

    /**
     * First child of the reader that is still (partly) below the top edge: scroll anchor for
     * a reflow. getBoundingClientRect() is viewport relative, so it must not be compared
     * against pageYOffset (document relative) - that mismatch picks a child far below the
     * fold and the compensation then scrolls to the wrong place.
     */
    function topVisibleChild() {
        if (!root) {
            return null;
        }
        var children = root.children;
        for (var i = 0; i < children.length; i++) {
            if (children[i].getBoundingClientRect().bottom > 4) {
                return children[i];
            }
        }
        return null;
    }

    /**
     * Re-apply the stylesheet after a theme / font change. A font change reflows the whole
     * article, so the child at the top of the viewport is used as an anchor and the scroll
     * offset is corrected by however much it drifted - without this the reader silently
     * jumps to another paragraph when the text size changes.
     *
     * The correction is RELATIVE (scrollBy): a WebView with scroll anchoring already
     * compensates the reflow by itself, which leaves the anchor in place and makes the
     * measured drift ~0. An absolute scrollTo(pageYOffset + delta) would use a stale
     * pageYOffset and undo the browser's compensation, throwing the reader off by a screen.
     */
    function restyle() {
        var style = document.getElementById(STYLE_ID);
        if (!style) {
            return;
        }
        var anchor = topVisibleChild();
        var anchorTop = anchor ? anchor.getBoundingClientRect().top : 0;
        style.textContent = buildCss();
        if (!anchor || !anchor.parentNode) {
            return;
        }
        var delta = anchor.getBoundingClientRect().top - anchorTop;
        if (delta) {
            try {
                window.scrollBy(0, delta);
            } catch (e) {
                // scroll compensation failed - viewport may shift slightly
            }
        }
    }

    /** Index in FONT_SIZES closest to the px value stored by the host. */
    function nearestFontIndex(size) {
        var best = DEFAULT_FONT_INDEX;
        var bestDiff = -1;
        for (var i = 0; i < FONT_SIZES.length; i++) {
            var diff = Math.abs(FONT_SIZES[i] - size);
            if (bestDiff < 0 || diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /**
     * Appearance handed over by the host (see module header). Values are validated here so
     * a stale / hand-edited preference can never leave the reader with an unusable layout.
     */
    function initAppearance() {
        var prefs = null;
        try {
            prefs = window.__keyphone_rm_init__;
        } catch (e) {
            prefs = null;
        }
        if (!prefs) {
            return;
        }
        var theme = parseInt(prefs.theme, 10);
        if (!isNaN(theme)) {
            themeIndex = Math.min(THEMES.length - 1, Math.max(0, theme));
        }
        var size = parseInt(prefs.font, 10);
        if (!isNaN(size)) {
            fontIndex = nearestFontIndex(size);
        }
    }

    /**
     * Keys 1 / 3: shrink / grow the body text. Returns the resulting px size - that return
     * value is the channel the host reads (bridge-free, see module header); it is returned
     * unchanged when a bound is reached, so the user sees the value stop changing.
     */
    function changeFont(delta) {
        if (!active) {
            return FONT_SIZES[fontIndex];
        }
        var next = Math.min(FONT_SIZES.length - 1, Math.max(0, fontIndex + (delta > 0 ? 1 : -1)));
        if (next !== fontIndex) {
            fontIndex = next;
            restyle();
        }
        return FONT_SIZES[fontIndex];
    }

    /**
     * Key 7: cycle the reading background (cream / sepia / green / night / black).
     * Returns the new theme index for the host to persist.
     */
    function cycleTheme(step) {
        if (!active) {
            return themeIndex;
        }
        var count = THEMES.length;
        var next = (themeIndex + (step || 1)) % count;
        if (next < 0) {
            next += count;
        }
        themeIndex = next;
        restyle();
        return themeIndex;
    }

    function createPage(html, sourceUrl) {
        var wrap = document.createElement('div');
        wrap.setAttribute(PAGE_ATTR, '1');
        if (sourceUrl) {
            wrap.setAttribute(SRC_ATTR, sourceUrl);
        }
        wrap.innerHTML = html;
        return wrap;
    }

    /**
     * Chapter title of a fetched page: prefer <title> (dropping the site name suffix),
     * then the first h1/h2/h3, then the last URL path segment.
     */
    function pageTitleOf(doc, baseUrl) {
        var candidates = [];
        var raw = '';
        try {
            raw = String(doc.title || '').trim();
        } catch (e) {
            raw = '';
        }
        // a chapter heading is usually cleaner than <title> (which carries the site name)
        try {
            var heading = doc.querySelector('h1,h2,h3');
            if (heading) {
                candidates = [normalizeText(heading.textContent)];
            }
        } catch (e) {
            candidates = [];
        }
        if (!candidates.length || !normalizeText(candidates.join(''))) {
            candidates = [];
            if (raw) {
                candidates = raw.split(TITLE_SPLIT_RE);
            }
        }
        var best = '';
        for (var i = 0; i < candidates.length; i++) {
            var part = normalizeText(candidates[i]);
            if (!part || HOSTLIKE_RE.test(part)) {
                continue;
            }
            if (part.length > best.length) {
                best = part;
            }
        }
        if (!best) {
            try {
                var path = new URL(baseUrl || location.href).pathname.replace(/\/+$/, '');
                var segment = path.split('/').pop() || '';
                best = normalizeText(segment.replace(/\.(html?|php|aspx?|jsp)$/i, ''));
            } catch (e) {
                best = '';
            }
        }
        return best.length > MAX_TITLE_LEN ? best.substring(0, MAX_TITLE_LEN) : best;
    }

    /**
     * Append ONE chapter to the reader root: a title header (reuses the separator
     * styling) + the extracted body. [reset] is only true for the first chapter - after
     * that the scroll position is left untouched so the next chapter continues right
     * below the last line of the previous one (no jump, nothing skipped).
     */
    function appendChapter(entry, reset) {
        if (!root || !entry || !entry.html) {
            return;
        }
        if (reset) {
            root.innerHTML = '';
            pages = [];
        }
        var header = document.createElement('div');
        header.setAttribute(SEP_ATTR, '1');
        header.textContent = entry.title || '';
        var wrap = createPage(entry.html, entry.url);
        root.appendChild(header);
        root.appendChild(wrap);
        pages.push({ header: header, wrap: wrap });
        try {
            if (entry.title) {
                document.title = entry.title;
            }
        } catch (e) {
            // title not writable - the shell <title> already carries a chapter name
        }
        if (reset) {
            try {
                window.scrollTo(0, 0);
            } catch (e) {
                // scrolling unavailable - chapter still readable from the top
            }
        }
        trace('append ' + shortUrl(entry.url) + ' dom=' + pages.length +
            ' text=' + String(textLen(root)) + ' docHeight=' + docHeight());
        trimChapters();
    }

    function docHeight() {
        var docEl = document.documentElement;
        return Math.max(docEl.scrollHeight || 0, document.body ? document.body.scrollHeight || 0 : 0);
    }

    function scrollTop() {
        var docEl = document.documentElement;
        return window.pageYOffset || docEl.scrollTop || (document.body ? document.body.scrollTop : 0) || 0;
    }

    function viewportPx() {
        return window.innerHeight || document.documentElement.clientHeight || 480;
    }

    /**
     * Bound the reader window: keep at most KEEP_CHAPTERS chapters in the DOM and drop
     * the oldest ones. A chapter is only dropped when its bottom is at least
     * TRIM_SAFE_SCREENS screens ABOVE the viewport, i.e. the user has long scrolled past
     * it.
     *
     * Scroll compensation is RELATIVE (same technique as restyle): the first visible
     * child is measured before/after the removal and only the drift it actually moved by
     * is scrolled back. Do NOT subtract the removed height instead: Chromium's scroll
     * anchoring already moves the offset up by that height, and subtracting it a second
     * time throws the reader back by a whole chapter (measured on the device before).
     */
    function trimChapters() {
        if (!root || pages.length <= MIN_CHAPTERS) {
            return;
        }
        var viewport = window.innerHeight || 480;
        while (pages.length > MIN_CHAPTERS && docHeight() > MAX_DOM_PX) {
            var oldest = pages[0];
            if (!oldest || !oldest.wrap || !oldest.wrap.parentNode) {
                pages.shift();
                continue;
            }
            var scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
            var bottom = oldest.wrap.getBoundingClientRect().bottom + scrollY;
            if (bottom > scrollY - viewport * TRIM_SAFE_SCREENS) {
                // still (nearly) on screen - keep the window as it is
                break;
            }
            var anchor = topVisibleChild();
            var anchorTop = anchor ? anchor.getBoundingClientRect().top : 0;
            pages.shift();
            removeNode(oldest.wrap);
            removeNode(oldest.header);
            if (anchor && anchor.parentNode) {
                var delta = anchor.getBoundingClientRect().top - anchorTop;
                if (delta) {
                    try {
                        window.scrollBy(0, delta);
                    } catch (e) {
                        // compensation failed - position may shift slightly
                    }
                }
            }
            trace('trim dom=' + pages.length + ' scroll=' + Math.round(window.pageYOffset || 0));
        }
    }

    /** Cache slot for a normalized url, or null. */
    function cachedChapter(url) {
        if (!url) {
            return null;
        }
        var key = normalizeUrl(url);
        var slots = [chapterCache.prev, chapterCache.current, chapterCache.next];
        for (var i = 0; i < slots.length; i++) {
            if (slots[i] && normalizeUrl(slots[i].url) === key) {
                return slots[i];
            }
        }
        return null;
    }

    /** Common part of every append: bookkeeping + append + prefetch. */
    function advanceTo(entry) {
        currentEntry = entry;
        chapterCache.current = entry;
        chapterCache.next = null;
        pageLog.push({ url: entry.url, title: entry.title });
        seen[normalizeUrl(entry.url)] = true;
        // going back and forward again must not dead-end: `seen` only guards NEW fetches
        nextUrl = entry.next || null;
        ended = !nextUrl;
        if (!ended) {
            // 离开了「最后一章」状态：之后再到达的 end 要允许再次提示
            endNotified = false;
        }
        appendChapter(entry, false);
        lastTurnAt = Date.now();
        notify('turn', entry.url);
        if (!ended) {
            setTimeout(prefetchNext, 300);
        }
    }

    /** Download + extract one chapter. Returns via onDone(entry, detectedNext). */
    function fetchChapter(url, interactive, onDone) {
        if (fetching) {
            if (onDone) {
                onDone(null, null);
            }
            return;
        }
        fetching = true;
        if (interactive) {
            beginLoading();
        }
        diagNextFetches += 1;
        trace('fetch#' + diagNextFetches + ' ' + url);

        // Promise.race based timeout (old WebViews have no AbortController). A hung
        // request used to keep the fetch state true forever, which silently stopped
        // all further page turns for the rest of the session.
        var timeout = new Promise(function (resolve, reject) {
            loadTimer = setTimeout(function () {
                reject(new Error('timeout'));
            }, LOAD_TIMEOUT_MS);
        });

        // no-cache: revalidate with the server (304 reuses the body) so a chapter that
        // was updated in place is not served from a stale cache entry
        Promise.race([
            fetch(url, { credentials: 'include', cache: 'no-cache' }).then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.text();
            }),
            timeout
        ])
            .then(function (html) {
                fetching = false;
                endLoading();
                hideFailHint();
                lastFailAt = 0;
                failCount = 0;
                var doc = null;
                try {
                    doc = new DOMParser().parseFromString(html, 'text/html');
                } catch (e) {
                    doc = null;
                }
                var contentHtml = null;
                if (doc) {
                    try {
                        contentHtml = extractHtml(doc, url);
                    } catch (e) {
                        contentHtml = null;
                    }
                }
                if (!contentHtml) {
                    trace('extract failed (' + String(html.length) + ' chars)');
                    if (interactive) {
                        ended = true;
                        notifyEndOnce();
                    }
                    if (onDone) {
                        onDone(null, null);
                    }
                    return;
                }
                var detected = null;
                try {
                    detected = findNextUrl(doc, url);
                } catch (e) {
                    detected = null;
                }
                if (detected && seen[normalizeUrl(detected)]) {
                    // the site's "next" loops back to a visited chapter: treat as the end
                    detected = null;
                }
                seen[normalizeUrl(url)] = true;
                if (onDone) {
                    onDone({ url: url, title: pageTitleOf(doc, url), html: contentHtml, next: detected }, detected);
                }
            })
            .catch(function (error) {
                fetching = false;
                endLoading();
                trace('fetch failed: ' + (error && error.message ? error.message : 'network error'));
                // keep `nextUrl` so the next key press can retry: only a confirmed
                // "no next page" is allowed to end the session
                failCount += 1;
                lastFailAt = Date.now();
                if (interactive) {
                    showFailHint();
                    notify('fail', error && error.message ? error.message : 'network error');
                }
                if (onDone) {
                    onDone(null, null);
                }
            });
    }

    /** Quietly download the next chapter so a page turn never waits for the network. */
    function prefetchNext() {
        if (!active || fetching || turning || ended || !nextUrl || entryNextCheckPending) {
            return;
        }
        if (lastFailAt && Date.now() - lastFailAt < retryGap()) {
            return;
        }
        if (cachedChapter(nextUrl)) {
            return;
        }
        fetchChapter(nextUrl, false, function (entry) {
            if (entry && active && normalizeUrl(entry.url) === normalizeUrl(nextUrl)) {
                chapterCache.next = entry;
            }
        });
    }

    /**
     * SSR self-check of the entry chapter's next URL (see enterWithContent).
     *
     * The live page the host extracted from may have had the next chapter stitched into
     * the DOM by the site itself (Qidian loads the next chapter into the page as you
     * scroll), so the live-DOM detection can resolve to the chapter AFTER next - one
     * full chapter gets skipped. The entry chapter's own SSR HTML carries the canonical
     * next link, so it is re-detected here on the fetched (clean) document before any
     * stitching starts. [fallback] is the live-DOM value and is only used when the SSR
     * check itself fails (network / no page / parse error).
     */
    function selfCheckEntryNext(url, fallback) {
        trace('entry SSR check ' + (url ? shortUrl(url) : 'none'));
        var timeout = new Promise(function (resolve, reject) {
            setTimeout(function () {
                reject(new Error('timeout'));
            }, LOAD_TIMEOUT_MS);
        });
        Promise.race([
            fetch(url, { credentials: 'include', cache: 'force-cache' }).then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.text();
            }),
            timeout
        ])
            .then(function (html) {
                var doc = null;
                try {
                    doc = new DOMParser().parseFromString(html, 'text/html');
                } catch (e) {
                    doc = null;
                }
                var detected = null;
                if (doc) {
                    try {
                        detected = findNextUrl(doc, url);
                    } catch (e) {
                        detected = null;
                    }
                }
                resolveEntryNext(detected, fallback);
            })
            .catch(function (error) {
                trace('entry SSR check failed: ' +
                    (error && error.message ? error.message : 'network error'));
                resolveEntryNext(null, fallback);
            });
    }

    /** Apply the SSR-checked entry next (or its live-DOM fallback) and start stitching. */
    function resolveEntryNext(ssrNext, fallback) {
        entryNextCheckPending = false;
        if (!active) {
            // reader torn down while the SSR check was in flight: nothing to resume
            return;
        }
        var next = ssrNext || fallback || null;
        if (next && seen[normalizeUrl(next)]) {
            // the detected next loops back to an already-visited chapter: treat as the end
            next = null;
        }
        if (next && normalizeUrl(next) !== normalizeUrl(currentEntry.url)) {
            nextUrl = next;
            ended = false;
            endNotified = false;
        } else {
            nextUrl = null;
            ended = true;
        }
        if (ssrNext && fallback && normalizeUrl(ssrNext) !== normalizeUrl(fallback)) {
            trace('entry next corrected by SSR: live=' + shortUrl(fallback) +
                ' ssr=' + shortUrl(ssrNext));
        }
        trace('entry next resolved to ' + (nextUrl ? shortUrl(nextUrl) : 'none'));
        if (!ended) {
            setTimeout(prefetchNext, 300);
        }
    }

    /**
     * Append the next chapter below the current one. Called by the host when the
     * viewport gets close to the end of the stitched document - the page simply grows,
     * the scroll position is not touched, so the boundary is seamless (nothing at the
     * chapter end is skipped and the reader keeps scrolling through the new text).
     *
     * The next chapter is normally already prefetched; only a cold turn waits for the
     * network (in-page "loading next chapter" hint while it does).
     */
    function appendNext() {
        if (!active || turning || ended || entryNextCheckPending) {
            return 'busy';
        }
        if (!nextUrl) {
            notifyEndOnce();
            return 'end';
        }
        var cached = cachedChapter(nextUrl);
        if (cached) {
            advanceTo(cached);
            return 'ok';
        }
        turning = true;
        beginLoading();
        fetchChapter(nextUrl, true, function (entry, detected) {
            turning = false;
            if (!entry) {
                // failure: state unchanged, the host keeps scrolling into the hint and
                // the next near-end key press retries
                return;
            }
            entry.next = detected;
            advanceTo(entry);
        });
        return 'ok';
    }

    /**
     * Reading progress for the host: one line per stitched chapter, in reading order,
     * encoded as "URL \t TITLE". The host uses it to mirror every chapter into the browser
     * URL history and to know which chapter to reopen when reading mode is left.
     */
    function progress() {
        var out = [];
        for (var i = 0; i < pageLog.length; i++) {
            var entry = pageLog[i];
            if (!entry || !entry.url) {
                continue;
            }
            out.push(
                String(entry.url) + '\t' +
                String(entry.title || '').replace(/[\t\r\n]+/g, ' ')
            );
        }
        return out.join('\n');
    }

    /** Bottom "loading next chapter" hint shown while a page is being fetched. */
    function showLoading() {
        if (!root || document.getElementById(LOADING_ID)) {
            return;
        }
        var box = document.createElement('div');
        box.id = LOADING_ID;
        box.setAttribute(LOADING_ATTR, '1');
        var spin = document.createElement('span');
        spin.className = 'krm-spin';
        var text = document.createElement('span');
        text.textContent = ' \u6b63\u5728\u52a0\u8f7d\u4e0b\u4e00\u7ae0\u2026';
        box.appendChild(spin);
        box.appendChild(text);
        root.appendChild(box);
    }

    function hideLoading() {
        removeNode(document.getElementById(LOADING_ID));
    }

    function beginLoading() {
        loading = true;
        showLoading();
    }

    /** Clears both the loading flag and the hint + pending timeout. */
    function endLoading() {
        loading = false;
        hideLoading();
        if (loadTimer) {
            clearTimeout(loadTimer);
            loadTimer = null;
        }
    }

    /** In-page hint that replaces the loading spinner when a fetch failed (no bridge needed). */
    function showFailHint() {
        if (!root) {
            return;
        }
        hideFailHint();
        var box = document.createElement('div');
        box.id = FAIL_ID;
        // "\u4e0b\u4e00\u9875\u52a0\u8f7d\u5931\u8d25\uff0c\u7ee7\u7eed\u7ffb\u9605\u53ef\u91cd\u8bd5"
        box.textContent = '\u4e0b\u4e00\u9875\u52a0\u8f7d\u5931\u8d25\uff0c\u7ee7\u7eed\u7ffb\u9605\u53ef\u91cd\u8bd5';
        root.appendChild(box);
    }

    function hideFailHint() {
        removeNode(document.getElementById(FAIL_ID));
    }

    /** "end of the last chapter" marker rendered inside the page (no bridge needed). */
    function showEndHint() {
        if (!root || document.getElementById(END_ID)) {
            return;
        }
        var box = document.createElement('div');
        box.id = END_ID;
        box.textContent = '\u2014\u2014 \u5df2\u5230\u6700\u540e\u4e00\u9875 \u2014\u2014';
        root.appendChild(box);
    }

    /** "no more pages" is reported at most once per reading session. */
    function notifyEndOnce() {
        if (!endNotified) {
            endNotified = true;
            showEndHint();
            notify('end', '');
        }
    }

    /** Growing backoff for repeated failures (avoids hammering a broken site). */
    function retryGap() {
        return Math.min(FAIL_RETRY_GAP_MS * Math.max(1, failCount), FAIL_RETRY_MAX_MS);
    }

    /**
     * Heartbeat. Keypad scrolling drives WebView.scrollBy(), which does not dispatch a
     * page scroll event on several devices - stitching must therefore never depend on
     * scroll listeners alone. The pump keeps at least PRE_READ_SCREENS screens of text
     * below the viewport and (re)starts the prefetch of the chapter after next.
     */
    function pump() {
        if (!active || turning) {
            return;
        }
        trimChapters();
        if (!ended && nextUrl &&
            docHeight() - scrollTop() - viewportPx() <= viewportPx() * PRE_READ_SCREENS) {
            appendNext();
        }
        prefetchNext();
    }

    function onRootClick(event) {
        var node = event.target;
        var link = null;
        while (node && node !== root) {
            if (node.tagName === 'A') {
                link = node;
                break;
            }
            node = node.parentNode;
        }
        if (!link) {
            return;
        }
        var url = toHttpUrl(link.getAttribute('href'), location.href);
        if (!url) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        notify('link', url);
    }

    /* ------------------------------------------------------------------ *
     * exit
     * ------------------------------------------------------------------ */

    function teardown() {
        active = false;
        try {
            if (root) {
                root.removeEventListener('click', onRootClick, true);
            }
        } catch (e) {
            // listeners already detached
        }
        root = null;
        pages = [];
        chapterCache = { prev: null, current: null, next: null };
        currentEntry = null;
        pageLog = [];
        nextUrl = null;
        entryNextCheckPending = false;
        ended = false;
        loading = false;
        turning = false;
        lastFailAt = 0;
        failCount = 0;
        endNotified = false;
        if (loadTimer) {
            clearTimeout(loadTimer);
            loadTimer = null;
        }
        if (pumpTimer) {
            clearInterval(pumpTimer);
            pumpTimer = null;
        }
        seen = {};
    }

    /**
     * Tear the reader down. On the clean shell page there is no original DOM to restore
     * (the page is ours), so this only stops the reader; the host navigates back to the
     * real chapter URL.
     */
    function restore() {
        if (!active) {
            return 'ok';
        }
        trace('exit teardown');
        teardown();
        removeNode(document.getElementById(STYLE_ID));
        notify('exit', '');
        return 'ok';
    }

    /* ------------------------------------------------------------------ *
     * render-isolated entry (scheme C): the host extracts once on the source
     * page, then navigates the WebView to a clean shell whose baseUrl is the
     * chapter URL and boots the reader with the extracted content.
     * ------------------------------------------------------------------ */

    /**
     * Read-only extraction for the host.
     *
     * Same [extractHtml] / [findNextUrl] / [pageTitleOf] as [enter], but it changes
     * NOTHING: no body wipe, no viewport rewrite, no listener. The host calls this on the
     * live page, then renders the result on a page of its own (see
     * [enterWithContent]); the source page - and every timer / observer / analytics script
     * the site registered - is discarded with the navigation.
     *
     * Returns a JSON string (the host parses it) so a single evaluateJavascript call can
     * carry html + next + title at once.
     */
    function extractSnapshot() {
        var pageUrl = location.href;
        var contentHtml = null;
        var detectedNext = null;
        var title = '';
        try {
            contentHtml = extractHtml(document, pageUrl);
            if (contentHtml) {
                detectedNext = findNextUrl(document, pageUrl);
                title = pageTitleOf(document, pageUrl);
            }
        } catch (e) {
            contentHtml = null;
        }
        if (!contentHtml) {
            return JSON.stringify({ ok: false, url: pageUrl });
        }
        var len = 0;
        try {
            var probe = document.createElement('div');
            probe.innerHTML = contentHtml;
            len = textLen(probe);
        } catch (e) {
            len = 0;
        }
        return JSON.stringify({
            ok: true,
            url: pageUrl,
            title: title,
            next: detectedNext || '',
            html: contentHtml,
            textLen: len
        });
    }

    /**
     * Start the reader on a clean page from already extracted content.
     *
     * [payload] = {url, title, html, next}. One chapter per page: only this chapter is
     * rendered; the next one is prefetched into the cache while the user reads. There is
     * no original DOM to restore ([backup] never existed here) - exit is handled by the
     * host navigating back to the real chapter URL.
     */
    function enterWithContent(payload) {
        if (active) {
            return 'ok';
        }
        if (!document.body || !payload || !payload.html) {
            return 'empty';
        }
        try {
            document.body.className = '';
            document.body.removeAttribute('style');
            document.body.innerHTML = '';
        } catch (e) {
            return 'error';
        }

        root = document.createElement('div');
        root.id = ROOT_ID;
        document.body.appendChild(root);
        // the shell already carries the reader viewport; rewriting keeps both paths identical
        applyViewport();
        injectStyle();
        root.addEventListener('click', onRootClick, true);

        var entry = {
            url: payload.url || location.href,
            title: String(payload.title || ''),
            html: payload.html,
            next: payload.next ? String(payload.next) : null
        };
        pageLog = [{ url: entry.url, title: entry.title }];
        seen = {};
        seen[normalizeUrl(entry.url)] = true;
        chapterCache = { prev: null, current: entry, next: null };
        pages = [];
        currentEntry = entry;
        // The live-DOM next (entry.next) is deliberately NOT trusted at boot: the site may
        // have stitched the next chapter into the source page, in which case the live DOM
        // resolves to the chapter AFTER the real next, skipping one full chapter. The
        // entry's own SSR page is fetched and its canonical next used instead; the
        // live-DOM value only remains as a fallback for a failed SSR check.
        entryNextCheckPending = true;
        nextUrl = null;
        ended = false;
        loading = false;
        turning = false;
        lastTurnAt = 0;
        lastFailAt = 0;
        failCount = 0;
        endNotified = false;
        active = true;
        if (pumpTimer) {
            clearInterval(pumpTimer);
        }
        pumpTimer = setInterval(pump, PUMP_INTERVAL_MS);

        appendChapter(entry, true);

        trace('enter(content) ' + shortUrl(entry.url) +
            ' next=' + (nextUrl ? shortUrl(nextUrl) : 'none'));
        notify('enter', String(textLen(root)));
        // read-only, never blocks the first screen: stitching waits for the result
        selfCheckEntryNext(entry.url, entry.next);
        return 'ok';
    }

    /* ------------------------------------------------------------------ *
     * public interface
     * ------------------------------------------------------------------ */
    window.__keyphone_rm__ = {
        extract: extractSnapshot,
        enterWithContent: enterWithContent,
        append: appendNext,
        next: appendNext,
        exit: restore,
        destroy: restore,
        isActive: function () {
            return active;
        },
        pageCount: function () {
            return 1;
        },
        nextUrl: function () {
            return nextUrl;
        },
        progress: progress,
        chapterCount: function () {
            return pageLog.length;
        },
        font: changeFont,
        theme: cycleTheme,
        fontSize: function () {
            return FONT_SIZES[fontIndex];
        },
        themeIndex: function () {
            return themeIndex;
        },
        /**
         * Diagnostics for the host (key presses / exit pull the host-side state too):
         *   state active/loading/ended, turn counters and the last steps.
         * Read-only: nothing here mutates the reader.
         */
        diag: function () {
            var docEl = document.documentElement;
            var scrollY = window.pageYOffset || docEl.scrollTop || 0;
            var cached = 0;
            if (chapterCache.prev) cached += 1;
            if (chapterCache.current) cached += 1;
            if (chapterCache.next) cached += 1;
            return [
                'active=' + (active ? 1 : 0),
                'loading=' + (loading ? 1 : 0),
                'turning=' + (turning ? 1 : 0),
                'ended=' + (ended ? 1 : 0),
                'paused=' + (lastFailAt && Date.now() - lastFailAt < retryGap() ? 1 : 0),
                'fails=' + failCount,
                'fetches=' + diagNextFetches,
                'chapters=' + pageLog.length,
                'dom=' + pages.length,
                'cached=' + cached,
                'cur=' + (currentEntry ? shortUrl(currentEntry.url) : '-'),
                'next=' + (nextUrl ? shortUrl(nextUrl) : '-'),
                'root=' + (root ? 1 : 0),
                'entryCheck=' + (entryNextCheckPending ? 1 : 0),
                'scroll=' + Math.round(scrollY),
                'viewport=' + (window.innerHeight || 0),
                'docHeight=' + (Math.max(docEl.scrollHeight || 0, document.body ? document.body.scrollHeight || 0 : 0)),
                'last=' + diagLastEvent,
                'trace=' + diagTrace.join(' | ')
            ].join(' ');
        }
    };

    initAppearance();
    // Boot dispatch:
    // - __keyphone_rm_boot__ set  -> render-isolated host already extracted this chapter
    //   on the source page; render it here (clean page, no site scripts) and turn pages
    //   from the in-memory cache;
    // - __keyphone_rm_noenter__ set -> host only wants the API (extract on the source page),
    //   do not touch the page;
    // - neither -> nothing to do: there is no in-page entry any more (the host always
    //   boots via one of the two flags above).
    var bootPayload = null;
    var bootSuppressed = false;
    try {
        bootPayload = window.__keyphone_rm_boot__ || null;
        bootSuppressed = !!window.__keyphone_rm_noenter__;
    } catch (e) {
        bootPayload = null;
        bootSuppressed = false;
    }
    if (bootPayload) {
        return enterWithContent(bootPayload);
    }
    return 'ok';
})();
