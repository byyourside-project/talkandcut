import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { cellAspect } from '../src/frames.js';
import { POSES_BY_COUNT, CHECKS, side, matchPose, armsFor, peopleInFrame, peopleInSlots, recommendPlan, guidePlacement } from '../src/pose.js';

// 각 포즈의 손과 팔 좌표로 테스트용 랜드마크를 만든다.
const ARMS = {
  down: [[-0.24, -0.12], [-0.26, 0.1], [0.24, -0.12], [0.26, 0.1]],
  v: [[-0.24, -0.12], [-0.26, 0.1], [0.27, -0.27], [0.13, -0.56]],
  heartTop: [[-0.3, -0.66], [-0.03, -0.86], [0.3, -0.66], [0.03, -0.86]],
  cheek: [[-0.22, -0.2], [-0.1, -0.5], [0.22, -0.2], [0.1, -0.5]],
  chin: [[-0.12, -0.14], [-0.03, -0.42], [0.12, -0.14], [0.03, -0.42]],
  wave: [[-0.24, -0.12], [-0.26, 0.1], [0.33, -0.5], [0.36, -0.82]],
  point: [[-0.24, -0.12], [-0.26, 0.1], [0.16, -0.2], [0.04, -0.28]],
  cross: [[-0.21, -0.14], [0.12, -0.2], [0.21, -0.14], [-0.12, -0.2]],
  fist: [[-0.31, -0.24], [-0.22, -0.54], [0.31, -0.24], [0.22, -0.54]],
  hips: [[-0.33, -0.08], [-0.15, 0.12], [0.33, -0.08], [0.15, 0.12]],
  surprise: [[-0.2, -0.2], [-0.05, -0.45], [0.2, -0.2], [0.05, -0.45]],
  think: [[-0.2, -0.14], [0.08, -0.2], [0.12, -0.14], [0.03, -0.42]],
  halfHeartL: [[-0.24, -0.12], [-0.26, 0.1], [0.06, -0.86], [0.32, -0.72]],
  halfHeartR: [[-0.06, -0.86], [-0.32, -0.72], [0.24, -0.12], [0.26, 0.1]],
  shoulderL: [[-0.24, -0.12], [-0.26, 0.1], [0.32, -0.3], [0.52, -0.36]],
  shoulderR: [[-0.32, -0.3], [-0.52, -0.36], [0.24, -0.12], [0.26, 0.1]],
};

function person(key, { cx = 0.66, cy = 0.6, s = 0.5, hiddenWrists = false } = {}) {
  const P = ([x, y], v = 1) => ({ x: cx + x * s, y: cy + y * s, v });
  const a = ARMS[key];
  const lm = Array.from({ length: 33 }, () => P([0, 0]));
  lm[0] = P([0, -0.55]);
  lm[11] = P([-0.18, -0.35]); lm[12] = P([0.18, -0.35]);
  lm[13] = P(a[0]); lm[14] = P(a[2]);
  lm[15] = P(a[1], hiddenWrists ? 0.1 : 1); lm[16] = P(a[3], hiddenWrists ? 0.1 : 1);
  lm[23] = P([-0.13, 0.2]); lm[24] = P([0.13, 0.2]);
  return lm;
}

const check = (name, lm) => {
  const b = side(lm);
  return CHECKS[name](b, Math.hypot(b.ls.x - b.rs.x, b.ls.y - b.rs.y));
};

test('포즈별 테스트 자세가 해당 판정을 통과한다', () => {
  for (const key of Object.keys(ARMS)) assert.ok(check(key, person(key)), key);
});

test('차렷 자세로 서 있으면 차렷 말고는 통과하지 않는다 (자동 셔터 오작동 방지)', () => {
  const idle = person('down');
  for (const key of Object.keys(CHECKS)) {
    if (['down', 'shoulderL', 'shoulderR'].includes(key)) continue;
    assert.equal(check(key, idle), false, key);
  }
});

