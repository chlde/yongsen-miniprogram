const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function player() {
  let page;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/learn/index.js'), 'utf8'), {
    require: () => ({}), Page: value => { page = value; }
  });
  const events = [];
  Object.assign(page, { material: { durationMs: 6000 }, _positionMs: 5780, _clock: () => 6000,
    setData(data) { Object.assign(this.data, data); },
    videoPayload(extra) { return Object.assign({ positionMs: this._positionMs }, extra); },
    emit(type, payload) { events.push({ type, payload }); }
  });
  return { page, events };
}

test('native final frame overrun stays inside the server media duration', () => {
  const { page, events } = player();
  page.onVideoTime({ detail: { currentTime: 6.014 } });
  page.onVideoEnded();
  assert.equal(events[0].payload.positionMs, 6000);
  assert.equal(events[0].payload.lastObservedPositionMs, 6000);
});

test('ordinary positions and large overruns are not rewritten as completed playback', () => {
  for (const [seconds, expected] of [[5.78, 5780], [8, 8000]]) {
    const { page } = player();
    page.onVideoTime({ detail: { currentTime: seconds } });
    assert.equal(page._positionMs, expected);
  }
});
