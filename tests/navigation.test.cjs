const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '..');
const baseline = process.env.NAV_BASELINE_JAVA;
const script = baseline
  ? [...fs.readFileSync(baseline, 'utf8').split('static final String INSTALL_JS =')[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(m => JSON.parse(m[0])).join('')
  : fs.readFileSync(path.join(root, 'app/assets/mobile/navigation.js'), 'utf8')
      .replaceAll('__DSH_NAV_CONNECTION__', '__dsh_nav_connect__:test-installation-key');
let browser;
before(async () => { browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || 'msedge' }); });
after(async () => { await browser?.close(); });
const hero = '<div class="Hqq-bq_root"><div class="Hqq-bq_stack"><div class="Hqq-bq_headline">Explore the unknown</div><div class="Hqq-bq_body"></div></div></div>';
async function fixture(t, options = {}) {
  const page = await browser.newPage();
  t.after(() => page.close());
  await page.route('http://127.0.0.1:8080/**', route => route.fulfill({ body: '<!doctype html>' }));
  await page.goto('http://127.0.0.1:8080/');
  await page.setContent(`<aside class="_2H3hWW_root ${options.expanded ? '' : '_2H3hWW_collapsed'}"><button class="_2H3hWW_toggle">Toggle</button><button class="_2H3hWW_newSession" aria-label="${options.english ? 'New session' : '新建会话'}">New</button><button class="_2H3hWW_panelRow ${options.panel ? '_2H3hWW_panelActive' : ''}">Plugins</button></aside><main>${options.home ? hero : '<article><p>Message: 探索未至之境</p><div id="stream"></div></article>'}</main>${options.overlay ? '<div class="wCInkW_overlay"><button aria-label="Other action"></button><button class="wCInkW_close" aria-label="Close">Close</button></div>' : ''}`);
  await page.evaluate(({ hero, baseline }) => {
    window.notifications = [];
    window.clicks = [];
    window.intervals = 0;
    window.rawMessages = [];
    window.legacyNativeReports = [];
    window.DSHNative = { onNavState: value => { window.legacyNativeReports.push(value); if (baseline) window.notifications.push(value); } };
    // Android's native postWebMessage has an empty sender origin; delivery is
    // restricted natively to the exact target page and authenticated by this key.
    window.connectNative = (data = '__dsh_nav_connect__:test-installation-key', origin = '', count = 1, target = 'notifications') => {
      const ports = [];
      for (let i = 0; i < count; i++) {
        const channel = new MessageChannel();
        channel.port1.onmessage = event => {
          if (target === 'notifications') { rawMessages.push(event.data); notifications.push(event.data === 'true'); }
          else window[target].push(event.data);
        };
        ports.push(channel.port2);
      }
      dispatchEvent(new MessageEvent('message', { data, origin, ports }));
    };
    const nativeInterval = window.setInterval;
    window.setInterval = (...args) => { window.intervals++; return nativeInterval(...args); };
    const q = document.querySelector.bind(document);
    window.queries = 0;
    document.querySelector = (...args) => { window.queries++; return q(...args); };
    q('._2H3hWW_toggle').onclick = () => { window.clicks.push('sidebar'); q('._2H3hWW_root').classList.toggle('_2H3hWW_collapsed'); };
    // Official panel rows select their id; clicking an active one does not close it.
    q('._2H3hWW_panelRow').onclick = () => { window.clicks.push('panel-reselect'); };
    q('._2H3hWW_newSession').onclick = () => { window.clicks.push('session'); q('._2H3hWW_panelRow').classList.remove('_2H3hWW_panelActive'); q('main').innerHTML = hero; };
    if (q('.wCInkW_close')) q('.wCInkW_close').onclick = () => { window.clicks.push('settings'); q('.wCInkW_overlay').remove(); };
  }, { hero, baseline: !!baseline });
  await page.evaluate(script);
  if (!options.noConnect) await page.evaluate(() => connectNative());
  await settle(page);
  return page;
}
async function settle(page) { await page.waitForTimeout(180); }

