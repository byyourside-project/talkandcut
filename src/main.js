import { FilterRenderer } from './glfilter.js';
import { NEUTRAL, PARAM_META, MOODS, applyMood, explain, nearestMood } from './emotion.js';
import { parseUtterance } from './intent.js';
import { POSES, recommendPlan, drawGuide, drawDebug, PoseDetector, matchPose } from './pose.js';
import { LAYOUTS, THEMES, themeById, cellAspect, recommendFrames, composite, averageColor } from './frames.js';
import { Voice } from './voice.js';

const $ = (id) => document.getElementById(id);
const els = {
  stage: $('stage'), preview: $('preview'), overlay: $('overlay'), video: $('video'),
  camMsg: $('camMsg'), camBtn: $('camBtn'), countdown: $('countdown'), flash: $('flash'),
  poseChip: $('poseChip'), poseName: $('poseName'), poseTip: $('poseTip'), poseMatch: $('poseMatch'),
  cutNow: $('cutNow'), voicebar: $('voicebar'), micBtn: $('micBtn'), micLabel: $('micLabel'),
  heard: $('heard'), reply: $('reply'), typedForm: $('typedForm'), typedInput: $('typedInput'),
  strip: $('strip'), saveBtn: $('saveBtn'), restartBtn: $('restartBtn'),
  headcount: $('headcount'), detectState: $('detectState'), plan: $('plan'), poseNextBtn: $('poseNextBtn'),
  autoShutter: $('autoShutter'), moods: $('moods'), explain: $('explain'), sliders: $('sliders'),
  resetFilterBtn: $('resetFilterBtn'), compareToggle: $('compareToggle'),
  recs: $('recs'), themes: $('themes'), layoutSeg: $('layoutSeg'), captionInput: $('captionInput'),
  lastJson: $('lastJson'), ttsToggle: $('ttsToggle'),
};

const state = {
  cameraOn: false,
  params: { ...NEUTRAL },
  moodId: null,
  shots: [null, null, null, null],
  headcount: 1,
  headcountManual: false,
  plan: [],
  pick: [0, 0, 0, 0],
  layout: 'strip',
  themeId: 'white',
  recs: [],
  recIndex: 0,
  caption: '',
  countdownTimer: null,
  counting: false,
  armed: null,          // 포즈 일치 대기 중인 컷 index
  matchSince: 0,
  people: null,
  lastDetect: 0,
  stableCount: { n: 0, frames: 0 },
};

const live = new FilterRenderer(els.preview);
const offscreen = new FilterRenderer(document.createElement('canvas'));
const detector = new PoseDetector();
// ?debug=1 이면 랜드마크·판정 수치를 뷰파인더에 겹쳐 그린다 (실제 카메라에서 TH 튜닝용)
const DEBUG = new URLSearchParams(location.search).has('debug');

// ---------- 공통 ----------

function nextIndex() {
  return state.shots.findIndex((s) => !s);
}

function currentPose(i = nextIndex()) {
  if (i < 0 || !state.plan[i]) return null;
  const list = state.plan[i];
  return list[state.pick[i] % list.length];
}

function isNeutral(p) {
  return Object.keys(NEUTRAL).every((k) => (p[k] ?? 0) === 0);
}

function reply(text, speak = true) {
  els.reply.textContent = text;
  if (speak) voice.say(text);
}

let audioCtx;
function beep(freq = 880, dur = 0.08, gain = 0.08) {
  try {
    audioCtx ??= new AudioContext();
    const o = audioCtx.createOscillator();
    const g = audioCtx.createGain();
    o.frequency.value = freq;
    g.gain.value = gain;
    o.connect(g).connect(audioCtx.destination);
    o.start();
    g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + dur);
    o.stop(audioCtx.currentTime + dur);
  } catch { /* 소리 없이 진행 */ }
}

function showTab(name) {
  for (const t of ['pose', 'filter', 'frame', 'help']) {
    $(`tab-${t}`).setAttribute('aria-selected', String(t === name));
    $(`pane-${t}`).hidden = t !== name;
  }
}

// ---------- 카메라 & 렌더 루프 ----------

