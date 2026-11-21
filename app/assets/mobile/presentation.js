(function () {
  'use strict';
  if (window.__dshMobilePresentation) {
    window.__dshMobilePresentation.refresh();
    return;
  }
  var stackSelector = '.Hqq-bq_root > .Hqq-bq_stack';
  var mobileQuery = window.matchMedia('(max-width:720px)');
  var reducedMotionQuery = window.matchMedia('(prefers-reduced-motion:reduce)');
  var pending = new Set();
  var frame = 0;
  var observing = false;
  var disposed = false;

  // Sidebar swipe gesture state (passive listeners + rAF transform updates).
  var drag = null;
  var activeSnap = null;
  var dragFrame = 0;
  var snapTimer = 0;

  // Native status-bar theme synchronization (activated only via authenticated native port).
  var themeKey = null;
  var themePort = null;
  var themeObserver = null;
  var lastThemePayload = null;
  var themeFrame = 0;

  function isStack(el) {
    return el.nodeType === 1 && el.classList && el.classList.contains('Hqq-bq_stack') && el.matches(stackSelector);
  }

  var observer = new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var record = records[i];
      // React may clear an existing hero's children during a remount.
      if (isStack(record.target)) pending.add(record.target);
      var added = record.addedNodes;
      for (var j = 0; j < added.length; j++) {
        var node = added[j];
        if (node.nodeType !== 1 || node.hasAttribute('data-dsh-mobile-art')) continue;
        if (isStack(node)) pending.add(node);
        // Leaf elements (e.g. streamed syntax-highlight spans) cannot contain the hero stack.
        if (node.firstElementChild) {
          var stacks = node.querySelectorAll(stackSelector);
          for (var k = 0; k < stacks.length; k++) pending.add(stacks[k]);
        }
      }
    }
    if (pending.size && !frame) frame = requestAnimationFrame(flush);
  });

  function decorate(stack) {
    if (!stack.isConnected || stack.querySelector(':scope > [data-dsh-mobile-art="welcome"]')) return;
    var image = document.createElement('img');
    image.setAttribute('data-dsh-mobile-art', 'welcome');
    image.setAttribute('aria-hidden', 'true');
    image.alt = '';
    image.width = 240;
    image.height = 160;
    image.loading = 'eager';
    image.decoding = 'async';
    if ('fetchPriority' in image) image.fetchPriority = 'high';
    image.draggable = false;
    image.src = '/__dsh_mobile/welcome.webp';
    stack.insertBefore(image, stack.firstChild);
  }

  function flush() {
    frame = 0;
    pending.forEach(decorate);
    pending.clear();
  }

  function refresh() {
    if (disposed || document.hidden) return;
    var stacks = document.querySelectorAll(stackSelector);
    for (var i = 0; i < stacks.length; i++) decorate(stacks[i]);
  }

  function rgbToHex(color) {
    if (!color) return '';
    var m = color.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([0-9.]+))?\s*\)/i);
    if (!m) return '';
    if (m[4] !== undefined && parseFloat(m[4]) === 0) return '';
    var r = Math.min(255, parseInt(m[1], 10)).toString(16).padStart(2, '0');
    var g = Math.min(255, parseInt(m[2], 10)).toString(16).padStart(2, '0');
    var b = Math.min(255, parseInt(m[3], 10)).toString(16).padStart(2, '0');
    return '#' + r + g + b;
  }

  function reportTheme() {
    themeFrame = 0;
    if (disposed || document.hidden || !themePort || !document.body) return;
    var dark = document.body.hasAttribute('data-ds-dark-theme');
    var bg = '';
    try {
      bg = rgbToHex(window.getComputedStyle(document.body).backgroundColor) ||
           rgbToHex(window.getComputedStyle(document.documentElement).backgroundColor);
    } catch (error) { /* Ignore detached style computation. */ }
    if (!bg) bg = dark ? '#18181b' : '#ffffff';
    var payload = (dark ? 'dark:' : 'light:') + bg;
    if (payload === lastThemePayload) return;
    try {
      themePort.postMessage(payload);
      lastThemePayload = payload;
    } catch (error) { /* Detached WebView port. */ }
  }

  function scheduleThemeReport() {
    if (!themePort || disposed || document.hidden || themeFrame) return;
    themeFrame = requestAnimationFrame(reportTheme);
  }

  function ensureThemeObserver() {
    if (themeObserver || !document.body || !themePort) return;
    themeObserver = new MutationObserver(scheduleThemeReport);
    themeObserver.observe(document.body, {
      attributes: true,
      attributeFilter: ['data-ds-dark-theme', 'class', 'style']
    });
  }

  function stopThemeObserver() {
    if (themeObserver) {
      themeObserver.disconnect();
      themeObserver = null;
    }
    if (themeFrame) {
      cancelAnimationFrame(themeFrame);
      themeFrame = 0;
    }
  }

  function rekeyTheme(key) {
    if (!key || key === themeKey) return;
    themeKey = key;
    if (themePort) {
      try { themePort.close(); } catch (error) { /* Ignore. */ }
      themePort = null;
    }
    lastThemePayload = null;
    stopThemeObserver();
  }

  function onThemeConnect(event) {
    if (!themeKey || event.data !== themeKey ||
        (event.origin !== '' && event.origin !== 'null') || event.source !== null ||
        !event.ports || event.ports.length !== 1) return;
    if (themePort) {
      try { themePort.close(); } catch (error) { /* Ignore. */ }
    }
    themePort = event.ports[0];
    lastThemePayload = null;
    ensureThemeObserver();
    scheduleThemeReport();
  }

  function resetDragNode(targetDrag) {
    if (!targetDrag) return;
    if (targetDrag.frame) {
      targetDrag.frame.removeAttribute('data-dsh-dragging');
      targetDrag.frame.removeAttribute('data-dsh-snap');
      targetDrag.frame.style.removeProperty('--dsh-scrim-alpha');
    }
    if (targetDrag.sidebar) {
      targetDrag.sidebar.style.transform = '';
    }
  }

  function clearDragVisuals(targetDrag) {
    if (dragFrame) {
      cancelAnimationFrame(dragFrame);
      dragFrame = 0;
    }
    if (snapTimer) {
      clearTimeout(snapTimer);
      snapTimer = 0;
    }
    resetDragNode(activeSnap);
    activeSnap = null;
    resetDragNode(targetDrag);
  }

  function pause() {
    observer.disconnect();
    observing = false;
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    pending.clear();
    clearDragVisuals(drag);
    drag = null;
    stopThemeObserver();
  }

  function resume() {
    if (disposed || document.hidden) return;
    refresh();
    if (!observing) {
      observer.observe(document.documentElement, { childList: true, subtree: true });
      observing = true;
    }
    if (themePort) {
      ensureThemeObserver();
      scheduleThemeReport();
    }
  }

  function visibilityChanged() {
    if (document.hidden) pause();
    else resume();
  }

  function dismissMobileSidebar(event) {
    if (!mobileQuery.matches || !event.target || !event.target.closest) return;
    var frameEl = event.target.closest('.BynINW_frame:not([data-sidebar-collapsed])');
    if (!frameEl || event.target.closest('.BynINW_overlayLayer')) return;
    var sidebar = frameEl.querySelector(':scope > .BynINW_sidebarCol');
    if (!sidebar || sidebar.contains(event.target)) return;
    var toggle = sidebar.querySelector('._2H3hWW_toggle');
    if (!toggle) return;
    event.preventDefault();
    event.stopPropagation();
    toggle.click();
  }

  function centerActiveSettingsTab(event) {
    if (!mobileQuery.matches || !event.target || !event.target.closest) return;
    var cell = event.target.closest('.wCInkW_navCell');
    if (!cell || !cell.scrollIntoView) return;
    try {
      cell.scrollIntoView({
        behavior: reducedMotionQuery.matches ? 'auto' : 'smooth',
        inline: 'center',
        block: 'nearest'
      });
    } catch (error) { /* Ignore unsupported scrollIntoView options on legacy WebView. */ }
  }

  function hasBlockingOverlay() {
    return !!(
      document.querySelector('.wCInkW_overlay') ||
      document.querySelector('._root_o6lrb_6') ||
      document.querySelector('[role="dialog"][aria-modal="true"]') ||
      document.querySelector('[role="menu"]') ||
      document.querySelector('[role="listbox"]') ||
      document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]')
    );
  }

  function renderDrag() {
    dragFrame = 0;
    if (!drag || !drag.locked || drag.mode !== 'close') return;
    var width = drag.width || 260;
    var offset = Math.max(-width, Math.min(0, drag.dx));
    var ratio = Math.max(0, 1 + offset / width);
    drag.sidebar.style.transform = 'translate3d(' + offset + 'px,0,0)';
    drag.frame.style.setProperty('--dsh-scrim-alpha', ratio.toFixed(3));
  }

  function onTouchStart(event) {
    if (!mobileQuery.matches || !event.touches || event.touches.length !== 1) return;
    var touch = event.touches[0];
    var target = event.target;
    if (!target || !target.closest) return;

    // Reset any unfinished snap transition before starting a new gesture.
    clearDragVisuals(drag);
    drag = null;

    var openFrame = target.closest('.BynINW_frame:not([data-sidebar-collapsed])');
    if (openFrame && !target.closest('.BynINW_overlayLayer')) {
      var sidebar = openFrame.querySelector(':scope > .BynINW_sidebarCol');
      if (!sidebar) return;
      drag = {
        mode: 'close',
        frame: openFrame,
        sidebar: sidebar,
        width: sidebar.getBoundingClientRect().width || 260,
        startX: touch.clientX,
        startY: touch.clientY,
        startTime: Date.now(),
        dx: 0,
        locked: false,
        rejected: false
      };
      return;
    }

    // Edge-swipe right from the left bezel to open the collapsed sidebar when no overlay blocks it.
    if (touch.clientX <= 26 && !hasBlockingOverlay()) {
      var collapsedFrame = target.closest('.BynINW_frame[data-sidebar-collapsed]');
      if (!collapsedFrame) return;
      drag = {
        mode: 'open',
        frame: collapsedFrame,
        sidebar: collapsedFrame.querySelector(':scope > .BynINW_sidebarCol'),
        startX: touch.clientX,
        startY: touch.clientY,
        startTime: Date.now(),
        dx: 0,
        locked: false,
        rejected: false
      };
    }
  }

  function onTouchMove(event) {
    if (!drag || drag.rejected || !event.touches || event.touches.length !== 1) return;
    var touch = event.touches[0];
    var dx = touch.clientX - drag.startX;
    var dy = touch.clientY - drag.startY;
    var absX = Math.abs(dx);
    var absY = Math.abs(dy);

    if (!drag.locked) {
      if (absX < 10 && absY < 10) return;
      if (absY > absX * 0.85) {
        drag.rejected = true;
        return;
      }
      if (drag.mode === 'close' && dx > 0) {
        drag.rejected = true;
        return;
      }
      if (drag.mode === 'open' && dx < 0) {
        drag.rejected = true;
        return;
      }
      drag.locked = true;
      if (drag.mode === 'close') {
        drag.frame.setAttribute('data-dsh-dragging', '1');
      }
    }

    drag.dx = dx;
    if (drag.mode === 'close' && !dragFrame) {
      dragFrame = requestAnimationFrame(renderDrag);
    }
  }

  function onTouchEnd() {
    if (!drag) return;
    var current = drag;
    drag = null;
    if (dragFrame) {
      cancelAnimationFrame(dragFrame);
      dragFrame = 0;
    }
    if (!current.locked || current.rejected) {
      clearDragVisuals(current);
      return;
    }

    var elapsed = Math.max(1, Date.now() - current.startTime);
    var velocity = Math.abs(current.dx) / elapsed;

    if (current.mode === 'open') {
      if (current.dx >= 52 || (current.dx >= 28 && velocity > 0.35)) {
        var openToggle = current.sidebar && current.sidebar.querySelector('._2H3hWW_toggle');
        if (openToggle) openToggle.click();
      }
      return;
    }

    // Close mode: commit close or spring back smoothly.
    var shouldClose = current.dx <= -56 || (current.dx <= -28 && velocity > 0.35);
    current.frame.removeAttribute('data-dsh-dragging');
    if (shouldClose) {
      var closeToggle = current.sidebar && current.sidebar.querySelector('._2H3hWW_toggle');
      if (reducedMotionQuery.matches) {
        clearDragVisuals(current);
        if (closeToggle) closeToggle.click();
        return;
      }
      activeSnap = current;
      current.frame.setAttribute('data-dsh-snap', '1');
      current.sidebar.style.transform = 'translate3d(' + (-current.width) + 'px,0,0)';
      current.frame.style.setProperty('--dsh-scrim-alpha', '0');
      snapTimer = setTimeout(function () {
        clearDragVisuals(current);
        if (closeToggle) closeToggle.click();
      }, 160);
    } else {
      activeSnap = current;
      current.frame.setAttribute('data-dsh-snap', '1');
      current.sidebar.style.transform = 'translate3d(0,0,0)';
      current.frame.style.setProperty('--dsh-scrim-alpha', '1');
      snapTimer = setTimeout(function () {
        clearDragVisuals(current);
      }, 190);
    }
  }

  function onTouchCancel() {
    if (!drag) return;
    clearDragVisuals(drag);
    drag = null;
  }

  function cleanup() {
    disposed = true;
    pause();
    if (themePort) {
      try { themePort.close(); } catch (error) { /* Ignore. */ }
      themePort = null;
    }
    themeKey = null;
    lastThemePayload = null;
    window.removeEventListener('pagehide', pause);
    window.removeEventListener('pageshow', resume);
    window.removeEventListener('message', onThemeConnect);
    document.removeEventListener('visibilitychange', visibilityChanged);
    document.removeEventListener('click', dismissMobileSidebar, true);
    document.removeEventListener('click', centerActiveSettingsTab, false);
    document.removeEventListener('touchstart', onTouchStart);
    document.removeEventListener('touchmove', onTouchMove);
    document.removeEventListener('touchend', onTouchEnd);
    document.removeEventListener('touchcancel', onTouchCancel);
    var arts = document.querySelectorAll('[data-dsh-mobile-art]');
    for (var i = 0; i < arts.length; i++) arts[i].remove();
    delete window.__dshMobilePresentation;
    delete window.__dshPresentationCleanup;
    delete window.__dshThemeRekey;
  }

  window.__dshMobilePresentation = { refresh: refresh };
  window.__dshPresentationCleanup = cleanup;
  window.__dshThemeRekey = rekeyTheme;
  window.addEventListener('pagehide', pause);
  window.addEventListener('pageshow', resume);
  window.addEventListener('message', onThemeConnect);
  document.addEventListener('visibilitychange', visibilityChanged);
  document.addEventListener('click', dismissMobileSidebar, true);
  document.addEventListener('click', centerActiveSettingsTab, false);
  document.addEventListener('touchstart', onTouchStart, { passive: true });
  document.addEventListener('touchmove', onTouchMove, { passive: true });
  document.addEventListener('touchend', onTouchEnd, { passive: true });
  document.addEventListener('touchcancel', onTouchCancel, { passive: true });
  resume();
})();
