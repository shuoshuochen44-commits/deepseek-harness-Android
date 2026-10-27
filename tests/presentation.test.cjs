const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'app/assets/mobile');
const script = fs.existsSync(path.join(assets, 'presentation.js')) ? fs.readFileSync(path.join(assets, 'presentation.js'), 'utf8') : '';
const skin = fs.existsSync(path.join(assets, 'skin.css')) ? fs.readFileSync(path.join(assets, 'skin.css'), 'utf8') : '';
function sourceCss(packageName) {
  const source = fs.readFileSync(path.join(process.env.DSH_RUNTIME_DIR || path.join(root, 'runtime-asar'), 'node_modules/@deepseek-ai', packageName, 'lib/client.js'), 'utf8');
  return [...source.matchAll(/(?:const css(?:\$\d+)?|var [\w]+_css_default) = ("(?:\\.|[^"\\])*");/g)].map(m => JSON.parse(m[1])).join('\n');
}
const officialCss = ['dsh-client-ui-theme', 'dsh-client-ui-conversation', 'dsh-client-ui-sidebar-files', 'dsh-client-ui-settings-general', 'dsh-client-ui-layout', 'dsh-client-ui-sidebar'].map(sourceCss).join('\n');
const nativeMark = fs.readFileSync(path.join(root, 'app/res/drawable/ic_whale_blue.xml'), 'utf8');
const markPath = nativeMark.match(/android:pathData="([^"]+)"/)[1];
const hero = `<div class="Dc7zOa_composerStack Dc7zOa_composerHero"><div class="Hqq-bq_root"><div class="Hqq-bq_stack"><div class="Hqq-bq_headline"><span class="Hqq-bq_fishHitbox"><svg class="Hqq-bq_fish" width="34" height="34" viewBox="0 0 50 50" aria-hidden="true"><path d="${markPath}" fill="currentColor"/></svg></span><span class="Hqq-bq_titleGroup">探索未至之境 <span class="Hqq-bq_previewBadge">Preview</span></span></div><div class="Hqq-bq_body"></div></div></div><div class="Dc7zOa_heroWorkspaceRow"><button class="Hqq-bq_workspace">选择工作空间</button></div><div class="fixture-composer"><textarea aria-label="Message" placeholder="有什么可以帮你？"></textarea><button class="RlGAzG_primary">发送</button></div></div>`;
let browser;
before(async () => { browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || 'msedge' }); });
after(async () => { await browser?.close(); });
async function fixture(t, width = 393, dark = false, content = '') {
  const page = await browser.newPage({ viewport: { width, height: 820 }, colorScheme: dark ? 'dark' : 'light' });
  t.after(() => page.close());
  await page.route('http://dsh.test/**', route => {
    const name = new URL(route.request().url()).pathname.split('/').pop();
    const file = path.join(assets, name);
    if (['welcome.webp', 'empty.webp'].includes(name) && fs.existsSync(file)) return route.fulfill({ body: fs.readFileSync(file), contentType: 'image/webp' });
    return route.fulfill({ body: '', contentType: 'text/html' });
  });
  await page.goto('http://dsh.test/');
  await page.setContent(`<html data-dsh-android="1"><head><style>${officialCss}</style><style>html,body{margin:0;height:100%;font-family:Arial,sans-serif}*{box-sizing:border-box}.fixture-composer{margin:0 16px;padding:12px;border:1px solid var(--dsw-alias-border-l3);border-radius:20px;background:var(--dsw-specific-input-major)}textarea{display:block;width:100%;height:90px;resize:none;border:0;background:transparent;color:inherit;font:16px Arial}.fixture-composer button{display:block;margin-left:auto;}.fixture-message{display:flex;min-width:0}.fixture-message>div{min-width:0;flex:1}.fixture-long{width:2400px} </style><style id="dsh-android-skin">${skin}</style></head><body ${dark ? 'data-ds-dark-theme' : ''}><main class="Dc7zOa_root" data-phase="hero"><div class="Dc7zOa_body" data-conversation-content><div class="Dc7zOa_scrollBody"><div class="Dc7zOa_composerSeat" data-conversation-region="composer">${content || hero}</div></div></div></main></body></html>`);
  await page.evaluate(() => { window.intervalCount = 0; const original = setInterval; window.setInterval = (...args) => { intervalCount++; return original(...args); }; });
  await page.evaluate(script);
  await settle(page);
  return page;
}
async function settle(page) { await page.waitForTimeout(60); }

