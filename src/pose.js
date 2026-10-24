// 포즈 추천 + (선택) MediaPipe 포즈 인식으로 인원수 감지·포즈 일치 판정.

// 팔 모양별 관절 좌표(사람 중심 기준, 화면에 보이는 대로 왼쪽이 -x). 상반신 위주(부스 구도).
// [왼팔 팔꿈치, 왼손목, 오른팔 팔꿈치, 오른손목]
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

// roles: 네 컷의 흐름 — opener(자연스럽게) → cute → fun → finale(함께)
export const POSES = [
  { id: 'smile', name: '자연스러운 미소', tip: '어깨 힘 빼고 카메라 렌즈를 보면서 웃어요', each: 'down', min: 1, max: 4, moods: ['calm', 'warm', 'bright', 'fresh'], roles: ['opener'] },
  { id: 'wave', name: '손 흔들기', tip: '한 손을 높이 들고 인사하듯 흔들어요', each: 'wave', min: 1, max: 4, moods: ['happy', 'fresh', 'bright'], roles: ['opener'] },
  { id: 'v', name: '얼굴 옆 브이', tip: '눈 옆에 브이를 대고 고개를 살짝 기울여요', each: 'v', min: 1, max: 4, moods: ['happy', 'bright', 'romantic', 'vivid'], roles: ['cute', 'opener'] },
  { id: 'cheek', name: '양 볼 꽃받침', tip: '두 손바닥으로 턱을 받쳐 꽃 모양을 만들어요', each: 'cheek', min: 1, max: 4, moods: ['romantic', 'happy', 'bright', 'dreamy'], roles: ['cute'] },
  { id: 'chin', name: '턱 괴기', tip: '주먹을 턱 아래에 대고 눈을 살짝 내려 봐요', each: 'chin', min: 1, max: 3, moods: ['chic', 'calm', 'dreamy', 'moody', 'gloomy'], roles: ['cute', 'opener'] },
  { id: 'surprise', name: '깜짝 놀라기', tip: '두 손으로 입을 가리고 눈을 크게 떠요', each: 'surprise', min: 1, max: 4, moods: ['happy', 'vivid', 'retro'], roles: ['fun'] },
  { id: 'fist', name: '파이팅', tip: '두 주먹을 얼굴 옆까지 올리고 힘차게', each: 'fist', min: 1, max: 4, moods: ['happy', 'vivid', 'fresh'], roles: ['fun'] },
  { id: 'point', name: '카메라 가리키기', tip: '렌즈를 손가락으로 콕 찌르듯 가리켜요', each: 'point', min: 1, max: 4, moods: ['happy', 'vivid', 'chic'], roles: ['fun'] },
  { id: 'cross', name: '팔짱 시크', tip: '팔짱을 끼고 무표정으로 렌즈를 봐요', each: 'cross', min: 1, max: 4, moods: ['chic', 'cool', 'mono', 'moody'], roles: ['opener', 'fun'] },
  { id: 'hips', name: '허리에 손', tip: '양손을 허리에 올리고 팔꿈치를 바깥으로', each: 'hips', min: 1, max: 3, moods: ['chic', 'vivid', 'retro'], roles: ['fun', 'opener'] },
  { id: 'think', name: '생각하는 사람', tip: '한 손은 턱, 한 손은 팔짱. 고개를 갸웃', each: 'think', min: 1, max: 3, moods: ['calm', 'gloomy', 'moody', 'retro', 'mono'], roles: ['fun'] },
  { id: 'heartTop', name: '머리 위 큰 하트', tip: '두 팔을 머리 위로 올려 하트를 만들어요', each: 'heartTop', min: 1, max: 4, moods: ['romantic', 'happy', 'warm', 'bright'], roles: ['finale'] },
  { id: 'halfHeart', name: '반쪽 하트 합체', tip: '둘이 팔을 하나씩 올려 큰 하트 하나를 완성해요', people: { 2: ['halfHeartL', 'halfHeartR'] }, min: 2, max: 2, moods: ['romantic', 'warm', 'dreamy'], roles: ['finale'] },
  { id: 'shoulder', name: '어깨동무', tip: '옆 사람 어깨에 팔을 올리고 몸을 붙여요', people: { 2: ['shoulderL', 'shoulderR'], 3: ['shoulderL', 'down', 'shoulderR'], 4: ['shoulderL', 'shoulderL', 'shoulderR', 'shoulderR'] }, min: 2, max: 4, moods: ['warm', 'happy', 'calm', 'retro', 'gloomy'], roles: ['finale'] },
  { id: 'stairs', name: '계단 높이', tip: '키 순서대로 높이를 다르게, 얼굴이 계단처럼', people: { 3: ['v', 'chin', 'cheek'], 4: ['v', 'chin', 'cheek', 'fist'] }, stagger: true, min: 3, max: 4, moods: ['happy', 'vivid', 'retro'], roles: ['fun', 'finale'] },
];