async function startCamera() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { width: { ideal: 1280 }, height: { ideal: 960 }, facingMode: 'user' },
      audio: false,
    });
    els.video.srcObject = stream;
    await els.video.play();
    state.cameraOn = true;
    els.camMsg.hidden = true;
    initDetector();
  } catch (err) {
    els.camMsg.hidden = false;
    els.camMsg.querySelector('p').textContent = err?.name === 'NotAllowedError'
      ? '카메라 권한이 막혀 있어요. 주소창 왼쪽 자물쇠 아이콘에서 카메라를 허용한 뒤 다시 눌러 주세요.'
      : '카메라를 찾지 못했어요. 카메라가 연결돼 있는지 확인하고 다시 눌러 주세요.';
  }
}

async function initDetector() {
  els.detectState.textContent = '포즈 인식 불러오는 중';
  try {
    await detector.init();
    els.detectState.textContent = '포즈 인식 켜짐';
  } catch (e) {
    console.warn('pose detector unavailable', e);
    els.detectState.textContent = '포즈 인식 없이 동작 중 · 인원은 직접 골라 주세요';
  }
}

function setStageAspect() {
  els.stage.style.setProperty('--cell-ar', String(cellAspect(state.layout)));
}

function frame(now) {
  requestAnimationFrame(frame);
  const v = els.video;
  if (!state.cameraOn || v.readyState < 2) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.round(els.stage.clientWidth * dpr);
  const h = Math.round(els.stage.clientHeight * dpr);
  if (!w || !h) return;
  live.setSize(w, h);
  live.render(v, v.videoWidth, v.videoHeight, els.compareToggle.checked ? NEUTRAL : state.params, { flip: true, seed: (now % 1000) / 100 });

  // 포즈 인식(약 8fps)
  if (detector.ready && now - state.lastDetect > 120) {
    state.lastDetect = now;
    try {
      state.people = detector.detect(v, now);
      onPeople(now);
    } catch (e) { console.warn(e); }
  }

  // 가이드 오버레이
  const ov = els.overlay;
  if (ov.width !== w || ov.height !== h) { ov.width = w; ov.height = h; }
  const ctx = ov.getContext('2d');
  ctx.clearRect(0, 0, w, h);
  const pose = currentPose();
  if (pose) {
    const m = detector.ready ? matchPose(pose, state.people, state.headcount) : null;
    drawGuide(ctx, pose, state.headcount, w, h, m?.ok);
    if (DEBUG && m) drawDebug(ctx, state.people, m, v.videoWidth, v.videoHeight, w, h);
  }
}

function onPeople(now) {
  const people = state.people ?? [];
  // 인원 자동 감지: 같은 수가 약 1.5초 유지되면 반영
  if (!state.headcountManual && people.length) {
    if (people.length === state.stableCount.n) state.stableCount.frames++;
    else state.stableCount = { n: people.length, frames: 0 };
    if (state.stableCount.frames > 12 && people.length !== state.headcount) {
      setHeadcount(people.length, false);
      reply(`${people.length}명으로 보여서 포즈를 다시 골랐어요`, false);
    }
  }

  const pose = currentPose();
  if (!pose) { els.poseMatch.hidden = true; return; }
  const m = matchPose(pose, people, state.headcount);
  els.poseMatch.hidden = false;
  els.poseMatch.textContent = people.length < m.total
    ? `${m.total}명이 모두 화면에 들어오게 서 주세요`
    : m.ok ? '포즈 좋아요' : `포즈 맞춘 사람 ${m.matched}/${m.total}`;

  const armed = state.armed !== null || (els.autoShutter.checked && !state.counting);
  if (!armed || state.counting) { state.matchSince = 0; return; }
  if (m.ok) {
    if (!state.matchSince) state.matchSince = now;
    else if (now - state.matchSince > 600) {
      const idx = state.armed ?? nextIndex();
      state.armed = null;
      state.matchSince = 0;
      startCountdown(1, idx);
    }
  } else {
    state.matchSince = 0;
  }
}

// ---------- 촬영 ----------

