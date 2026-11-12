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

  voice.rec.onerror({ error: 'not-allowed' });
  voice.rec.onend();
  assert.equal(statuses.at(-1), 'denied');
  assert.equal(voice.rec.starts, 1);

  voice.start();
  voice.rec.onstart();
  assert.equal(statuses.at(-1), 'listening');
  assert.equal(voice.rec.starts, 2);
  voice.rec.onerror({ error: 'network' });
  voice.rec.onend();
  assert.equal(statuses.at(-1), 'error');
  assert.equal(voice.rec.starts, 2);
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

  voice.stop();
  voice.rec.onend();
  assert.equal(voice.rec.starts, 2);
});