test('fixtures track the shipped hero, files, settings, and theme selectors', () => {
  for (const selector of ['.Hqq-bq_stack', '.Dc7zOa_composerHero', '.Woyo-G_level', '.wCInkW_panel', 'body[data-ds-dark-theme]']) assert.ok(officialCss.includes(selector), selector);
});

test('both packaged illustrations decode through the local app asset URLs', async t => {
  for (const name of ['welcome.webp', 'empty.webp']) assert.ok(fs.existsSync(path.join(assets, name)), `Missing local illustration: ${name}`);
  const page = await fixture(t);
  const decoded = await page.evaluate(async () => {
    return Promise.all(['welcome.webp', 'empty.webp'].map(async name => {
      const image = new Image();
      image.src = '/__dsh_mobile/' + name;
      await image.decode();
      return { width: image.naturalWidth, height: image.naturalHeight };
    }));
  });
  for (const image of decoded) assert.ok(image.width >= 240 && image.height >= 160);
});

for (const width of [360, 393, 430]) for (const dark of [false, true]) {
  test(`welcome fits ${width}px in ${dark ? 'dark' : 'light'} theme with a prominent composer`, async t => {
    const page = await fixture(t, width, dark);
    assert.equal(await page.locator('.Hqq-bq_stack > img[data-dsh-mobile-art="welcome"]').count(), 1);
    const metrics = await page.evaluate(() => {
      const image = document.querySelector('[data-dsh-mobile-art="welcome"]');
      const composer = document.querySelector('textarea').getBoundingClientRect();
      return { width: image.getBoundingClientRect().width, height: image.getBoundingClientRect().height, overflow: document.documentElement.scrollWidth > innerWidth, composerBottom: composer.bottom, color: getComputedStyle(document.body).backgroundColor, src: image.getAttribute('src'), hidden: image.getAttribute('aria-hidden'), alt: image.alt };
    });
    assert.ok(metrics.width > 150 && metrics.width <= 240);
    assert.ok(metrics.height > 70 && metrics.height <= 160);
    assert.equal(metrics.overflow, false);
    assert.ok(metrics.composerBottom < 760);
    assert.equal(metrics.src, '/__dsh_mobile/welcome.webp');
    assert.equal(metrics.alt, '');
    assert.equal(metrics.hidden, 'true');
    assert.notEqual(metrics.color, 'rgba(0, 0, 0, 0)');
    assert.ok(await page.locator('.Hqq-bq_workspace').evaluate(e => e.getBoundingClientRect().height >= 44));
    if (process.env.PRESENTATION_SCREENSHOTS) await page.screenshot({ path: path.join(process.env.PRESENTATION_SCREENSHOTS, `welcome-${width}-${dark ? 'dark' : 'light'}.png`) });
  });
}

test('repeated installation and React-style remounts retain exactly one welcome illustration', async t => {
  const page = await fixture(t);
  await page.evaluate(script);
  await page.evaluate(script);
  await page.evaluate(hero => { document.querySelector('[data-conversation-region="composer"]').innerHTML = hero; }, hero);
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art="welcome"]').count(), 1);
  await page.evaluate(() => document.querySelector('[data-dsh-mobile-art="welcome"]').remove());
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art="welcome"]').count(), 1);
});