function requestCapture({ delay = 3, when, index = null }) {
  if (!state.cameraOn) return '카메라를 먼저 켜 주세요';
  const idx = index ?? nextIndex();
  if (idx < 0) return '네 컷을 다 찍었어요. “두 번째 컷 다시 찍어”처럼 말하면 다시 찍을 수 있어요';
  if (when === 'POSE_MATCH') {
    if (!detector.ready) {
      startCountdown(delay, idx);
      return `포즈 인식이 꺼져 있어서 ${delay}초 뒤에 찍을게요`;
    }
    state.armed = idx;
    return `${idx + 1}번째 컷, 포즈를 맞추면 바로 찍을게요`;
  }
  startCountdown(delay, idx);
  return delay ? `${idx + 1}번째 컷, ${delay}초 뒤에 찍을게요` : '바로 찍을게요';
}

function startCountdown(sec, idx) {
  cancelCountdown();
  state.counting = true;
  let n = sec;
  const step = () => {
    if (n <= 0) {
      els.countdown.textContent = '';
      shoot(idx);
      return;
    }
    els.countdown.textContent = String(n);
    els.countdown.classList.remove('tick');
    void els.countdown.offsetWidth;
    els.countdown.classList.add('tick');
    beep(660);
    n -= 1;
    state.countdownTimer = setTimeout(step, 1000);
  };
  // 음성 안내가 끝날 여유를 조금 준다
  state.countdownTimer = setTimeout(step, sec ? 400 : 0);
}

function cancelCountdown() {
  clearTimeout(state.countdownTimer);
  state.countdownTimer = null;
  state.counting = false;
  els.countdown.textContent = '';
}

function shoot(idx) {
  const v = els.video;
  const c = document.createElement('canvas');
  c.width = v.videoWidth;
  c.height = v.videoHeight;
  c.getContext('2d').drawImage(v, 0, 0);
  state.shots[idx] = c;
  state.counting = false;
  els.flash.classList.remove('on');
  void els.flash.offsetWidth;
  els.flash.classList.add('on');
  beep(1400, 0.12, 0.12);

  updateCutUI();
  scheduleStrip();
  const next = nextIndex();
  if (next < 0) {
    onAllShot();
  } else {
    const p = currentPose(next);
    reply(`좋아요. ${next + 1}번째는 ${p.name}`);
  }
}

function onAllShot() {
  recommendNow(true);
  showTab('frame');
  const top = state.recs[0];
  reply(`네 컷 다 찍었어요. ${themeById(top.themeId).name} 프레임을 추천해요. 느낌을 말하면 보정도 바꿀 수 있어요`);
}

function restart() {
  cancelCountdown();
  state.armed = null;
  state.shots = [null, null, null, null];
  state.pick = [0, 0, 0, 0];
  updateCutUI();
  scheduleStrip();
  showTab('pose');
}

// ---------- 포즈 ----------

function replan() {
  state.plan = recommendPlan(state.headcount, state.moodId);
  state.pick = [0, 0, 0, 0];
  renderPlan();
}

function setHeadcount(n, manual = true) {
  state.headcount = Math.max(1, Math.min(4, n));
  if (manual) state.headcountManual = true;
  for (const b of els.headcount.querySelectorAll('button')) {
    b.setAttribute('aria-checked', String(Number(b.dataset.n) === state.headcount));
  }
  replan();
  recommendNow(false);
}

function renderPlan() {
  const cur = nextIndex();
  els.plan.innerHTML = '';
  state.plan.forEach((list, i) => {
    const p = list[state.pick[i] % list.length];
    const li = document.createElement('li');
    li.className = (i === cur ? 'current' : '') + (state.shots[i] ? ' done' : '');
    li.innerHTML = `<span class="n">${i + 1}</span><b></b><small></small>`;
    li.querySelector('b').textContent = p.name;
    li.querySelector('small').textContent = p.tip;
    li.title = '눌러서 이 컷의 포즈 바꾸기';
    li.addEventListener('click', () => { state.pick[i]++; renderPlan(); });
    els.plan.append(li);
  });
  const p = currentPose();
  els.poseChip.hidden = !p;
  if (p) {
    els.poseName.textContent = p.name;
    els.poseTip.textContent = p.tip;
  }
}

function updateCutUI() {
  const i = nextIndex();
  els.cutNow.textContent = i < 0 ? '4' : String(i + 1);
  renderPlan();
}

