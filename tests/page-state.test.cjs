const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');

test('page load callback ordering preserves failure and explicit recovery', t => {
  const root = path.resolve(__dirname, '..');
  const source = path.join(root, 'app/src/io/dsh/android/PageLoadState.java');
  assert.ok(fs.existsSync(source), 'PageLoadState must handle failure followed by a late page-finished event');
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-page-state-'));
  t.after(() => {
    assert.equal(path.dirname(out), path.resolve(os.tmpdir()));
    fs.rmSync(out, { recursive: true, force: true });
  });
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
  const executable = name => java ? path.join(java, name + (process.platform === 'win32' ? '.exe' : '')) : name;
  const compile = spawnSync(executable('javac'), ['--release', '11', '-encoding', 'UTF-8', '-d', out, source, path.join(__dirname, 'PageLoadStateTest.java')], { encoding: 'utf8', windowsHide: true });
  assert.equal(compile.status, 0, compile.stderr);
  const run = spawnSync(executable('java'), ['-cp', out, 'io.dsh.android.PageLoadStateTest'], { encoding: 'utf8', windowsHide: true });
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /PASS:/);
  console.log(run.stdout.trim());
});