test('only the root empty file directory receives artwork, including newly mounted trees', async t => {
  const page = await fixture(t);
  await page.evaluate(() => {
    document.querySelector('main').innerHTML = '<section class="Woyo-G_root"><div data-files-body><ul class="Woyo-G_level"><li data-files-row="empty">Empty directory</li></ul></div></section><section><div data-files-body><ul class="Woyo-G_level"><li data-files-entry="directory"><ul class="Woyo-G_level"><li data-files-row="empty">Empty directory</li></ul></li></ul></div></section><article><p>Empty directory</p></article>';
  });
  const states = await page.locator('[data-files-row="empty"]').evaluateAll(elements => elements.map(e => ({ image: getComputedStyle(e, '::before').backgroundImage, text: e.textContent })));
  assert.match(states[0].image, /\/__dsh_mobile\/empty\.webp/);
  assert.equal(states[1].image, 'none');
  assert.deepEqual(states.map(e => e.text), ['Empty directory', 'Empty directory']);
  if (process.env.PRESENTATION_SCREENSHOTS) await page.screenshot({ path: path.join(process.env.PRESENTATION_SCREENSHOTS, 'empty-root.png') });
});

test('long conversation code scrolls inside its container without page overflow', async t => {
  const page = await fixture(t, 360, false, '<article class="fixture-message"><div><pre><code>' + 'const_message_'.repeat(140) + '</code></pre><p>探索未至之境: user-authored text</p></div></article>');
  const metrics = await page.locator('pre').evaluate(e => ({ overflow: document.documentElement.scrollWidth > innerWidth, scrolls: e.scrollWidth > e.clientWidth, mode: getComputedStyle(e).overflowX }));
  assert.equal(metrics.overflow, false);
  assert.equal(metrics.scrolls, true);
  assert.equal(metrics.mode, 'auto');
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 0);
  assert.equal(await page.locator('p').textContent(), '探索未至之境: user-authored text');
});

test('settings fit the mobile viewport and keep horizontally scrolling tabs', async t => {
  const page = await fixture(t, 360);
  await page.evaluate(() => { document.body.insertAdjacentHTML('beforeend', '<div class="wCInkW_overlay"><div class="wCInkW_panel"><nav class="wCInkW_nav"><div class="wCInkW_navList">' + Array.from({ length: 10 }, (_, i) => '<button class="wCInkW_navCell">Settings ' + i + '</button>').join('') + '</div></nav><div class="wCInkW_content"><header class="wCInkW_header">设置</header><div class="wCInkW_options">账号</div></div></div></div>'); });
  const metrics = await page.locator('.wCInkW_panel').evaluate(e => ({ width: e.getBoundingClientRect().width, height: e.getBoundingClientRect().height, overflow: document.documentElement.scrollWidth > innerWidth, tabs: getComputedStyle(document.querySelector('.wCInkW_navList')).overflowX, touch: document.querySelector('.wCInkW_navCell').getBoundingClientRect().height }));
  assert.equal(metrics.width, 360);
  assert.equal(metrics.height, 820);
  assert.equal(metrics.overflow, false);
  assert.equal(metrics.tabs, 'auto');
  assert.ok(metrics.touch >= 44);
});

for (const width of [360, 393, 430]) {
  test(`expanded sidebar overlays the ${width}px center without squeezing it and dismisses outside`, async t => {
    const page = await fixture(t, width);
    await page.evaluate(() => {
      const main = document.querySelector('main');
      const frame = document.createElement('div');
      frame.className = 'BynINW_frame';
      frame.style.gridTemplateColumns = '280px minmax(0px, 1fr) minmax(0px, 0px)';
      frame.innerHTML = '<aside class="BynINW_sidebarCol"><div class="_2H3hWW_root"><button class="_2H3hWW_toggle">Close sidebar</button></div></aside><div class="BynINW_centerCol"></div><div class="BynINW_rightbarCol"></div><div class="BynINW_overlayLayer"></div>';
      main.replaceWith(frame);
      frame.querySelector('.BynINW_centerCol').append(main);
      window.centerClicks = 0;
      main.onclick = () => centerClicks++;
      frame.querySelector('button').onclick = () => {
        frame.setAttribute('data-sidebar-collapsed', 'true');
        frame.style.gridTemplateColumns = '56px minmax(0px, 1fr) minmax(0px, 0px)';
        frame.querySelector('._2H3hWW_root').classList.add('_2H3hWW_collapsed');
      };
    });
    const metrics = await page.evaluate(() => {
      const rect = s => document.querySelector(s).getBoundingClientRect();
      return { center: rect('.BynINW_centerCol').width, sidebar: rect('.BynINW_sidebarCol').width, art: rect('[data-dsh-mobile-art]').width, overflow: document.documentElement.scrollWidth > innerWidth };
    });
    assert.ok(metrics.center >= width - 57, JSON.stringify(metrics));
    assert.ok(metrics.sidebar >= 264 && metrics.sidebar <= width - 44);
    assert.ok(metrics.art >= 150);
    assert.equal(metrics.overflow, false);
    await page.mouse.click(width - 12, 200);
    await settle(page);
    assert.equal(await page.locator('.BynINW_frame').getAttribute('data-sidebar-collapsed'), 'true');
    assert.equal(await page.evaluate(() => centerClicks), 0);
    assert.ok(await page.locator('.BynINW_centerCol').evaluate(e => e.getBoundingClientRect().width >= innerWidth - 57));
    await page.evaluate(script);
    await page.evaluate(() => __dshPresentationCleanup());
    await page.evaluate(script);
    assert.equal(await page.locator('[data-dsh-mobile-art="welcome"]').count(), 1);
  });
}

