/*
 * Keyphone cursor scroll targeting - scroll the element the user is pointing at.
 *
 * Problem: WebView.scrollBy()/scrollTo() always scroll the document scroller. On
 * mobile sites a drawer / sheet is a fixed overlay with its own scroll container
 * (m.xxsy.net: div.flex-1.overflow-auto inside div.fixed.z-3) and the page behind
 * is scroll-locked while it is open, so the old code either scrolled the page
 * behind the drawer or - with the page locked - scrolled nothing at all.
 *
 * Targeting rules, in order:
 *   1. nearest scrollable ancestor of the element under the cursor (a real mouse
 *      wheel behaves like this);
 *   2. otherwise, if a modal overlay (fixed/absolute, most of the viewport, not a
 *      plain wrapper of the page content) is on screen, the scroll belongs to it -
 *      the cursor position is irrelevant here, which is what makes a drawer work
 *      even when the cursor sits on the bottom toolbar or on the dimmed backdrop;
 *   3. otherwise "doc": the host scrolls the document root exactly like before.
 *
 * Injected by KeyphoneBrowserActivity (Mezzanine -> acr.browser.lightning.js.KeyphoneScroll).
 * Installed as window.__keyphone_scroll__, returns "ok" (idempotent).
 *
 * window.__keyphone_scroll__.by(x, y, dx, dy, viewW, viewH, resolveAgain)
 *   x, y          cursor position in WebView view pixels
 *   dx, dy        scroll delta in view pixels (one axis is always 0)
 *   viewW, viewH  WebView size in view pixels, used to map to CSS pixels
 *   resolveAgain  1 = re-pick the target (first frame of a gesture)
 * returns: "scroll"  scrolled
 *          "end"     the target cannot scroll further in this direction
 *          "consume" a modal overlay swallowed the scroll
 *          "doc"     no element target: host should scroll the document (old path)
 */