test('English home is root without relying on localized text', async t => {
  const page = await fixture(t, { home: true, english: true });
  assert.deepEqual(await page.evaluate(() => notifications), [false]);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
});

test('a conversation quoting the Chinese slogan remains a conversation', async t => {
  const page = await fixture(t);
  assert.deepEqual(await page.evaluate(() => notifications), [true]);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  assert.deepEqual(await page.evaluate(() => clicks), ['session']);
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [true, false]);
});

test('back closes settings, returns from the global panel to home, then closes the sidebar', async t => {
  const page = await fixture(t, { overlay: true, panel: true, expanded: true, english: true });
  for (let i = 0; i < 3; i++) { assert.equal(await page.evaluate(() => __dshBack()), 'handled'); await settle(page); }
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
  assert.deepEqual(await page.evaluate(() => clicks), ['settings', 'session', 'sidebar']);
  assert.deepEqual(await page.evaluate(() => notifications), [true, false]);
});

test('settings fallback finds the close label after other labelled controls', async t => {
  const page = await fixture(t, { home: true, overlay: true });
  await page.evaluate(() => document.querySelector('.wCInkW_close').classList.remove('wCInkW_close'));
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  assert.deepEqual(await page.evaluate(() => clicks), ['settings']);
});

test('global panel back returns to home without reselecting the active panel', async t => {
  const page = await fixture(t, { home: true, panel: true });
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.deepEqual(await page.evaluate(() => clicks), ['session']);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
  assert.deepEqual(await page.evaluate(() => notifications), [true, false]);
});

test('back closes an open right sidebar before leaving home and reports attribute changes', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => {
    document.body.insertAdjacentHTML('beforeend', '<div class="OUqwTW_panel" data-sidebar-right-panel="fullscreen"><button data-sidebar-right-toggle aria-label="Collapse right sidebar">Close</button></div>');
    const panel = document.querySelector('[data-sidebar-right-panel]');
    panel.querySelector('button').onclick = () => { clicks.push('right'); panel.removeAttribute('data-sidebar-right-open'); };
    panel.setAttribute('data-sidebar-right-open', 'true');
  });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.deepEqual(await page.evaluate(() => clicks), ['right']);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true, false]);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
  await page.evaluate(() => document.querySelector('[data-sidebar-right-panel]').setAttribute('data-sidebar-right-open', 'true'));
  await settle(page);
  assert.equal(await page.evaluate(() => notifications.at(-1)), true);
  await page.evaluate(() => document.querySelector('[data-sidebar-right-panel]').setAttribute('aria-hidden', 'true'));
  await settle(page);
  assert.equal(await page.evaluate(() => notifications.at(-1)), false);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
});

test('settings remain the first back target above an open right sidebar', async t => {
  const page = await fixture(t, { home: true, overlay: true });
  await page.evaluate(() => {
    document.body.insertAdjacentHTML('beforeend', '<div class="OUqwTW_panel" data-sidebar-right-panel="fullscreen" data-sidebar-right-open><button data-sidebar-right-toggle>Close</button></div>');
    const panel = document.querySelector('[data-sidebar-right-panel]');
    panel.querySelector('button').onclick = () => { clicks.push('right'); panel.removeAttribute('data-sidebar-right-open'); };
  });
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  assert.deepEqual(await page.evaluate(() => clicks), ['settings', 'right']);
});

test('idle creates no polling timers or repeated native notifications', async t => {
  const page = await fixture(t, { home: true });
  const start = await page.evaluate(() => queries);
  await page.waitForTimeout(1500);
  assert.equal(await page.evaluate(() => intervals), 0);
  assert.equal(await page.evaluate(() => queries), start);
  assert.deepEqual(await page.evaluate(() => notifications), [false]);
});

