import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseUtterance } from '../src/intent.js';
import { applyMood, NEUTRAL } from '../src/emotion.js';

const acts = (t, ctx) => parseUtterance(t, ctx).map((a) => a.action);

test('기본 촬영과 지연', () => {
  assert.deepEqual(parseUtterance('찍어줘'), [{ action: 'CAPTURE', delay: 3 }]);
  assert.equal(parseUtterance('5초 뒤에 찍어')[0].delay, 5);
  assert.equal(parseUtterance('다섯 초 뒤에 찍어')[0].delay, 5);
  assert.equal(parseUtterance('바로 찍어')[0].delay, 0);
  assert.deepEqual(acts('치즈'), ['CAPTURE']);
});

test('조건부 셔터', () => {
  const a = parseUtterance('포즈 맞추면 찍어줘');
  assert.deepEqual(a, [{ action: 'CAPTURE', delay: 3, when: 'POSE_MATCH' }]);
});

test('다시 찍기와 컷 번호', () => {
  assert.deepEqual(parseUtterance('두 번째 컷 다시 찍어'), [{ action: 'RETAKE', index: 1, delay: 3 }]);
  assert.equal(parseUtterance('마지막 사진 다시 찍어줘')[0].index, 3);
  assert.equal(parseUtterance('다시 찍을래')[0].index, null);
});

test('감성 + 촬영 복합 명령은 보정 먼저', () => {
  assert.deepEqual(acts('따뜻한 느낌으로 3초 뒤에 찍어줘'), ['FILTER', 'CAPTURE']);
});

test('감성 해석: set vs adjust', () => {
  const set = parseUtterance('몽환적인 느낌으로 해줘')[0];
  assert.equal(set.mode, 'set');
  assert.deepEqual(set.moodIds, ['dreamy']);

  const adj = parseUtterance('조금 더 따뜻하게', { hasFilter: true })[0];
  assert.equal(adj.mode, 'adjust');
  assert.ok(adj.deltas.warmth > 0 && adj.deltas.warmth < 35);

  const less = parseUtterance('아까보다 덜 따뜻하게', { hasFilter: true })[0];
  assert.ok(less.deltas.warmth < 0);

  const dark = parseUtterance('좀 더 어둡게', { hasFilter: true })[0];
  assert.equal(dark.mode, 'adjust');
  assert.ok(dark.deltas.brightness < 0);
});

test('멀티턴 적용 결과', () => {
  let p = applyMood(NEUTRAL, parseUtterance('따뜻하게')[0]);
  const w1 = p.warmth;
  p = applyMood(p, parseUtterance('조금 더 따뜻하게', { hasFilter: true })[0]);
  assert.ok(p.warmth > w1);
  p = applyMood(p, parseUtterance('흑백으로')[0]);
  assert.equal(p.saturation, -100);
  assert.equal(p.warmth, 0);
});

test('레트로/필름 비유', () => {
  assert.deepEqual(parseUtterance('90년대 필름카메라처럼')[0].moodIds, ['retro']);
});

test('초기화', () => {
  assert.deepEqual(acts('원본으로 돌려줘'), ['RESET_FILTER']);
});

test('포즈·인원·프레임·문구·저장', () => {
  assert.deepEqual(parseUtterance('우리 세 명이야'), [{ action: 'HEADCOUNT', count: 3 }]);
  assert.deepEqual(acts('둘이 찍을 포즈 추천해줘'), ['HEADCOUNT', 'POSE_PLAN']);
  assert.deepEqual(acts('다른 포즈'), ['POSE_NEXT']);
  assert.deepEqual(acts('프레임 추천해줘'), ['FRAME_RECOMMEND']);
  assert.deepEqual(acts('다른 프레임으로 바꿔'), ['FRAME_NEXT']);
  assert.deepEqual(parseUtterance('2x2로 바꿔'), [{ action: 'LAYOUT', layout: 'grid' }]);
  assert.deepEqual(parseUtterance('문구는 우리 졸업 축하해로 해줘'), [{ action: 'CAPTION', text: '우리 졸업 축하해' }]);
  assert.deepEqual(acts('저장해줘'), ['SAVE']);
  assert.deepEqual(acts('처음부터 다시 시작'), ['START']);
});

test('모르는 말은 빈 배열', () => {
  assert.deepEqual(parseUtterance('오늘 점심 뭐 먹지'), []);
});