// ---------- 보정 ----------

function buildFilterUI() {
  for (const m of MOODS) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = m.label;
    b.dataset.mood = m.id;
    b.setAttribute('aria-pressed', 'false');
    b.addEventListener('click', () => {
      const msg = applyFilter({ mode: 'set', moodIds: [m.id], deltas: m.vec, label: m.label });
      reply(msg, false);
    });
    els.moods.append(b);
  }
  for (const [k, meta] of Object.entries(PARAM_META)) {
    const row = document.createElement('label');
    row.className = 'slider';
    row.innerHTML = `<span>${meta.name}</span><input type="range" min="${meta.min}" max="${meta.max}" step="1" data-k="${k}"><span data-v="${k}">0</span>`;
    row.querySelector('input').addEventListener('input', (e) => {
      state.params[k] = Number(e.target.value);
      state.moodId = nearestMood(state.params);
      syncFilterUI();
      scheduleStrip();
    });
    els.sliders.append(row);
  }
  syncFilterUI();
}

function syncFilterUI() {
  for (const input of els.sliders.querySelectorAll('input')) {
    input.value = state.params[input.dataset.k];
    els.sliders.querySelector(`[data-v="${input.dataset.k}"]`).textContent = state.params[input.dataset.k];
  }
  for (const b of els.moods.querySelectorAll('button')) {
    b.setAttribute('aria-pressed', String(b.dataset.mood === state.moodId));
  }
}

function applyFilter(result) {
  const before = { ...state.params };
  state.params = applyMood(state.params, result);
  const prevMood = state.moodId;
  state.moodId = result.mode === 'set' ? (result.moodIds[0] ?? nearestMood(state.params)) : (state.moodId ?? nearestMood(state.params));
  const msg = explain(result, before, state.params);
  els.explain.textContent = msg;
  els.explain.classList.add('fresh');
  syncFilterUI();
  if (state.moodId !== prevMood) {
    // 분위기가 바뀌면 아직 안 찍은 컷의 포즈를 분위기에 맞게 다시 고른다
    const keep = state.plan.map((l, i) => (state.shots[i] ? l : null));
    replan();
    keep.forEach((l, i) => { if (l) state.plan[i] = l; });
    renderPlan();
  }
  recommendNow(false);
  scheduleStrip();
  return msg;
}

function resetFilter() {
  state.params = { ...NEUTRAL };
  state.moodId = null;
  els.explain.textContent = '보정을 모두 껐어요';
  syncFilterUI();
  scheduleStrip();
}

// ---------- 프레임 ----------

function buildFrameUI() {
  for (const t of THEMES) {
    const b = document.createElement('button');
    b.type = 'button';
    b.style.background = t.bg;
    b.title = t.name;
    b.setAttribute('aria-label', t.name);
    b.dataset.theme = t.id;
    b.addEventListener('click', () => { state.themeId = t.id; syncFrameUI(); scheduleStrip(); });
    els.themes.append(b);
  }
  for (const b of els.layoutSeg.querySelectorAll('button')) {
    b.addEventListener('click', () => setLayout(b.dataset.layout));
  }
  els.captionInput.addEventListener('input', () => { state.caption = els.captionInput.value; scheduleStrip(); });
}

function setLayout(layout) {
  state.layout = layout;
  setStageAspect();
  syncFrameUI();
  scheduleStrip();
}

function recommendNow(apply) {
  const avg = averageColor(state.shots, state.params, offscreen);
  state.recs = recommendFrames({ avgRgb: avg, moodId: state.moodId, headcount: state.headcount });
  state.recIndex = 0;
  if (apply) applyRec(0);
  renderRecs();
}

function applyRec(i) {
  const r = state.recs[i];
  if (!r) return;
  state.recIndex = i;
  state.themeId = r.themeId;
  if (r.layout !== state.layout) setLayout(r.layout);
  syncFrameUI();
  scheduleStrip();
}

