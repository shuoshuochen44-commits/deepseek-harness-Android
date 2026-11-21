(function () {
    'use strict';

    var connectionKey = '__DSH_NAV_CONNECTION__';
    if (window.__dshNavReady) {
        window.__dshNavRekey(connectionKey);
        window.__dshNavReport();
        return;
    }

    // Selectors belong to the bundled SidebarRoot, HeroShell, settings, and shared Modal/Menu UI.
    // Hero structure is stable across locale changes and cannot match message text.
    var relevant = '.wCInkW_overlay,._2H3hWW_root,._2H3hWW_panelRow,.Hqq-bq_root,.Hqq-bq_stack,.Hqq-bq_headline,[data-sidebar-right-panel],.OUqwTW_session,._root_o6lrb_6,[role="dialog"],[role="alertdialog"],[role="menu"],[role="listbox"]';
    var relevantClass = /(?:^|\s)(?:wCInkW_overlay|_2H3hWW_(?:root|panelRow)|Hqq-bq_(?:root|stack|headline)|_root_o6lrb_6)(?:\s|$)/;
    var pending = null;
    var lastState;
    var stopped = false;
    var pageHidden = false;
    var observing = false;
    var nativePort = null;

    function q(selector) { return document.querySelector(selector); }
    function rail() { return q('._2H3hWW_root'); }
    function overlay() { return q('.wCInkW_overlay'); }
    function isVisible(el) {
        if (!el || !el.isConnected || el.closest('[hidden],[aria-hidden="true"]')) return false;
        try {
            var style = window.getComputedStyle(el);
            return style.display !== 'none' && style.visibility !== 'hidden';
        } catch (error) {
            return true;
        }
    }
    function topPopupMenu() {
        var menus = document.querySelectorAll('[role="menu"],[role="listbox"]');
        for (var i = menus.length - 1; i >= 0; i--) {
            if (isVisible(menus[i])) return menus[i];
        }
        return null;
    }
    function topModalDialog() {
        var dialogs = document.querySelectorAll('._root_o6lrb_6,[role="dialog"],[role="alertdialog"]');
        for (var i = dialogs.length - 1; i >= 0; i--) {
            var el = dialogs[i];
            // Settings overlay has its own dedicated handler; dialogs portaled above it remain eligible.
            if (el.classList.contains('wCInkW_overlay') || el.closest('.wCInkW_overlay')) continue;
            if (isVisible(el)) return el;
        }
        return null;
    }
    function dispatchEscape(target) {
        var init = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
        var el = target || document.activeElement || document.body || document;
        try {
            el.dispatchEvent(new KeyboardEvent('keydown', init));
        } catch (error) { /* Ignore legacy KeyboardEvent init failures. */ }
        if (el !== document) {
            try {
                document.dispatchEvent(new KeyboardEvent('keydown', init));
            } catch (error) { /* Ignore. */ }
        }
    }
    function findCloseButton(container, includeCancel) {
        var direct = container.querySelector('._close_o6lrb_87,.wCInkW_close');
        if (direct && isVisible(direct)) return direct;
        var labelled = container.querySelectorAll('button,[role="button"],[aria-label]');
        var pattern = includeCancel ? /关闭|close|取消|cancel/i : /关闭|close/i;
        for (var i = 0; i < labelled.length; i++) {
            var node = labelled[i];
            if (!isVisible(node) || node.disabled) continue;
            var label = (node.getAttribute('aria-label') || '').trim();
            if (label && pattern.test(label)) return node;
            if (includeCancel && (node.tagName === 'BUTTON' || node.getAttribute('role') === 'button')) {
                var text = (node.textContent || '').trim();
                if (/^(取消|关闭|返回|Cancel|Close)$/i.test(text)) return node;
            }
        }
        return null;
    }
    function closePopupMenu(menu) {
        var activeEl = document.activeElement;
        dispatchEscape(menu.contains(activeEl) ? activeEl : menu);
        // If a custom menu did not close on Escape alone, blur or click outside its subtree.
        if (menu.isConnected && activeEl && typeof activeEl.blur === 'function' && menu.contains(activeEl)) {
            try { activeEl.blur(); } catch (error) { /* Ignore. */ }
        }
        return true;
    }
    function closeModalDialog(el) {
        var modalRoot = el.classList.contains('_root_o6lrb_6') ? el : el.closest('._root_o6lrb_6');
        var scope = modalRoot || el;
        // Inside directory browser: if path input is being edited, Escape cancels editing first.
        var activeEl = document.activeElement;
        if (activeEl && scope.contains(activeEl) &&
                (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA') &&
                scope.querySelector('.Fv-y6W_dialog') && !scope.querySelector('.Fv-y6W_createDialog')) {
            dispatchEscape(activeEl);
            return true;
        }
        // 1. Prefer explicit close button inside the topmost modal.
        var closeBtn = findCloseButton(scope, false);
        if (closeBtn) { closeBtn.click(); return true; }
        // 2. For headless shared Modals (e.g. directory picker), clicking the backdrop mask calls onClose directly.
        if (modalRoot) {
            var mask = modalRoot.querySelector(':scope > ._mask_o6lrb_18');
            if (mask) { mask.click(); return true; }
        }
        // 3. Fallback to explicit Cancel button inside the dialog.
        var cancelBtn = findCloseButton(scope, true);
        if (cancelBtn) { cancelBtn.click(); return true; }
        // 4. Last resort: dispatch Escape for custom Escape-dismissible dialogs.
        dispatchEscape(scope);
        return true;
    }
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
        var value = !!(topPopupMenu() || topModalDialog() || overlay() || rightPanelClose() || railPanel() || sidebarOpen() || !atHome());
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
        return node.nodeType === 1 && (node.matches(relevant) || (!!node.firstElementChild && !!node.querySelector(relevant)));
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
                attributeFilter: ['class', 'data-sidebar-right-open', 'hidden', 'aria-hidden', 'role', 'aria-modal'],
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
            // 1. Dismiss topmost floating menu / dropdown / command listbox first.
            var menu = topPopupMenu();
            if (menu && closePopupMenu(menu)) { schedule(); return 'handled'; }
            // 2. Dismiss topmost modal dialog (directory picker, create folder, confirm, sub-modals).
            var modal = topModalDialog();
            if (modal && closeModalDialog(modal)) { schedule(); return 'handled'; }
            // 3. Close settings overlay.
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
            // 4. Close open right panel.
            var rightClose = rightPanelClose();
            if (rightClose) { rightClose.click(); schedule(); return 'handled'; }
            // 5. Return from global sidebar panel (plugins, automations) to home.
            var panel = railPanel();
            if (panel) {
                // PanelRow only selects its id. Use the official home action;
                // active panels deliberately clear the sidebar's session selection.
                var sidebar = rail();
                var home = sidebar && sidebar.querySelector('button._2H3hWW_newSession');
                if (home) { home.click(); schedule(); return 'handled'; }
            }
            // 6. Collapse expanded left sidebar drawer.
            if (sidebarOpen()) {
                var toggle = q('._2H3hWW_toggle');
                if (toggle) { toggle.click(); schedule(); return 'handled'; }
            }
            // 7. Return from conversation view back to home.
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