test('streaming text and unrelated class changes do not recompute navigation', async t => {
  const page = await fixture(t);
  const start = await page.evaluate(() => queries);
  await page.evaluate(() => {
    const stream = document.getElementById('stream');
    for (let i = 0; i < 100; i++) { stream.append(document.createTextNode('token')); stream.className = 'token' + i; }
  });
  await settle(page);
  assert.equal(await page.evaluate(() => queries), start);
  assert.deepEqual(await page.evaluate(() => notifications), [true]);
});

test('bursts coalesce and only final state changes are reported', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => {
    const rail = document.querySelector('._2H3hWW_root');
    for (let i = 0; i < 99; i++) rail.classList.toggle('_2H3hWW_collapsed');
  });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
  await page.evaluate(() => { for (let i = 0; i < 20; i++) __dshNavReport(); });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
});

test('hidden documents pause observation and resynchronize when visible', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => {
    window.testHidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.testHidden });
    document.dispatchEvent(new Event('visibilitychange'));
    document.querySelector('._2H3hWW_root').classList.remove('_2H3hWW_collapsed');
  });
  const start = await page.evaluate(() => queries);
  await settle(page);
  assert.equal(await page.evaluate(() => queries), start);
  assert.deepEqual(await page.evaluate(() => notifications), [false]);
  await page.evaluate(() => { window.testHidden = false; document.dispatchEvent(new Event('visibilitychange')); });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
});

test('installation is idempotent and cleanup permits a fresh installation', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(script);
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false]);
  await page.evaluate(() => __dshNavCleanup());
  await page.evaluate(() => document.querySelector('._2H3hWW_root').classList.remove('_2H3hWW_collapsed'));
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false]);
  await page.evaluate(script);
  await page.evaluate(() => connectNative());
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
});

test('pagehide disconnects and pageshow restores synchronization', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => { dispatchEvent(new Event('pagehide')); document.querySelector('._2H3hWW_root').classList.remove('_2H3hWW_collapsed'); });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false]);
  await page.evaluate(() => dispatchEvent(new Event('pageshow')));
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
});

test('navigation reports string booleans only through the connected port', async t => {
  const page = await fixture(t, { home: true });
  assert.deepEqual(await page.evaluate(() => rawMessages), ['false']);
  assert.deepEqual(await page.evaluate(() => legacyNativeReports), []);
  await page.evaluate(() => document.querySelector('._2H3hWW_root').classList.remove('_2H3hWW_collapsed'));
  await settle(page);
  assert.deepEqual(await page.evaluate(() => rawMessages), ['false', 'true']);
});

test('no navigation state leaves the page before a port connects', async t => {
  const page = await fixture(t, { home: true, noConnect: true });
  assert.deepEqual(await page.evaluate(() => notifications), []);
  assert.deepEqual(await page.evaluate(() => legacyNativeReports), []);
  await page.evaluate(() => connectNative());
  await settle(page);
  assert.deepEqual(await page.evaluate(() => rawMessages), ['false']);
});

test('wrong-origin, wrong-key and malformed messages cannot replace the native port', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => {
    window.attacker = [];
    connectNative('__dsh_nav_connect__:test-installation-key', 'https://untrusted.example', 1, 'attacker');
    connectNative('__dsh_nav_connect__:wrong-key', '', 1, 'attacker');
    connectNative('__dsh_nav_connect__:test-installation-key', '', 2, 'attacker');
    connectNative('__dsh_nav_connect__:test-installation-key', '', 0, 'attacker');
    document.querySelector('._2H3hWW_root').classList.remove('_2H3hWW_collapsed');
  });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => attacker), []);
  assert.deepEqual(await page.evaluate(() => rawMessages), ['false', 'true']);
});

test('a replacement native channel receives the current state immediately', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => { window.reconnected = []; connectNative('__dsh_nav_connect__:test-installation-key', '', 1, 'reconnected'); });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => reconnected), ['false']);
  await page.evaluate(() => document.querySelector('._2H3hWW_root').classList.remove('_2H3hWW_collapsed'));
  await settle(page);
  assert.deepEqual(await page.evaluate(() => reconnected), ['false', 'true']);
  assert.deepEqual(await page.evaluate(() => rawMessages), ['false']);
});