const ROLE_ORDER = ['opener', 'cute', 'fun', 'finale'];

function fits(p, n) {
  return n >= p.min && n <= p.max;
}

function score(p, n, moodId, role) {
  if (!fits(p, n)) return -Infinity;
  let s = 0;
  if (p.roles.includes(role)) s += 3;
  if (moodId && p.moods.includes(moodId)) s += 2;
  if (p.people && p.people[n]) s += 1.5; // 인원 전용 포즈 우대
  return s;
}

/** 4컷 포즈 플랜: 컷마다 후보 목록(점수순)을 돌려준다. 같은 포즈가 연속 추천되지 않게 한다. */
export function recommendPlan(n, moodId) {
  const used = new Set();
  return ROLE_ORDER.map((role) => {
    const ranked = POSES
      .map((p) => ({ p, s: score(p, n, moodId, role) }))
      .filter((x) => x.s > -Infinity)
      .sort((a, b) => b.s - a.s)
      .map((x) => x.p);
    const first = ranked.find((p) => !used.has(p.id)) ?? ranked[0];
    used.add(first.id);
    return [first, ...ranked.filter((p) => p !== first)];
  });
}

export function armsFor(pose, n) {
  if (pose.people?.[n]) return pose.people[n];
  return Array.from({ length: n }, () => pose.each ?? 'down');
}

