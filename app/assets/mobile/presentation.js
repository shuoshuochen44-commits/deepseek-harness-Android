(function () {
  'use strict';
  if (window.__dshMobilePresentation) {
    window.__dshMobilePresentation.refresh();
    return;
  }
  var stackSelector = '.Hqq-bq_root > .Hqq-bq_stack';
  var pending = new Set();
  var frame = 0;
  var observing = false;
  var disposed = false;
  var observer = new MutationObserver(function (records) {
    records.forEach(function (record) {
      // React may clear an existing hero's children during a remount.
      if (record.target.nodeType === 1 && record.target.matches(stackSelector)) pending.add(record.target);
      record.addedNodes.forEach(function (node) {
        if (node.nodeType !== 1 || node.hasAttribute('data-dsh-mobile-art')) return;
        if (node.matches(stackSelector)) pending.add(node);
        node.querySelectorAll(stackSelector).forEach(function (stack) { pending.add(stack); });
      });
    });
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
    image.decoding = 'async';
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
    document.querySelectorAll(stackSelector).forEach(decorate);
  }
  function pause() {
    observer.disconnect();
    observing = false;
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    pending.clear();
  }
  function resume() {
    if (disposed || document.hidden) return;
    refresh();
    if (!observing) {
      observer.observe(document.documentElement, { childList: true, subtree: true });
      observing = true;
    }
  }
  function visibilityChanged() {
    if (document.hidden) pause();
    else resume();
  }
  function dismissMobileSidebar(event) {
    if (!window.matchMedia('(max-width:720px)').matches || !event.target.closest) return;
    var frame = event.target.closest('.BynINW_frame:not([data-sidebar-collapsed])');
    if (!frame || event.target.closest('.BynINW_overlayLayer')) return;
    var sidebar = frame.querySelector(':scope > .BynINW_sidebarCol');
    if (!sidebar || sidebar.contains(event.target)) return;
    var toggle = sidebar.querySelector('._2H3hWW_toggle');
    if (!toggle) return;
    event.preventDefault();
    event.stopPropagation();
    toggle.click();
  }
  function cleanup() {
    disposed = true;
    pause();
    window.removeEventListener('pagehide', pause);
    window.removeEventListener('pageshow', resume);
    document.removeEventListener('visibilitychange', visibilityChanged);
    document.removeEventListener('click', dismissMobileSidebar, true);
    document.querySelectorAll('[data-dsh-mobile-art]').forEach(function (image) { image.remove(); });
    delete window.__dshMobilePresentation;
    delete window.__dshPresentationCleanup;
  }
  window.__dshMobilePresentation = { refresh: refresh };
  window.__dshPresentationCleanup = cleanup;
  window.addEventListener('pagehide', pause);
  window.addEventListener('pageshow', resume);
  document.addEventListener('visibilitychange', visibilityChanged);
  document.addEventListener('click', dismissMobileSidebar, true);
  resume();
})();