test('a short viewport keeps the welcome image compact and the composer reachable', async t => {
  const page = await fixture(t, 360);
  await page.setViewportSize({ width: 360, height: 480 });
  const metrics = await page.evaluate(() => ({ image: document.querySelector('[data-dsh-mobile-art]').getBoundingClientRect().height, composer: document.querySelector('textarea').getBoundingClientRect().bottom, overflow: document.documentElement.scrollWidth > innerWidth }));
  assert.equal(metrics.image, 96);
  assert.ok(metrics.composer <= 480);
  assert.equal(metrics.overflow, false);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  assert.equal(await page.locator('.Hqq-bq_fish').evaluate(e => getComputedStyle(e).animationName), 'none');
});

test('the composer send control has a 44px mobile touch target', async t => {
  const page = await fixture(t, 360);
  const target = await page.locator('.RlGAzG_primary').evaluate(e => ({ width: e.getBoundingClientRect().width, height: e.getBoundingClientRect().height }));
  assert.ok(target.width >= 44 && target.height >= 44);
});

test('a hidden document suspends decoration and resumes on visibility', async t => {
  const page = await fixture(t);
  await page.evaluate(hero => {
    window.testHidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.testHidden });
    document.dispatchEvent(new Event('visibilitychange'));
    document.querySelector('[data-conversation-region="composer"]').innerHTML = hero;
  }, hero);
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 0);
  await page.evaluate(() => { window.testHidden = false; document.dispatchEvent(new Event('visibilitychange')); });
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 1);
});

test('streaming text causes no polling or artwork rescans, and lifecycle cleanup is reversible', async t => {
  const page = await fixture(t);
  await page.evaluate(() => { window.scans = 0; const q = Element.prototype.querySelectorAll; Element.prototype.querySelectorAll = function(...args) { scans++; return q.apply(this, args); }; document.body.insertAdjacentHTML('beforeend', '<article id="stream"></article>'); });
  await settle(page);
  const start = await page.evaluate(() => scans);
  await page.evaluate(() => { const stream = document.getElementById('stream'); for (let i = 0; i < 100; i++) stream.append(document.createTextNode('token')); });
  await settle(page);
  assert.equal(await page.evaluate(() => scans), start);
  assert.equal(await page.evaluate(() => intervalCount), 0);
  await page.evaluate(() => { dispatchEvent(new Event('pagehide')); document.querySelector('[data-conversation-region="composer"]').innerHTML = ''; });
  await page.evaluate(hero => { document.querySelector('[data-conversation-region="composer"]').innerHTML = hero; }, hero);
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 0);
  await page.evaluate(() => dispatchEvent(new Event('pageshow')));
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 1);
  await page.evaluate(() => __dshPresentationCleanup());
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 0);
  await page.evaluate(script);
  await settle(page);
  assert.equal(await page.locator('[data-dsh-mobile-art]').count(), 1);
});
