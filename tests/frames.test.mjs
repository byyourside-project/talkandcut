import test from 'node:test';
import assert from 'node:assert/strict';
import { cellAspect, coverCrop, recommendFrames } from '../src/frames.js';

test('같은 카메라에서 2×2는 좌우를, 1×4는 상하를 중앙 기준으로 자른다', () => {
  const grid = coverCrop(1280, 960, cellAspect('grid'));
  const strip = coverCrop(1280, 960, cellAspect('strip'));
  assert.ok(grid.left > 0);
  assert.equal(grid.top, 0);
  assert.equal(strip.left, 0);
  assert.ok(strip.top > 0);
  for (const [id, crop] of [['grid', grid], ['strip', strip]]) {
    assert.equal(crop.left + crop.width / 2, 640);
    assert.equal(crop.top + crop.height / 2, 480);
    assert.ok(Math.abs(crop.width / crop.height - cellAspect(id)) < 1e-8);
  }
});

test('프레임 추천은 촬영 때 선택한 모양을 덮어쓰지 않는다', () => {
  const frames = recommendFrames({ avgRgb: [0.6, 0.5, 0.4], moodId: 'warm' });
  assert.ok(frames.length > 0);
  assert.ok(frames.every((frame) => !Object.hasOwn(frame, 'layout') && frame.reasons.length > 0));
});
