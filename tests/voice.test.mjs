import { test } from 'node:test';
import assert from 'node:assert/strict';

class Recognition {
  starts = 0;
  aborts = 0;

  start() { this.starts++; }
  abort() { this.aborts++; }
  hear(transcript) {
    this.onresult({ resultIndex: 0, results: [Object.assign([{ transcript }], { isFinal: true })] });
  }
}

let spoken;
globalThis.window = {
  SpeechRecognition: Recognition,
  speechSynthesis: { speak: (utterance) => { spoken = utterance; }, cancel: () => {} },
};
globalThis.SpeechSynthesisUtterance = class { constructor(text) { this.text = text; } };

const { Voice } = await import('../src/voice.js');

test('연결 오류가 나면 자동 재시작하지 않고 메뉴에서 다시 연결할 수 있다', () => {
  const statuses = [];
  const voice = new Voice({ onState: (status) => statuses.push(status), onFinal: () => {} });
  voice.start();
  assert.equal(statuses.at(-1), 'connecting');
  voice.rec.onstart();
  assert.equal(statuses.at(-1), 'listening');

  const denied = voice.rec;
  denied.onerror({ error: 'not-allowed' });
  denied.onend();
  assert.equal(statuses.at(-1), 'denied');
  assert.equal(denied.starts, 1);

  voice.start();
  voice.rec.onstart();
  assert.equal(statuses.at(-1), 'listening');
  const retry = voice.rec;
  assert.notEqual(retry, denied);
  assert.equal(retry.starts, 1);
  retry.onerror({ error: 'network' });
  retry.onend();
  assert.equal(statuses.at(-1), 'error');
  assert.equal(retry.starts, 1);
});

test('음성 안내가 먼저 끝나도 마이크가 종료된 뒤에만 다시 듣는다', () => {
  const heard = [];
  const voice = new Voice({ onState: () => {}, onFinal: (text) => heard.push(text) });
  voice.start();
  voice.rec.onstart();
  voice.say('좋아요. 다음 컷을 찍어요.');
  assert.equal(voice.rec.aborts, 1);
  voice.rec.hear('찍어요');
  assert.deepEqual(heard, []);

  spoken.onend();
  assert.equal(voice.rec.starts, 1);
  voice.rec.onend();
  voice.rec.onstart();
  voice.rec.hear('바로 찍어');
  assert.deepEqual(heard, ['바로 찍어']);
  assert.equal(voice.rec.starts, 2);

  const stopped = voice.rec;
  voice.stop();
  stopped.onend();
  assert.equal(stopped.starts, 2);
});

test('시작 이벤트가 없으면 연결을 중단하고 늦게 도착한 응답을 무시한다', (t) => {
  let scheduled;
  t.mock.method(globalThis, 'setTimeout', (callback) => {
    scheduled = callback;
    return 1;
  });
  t.mock.method(globalThis, 'clearTimeout', () => { scheduled = null; });
  const statuses = [];
  const voice = new Voice({ onState: (...args) => statuses.push(args), onFinal: () => assert.fail('late command') });
  voice.start();
  const stalled = voice.rec;
  scheduled();
  assert.deepEqual(statuses.at(-1), ['error', 'timeout']);
  assert.equal(stalled.aborts, 1);

  voice.start();
  const fresh = voice.rec;
  assert.notEqual(fresh, stalled);
  stalled.onstart();
  stalled.onend();
  stalled.hear('찍어줘');
  assert.deepEqual(statuses.at(-1), ['connecting']);
  fresh.onstart();
  assert.equal(scheduled, null);
  assert.deepEqual(statuses.at(-1), ['listening']);
  voice.stop();
});

test('시작 전에 연결이 닫히면 자동 재시작하지 않는다', () => {
  const statuses = [];
  const voice = new Voice({ onState: (...args) => statuses.push(args), onFinal: () => {} });
  voice.start();
  const stalled = voice.rec;
  stalled.onend();
  assert.deepEqual(statuses.at(-1), ['error', 'start-ended']);
  assert.equal(stalled.starts, 1);
});