function renderRecs() {
  els.recs.innerHTML = '';
  state.recs.slice(0, 3).forEach((r, i) => {
    const t = themeById(r.themeId);
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    b.innerHTML = `<span class="sw"></span><b></b><small></small>`;
    b.querySelector('.sw').style.background = t.bg;
    b.querySelector('b').textContent = `${t.name} · ${LAYOUTS[r.layout].name}`;
    b.querySelector('small').textContent = r.reasons.join('. ');
    b.setAttribute('aria-pressed', String(state.themeId === r.themeId && state.layout === r.layout));
    b.addEventListener('click', () => { applyRec(i); renderRecs(); });
    li.append(b);
    els.recs.append(li);
  });
}

function syncFrameUI() {
  for (const b of els.themes.querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.theme === state.themeId));
  for (const b of els.layoutSeg.querySelectorAll('button')) b.setAttribute('aria-checked', String(b.dataset.layout === state.layout));
  for (const [i, b] of [...els.recs.querySelectorAll('button')].entries()) {
    const r = state.recs[i];
    b.setAttribute('aria-pressed', String(!!r && r.themeId === state.themeId && r.layout === state.layout));
  }
}

let stripQueued = false;
function scheduleStrip() {
  if (stripQueued) return;
  stripQueued = true;
  requestAnimationFrame(() => {
    stripQueued = false;
    composite(els.strip, {
      layout: state.layout, themeId: state.themeId, shots: state.shots,
      params: state.params, caption: state.caption, renderer: offscreen,
    });
  });
}

function save() {
  if (!state.shots.some(Boolean)) return '아직 찍은 사진이 없어요';
  composite(els.strip, {
    layout: state.layout, themeId: state.themeId, shots: state.shots,
    params: state.params, caption: state.caption, renderer: offscreen,
  });
  const d = new Date();
  const name = `talkandcut-${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}-${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}.png`;
  els.strip.toBlob((blob) => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }, 'image/png');
  return state.shots.every(Boolean) ? '사진을 저장했어요' : '찍은 컷까지만 저장했어요';
}

// ---------- 명령 실행 ----------

function handleUtterance(text) {
  els.heard.classList.remove('interim');
  els.heard.textContent = `“${text}”`;
  let actions = parseUtterance(text, { hasFilter: !isNeutral(state.params) });
  if (actions.some((a) => a.action === 'START')) {
    actions = actions.filter((a) => a.action !== 'CAPTURE' && a.action !== 'RETAKE');
  }
  els.lastJson.textContent = JSON.stringify({ heard: text, actions }, null, 2);

  if (!actions.length) {
    reply('잘 못 알아들었어요. “찍어줘”나 “따뜻하게”처럼 말해 보세요');
    return;
  }
  const msgs = [];
  for (const a of actions) {
    const m = execute(a);
    if (m) msgs.push(m);
  }
  reply(msgs.join('. '));
}

function execute(a) {
  switch (a.action) {
    case 'START': {
      restart();
      return `새로 시작할게요. 첫 번째는 ${currentPose().name}`;
    }
    case 'CANCEL':
      cancelCountdown();
      state.armed = null;
      return '촬영을 멈췄어요';
    case 'CAPTURE':
      return requestCapture({ delay: a.delay, when: a.when });
    case 'RETAKE': {
      let idx = a.index;
      if (idx === null || idx === undefined) {
        // 몇 번째인지 말하지 않으면 마지막으로 찍은 컷
        idx = state.shots.reduce((last, s, i) => (s ? i : last), -1);
      }
      if (idx < 0) return requestCapture({ delay: a.delay });
      state.shots[idx] = null;
      updateCutUI();
      scheduleStrip();
      return requestCapture({ delay: a.delay, index: idx });
    }
    case 'HEADCOUNT':
      setHeadcount(a.count);
      showTab('pose');
      return `${a.count}명에 맞춰 포즈를 골랐어요`;
    case 'FILTER':
      showTab('filter');
      return applyFilter(a);
    case 'RESET_FILTER':
      resetFilter();
      return '보정을 껐어요';
    case 'POSE_PLAN': {
      showTab('pose');
      const p = currentPose();
      return p ? `이번 컷은 ${p.name}. ${p.tip}` : '네 컷을 다 찍었어요';
    }
    case 'POSE_NEXT': {
      showTab('pose');
      const i = nextIndex();
      if (i < 0) return '네 컷을 다 찍었어요';
      state.pick[i]++;
      renderPlan();
      return `그럼 ${currentPose().name} 어때요`;
    }
    case 'FRAME_RECOMMEND': {
      showTab('frame');
      recommendNow(true);
      const r = state.recs[0];
      return `${themeById(r.themeId).name} 추천해요. ${r.reasons[0]}`;
    }
    case 'FRAME_NEXT': {
      showTab('frame');
      if (!state.recs.length) recommendNow(false);
      const i = (state.recIndex + 1) % Math.min(state.recs.length, 5);
      applyRec(i);
      renderRecs();
      return `${themeById(state.recs[i].themeId).name}로 바꿨어요`;
    }
    case 'LAYOUT':
      setLayout(a.layout);
      renderRecs();
      return `${LAYOUTS[a.layout].name}로 바꿨어요`;
    case 'CAPTION':
      state.caption = a.text;
      els.captionInput.value = a.text;
      scheduleStrip();
      return a.text ? `문구를 “${a.text}”로 넣었어요` : '문구를 지웠어요';
    case 'SAVE':
      return save();
    default:
      return '';
  }
}

