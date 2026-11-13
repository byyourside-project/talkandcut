// 포즈 추천 + (선택) MediaPipe 포즈 인식으로 인원수 감지·포즈 일치 판정.

// roles: 네 컷의 흐름 — opener(자연스럽게) → cute → fun → finale(함께)
export const POSES = [
  { id: 'smile', name: '자연스러운 미소', tip: '어깨 힘을 빼고 편하게 웃어 보세요.', each: 'down', min: 1, max: 4, moods: ['calm', 'warm', 'bright', 'fresh'], roles: ['opener'] },
  { id: 'wave', name: '손 흔들기', tip: '친구를 만난 것처럼 가볍게 손을 흔들어 보세요.', each: 'wave', min: 1, max: 4, moods: ['happy', 'fresh', 'bright'], roles: ['opener'] },
  { id: 'v', name: '얼굴 옆 브이', tip: '볼 옆에 브이를 하고 고개를 살짝 기울여 보세요.', each: 'v', min: 1, max: 4, moods: ['happy', 'bright', 'romantic', 'vivid'], roles: ['cute', 'opener'] },
  { id: 'cheek', name: '양 볼 꽃받침', tip: '두 손을 볼 옆에 대고 살짝 웃어 보세요.', each: 'cheek', min: 1, max: 4, moods: ['romantic', 'happy', 'bright', 'dreamy'], roles: ['cute'] },
  { id: 'chin', name: '턱 괴기', tip: '주먹을 턱 아래에 대고 눈을 살짝 내려 봐요', each: 'chin', min: 1, max: 3, moods: ['chic', 'calm', 'dreamy', 'moody', 'gloomy'], roles: ['cute', 'opener'] },
  { id: 'surprise', name: '깜짝 놀라기', tip: '깜짝 놀란 척, 두 손을 입가에 모아 보세요.', each: 'surprise', min: 1, max: 4, moods: ['happy', 'vivid', 'retro'], roles: ['fun'] },
  { id: 'fist', name: '파이팅', tip: '두 주먹을 얼굴 옆까지 올리고 힘차게', each: 'fist', min: 1, max: 4, moods: ['happy', 'vivid', 'fresh'], roles: ['fun'] },
  { id: 'point', name: '카메라 가리키기', tip: '렌즈를 손가락으로 콕 찌르듯 가리켜요', each: 'point', min: 1, max: 4, moods: ['happy', 'vivid', 'chic'], roles: ['fun'] },
  { id: 'cross', name: '팔짱 시크', tip: '팔짱을 끼고 무표정으로 렌즈를 봐요', each: 'cross', min: 1, max: 4, moods: ['chic', 'cool', 'mono', 'moody'], roles: ['opener', 'fun'] },
  { id: 'hips', name: '허리에 손', tip: '양손을 허리에 올리고 팔꿈치를 바깥으로', each: 'hips', min: 1, max: 3, moods: ['chic', 'vivid', 'retro'], roles: ['fun', 'opener'] },
  { id: 'think', name: '생각하는 사람', tip: '한 손은 턱, 한 손은 팔짱. 고개를 갸웃', each: 'think', min: 1, max: 3, moods: ['calm', 'gloomy', 'moody', 'retro', 'mono'], roles: ['fun'] },
  { id: 'heartTop', name: '머리 위 큰 하트', tip: '머리 위에 각자 하트를 만들어 보세요.', each: 'heartTop', min: 1, max: 4, moods: ['romantic', 'happy', 'warm', 'bright'], roles: ['finale'] },
  { id: 'halfHeart', name: '반쪽 하트 합체', tip: '둘이 팔을 하나씩 올려 큰 하트 하나를 완성해요', people: { 2: ['halfHeartL', 'halfHeartR'] }, min: 2, max: 2, moods: ['romantic', 'warm', 'dreamy'], roles: ['finale'] },
  { id: 'shoulder', name: '어깨동무', tip: '얼굴은 각자 자리에 두고 옆 사람 어깨에 팔을 올려 보세요.', people: { 2: ['shoulderL', 'shoulderR'], 3: ['shoulderL', 'down', 'shoulderR'], 4: ['shoulderL', 'shoulderL', 'shoulderR', 'shoulderR'] }, min: 2, max: 4, moods: ['warm', 'happy', 'calm', 'retro', 'gloomy'], roles: ['finale'] },
  { id: 'stairs', name: '계단 높이', tip: '각자 자리에서 고개 높이를 조금씩 다르게 해 보세요.', people: { 3: ['v', 'chin', 'cheek'], 4: ['v', 'chin', 'cheek', 'fist'] }, min: 3, max: 4, moods: ['happy', 'vivid', 'retro'], roles: ['fun', 'finale'] },
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

/** 미러링된 영상 좌표에서 얼굴이 촬영 범위 안에 있는 사람을 고른다. */
export function peopleInFrame(people, { left, top, width, height }) {
  return people.filter(([nose]) => seen(nose) && nose.x >= left && nose.x <= left + width
    && nose.y >= top && nose.y <= top + height);
}

/** 촬영 범위 안에 있는 사람 중 각 칸에서 가장 가까운 사람을 한 명씩 고른다. */
export function peopleInSlots(people, count, { left, width }) {
  const slots = Array(count).fill(null);
  const sizes = Array(count).fill(0);
  for (const person of people) {
    const position = (person[0].x - left) / width;
    const index = Math.min(count - 1, Math.floor(position * count));
    const size = dist(person[11], person[12]);
    if (!slots[index] || size > sizes[index]) {
      slots[index] = person;
      sizes[index] = size;
    }
  }
  return slots;
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

  /**
   * @returns {Array<Array<{x:number,y:number,v:number}>>} 사람별 랜드마크.
   * 화면 거울 좌표로 뒤집고, x를 영상 비율만큼 늘려 x·y가 같은 단위(영상 높이=1)가 되게 한다.
   */
  detect(video, ts) {
    if (!this.ready || ts <= this.lastTs) return null;
    this.lastTs = ts;
    const res = this.landmarker.detectForVideo(video, ts);
    const ar = video.videoWidth / video.videoHeight || 1;
    return (res.landmarks ?? []).map((lm) => lm.map((p) => ({ x: (1 - p.x) * ar, y: p.y, v: p.visibility ?? 1 })));
  }
}

// 화면(거울) 기준 좌표에서 사람의 왼쪽 = 화면 왼쪽에 보이는 팔
export function side(person) {
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

/**
 * 판정 임계값. 단위는 어깨너비(sw) 배수. 실제 카메라에서 ?debug=1 로 수치를 보며 조정한다.
 * MIN_VIS 미만인 손목은 화면 밖(부스 구도에서 보통 아래로 잘림)으로 본다.
 */
export const TH = {
  MIN_VIS: 0.5,
  DOWN_BELOW_SHOULDER: 0.3, // 차렷: 손목이 어깨보다 이만큼 아래
  NEAR_FACE: 1.0,           // 브이·꽃받침: 손목-코 거리
  NEAR_FACE_TIGHT: 0.9,     // 입 가리기
  CHIN: 0.8,
  AWAY_FACE: 1.2,           // 한 손 포즈에서 다른 손은 얼굴에서 이만큼 떨어져야 함
  HEART_WRISTS: 0.9,        // 머리 위 하트: 두 손목 사이
  POINT_CENTER: 0.5,        // 가리키기: 손목이 몸 가운데에서
  CROSS: 0.7,               // 팔짱: 손목-반대쪽 팔꿈치
  HIPS: 0.7,                // 허리: 손목-골반
  ELBOW_OUT: 0.3,           // 허리: 팔꿈치가 어깨보다 바깥으로
  SHOULDER_GAP: 0.6,        // 어깨동무: 옆 사람 어깨 사이 간격
};

const seen = (p) => (p.v ?? 1) >= TH.MIN_VIS;
const wrists = (b) => [b.lw, b.rw];
const nearFace = (w, b, sw, k) => seen(w) && dist(w, b.nose) < sw * k;
const raised = (w, y) => seen(w) && w.y < y;
const isDown = (w, s, sw) => !seen(w) || w.y > s.y + sw * TH.DOWN_BELOW_SHOULDER;
/** 한 손만 얼굴 근처 (다른 손은 얼굴에서 떨어져 있거나 화면 밖) */
const oneHandFace = (b, sw, k) => {
  const [l, r] = [nearFace(b.lw, b, sw, k), nearFace(b.rw, b, sw, k)];
  const far = (w) => !seen(w) || dist(w, b.nose) > sw * TH.AWAY_FACE;
  return (l && far(b.rw)) || (r && far(b.lw));
};

export const CHECKS = {
  down: (b, sw) => isDown(b.lw, b.ls, sw) && isDown(b.rw, b.rs, sw),
  v: (b, sw) => oneHandFace(b, sw, TH.NEAR_FACE),
  heartTop: (b, sw) => raised(b.lw, b.nose.y) && raised(b.rw, b.nose.y) && dist(b.lw, b.rw) < sw * TH.HEART_WRISTS,
  cheek: (b, sw) => nearFace(b.lw, b, sw, TH.NEAR_FACE) && nearFace(b.rw, b, sw, TH.NEAR_FACE),
  chin: (b, sw) => wrists(b).some((w) => nearFace(w, b, sw, TH.CHIN)),
  surprise: (b, sw) => nearFace(b.lw, b, sw, TH.NEAR_FACE_TIGHT) && nearFace(b.rw, b, sw, TH.NEAR_FACE_TIGHT),
  wave: (b) => wrists(b).some((w) => raised(w, b.nose.y)),
  fist: (b) => raised(b.lw, b.ls.y) && raised(b.rw, b.rs.y),
  point: (b, sw) => {
    const mx = (b.ls.x + b.rs.x) / 2;
    const at = (w) => seen(w) && Math.abs(w.x - mx) < sw * TH.POINT_CENTER && w.y > b.ls.y - sw * 0.3 && w.y < b.lh.y;
    // 한 손만 가리키고 다른 손은 내림 (팔짱과 구분)
    return (at(b.lw) && isDown(b.rw, b.rs, sw) && !at(b.rw)) || (at(b.rw) && isDown(b.lw, b.ls, sw) && !at(b.lw));
  },
  cross: (b, sw) => seen(b.lw) && seen(b.rw) && dist(b.lw, b.re) < sw * TH.CROSS && dist(b.rw, b.le) < sw * TH.CROSS,
  hips: (b, sw) => seen(b.lw) && seen(b.rw)
    && dist(b.lw, b.lh) < sw * TH.HIPS && dist(b.rw, b.rh) < sw * TH.HIPS
    // 차렷과 구분: 팔꿈치가 바깥으로 벌어져야 함
    && b.ls.x - b.le.x > sw * TH.ELBOW_OUT && b.re.x - b.rs.x > sw * TH.ELBOW_OUT,
  think: (b, sw) => {
    const chest = (w) => seen(w) && w.y > b.ls.y && w.y < b.lh.y;
    return (nearFace(b.lw, b, sw, TH.NEAR_FACE_TIGHT) && chest(b.rw)) || (nearFace(b.rw, b, sw, TH.NEAR_FACE_TIGHT) && chest(b.lw));
  },
  halfHeartL: (b) => wrists(b).some((w) => raised(w, b.nose.y)),
  halfHeartR: (b) => wrists(b).some((w) => raised(w, b.nose.y)),
  // 혼자서는 판정 불가 — matchPose에서 옆 사람과의 간격으로 본다
  shoulderL: () => true,
  shoulderR: () => true,
};

const GROUP_CHECKS = {
  // 어깨동무: 이웃한 두 사람의 맞닿는 어깨가 가까워야 함
  shoulder: (bodies) => bodies.every((b, i) => {
    if (i === 0) return true;
    const a = bodies[i - 1];
    const sw = (dist(a.ls, a.rs) + dist(b.ls, b.rs)) / 2;
    return b.ls.x - a.rs.x < sw * TH.SHOULDER_GAP;
  }),
};

/** 감지된 사람들이 포즈를 따라 했는지 판정. {matched, total, ok, group, results, picked} */
export function matchPose(pose, people, n) {
  const arms = armsFor(pose, n);
  if (!people || people.length < arms.length) return { matched: 0, total: arms.length, ok: false, results: [] };
  // 화면에 크게 잡힌(가까운) 사람 n명을 고른 뒤 왼쪽부터 가이드에 대응
  const bodies = people
    .map((lm) => ({ lm, b: side(lm) }))
    .map((x) => ({ ...x, sw: Math.max(0.04, dist(x.b.ls, x.b.rs)) }))
    .sort((a, c) => c.sw - a.sw)
    .slice(0, arms.length)
    .sort((a, c) => a.b.nose.x - c.b.nose.x);
  const results = bodies.map(({ b, sw }, i) => (CHECKS[arms[i]] ?? CHECKS.down)(b, sw));
  const group = GROUP_CHECKS[pose.id]?.(bodies.map((x) => x.b)) ?? true;
  const matched = results.filter(Boolean).length;
  return { matched, total: arms.length, ok: matched === arms.length && group, group, results, picked: bodies.map((x) => x.lm) };
}

/** 디버그: 랜드마크와 사람별 판정 결과를 오버레이에 그린다. 좌표는 detect() 단위 → 화면 cover-crop 좌표로 변환 */
export function drawDebug(ctx, people, match, vw, vh, w, h) {
  if (!people?.length) return;
  const s = Math.max(w / vw, h / vh);
  const ox = (w - vw * s) / 2, oy = (h - vh * s) / 2;
  const P = (p) => [ox + p.x * vh * s, oy + p.y * vh * s];
  const LINKS = [[11, 12], [11, 13], [13, 15], [12, 14], [14, 16], [11, 23], [12, 24], [23, 24]];
  ctx.save();
  ctx.lineWidth = 2;
  ctx.font = `${Math.round(h * 0.03)}px sans-serif`;
  people.forEach((lm) => {
    ctx.strokeStyle = 'rgba(255, 216, 74, 0.9)';
    for (const [a, c] of LINKS) {
      ctx.beginPath(); ctx.moveTo(...P(lm[a])); ctx.lineTo(...P(lm[c])); ctx.stroke();
    }
    for (const i of [0, 11, 12, 13, 14, 15, 16, 23, 24]) {
      ctx.fillStyle = seen(lm[i]) ? '#FFD84A' : '#ff5a5a'; // 빨강 = 화면 밖으로 판단한 점
      ctx.beginPath(); ctx.arc(...P(lm[i]), 4, 0, Math.PI * 2); ctx.fill();
    }
  });
  (match?.picked ?? []).forEach((lm, i) => {
    const r = match.results[i];
    const b = side(lm);
    const sw = dist(b.ls, b.rs);
    const [x, y] = P(b.nose);
    const dl = (dist(b.lw, b.nose) / sw).toFixed(2), dr = (dist(b.rw, b.nose) / sw).toFixed(2);
    ctx.fillStyle = r ? '#6EE7C8' : '#ffffff';
    ctx.fillText(`${r ? 'OK' : '--'} 손-코 L${dl} R${dr}`, x - 60, y - h * 0.06);
  });
  ctx.restore();
}
