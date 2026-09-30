/*
 * Keyphone reading mode - text-only reader with automatic next-page stitching.
 *
 * Loaded through Mezzanine: src/main/js/KeyphoneReadingMode.js
 *   -> acr.browser.lightning.js.KeyphoneReadingMode.provideJs()
 *
 * The file body is an IIFE that installs window.__keyphone_rm__ and immediately enters
 * reading mode. evaluateJavascript() receives the IIFE result:
 *   "ok"    reading mode is active
 *   "empty" no main content recognised
 *   "error" unexpected failure
 *
 * Kotlin (KeyphoneBrowserActivity) keeps per-tab state and receives user feedback from
 * window.__keyphoneReadingBridge__.onEvent(type, payload), with types:
 *   "enter"  payload = extracted text length
 *   "append" payload = total stitched page count
 *   "end"    no further page found / nothing left to stitch
 *   "fail"   next page download failed (payload = reason)
 *   "link"   user tapped a link inside the article (payload = absolute url)
 *   "exit"   reading mode left, original DOM restored
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
    var KEEP_PAGES = 5;              // max stitched pages kept in the DOM
    var MIN_CONTENT_CHARS = 200;     // minimum amount of text accepted as article body
    var SCROLL_READ_AHEAD_RATIO = 1.2; // pre-read window = viewport height * ratio
    var SCROLL_MIN_THRESHOLD = 400;  // px floor of the pre-read window
    var SCROLL_THROTTLE_MS = 200;    // scroll handler throttle
    var MAX_LINK_TEXT_LEN = 12;      // max length of a "next page" link label
    var MAX_NAV_LABEL_LEN = 24;      // max length of a navigation / meta label
    var MAX_BARE_URL_LEN = 60;       // max length of a bare host / url text block
    var MAX_TITLE_LEN = 40;          // max length of the stitched chapter title
    var SCAN_LIMIT = 1500;           // max nodes scanned while picking the article body
    var LOAD_TIMEOUT_MS = 12000;     // give up waiting for a slow next page after this
    var FAIL_RETRY_GAP_MS = 2000;    // minimum gap before retrying a failed request
    var FAIL_RETRY_MAX_MS = 15000;   // backoff ceiling for repeated failures
    var PUMP_INTERVAL_MS = 800;      // stitcher heartbeat: WebView scrollBy() often does
                                     // NOT fire a page scroll event, so nearBottom() alone
                                     // is not enough on keypad devices
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

    var READER_CSS = [
        'html,body{background:#F4F4EE!important;color:#222!important;}',
        'body{overflow-x:hidden!important;}',
        '#' + ROOT_ID + '{box-sizing:border-box!important;max-width:100%!important;',
        'padding:10px 10px 30px 10px!important;font-family:sans-serif!important;',
        'font-size:16px!important;line-height:1.8!important;word-wrap:break-word!important;}',
        '#' + ROOT_ID + ' p{' + 'color:#222!important;background:transparent!important;',
        'font-size:16px!important;line-height:1.8!important;margin:0 0 0.8em 0!important;',
        'text-indent:0!important;}',
        '#' + ROOT_ID + ' div,#' + ROOT_ID + ' td,#' + ROOT_ID + ' span,#' + ROOT_ID + ' li{',
        'color:#222!important;background:transparent!important;font-size:16px!important;',
        'line-height:1.8!important;}',
        '#' + ROOT_ID + ' h1,#' + ROOT_ID + ' h2,#' + ROOT_ID + ' h3{',
        'color:#111!important;font-size:18px!important;line-height:1.6!important;',
        'margin:0.7em 0!important;}',
        '#' + ROOT_ID + ' a{color:#0B62C4!important;text-decoration:none!important;}',
        '#' + ROOT_ID + ' img,#' + ROOT_ID + ' video,#' + ROOT_ID + ' audio,',
        '#' + ROOT_ID + ' iframe,#' + ROOT_ID + ' canvas{display:none!important;}',
        '[' + SEP_ATTR + ']{text-align:center!important;color:#333!important;',
        'font-size:14px!important;font-weight:bold!important;',
        'margin:28px 0 18px 0!important;padding:10px 0!important;',
        'border-top:3px solid #9a9a90!important;border-bottom:3px solid #9a9a90!important;',
        'letter-spacing:1px!important;word-break:break-all!important;}',
        '#' + LOADING_ID + '{text-align:center!important;color:#777!important;',
        'font-size:13px!important;margin:16px 0 24px 0!important;padding:6px 0!important;}',
        '#' + END_ID + '{text-align:center!important;color:#999!important;',
        'font-size:13px!important;margin:22px 0 30px 0!important;}',
        '#' + FAIL_ID + '{text-align:center!important;color:#b25a00!important;',
        'font-size:13px!important;margin:16px 0 24px 0!important;padding:6px 0!important;}',
        '.krm-spin{display:inline-block!important;width:12px!important;height:12px!important;',
        'border:2px solid #c8c8c0!important;border-top-color:#666!important;',
        'border-radius:50%!important;vertical-align:middle!important;',
        'animation:krm-spin 0.9s linear infinite!important;}',
        '@keyframes krm-spin{from{transform:rotate(0deg);}to{transform:rotate(360deg);}}'
    ].join('');

    /* ------------------------------------------------------------------ *
     * state
     * ------------------------------------------------------------------ */
    var active = false;
    var root = null;
    var pages = [];
    var pageSeq = 0;
    var nextUrl = null;
    var loading = false;
    var ended = false;
    var seen = {};
    var backup = null;
    var lastScrollCheck = 0;
    var lastFailAt = 0;
    var failCount = 0;
    var loadTimer = null;
    var pumpTimer = null;
    var endNotified = false;

    /* ------------------------------------------------------------------ *
     * helpers
     * ------------------------------------------------------------------ */
    function notify(type, payload) {
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

    function extractHtml(doc, baseUrl) {
        var source = pickContainer(doc);
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
        style.textContent = READER_CSS;
        (document.head || document.documentElement).appendChild(style);
    }

    function createPage(html, seq, sourceUrl) {
        var wrap = document.createElement('div');
        wrap.setAttribute(PAGE_ATTR, String(seq));
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

    /** Break between stitched pages: full-width rules above/below with the chapter title. */
    function createSeparator(seq, titleText) {
        var sep = document.createElement('div');
        sep.setAttribute(SEP_ATTR, '1');
        sep.textContent = titleText ? (seq + '. ' + titleText) : String(seq);
        return sep;
    }

    /**
     * Keep only the newest KEEP_PAGES pages: the user asked for the oldest stitched
     * pages to be dropped so the DOM (and therefore memory) stays bounded. The scroll
     * position is compensated by the removed height so the viewport does not jump.
     */
    function trimPages() {
        var viewport = window.innerHeight || 480;
        while (pages.length > KEEP_PAGES) {
            var oldest = pages[0];
            if (!oldest) {
                pages.shift();
                continue;
            }
            var scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
            var rect = oldest.getBoundingClientRect();
            var docTop = rect.top + scrollY;
            var pageSize = rect.height || 0;
            // Only drop a page once it is at least two screens above the viewport.
            // Trimming (plus scroll compensation) while the reader is actively scrolling
            // is what makes the stitched view feel "stuck" on slow devices.
            if (docTop + pageSize > scrollY - viewport * 2) {
                break;
            }
            pages.shift();
            var beforeHeight = root ? root.offsetHeight : 0;
            var sep = oldest.previousElementSibling;
            removeNode(oldest);
            if (sep && sep.hasAttribute && sep.hasAttribute(SEP_ATTR)) {
                removeNode(sep);
            }
            var afterHeight = root ? root.offsetHeight : 0;
            var removed = beforeHeight - afterHeight;
            var nowY = window.pageYOffset || document.documentElement.scrollTop || 0;
            if (removed > 0 && nowY > docTop) {
                try {
                    window.scrollTo(0, Math.max(0, nowY - removed));
                } catch (e) {
                    // scroll compensation failed - viewport may shift slightly
                }
            }
        }
    }

    function appendPage(html, sourceUrl, titleText) {
        if (!root) {
            return;
        }
        root.appendChild(createSeparator(pageSeq, titleText));
        var wrap = createPage(html, pageSeq, sourceUrl);
        root.appendChild(wrap);
        pages.push(wrap);
        pageSeq += 1;
        trimPages();
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
     * Stitcher heartbeat. Keypad scrolling drives WebView.scrollBy(), which on several
     * devices does not dispatch a page scroll event - the reader then sat at the chapter
     * end until the user touched the screen. The pump re-checks the pre-read window on a
     * timer so stitching never depends on scroll events.
     */
    function pump() {
        if (!active || loading || ended || !nextUrl) {
            return;
        }
        if (lastFailAt && Date.now() - lastFailAt < retryGap()) {
            return;
        }
        if (needsMore()) {
            appendNext();
        }
    }

    /** Pre-read window in px: at least SCROLL_MIN_THRESHOLD, otherwise ~1.2 screens. */
    function scrollThreshold() {
        var viewport = window.innerHeight || 480;
        return Math.max(SCROLL_MIN_THRESHOLD, Math.round(viewport * SCROLL_READ_AHEAD_RATIO));
    }

    function nearBottom() {
        var docEl = document.documentElement;
        var body = document.body;
        var scrollY = window.pageYOffset || docEl.scrollTop || (body ? body.scrollTop : 0) || 0;
        var viewport = window.innerHeight || docEl.clientHeight || 0;
        var total = Math.max(docEl.scrollHeight || 0, body ? (body.scrollHeight || 0) : 0);
        return (total - scrollY - viewport) <= scrollThreshold();
    }

    /**
     * "Pre-read" condition: either the viewport is already inside the pre-read window,
     * or the stitched content is still shorter than two screens (keeps at least one
     * screen of content ready below the fold so reading never waits for the network).
     */
    function needsMore() {
        if (!root) {
            return false;
        }
        var viewport = window.innerHeight || 480;
        if (root.scrollHeight < viewport * 2) {
            return true;
        }
        return nearBottom();
    }

    function appendNext() {
        if (!active || loading || ended || !nextUrl) {
            return;
        }
        if (lastFailAt && Date.now() - lastFailAt < retryGap()) {
            return;
        }
        var url = nextUrl;
        beginLoading();

        // Promise.race based timeout (old WebViews have no AbortController). A hung
        // request used to keep `loading` true forever, which silently stopped all
        // further stitching for the rest of the session.
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
                endLoading();
                hideFailHint();
                lastFailAt = 0;
                failCount = 0;
                var doc = new DOMParser().parseFromString(html, 'text/html');
                if (!doc) {
                    ended = true;
                    notifyEndOnce();
                    return;
                }
                var contentHtml = null;
                try {
                    contentHtml = extractHtml(doc, url);
                } catch (e) {
                    contentHtml = null;
                }
                if (!contentHtml) {
                    ended = true;
                    notifyEndOnce();
                    return;
                }

                seen[normalizeUrl(url)] = true;
                appendPage(contentHtml, url, pageTitleOf(doc, url));
                notify('append', String(pageSeq - 1));

                var detected = null;
                try {
                    detected = findNextUrl(doc, url);
                } catch (e) {
                    detected = null;
                }
                if (!detected || seen[normalizeUrl(detected)]) {
                    nextUrl = null;
                    ended = true;
                    notifyEndOnce();
                } else {
                    nextUrl = detected;
                    // keep at least one screen stitched ahead of the reader
                    if (needsMore()) {
                        setTimeout(function () {
                            appendNext();
                        }, 250);
                    }
                }
            })
            .catch(function (error) {
                endLoading();
                // keep `nextUrl` so the pump / next key press can retry: only a confirmed
                // "no next page" is allowed to end the session
                failCount += 1;
                lastFailAt = Date.now();
                showFailHint();
                notify('fail', error && error.message ? error.message : 'network error');
            });
    }

    function onScroll() {
        if (!active || loading || ended || !nextUrl) {
            return;
        }
        var now = Date.now();
        if (now - lastScrollCheck < SCROLL_THROTTLE_MS) {
            return;
        }
        lastScrollCheck = now;
        if (nearBottom()) {
            appendNext();
        }
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
     * enter / exit
     * ------------------------------------------------------------------ */
    function enter() {
        if (active) {
            return 'ok';
        }
        if (!document.body) {
            return 'error';
        }
        // A reader root that survived without a usable instance (e.g. the previous
        // instance could not restore its backup) must not be extracted: it would
        // duplicate the already stitched pages. Refuse instead of corrupting the DOM.
        if (document.getElementById(ROOT_ID)) {
            return 'error';
        }

        var pageUrl = location.href;
        var contentHtml = null;
        var detectedNext = null;
        try {
            contentHtml = extractHtml(document, pageUrl);
            if (contentHtml) {
                detectedNext = findNextUrl(document, pageUrl);
            }
        } catch (e) {
            contentHtml = null;
        }
        if (!contentHtml) {
            return 'empty';
        }

        backup = {
            bodyHtml: document.body.innerHTML,
            bodyClass: document.body.getAttribute('class') || '',
            bodyStyle: document.body.getAttribute('style') || '',
            title: document.title,
            scrollY: window.pageYOffset || document.documentElement.scrollTop || 0
        };

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

        pages = [];
        pageSeq = 1;
        var first = createPage(contentHtml, pageSeq, pageUrl);
        root.appendChild(first);
        pages.push(first);
        pageSeq += 1;

        injectStyle();
        root.addEventListener('click', onRootClick, true);
        window.addEventListener('scroll', onScroll, true);
        document.addEventListener('scroll', onScroll, true);

        seen = {};
        seen[normalizeUrl(pageUrl)] = true;
        nextUrl = detectedNext;
        ended = !nextUrl;
        loading = false;
        lastScrollCheck = 0;
        active = true;
        if (pumpTimer) {
            clearInterval(pumpTimer);
        }
        pumpTimer = setInterval(pump, PUMP_INTERVAL_MS);

        try {
            window.scrollTo(0, 0);
        } catch (e) {
            // scrolling unavailable - article still readable from the top
        }

        notify('enter', String(textLen(root)));
        if (needsMore()) {
            setTimeout(function () {
                appendNext();
            }, 300);
        }
        return 'ok';
    }

    function teardown() {
        active = false;
        try {
            if (root) {
                root.removeEventListener('click', onRootClick, true);
            }
            window.removeEventListener('scroll', onScroll, true);
            document.removeEventListener('scroll', onScroll, true);
        } catch (e) {
            // listeners already detached
        }
        root = null;
        pages = [];
        nextUrl = null;
        ended = false;
        loading = false;
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

    function restore() {
        if (!active) {
            return 'ok';
        }
        var snapshot = backup;
        teardown();
        if (snapshot) {
            try {
                document.body.innerHTML = snapshot.bodyHtml;
                if (snapshot.bodyClass) {
                    document.body.setAttribute('class', snapshot.bodyClass);
                } else {
                    document.body.removeAttribute('class');
                }
                if (snapshot.bodyStyle) {
                    document.body.setAttribute('style', snapshot.bodyStyle);
                } else {
                    document.body.removeAttribute('style');
                }
                if (snapshot.title) {
                    document.title = snapshot.title;
                }
                var targetY = snapshot.scrollY || 0;
                setTimeout(function () {
                    try {
                        window.scrollTo(0, targetY);
                    } catch (e) {
                        // viewport restore failed - harmless
                    }
                }, 0);
            } catch (e) {
                // restore failed: the reader layout stays until the page is reloaded
            }
        }
        backup = null;
        removeNode(document.getElementById(STYLE_ID));
        notify('exit', '');
        return 'ok';
    }

    /* ------------------------------------------------------------------ *
     * public interface
     * ------------------------------------------------------------------ */
    window.__keyphone_rm__ = {
        enter: enter,
        exit: restore,
        destroy: restore,
        append: appendNext,
        isActive: function () {
            return active;
        },
        pageCount: function () {
            return pages.length;
        },
        nextUrl: function () {
            return nextUrl;
        }
    };

    return enter();
})();