test('a new native installation invalidates the previous connection key', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(script.replaceAll('test-installation-key', 'replacement-installation-key'));
  await page.evaluate(() => {
    window.oldKey = [];
    window.newKey = [];
    connectNative('__dsh_nav_connect__:test-installation-key', '', 1, 'oldKey');
    connectNative('__dsh_nav_connect__:replacement-installation-key', '', 1, 'newKey');
  });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => oldKey), []);
  assert.deepEqual(await page.evaluate(() => newKey), ['false']);
});

test('back closes the directory picker modal instead of leaving the app', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => {
    const modal = document.createElement('div');
    modal.className = '_root_o6lrb_6';
    modal.setAttribute('role', 'presentation');
    modal.innerHTML = '<div class="_mask_o6lrb_18" aria-hidden="true"></div><div class="_dialog_o6lrb_33 Fv-y6W_dialog" role="dialog" aria-modal="true"><button type="button">取消</button><button type="button">打开</button></div>';
    modal.querySelector('._mask_o6lrb_18').onclick = () => { clicks.push('directory-picker'); modal.remove(); };
    document.body.appendChild(modal);
  });
  await settle(page);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true]);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.deepEqual(await page.evaluate(() => clicks), ['directory-picker']);
  assert.deepEqual(await page.evaluate(() => notifications), [false, true, false]);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
});

test('back unwinds floating menus and nested modals in LIFO stack order', async t => {
  const page = await fixture(t, { home: true });
  await page.evaluate(() => {
    const picker = document.createElement('div');
    picker.className = '_root_o6lrb_6';
    picker.innerHTML = '<div class="_mask_o6lrb_18" aria-hidden="true"></div><div class="_dialog_o6lrb_33 Fv-y6W_dialog" role="dialog" aria-modal="true"></div>';
    picker.querySelector('._mask_o6lrb_18').onclick = () => { clicks.push('picker'); picker.remove(); };
    document.body.appendChild(picker);

    const create = document.createElement('div');
    create.className = '_root_o6lrb_6';
    create.innerHTML = '<div class="_mask_o6lrb_18" aria-hidden="true"></div><div class="_dialog_o6lrb_33 Fv-y6W_createDialog" role="dialog" aria-modal="true"><button class="_close_o6lrb_87" aria-label="关闭">X</button></div>';
    create.querySelector('._close_o6lrb_87').onclick = () => { clicks.push('create-folder'); create.remove(); };
    document.body.appendChild(create);

    const menu = document.createElement('div');
    menu.setAttribute('role', 'menu');
    menu.textContent = 'Menu';
    document.addEventListener('keydown', function onEsc(e) {
      if (e.key === 'Escape' && menu.isConnected) {
        clicks.push('menu-esc');
        menu.remove();
        document.removeEventListener('keydown', onEsc);
      }
    });
    document.body.appendChild(menu);
  });
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
  assert.deepEqual(await page.evaluate(() => clicks), ['menu-esc', 'create-folder', 'picker']);
});

test('back closes a modal portaled above settings before closing settings itself', async t => {
  const page = await fixture(t, { home: true, overlay: true });
  await page.evaluate(() => {
    const confirm = document.createElement('div');
    confirm.className = '_root_o6lrb_6';
    confirm.innerHTML = '<div class="_mask_o6lrb_18" aria-hidden="true"></div><div class="_dialog_o6lrb_33" role="dialog" aria-modal="true"><button class="_close_o6lrb_87" aria-label="Close">X</button></div>';
    confirm.querySelector('._close_o6lrb_87').onclick = () => { clicks.push('confirm-modal'); confirm.remove(); };
    document.body.appendChild(confirm);
  });
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'handled');
  await settle(page);
  assert.equal(await page.evaluate(() => __dshBack()), 'root');
  assert.deepEqual(await page.evaluate(() => clicks), ['confirm-modal', 'settings']);
});