/** 뷰파인더 위에 반투명 가이드 실루엣을 그린다 */
export function drawGuide(ctx, pose, n, w, h, ok) {
  const arms = armsFor(pose, n);
  const span = w / (n + 0.4);
  const scale = Math.min(h * 0.42, span * 1.1);
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = ok ? 'rgba(110, 231, 200, 0.95)' : 'rgba(255, 255, 255, 0.75)';
  ctx.fillStyle = ok ? 'rgba(110, 231, 200, 0.18)' : 'rgba(255, 255, 255, 0.12)';
  ctx.lineWidth = Math.max(3, scale * 0.045);
  ctx.setLineDash(ok ? [] : [scale * 0.06, scale * 0.05]);
  arms.forEach((armKey, i) => {
    const cx = w / 2 + (i - (n - 1) / 2) * span;
    const cy = h * 0.68 + (pose.stagger ? (i % 2 ? -0.08 : 0.06) * h : 0);
    const P = ([x, y]) => [cx + x * scale, cy + y * scale];
    const a = ARMS[armKey] ?? ARMS.down;
    // 머리
    const [hx, hy] = P([0, -0.55]);
    ctx.beginPath();
    ctx.arc(hx, hy, scale * 0.13, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    // 몸통
    const ls = P([-0.18, -0.35]), rs = P([0.18, -0.35]);
    const lh = P([-0.13, 0.2]), rh = P([0.13, 0.2]);
    ctx.beginPath();
    ctx.moveTo(...ls); ctx.lineTo(...rs); ctx.lineTo(...rh); ctx.lineTo(...lh); ctx.closePath();
    ctx.fill();
    ctx.stroke();
    // 팔
    for (const [s, e, wr] of [[ls, a[0], a[1]], [rs, a[2], a[3]]]) {
      ctx.beginPath();
      ctx.moveTo(...s); ctx.lineTo(...P(e)); ctx.lineTo(...P(wr));
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(...P(wr), scale * 0.035, 0, Math.PI * 2);
      ctx.fill();
    }
  });
  ctx.restore();
}

// ---------- MediaPipe (선택) ----------

const MP_VERSION = '0.10.14';
const MP_URL = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VERSION}`;
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task';

export class PoseDetector {
  constructor() {
    this.ready = false;
    this.landmarker = null;
    this.lastTs = -1;
  }

  async init() {
    const vision = await import(/* @vite-ignore */ `${MP_URL}/vision_bundle.mjs`);
    const fileset = await vision.FilesetResolver.forVisionTasks(`${MP_URL}/wasm`);
    this.landmarker = await vision.PoseLandmarker.createFromOptions(fileset, {
      baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
      runningMode: 'VIDEO',
      numPoses: 4,
    });
    this.ready = true;
  }

  /** @returns {Array<Array<{x:number,y:number}>>} 사람별 랜드마크(화면 거울 좌표로 변환됨) */
  detect(video, ts) {
    if (!this.ready || ts <= this.lastTs) return null;
    this.lastTs = ts;
    const res = this.landmarker.detectForVideo(video, ts);
    // 미리보기가 거울 모드라 x를 뒤집어 화면 기준으로 맞춘다
    return (res.landmarks ?? []).map((lm) => lm.map((p) => ({ x: 1 - p.x, y: p.y, v: p.visibility ?? 1 })));
  }
}

// 화면(거울) 기준 좌표에서 사람의 왼쪽 = 화면 왼쪽에 보이는 팔
function side(person) {
  const [a, b] = [person[11], person[12]];
  // 거울 화면에서 x가 작은 어깨가 화면 왼쪽
  const leftIsA = a.x < b.x;
  const pick = (ia, ib) => (leftIsA ? [person[ia], person[ib]] : [person[ib], person[ia]]);
  const [ls, rs] = pick(11, 12);
  const [le, re] = pick(13, 14);
  const [lw, rw] = pick(15, 16);
  const [lh, rh] = pick(23, 24);
  return { nose: person[0], ls, rs, le, re, lw, rw, lh, rh };
}

const dist = (p, q) => Math.hypot(p.x - q.x, p.y - q.y);

const CHECKS = {
  down: (b, sw) => b.lw.y > b.ls.y + sw * 0.3 && b.rw.y > b.rs.y + sw * 0.3,
  v: (b, sw) => dist(b.lw, b.nose) < sw * 1.0 || dist(b.rw, b.nose) < sw * 1.0,
  heartTop: (b, sw) => b.lw.y < b.nose.y && b.rw.y < b.nose.y && dist(b.lw, b.rw) < sw * 0.9,
  cheek: (b, sw) => dist(b.lw, b.nose) < sw * 1.0 && dist(b.rw, b.nose) < sw * 1.0,
  chin: (b, sw) => Math.min(dist(b.lw, b.nose), dist(b.rw, b.nose)) < sw * 0.8,
  surprise: (b, sw) => dist(b.lw, b.nose) < sw * 0.9 && dist(b.rw, b.nose) < sw * 0.9,
  wave: (b) => b.lw.y < b.nose.y || b.rw.y < b.nose.y,
  fist: (b) => b.lw.y < b.ls.y && b.rw.y < b.rs.y,
  point: (b, sw) => {
    const mx = (b.ls.x + b.rs.x) / 2;
    return [b.lw, b.rw].some((w) => Math.abs(w.x - mx) < sw * 0.5 && w.y > b.ls.y - sw * 0.3 && w.y < b.lh.y);
  },
  cross: (b, sw) => dist(b.lw, b.re) < sw * 0.7 && dist(b.rw, b.le) < sw * 0.7,
  hips: (b, sw) => dist(b.lw, b.lh) < sw * 0.7 && dist(b.rw, b.rh) < sw * 0.7,
  think: (b, sw) => Math.min(dist(b.lw, b.nose), dist(b.rw, b.nose)) < sw * 0.9,
  halfHeartL: (b) => b.rw.y < b.nose.y || b.lw.y < b.nose.y,
  halfHeartR: (b) => b.rw.y < b.nose.y || b.lw.y < b.nose.y,
  shoulderL: () => true,
  shoulderR: () => true,
};

/** 감지된 사람들이 포즈를 따라 했는지 판정. {matched, total} */
export function matchPose(pose, people, n) {
  const arms = armsFor(pose, n);
  if (!people || people.length < arms.length) return { matched: 0, total: arms.length, ok: false };
  const sorted = [...people].sort((a, b) => a[0].x - b[0].x).slice(0, arms.length);
  let matched = 0;
  sorted.forEach((person, i) => {
    const b = side(person);
    const sw = Math.max(0.04, dist(b.ls, b.rs));
    if ((CHECKS[arms[i]] ?? CHECKS.down)(b, sw)) matched += 1;
  });
  return { matched, total: arms.length, ok: matched === arms.length };
}