(function () {
  if (window.__keyphone_scroll__) return 'ok';

  var MIN_OVERFLOW = 1;
  var OVERLAY_RATIO = 0.3;

  /* Reliable probes for finding an on-screen overlay without scanning the DOM. */
  var PROBE_POINTS = [
    [0.15, 0.25], [0.15, 0.5], [0.15, 0.75],
    [0.5, 0.25], [0.5, 0.5], [0.5, 0.75],
    [0.85, 0.25], [0.85, 0.5], [0.85, 0.75]
  ];

  function isScrollOverflow(v) {
    return v === 'auto' || v === 'scroll' || v === 'overlay';
  }

  function axisOverflow(el, vertical) {
    var cs = getComputedStyle(el);
    return vertical ? cs.overflowY : cs.overflowX;
  }

  function overflowLen(el, vertical) {
    return vertical
      ? el.scrollHeight - el.clientHeight
      : el.scrollWidth - el.clientWidth;
  }

  function viewLen(el, vertical) {
    return vertical ? el.clientHeight : el.clientWidth;
  }

  function totalLen(el, vertical) {
    return vertical ? el.scrollHeight : el.scrollWidth;
  }

  function posOf(el, vertical) {
    return vertical ? el.scrollTop : el.scrollLeft;
  }

  /* Element is a real scroll container for this axis? */
  function isScroller(el, vertical) {
    if (!el || !el.getBoundingClientRect) return false;
    if (!isScrollOverflow(axisOverflow(el, vertical))) return false;
    return overflowLen(el, vertical) > MIN_OVERFLOW;
  }

  /* Still room left in this direction? */
  function canScrollMore(el, vertical, dir) {
    if (!el || !el.isConnected) return false;
    if (overflowLen(el, vertical) <= MIN_OVERFLOW) return false;
    var pos = posOf(el, vertical);
    if (dir < 0) return pos > 0;
    return pos + viewLen(el, vertical) < totalLen(el, vertical) - 1;
  }

  /* body / html scroll through the host WebView (old, proven path). */
  function isDocRoot(el) {
    return !el || el === document.body || el === document.documentElement;
  }

  /* A layer painted over the page content: fixed/absolute and mostly viewport sized. */
  function isOverlay(el) {
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    var r = el.getBoundingClientRect();
    var vw = document.documentElement.clientWidth;
    var vh = document.documentElement.clientHeight;
    return r.width >= vw * OVERLAY_RATIO && r.height >= vh * OVERLAY_RATIO;
  }

  function depthOf(el) {
    var d = 0;
    while (el && el.parentElement) {
      d++;
      el = el.parentElement;
    }
    return d;
  }

  /* Diagnostics helper: short readable description of an element. */
  function describe(el) {
    if (!el) return 'null';
    var cs = getComputedStyle(el);
    var r = el.getBoundingClientRect();
    return el.tagName + '.' + ('' + el.className).slice(0, 45) +
      ' pos=' + cs.position + ' z=' + cs.zIndex + ' ov=' + cs.overflowX + '/' + cs.overflowY +
      ' [' + Math.round(r.left) + ',' + Math.round(r.top) + ',' +
      Math.round(r.width) + ',' + Math.round(r.height) + ']';
  }

  /*
   * The page content container: the tallest direct child of body. Used to tell a
   * real overlay apart from a plain wrapper that merely contains the content
   * (many mobile sites wrap everything in div.fixed.inset-0).
   */
  function contentRoot() {
    var kids = document.body ? document.body.children : null;
    if (!kids || !kids.length) return null;
    var best = null;
    var bestLen = -1;
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i];
      var len = Math.max(el.scrollHeight || 0, el.offsetHeight || 0);
      if (len > bestLen) {
        bestLen = len;
        best = el;
      }
    }
    return best;
  }

  /*
   * Deepest scroller inside an overlay, preferring one that covers the cursor.
   * Subtree scan only: overlays are small, and this runs once per gesture.
   */
  function scrollerInside(root, vertical, cx, cy) {
    var best = null;
    var bestDepth = -1;
    var bestCover = null;
    var bestCoverDepth = -1;
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!isScroller(el, vertical)) continue;
      var d = depthOf(el);
      if (d > bestDepth) {
        bestDepth = d;
        best = el;
      }
      var r = el.getBoundingClientRect();
      if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom && d > bestCoverDepth) {
        bestCoverDepth = d;
        bestCover = el;
      }
    }
    return bestCover || best;
  }

  /* Overlay detection result, cached per gesture (the drawer does not move mid-hold). */
  var overlayStamp = -1;
  var overlayEl = null;
  var overlayScrollerEl = null;

  function findModalOverlay(stamp, vertical, cx, cy) {
    if (stamp === overlayStamp) {
      var elOk = !overlayEl || overlayEl.isConnected;
      var scOk = !overlayScrollerEl || overlayScrollerEl.isConnected;
      if (elOk && scOk) return;
    }
    overlayStamp = stamp;
    overlayEl = null;
    overlayScrollerEl = null;

    var vw = document.documentElement.clientWidth;
    var vh = document.documentElement.clientHeight;
    var content = contentRoot();
    for (var p = 0; p < PROBE_POINTS.length; p++) {
      var px = vw * PROBE_POINTS[p][0];
      var py = vh * PROBE_POINTS[p][1];
      var stack = document.elementsFromPoint(px, py);
      for (var i = 0; i < stack.length; i++) {
        var el = stack[i];
        if (!isOverlay(el)) continue;
        if (content && el !== content && el.contains(content)) continue;
        overlayEl = el;
        overlayScrollerEl = scrollerInside(el, vertical, cx, cy);
        return;
      }
    }
  }

  function resolve(cx, cy, vertical, stamp) {
    /* 1. nearest scrollable ancestor of the hit element (document root excluded) */
    var stack = document.elementsFromPoint(cx, cy);
    if (stack && stack.length) {
      var el = stack[0];
      while (el && !isDocRoot(el)) {
        if (isScroller(el, vertical)) return { kind: 'el', el: el };
        el = el.parentElement;
      }
    }

    /* 2. a modal overlay on screen owns the scroll, wherever the cursor is */
    findModalOverlay(stamp, vertical, cx, cy);
    if (overlayScrollerEl) return { kind: 'el', el: overlayScrollerEl };
    if (overlayEl) return { kind: 'consume' };

    /* 3. plain page: let the host scroll the document like before */
    return { kind: 'doc' };
  }

  function applyScroll(el, vertical, dir, delta) {
    if (vertical) el.scrollTop = posOf(el, vertical) + dir * delta;
    else el.scrollLeft = posOf(el, vertical) + dir * delta;
  }

  var target = null;
  var gestureId = 0;

  function step(cx, cy, vertical, dir, delta, stamp) {
    if (!target || (target.el && !target.el.isConnected)) {
      target = resolve(cx, cy, vertical, stamp);
    }
    if (target.kind === 'consume') return 'consume';
    if (target.kind === 'doc') return 'doc';

    if (canScrollMore(target.el, vertical, dir)) {
      applyScroll(target.el, vertical, dir, delta);
      return 'scroll';
    }
    /* At the end of the cached target: re-pick once, then give up. */
    target = resolve(cx, cy, vertical, stamp);
    if (target.kind === 'consume') return 'consume';
    if (target.kind === 'doc') return 'doc';
    if (target.el && canScrollMore(target.el, vertical, dir)) {
      applyScroll(target.el, vertical, dir, delta);
      return 'scroll';
    }
    return 'end';
  }

  window.__keyphone_scroll__ = {
    by: function (x, y, dx, dy, viewW, viewH, resolveAgain) {
      var kx = viewW > 0 ? window.innerWidth / viewW : 1;
      var ky = viewH > 0 ? window.innerHeight / viewH : 1;
      var cx = x * kx;
      var cy = y * ky;
      var vertical = dx === 0;
      var raw = vertical ? dy : dx;
      var delta = Math.abs(raw);
      if (delta < 1) return 'scroll';
      var dir = raw < 0 ? -1 : 1;
      if (resolveAgain) {
        target = null;
        gestureId++;
      }
      return step(cx, cy, vertical, dir, delta, gestureId);
    },
    /* Diagnostics only: which element would receive the scroll right now. */
    probe: function (x, y, viewW, viewH, vertical) {
      var kx = viewW > 0 ? window.innerWidth / viewW : 1;
      var ky = viewH > 0 ? window.innerHeight / viewH : 1;
      var cx = x * kx;
      var cy = y * ky;
      var vert = vertical !== false;
      var stamp = ++gestureId;
      var chain = [];
      var stack = document.elementsFromPoint(cx, cy);
      var node = stack && stack.length ? stack[0] : null;
      while (node && !isDocRoot(node) && chain.length < 8) {
        chain.push(describe(node));
        node = node.parentElement;
      }
      findModalOverlay(stamp, vert, cx, cy);
      var t = resolve(cx, cy, vert, stamp);
      return JSON.stringify({
        kind: t.kind,
        target: t.el ? describe(t.el) : null,
        overlay: overlayEl ? describe(overlayEl) : null,
        overlayScroller: overlayScrollerEl ? describe(overlayScrollerEl) : null,
        contentRoot: contentRoot() ? describe(contentRoot()) : null,
        hitChain: chain
      });
    },
    reset: function () {
      target = null;
      overlayStamp = -1;
      overlayEl = null;
      overlayScrollerEl = null;
      return 'ok';
    }
  };
  return 'ok';
})();
