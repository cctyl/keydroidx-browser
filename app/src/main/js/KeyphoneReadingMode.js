/*
 * Keyphone reading mode - chapter retriever (text rendering lives in Kotlin).
 *
 * Loaded through Mezzanine: src/main/js/KeyphoneReadingMode.js
 *   -> acr.browser.lightning.js.KeyphoneReadingMode.provideJs()
 *
 * Architecture (native TextView display + WebView as a fetch tool only):
 *   1. the host extracts the article of the CURRENT page read-only (extract()) and shows it
 *      in a native TextView (`KeyphoneReaderView`) - the WebView is NOT used for display;
 *   2. the host then hides the WebView and navigates it to a clean shell whose baseUrl is
 *      the chapter URL, so this script runs same-origin and `fetch`/XHR carry the cookies;
 *   3. every time the reader runs out of queued chapters the host calls fetchNext(), which
 *      downloads the next chapter SYNCHRONOUSLY and returns it as the evaluateJavascript
 *      result - no polling timer, no heartbeat, nothing burning CPU while the user reads.
 *
 * Why synchronous XHR: this device never delivers addJavascriptInterface events to an
 * already loaded page (see KeyphoneReadingBridge), so an async fetch has no reliable way
 * back to Kotlin. A synchronous request runs on the renderer's script thread (not the UI
 * thread), and evaluateJavascript() hands the return value back on the UI thread, which
 * makes "download one chapter" a single round trip. The async fetch is still kept as a
 * fallback: if the synchronous request is rejected by the WebView, it starts an async
 * fetch, caches the result and reports it over the bridge (best effort); the next call of
 * fetchNext() returns the cached chapter.
 *
 * Host -> JS:
 *   window.__keyphone_rm_boot__ = {url: <ch. url>, next: <next url or ''>, seen: [<url>...]}
 *   evaluated together with this file when the shell is loaded (see buildReaderFetchShell).
 *
 * JS -> host (window.__keyphone_rm__):
 *   extract()     read-only extraction for the source page, returns a JSON string
 *                 {ok, url, title, next, html, textLen}
 *   fetchNext()   downloads the next chapter, returns a JSON string
 *                 {ok:true, url, title, html, next}   chapter ready
 *                 {ok:false, end:true}                no further chapter
 *                 {ok:false, pending:true}            async fallback in flight, retry later
 *                 {ok:false, error:<msg>}             failed, retry later
 *   nextUrl()     the next chapter URL this shell will fetch ('' when none)
 *   currentUrl()  the chapter URL the shell currently represents
 *   diag()        read-only diagnostics string for the log
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
    var MAX_LINK_TEXT_LEN = 12;      // max length of a "next page" link label
    var MAX_NAV_LABEL_LEN = 24;      // max length of a navigation / meta label
    var MAX_BARE_URL_LEN = 60;       // max length of a bare host / url text block
    var MAX_TITLE_LEN = 40;          // max length of the chapter title
    var SCAN_LIMIT = 1500;           // max nodes scanned while picking the article body
    var LOAD_TIMEOUT_MS = 12000;     // give up waiting for a slow async fetch after this
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

    /* ------------------------------------------------------------------ *
     * state
     * ------------------------------------------------------------------ */
    var currentUrl = '';             // chapter URL this shell represents
    var nextUrl = null;              // chapter to download next (null = none)
    var seen = {};                   // normalized urls already consumed by the host
    var entryChecked = false;        // SSR self-check of the boot next url already done
    var busy = false;                // a synchronous fetch is in flight
    var asyncJob = null;             // {url, entry, startedAt} async fallback
    var diagFetches = 0;
    var diagLast = '';
    var diagTrace = [];
    var DIAG_TRACE_MAX = 12;

    /* ------------------------------------------------------------------ *
     * helpers
     * ------------------------------------------------------------------ */
    function trace(message) {
        try {
            diagLast = String(message);
            diagTrace.push(shortUrl(String(message)));
            if (diagTrace.length > DIAG_TRACE_MAX) {
                diagTrace.shift();
            }
        } catch (e) {
            // diagnostics must never break the fetch path
        }
    }

    function shortUrl(text) {
        return String(text).replace(/^https?:\/\//i, '');
    }

    function notify(type, payload) {
        try {
            var bridge = window[BRIDGE_NAME];
            if (bridge && typeof bridge.onEvent === 'function') {
                bridge.onEvent(String(type), payload === undefined || payload === null ? '' : String(payload));
            }
        } catch (e) {
            // bridge detached / unavailable - the host reads the return value instead
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

    function sameUrl(a, b) {
        return !!a && !!b && normalizeUrl(a) === normalizeUrl(b);
    }

    function parseHtml(html) {
        try {
            return new DOMParser().parseFromString(html, 'text/html');
        } catch (e) {
            return null;
        }
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
            var genericScore = scoreCandidate(pool[i]);
            if (genericScore > bestScore) {
                bestScore = genericScore;
                best = pool[i];
            }
        }
        if (best && bestScore >= MIN_CONTENT_CHARS) {
            return best;
        }
        return doc.body || null;
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

    /**
     * Chapter title of a fetched page: prefer the first h1/h2/h3 (cleaner than <title>,
     * which carries the site name), then <title> parts, then the last URL segment.
     */
    function pageTitleOf(doc, baseUrl) {
        var candidates = [];
        var raw = '';
        try {
            raw = String(doc.title || '').trim();
        } catch (e) {
            raw = '';
        }
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

    /* ------------------------------------------------------------------ *
     * host entry point: read-only extraction on the source page
     * ------------------------------------------------------------------ */

    /**
     * Read-only extraction for the host.
     *
     * Changes NOTHING on the page (no body wipe, no viewport rewrite, no listener): the
     * host calls this on the live page, shows the text in its native reader view and then
     * navigates the WebView away to the clean fetch shell.
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

    /* ------------------------------------------------------------------ *
     * chapter retrieval (clean shell, same origin as the chapter URL)
     * ------------------------------------------------------------------ */

    /**
     * The entry chapter's next URL is NOT trusted from the live DOM: some sites stitch the
     * next chapter into the page while scrolling, so the live DOM may resolve to the
     * chapter AFTER the real next (skipping one chapter). The chapter's own SSR page
     * carries the canonical link, so it is re-detected here once before the first fetch;
     * the live value is only the fallback when the check itself fails.
     */
    function resolveNext() {
        if (entryChecked) {
            return nextUrl;
        }
        entryChecked = true;
        if (!currentUrl) {
            return nextUrl;
        }
        try {
            var html = xhrText(currentUrl);
            var doc = parseHtml(html);
            var detected = doc ? findNextUrl(doc, currentUrl) : null;
            if (detected && !seen[normalizeUrl(detected)]) {
                if (nextUrl && !sameUrl(detected, nextUrl)) {
                    trace('entry next corrected by SSR: live=' + shortUrl(nextUrl) +
                        ' ssr=' + shortUrl(detected));
                }
                nextUrl = detected;
            }
        } catch (e) {
            trace('entry SSR check failed: ' + (e && e.message ? e.message : 'error'));
        }
        return nextUrl;
    }

    /** Blocking GET. Runs on the renderer's script thread, never on the UI thread. */
    function xhrText(url) {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, false);
        xhr.send(null);
        if (!xhr.status || xhr.status < 200 || xhr.status >= 300) {
            throw new Error('HTTP ' + xhr.status);
        }
        return xhr.responseText;
    }

    function chapterFrom(html, url) {
        var doc = parseHtml(html);
        if (!doc) {
            return null;
        }
        var content;
        try {
            content = extractHtml(doc, url);
        } catch (e) {
            content = null;
        }
        if (!content) {
            return null;
        }
        var detected = null;
        try {
            detected = findNextUrl(doc, url);
        } catch (e) {
            detected = null;
        }
        if (detected && seen[normalizeUrl(detected)]) {
            // the site's "next" loops back to an already visited chapter: treat as the end
            detected = null;
        }
        return {
            ok: true,
            url: url,
            title: pageTitleOf(doc, url),
            html: content,
            next: detected || ''
        };
    }

    /** Async fallback (see the module header): cache the chapter, retry on the next call. */
    function startAsync(target) {
        if (asyncJob) {
            return;
        }
        asyncJob = { url: target, entry: null, startedAt: Date.now() };
        trace('async fetch ' + shortUrl(target));
        var timer = null;
        var timeout = new Promise(function (resolve, reject) {
            timer = setTimeout(function () {
                reject(new Error('timeout'));
            }, LOAD_TIMEOUT_MS);
        });
        Promise.race([
            fetch(target, { credentials: 'include', cache: 'no-cache' }).then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.text();
            }),
            timeout
        ]).then(function (html) {
            if (timer) {
                clearTimeout(timer);
            }
            var entry = chapterFrom(html, target);
            if (entry) {
                asyncJob = { url: target, entry: entry, startedAt: Date.now() };
                seen[normalizeUrl(target)] = true;
                notify('chapter', JSON.stringify(entry));
                trace('async ok ' + shortUrl(target));
            } else {
                asyncJob = null;
                trace('async extract failed ' + shortUrl(target));
            }
        }).catch(function (error) {
            if (timer) {
                clearTimeout(timer);
            }
            asyncJob = null;
            trace('async failed: ' + (error && error.message ? error.message : 'network error'));
        });
    }

    /**
     * Download the next chapter and return it as the evaluateJavascript result.
     *
     * The synchronous request makes this a single round trip for the host; if the WebView
     * refuses it, an async fetch is started and its result is picked up by the next call
     * (or reported over the bridge, when available).
     */
    function fetchNext() {
        if (!currentUrl) {
            // Never booted (source page / shell still loading): the host must not read this
            // as "no more chapters" - it retries or reloads the shell instead.
            return JSON.stringify({ ok: false, notready: true });
        }
        var target = resolveNext();
        if (!target) {
            return JSON.stringify({ ok: false, end: true });
        }
        if (asyncJob && sameUrl(asyncJob.url, target) && asyncJob.entry) {
            var cached = asyncJob.entry;
            asyncJob = null;
            nextUrl = cached.next ? cached.next : null;
            currentUrl = cached.url;
            trace('async hit ' + shortUrl(cached.url));
            return JSON.stringify(cached);
        }
        if (busy) {
            return JSON.stringify({ ok: false, pending: true, error: 'busy' });
        }
        busy = true;
        diagFetches += 1;
        trace('fetch#' + diagFetches + ' ' + shortUrl(target));
        try {
            var html = xhrText(target);
            var entry = chapterFrom(html, target);
            busy = false;
            if (!entry) {
                trace('sync extract failed (' + String(html ? html.length : 0) + ' chars)');
                startAsync(target);
                return JSON.stringify({ ok: false, pending: true, error: 'extract' });
            }
            seen[normalizeUrl(target)] = true;
            nextUrl = entry.next ? entry.next : null;
            currentUrl = entry.url;
            notify('chapter', JSON.stringify(entry));
            return JSON.stringify(entry);
        } catch (e) {
            busy = false;
            var message = e && e.message ? e.message : 'network error';
            trace('sync fetch failed: ' + message);
            startAsync(target);
            return JSON.stringify({ ok: false, pending: true, error: message });
        }
    }

    /* ------------------------------------------------------------------ *
     * public interface
     * ------------------------------------------------------------------ */
    window.__keyphone_rm__ = {
        extract: extractSnapshot,
        fetchNext: fetchNext,
        nextUrl: function () {
            return nextUrl || '';
        },
        currentUrl: function () {
            return currentUrl;
        },
        /**
         * Read-only diagnostics: the host logs one line per fetch, so a failed chapter
         * leaves a trace even though the bridge is not reliable on this device.
         */
        diag: function () {
            return [
                'url=' + shortUrl(currentUrl || '-'),
                'next=' + (nextUrl ? shortUrl(nextUrl) : '-'),
                'checked=' + (entryChecked ? 1 : 0),
                'busy=' + (busy ? 1 : 0),
                'async=' + (asyncJob ? 1 : 0),
                'fetches=' + diagFetches,
                'last=' + diagLast,
                'trace=' + diagTrace.join(' | ')
            ].join(' ');
        }
    };

    // Boot dispatch: the shell always carries __keyphone_rm_boot__ (see
    // KeyphoneBrowserActivity.buildReaderFetchShell). Nothing is rendered here - the host
    // displays the text natively and calls fetchNext() when it needs the next chapter.
    try {
        var boot = window.__keyphone_rm_boot__ || null;
        if (boot) {
            currentUrl = String(boot.url || '');
            nextUrl = boot.next ? String(boot.next) : null;
            var urls = boot.seen || [];
            for (var i = 0; i < urls.length; i++) {
                if (urls[i]) {
                    seen[normalizeUrl(String(urls[i]))] = true;
                }
            }
            if (currentUrl) {
                seen[normalizeUrl(currentUrl)] = true;
            }
            trace('boot ' + shortUrl(currentUrl) + ' next=' + (nextUrl ? shortUrl(nextUrl) : '-'));
        }
    } catch (e) {
        // boot payload missing / malformed: fetchNext() will simply report "end"
    }
    return 'ok';
})();
