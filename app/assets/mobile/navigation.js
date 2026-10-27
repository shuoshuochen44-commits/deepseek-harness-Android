(function () {
    'use strict';

    var connectionKey = '__DSH_NAV_CONNECTION__';
    if (window.__dshNavReady) {
        window.__dshNavRekey(connectionKey);
        window.__dshNavReport();
        return;
    }

    // Selectors belong to the bundled SidebarRoot, HeroShell and settings UI.
    // Hero structure is stable across locale changes and cannot match message text.
    var relevant = '.wCInkW_overlay,._2H3hWW_root,._2H3hWW_panelRow,.Hqq-bq_root,.Hqq-bq_stack,.Hqq-bq_headline,[data-sidebar-right-panel],.OUqwTW_session';
    var relevantClass = /(?:^|\s)(?:wCInkW_overlay|_2H3hWW_(?:root|panelRow)|Hqq-bq_(?:root|stack|headline))(?:\s|$)/;
    var pending = null;
    var lastState;
    var stopped = false;
    var pageHidden = false;
    var observing = false;
    var nativePort = null;

    function q(selector) { return document.querySelector(selector); }
    function rail() { return q('._2H3hWW_root'); }
    function overlay() { return q('.wCInkW_overlay'); }
    function sidebarOpen() {
        var root = rail();
        return !!root && !root.classList.contains('_2H3hWW_collapsed');
    }
    function railPanel() {
        var root = rail();
        return root && root.querySelector('._2H3hWW_panelRow._2H3hWW_panelActive');
    }
    function rightPanelClose() {
        var panels = document.querySelectorAll('[data-sidebar-right-panel][data-sidebar-right-open]');
        for (var i = 0; i < panels.length; i++) {
            if (panels[i].closest('[hidden],[aria-hidden="true"]')) continue;
            var close = panels[i].querySelector('[data-sidebar-right-toggle]');
            if (close) return close;
        }
        return null;
    }
    function atHome() {
        return !document.body || !!q('.Hqq-bq_root > .Hqq-bq_stack > .Hqq-bq_headline');
    }
    function active() { return !stopped && !pageHidden && !document.hidden; }
    function report() {
        pending = null;
        if (!active()) return;
        var value = !!(overlay() || rightPanelClose() || railPanel() || sidebarOpen() || !atHome());
        if (value === lastState) return;
        try {
            if (nativePort) {
                nativePort.postMessage(String(value));
                lastState = value;
            }
        } catch (error) { /* A detached WebView may no longer have a native host. */ }
    }
    function schedule() {
        if (active() && pending === null) pending = setTimeout(report, 32);
    }
    function cancel() {
        if (pending !== null) clearTimeout(pending);
        pending = null;
    }
    function containsNavigation(node) {
        return node.nodeType === 1 && (node.matches(relevant) || !!node.querySelector(relevant));
    }
    var observer = new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
            var record = records[i];
            if (record.type === 'attributes') {
                if (record.target.matches(relevant) || relevantClass.test(record.oldValue || '')) {
                    schedule();
                    return;
                }
            } else {
                var lists = [record.addedNodes, record.removedNodes];
                for (var l = 0; l < lists.length; l++) {
                    for (var n = 0; n < lists[l].length; n++) {
                        if (containsNavigation(lists[l][n])) {
                            schedule();
                            return;
                        }
                    }
                }
            }
        }
    });
    function synchronize() {
        if (!active()) {
            observer.disconnect();
            observing = false;
            cancel();
            return;
        }
        if (!observing && document.documentElement) {
            observer.observe(document.documentElement, {
                subtree: true,
                childList: true,
                attributes: true,
                attributeFilter: ['class', 'data-sidebar-right-open', 'hidden', 'aria-hidden'],
                attributeOldValue: true
            });
            observing = true;
        }
        schedule();
    }
    function onPageHide() { pageHidden = true; synchronize(); }
    function onPageShow() { pageHidden = false; synchronize(); }
    function connect(event) {
        // Android posts from a native sender with an empty/opaque origin.
        // The native side enforces the exact target origin; the per-installation
        // secret distinguishes its channel from messages sent by web frames.
        if (event.data !== connectionKey ||
                (event.origin !== '' && event.origin !== 'null') || event.source !== null ||
                !event.ports || event.ports.length !== 1) return;
        if (nativePort) nativePort.close();
        nativePort = event.ports[0];
        lastState = undefined;
        schedule();
    }
    function rekey(key) {
        if (key === connectionKey) return;
        connectionKey = key;
        if (nativePort) nativePort.close();
        nativePort = null;
        lastState = undefined;
    }
    function back() {
        try {
            var settings = overlay();
            if (settings) {
                var close = settings.querySelector('.wCInkW_close');
                if (!close) {
                    var labelled = settings.querySelectorAll('[aria-label]');
                    for (var i = 0; i < labelled.length; i++) {
                        if (/关闭|close/i.test(labelled[i].getAttribute('aria-label') || '')) {
                            close = labelled[i];
                            break;
                        }
                    }
                }
                if (close) { close.click(); schedule(); return 'handled'; }
            }
            var rightClose = rightPanelClose();
            if (rightClose) { rightClose.click(); schedule(); return 'handled'; }
            var panel = railPanel();
            if (panel) {
                // PanelRow only selects its id. Use the official home action;
                // active panels deliberately clear the sidebar's session selection.
                var sidebar = rail();
                var home = sidebar && sidebar.querySelector('button._2H3hWW_newSession');
                if (home) { home.click(); schedule(); return 'handled'; }
            }
            if (sidebarOpen()) {
                var toggle = q('._2H3hWW_toggle');
                if (toggle) { toggle.click(); schedule(); return 'handled'; }
            }
            if (!atHome()) {
                var root = rail();
                var newSession = root && root.querySelector('button._2H3hWW_newSession');
                if (newSession) { newSession.click(); schedule(); return 'handled'; }
            }
        } catch (error) { /* Let the native layer handle a detached document. */ }
        return 'root';
    }
    function cleanup() {
        stopped = true;
        observer.disconnect();
        cancel();
        if (nativePort) nativePort.close();
        nativePort = null;
        document.removeEventListener('visibilitychange', synchronize);
        window.removeEventListener('message', connect);
        window.removeEventListener('pagehide', onPageHide);
        window.removeEventListener('pageshow', onPageShow);
        delete window.__dshNavReady;
        delete window.__dshNavReport;
        delete window.__dshBack;
        delete window.__dshNavCleanup;
        delete window.__dshNavRekey;
    }

    window.__dshNavReady = true;
    window.__dshNavReport = schedule;
    window.__dshBack = back;
    window.__dshNavCleanup = cleanup;
    window.__dshNavRekey = rekey;
    document.addEventListener('visibilitychange', synchronize);
    window.addEventListener('message', connect);
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('pageshow', onPageShow);
    synchronize();
})();