// ---------- 음성 ----------

const voice = new Voice({
  onFinal: (t) => t && handleUtterance(t),
  onInterim: (t) => { els.heard.classList.add('interim'); els.heard.textContent = t; },
  onState: (s) => {
    els.voicebar.dataset.state = s === 'error' ? els.voicebar.dataset.state : s;
    els.micBtn.setAttribute('aria-pressed', String(s === 'listening'));
    els.micLabel.textContent = s === 'listening' ? '듣는 중' : s === 'denied' ? '마이크 권한 필요' : '말로 조작하기';
    if (s === 'denied') reply('마이크 권한이 막혀 있어요. 주소창에서 마이크를 허용하거나 아래 입력칸을 써 주세요', false);
  },
});

if (!voice.supported) {
  els.voicebar.dataset.state = 'unsupported';
  els.micLabel.textContent = '음성 인식 미지원';
  els.micBtn.disabled = true;
  els.heard.textContent = '이 브라우저는 음성 인식을 지원하지 않아요. 크롬이나 엣지에서 열거나 입력칸을 써 주세요';
}

// ---------- 이벤트 ----------

els.camBtn.addEventListener('click', startCamera);
els.micBtn.addEventListener('click', () => (voice.active ? voice.stop() : voice.start()));
els.typedForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const t = els.typedInput.value.trim();
  if (!t) return;
  els.typedInput.value = '';
  handleUtterance(t);
});
els.saveBtn.addEventListener('click', () => reply(save(), false));
els.restartBtn.addEventListener('click', () => { restart(); reply('처음부터 다시 찍어요', false); });
els.poseNextBtn.addEventListener('click', () => reply(execute({ action: 'POSE_NEXT' }), false));
els.resetFilterBtn.addEventListener('click', resetFilter);
els.compareToggle.addEventListener('change', () => {});
els.ttsToggle.addEventListener('change', () => { voice.ttsEnabled = els.ttsToggle.checked; });
for (const b of els.headcount.querySelectorAll('button')) {
  b.addEventListener('click', () => setHeadcount(Number(b.dataset.n)));
}
for (const t of ['pose', 'filter', 'frame', 'help']) {
  $(`tab-${t}`).addEventListener('click', () => showTab(t));
}
// 키보드: 스페이스로도 촬영 (음성이 안 될 때)
window.addEventListener('keydown', (e) => {
  if (e.code === 'Space' && !['INPUT', 'BUTTON'].includes(document.activeElement?.tagName)) {
    e.preventDefault();
    reply(requestCapture({ delay: 3 }), false);
  }
});

// ---------- 시작 ----------

buildFilterUI();
buildFrameUI();
setStageAspect();
setHeadcount(1, false);
recommendNow(false);
syncFrameUI();
updateCutUI();
scheduleStrip();
document.fonts?.ready.then(scheduleStrip);
requestAnimationFrame(frame);
startCamera();

// 디버그/테스트용
window.talkandcut = { state, handleUtterance, POSES };