test('한 손 포즈(브이·가리키기·생각)는 양손 포즈로 통과되지 않는다', () => {
  for (const two of ['cheek', 'surprise', 'heartTop', 'cross']) {
    assert.equal(check('v', person(two)), false, `v ← ${two}`);
    assert.equal(check('point', person(two)), false, `point ← ${two}`);
  }
  assert.equal(check('think', person('cheek')), false);
});

test('손목이 화면 밖이면 차렷으로 보고, 손 올린 포즈는 통과시키지 않는다', () => {
  for (const key of ['wave', 'fist', 'heartTop', 'cheek']) {
    const lm = person(key, { hiddenWrists: true });
    assert.equal(check(key, lm), false, key);
    assert.ok(check('down', lm), `down ← hidden ${key}`);
  }
});

test('어깨동무는 두 사람이 붙어 있어야 통과', () => {
  const pose = POSES_BY_COUNT[2].find((p) => p.id === 'shoulder');
  const [l, r] = armsFor(pose, 2);
  const close = [person(l, { cx: 0.5, s: 0.4 }), person(r, { cx: 0.5 + 0.4 * 0.4, s: 0.4 })];
  const apart = [person(l, { cx: 0.3, s: 0.4 }), person(r, { cx: 1.0, s: 0.4 })];
  assert.equal(matchPose(pose, close, 2).ok, true);
  assert.equal(matchPose(pose, apart, 2).ok, false);
});

test('인원보다 많이 잡히면 크게 보이는(가까운) 사람으로 판정', () => {
  const pose = POSES_BY_COUNT[1].find((p) => p.id === 'wave');
  const front = person('wave', { cx: 0.7, s: 0.5 });
  const passerby = person('down', { cx: 0.2, s: 0.15 });
  const m = matchPose(pose, [passerby, front], 1);
  assert.equal(m.ok, true);
  assert.equal(m.picked[0], front);
});

test('촬영 범위를 벗어나거나 같은 칸에 모인 사람은 빈 칸을 채우지 않는다', () => {
  const area = { left: 0.25, top: 0.15, width: 1, height: 0.7 };
  const left = person('down', { cx: 0.39 });
  const right = person('down', { cx: 1.1 });
  const background = person('down', { cx: 0.48, s: 0.2 });
  const outside = person('down', { cx: 0.1 });
  const above = person('down', { cx: 0.86, cy: 0.32 });
  const framed = peopleInFrame([outside, background, right, above, left], area);
  assert.deepEqual(peopleInSlots(framed, 4, area), [left, null, null, right]);
});

test('인원별 네 컷의 후보에 해당 인원의 PNG가 모두 들어 있다', () => {
  for (const [count, expected] of [[1, 12], [2, 14], [3, 14], [4, 11]]) {
    const plans = recommendPlan(count, null);
    assert.equal(new Set(plans.map(([first]) => first.id)).size, 4);
    for (const candidates of plans) assert.equal(new Set(candidates.map((p) => p.id)).size, expected);
    for (const pose of plans[0]) {
      const png = readFileSync(new URL(`../assets/poses/${count}/${pose.id}.png`, import.meta.url));
      const [width, height] = pose.image;
      const [left, top, right, bottom] = pose.bounds;
      assert.equal(png.readUInt32BE(16), width);
      assert.equal(png.readUInt32BE(20), height);
      for (const layout of ['grid', 'strip']) {
        const aspect = cellAspect(layout);
        const placement = guidePlacement(pose, aspect);
        const scale = placement.height / 100;
        const outlineTop = placement.top / 100 + top / height * scale;
        const outlineBottom = placement.top / 100 + bottom / height * scale;
        const face = placement.top / 100 + pose.faceY * scale;
        assert.ok((right - left) / height * scale <= aspect * 0.881);
        assert.ok(outlineTop >= 0.059 && outlineBottom <= 0.941);
        assert.ok(face >= 0.35 && face <= 0.37);
      }
    }
  }
});

test('관절 판정이 없는 그룹 포즈는 차렷 자세로 통과하지 않는다', () => {
  const pose = POSES_BY_COUNT[3].find((p) => p.id === 'centerStar');
  assert.equal(matchPose(pose, [person('down'), person('down'), person('down')], 3).ok, false);
});
