import { FilterRenderer } from './glfilter.js';
import { NEUTRAL, PARAM_META, MOODS, applyMood, explain, nearestMood } from './emotion.js';
import { parseUtterance } from './intent.js';
import { POSES, recommendPlan, drawGuide, drawDebug, PoseDetector, matchPose } from './pose.js';
import { LAYOUTS, THEMES, themeById, cellAspect, recommendFrames, composite, averageColor } from './frames.js';
import { Voice } from './voice.js';

const $ = (id) => document.getElementById(id);
const els = {
  welcome: $('welcome'), session: $('session'), startBtn: $('startBtn'), result: $('result'),
  resultTitle: $('resultTitle'), frameLabel: $('frameLabel'), menuBtn: $('menuBtn'), controls: $('controls'),
  closeMenuBtn: $('closeMenuBtn'), shootBtn: $('shootBtn'), cancelBtn: $('cancelBtn'), homeBtn: $('homeBtn'),
  retakeRow: $('retakeRow'), retakeCut: $('retakeCut'), retakeBtn: $('retakeBtn'),
  fullscreenBtn: $('fullscreenBtn'), deviceHint: $('deviceHint'), viewfinder: $('viewfinder'),
  stage: $('stage'), preview: $('preview'), overlay: $('overlay'), video: $('video'),
  camMsg: $('camMsg'), camBtn: $('camBtn'), countdown: $('countdown'), flash: $('flash'),
  poseChip: $('poseChip'), poseName: $('poseName'), poseTip: $('poseTip'), poseMatch: $('poseMatch'),
  cutNow: $('cutNow'), cutTrack: $('cutTrack'), voicebar: $('voicebar'), micBtn: $('micBtn'),
  voiceState: $('voiceState'), voicePrompt: $('voicePrompt'), reply: $('reply'), typedForm: $('typedForm'), typedInput: $('typedInput'),
  strip: $('strip'), saveBtn: $('saveBtn'), restartBtn: $('restartBtn'),
  headcount: $('headcount'), detectState: $('detectState'), plan: $('plan'), poseNextBtn: $('poseNextBtn'),
  autoShutter: $('autoShutter'), moods: $('moods'), explain: $('explain'), sliders: $('sliders'),
  resetFilterBtn: $('resetFilterBtn'), compareToggle: $('compareToggle'),
  recs: $('recs'), themes: $('themes'), layoutSeg: $('layoutSeg'), captionInput: $('captionInput'),
  lastJson: $('lastJson'), ttsToggle: $('ttsToggle'),
};

const state = {
  phase: 'home',
  cameraOn: false,
  retake: null,
  cameraStatus: '',
  voiceStatus: '',
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

function currentPose(i = state.retake ?? nextIndex()) {
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

const tabs = ['pose', 'filter', 'frame', 'help'];

function showTab(name) {
  for (const t of tabs) {
    const tab = $(`tab-${t}`);
    tab.setAttribute('aria-selected', String(t === name));
    tab.tabIndex = t === name ? 0 : -1;
    $(`pane-${t}`).hidden = t !== name;
  }
}

// ---------- 카메라 & 렌더 루프 ----------

let cameraRequest = null;
let detectorRequest = null;

function showCameraMessage(message) {
  state.cameraStatus = message;
  els.camMsg.querySelector('p').textContent = message;
  els.camMsg.hidden = !message;
  els.viewfinder.hidden = !!message;
  els.deviceHint.textContent = [message, state.voiceStatus].filter(Boolean).join(' ');
  updateControls();
}

function stopCamera() {
  cameraRequest = null;
  state.cameraOn = false;
  state.people = null;
  state.stableCount = { n: 0, frames: 0 };
  els.poseMatch.hidden = true;
  const stream = els.video.srcObject;
  els.video.srcObject = null;
  stream?.getTracks().forEach((track) => track.stop());
  els.overlay.getContext('2d').clearRect(0, 0, els.overlay.width, els.overlay.height);
}

function startCamera() {
  if (state.phase !== 'shoot') return Promise.resolve(false);
  if (state.cameraOn) return Promise.resolve(true);
  if (cameraRequest) return cameraRequest.promise;
  if (!navigator.mediaDevices?.getUserMedia) {
    showCameraMessage('카메라를 사용하려면 HTTPS 또는 localhost로 접속해 주세요.');
    return Promise.resolve(false);
  }
  const request = {};
  cameraRequest = request;
  showCameraMessage('카메라를 여는 중이에요');
  request.promise = openCamera(request);
  return request.promise;
}

async function openCamera(request) {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { width: { ideal: 1280 }, height: { ideal: 960 }, facingMode: 'user' },
      audio: false,
    });
    if (cameraRequest !== request) {
      stream.getTracks().forEach((track) => track.stop());
      return false;
    }
    els.video.srcObject = stream;
    await els.video.play();
    if (cameraRequest !== request) return false;
    state.cameraOn = true;
    for (const track of stream.getVideoTracks()) {
      track.addEventListener('ended', () => {
        if (els.video.srcObject !== stream) return;
        cancelCountdown();
        stopCamera();
        showCameraMessage('카메라 연결이 끊겼어요. 오른쪽 메뉴에서 다시 연결해 주세요.');
      });
    }
    showCameraMessage('');
    if (!detectorRequest) detectorRequest = initDetector();
    return true;
  } catch (err) {
    if (cameraRequest !== request) return false;
    stopCamera();
    showCameraMessage(err?.name === 'NotAllowedError'
      ? '카메라 권한을 허용한 뒤 오른쪽 메뉴에서 다시 연결해 주세요.'
      : '카메라를 열 수 없어요. 연결을 확인하고 오른쪽 메뉴에서 다시 연결해 주세요.');
    return false;
  } finally {
    if (cameraRequest === request) {
      cameraRequest = null;
      updateControls();
    }
  }
}

async function initDetector() {
  els.detectState.textContent = '포즈 인식 불러오는 중';
  try {
    await detector.init();
    els.detectState.textContent = '포즈 인식 켜짐';
    updateControls();
  } catch (e) {
    console.warn('pose detector unavailable', e);
    els.detectState.textContent = '포즈 인식 없이 동작 중 · 인원은 직접 골라 주세요';
  }
}

function enterFullscreen() {
  if (state.phase !== 'shoot' || document.fullscreenElement || !els.session.requestFullscreen) return;
  // 거절되거나 지원되지 않아도 고정 뷰포트에서 촬영한다.
  els.session.requestFullscreen().then(() => {
    if (state.phase !== 'shoot') leaveFullscreen();
  }).catch(() => {});
}

function leaveFullscreen() {
  if (document.fullscreenElement === els.session) document.exitFullscreen().catch(() => {});
}

function setPhase(phase) {
  state.phase = phase;
  els.welcome.hidden = phase !== 'home';
  els.session.hidden = phase === 'home';
  if (phase !== 'home') els.session.dataset.phase = phase;
  els.stage.hidden = phase !== 'shoot';
  els.result.hidden = phase !== 'result';
  if (phase !== 'shoot') {
    stopCamera();
    showCameraMessage('');
    leaveFullscreen();
  }
  updateVoicePrompt();
  updateControls();
}

function updateControls() {
  const shooting = state.phase === 'shoot';
  const busy = state.counting || state.armed !== null;
  const retaking = state.retake !== null;
  els.shootBtn.hidden = !shooting;
  els.shootBtn.disabled = !state.cameraOn || busy;
  els.cancelBtn.hidden = !shooting;
  els.cancelBtn.disabled = !busy && !retaking;
  els.camBtn.hidden = !shooting;
  els.camBtn.disabled = state.cameraOn || !!cameraRequest;
  els.fullscreenBtn.hidden = !shooting || !els.session.requestFullscreen || !!document.fullscreenElement;
  els.saveBtn.disabled = !state.shots.some(Boolean);
  els.retakeRow.hidden = !state.shots.some(Boolean) || busy || retaking || (shooting && !state.cameraOn);
  els.autoShutter.disabled = !shooting || !detector.ready;
}

function startSession() {
  resetSession();
  setPhase('shoot');
  els.menuBtn.focus({ preventScroll: true });
  // 같은 클릭에서 카메라·마이크와 전체 화면을 요청한다.
  try {
    audioCtx ??= new AudioContext();
    audioCtx.resume().catch(() => {});
  } catch { /* 소리 없이 진행 */ }
  startCamera();
  voice.start();
  enterFullscreen();
}

function goHome() {
  closeMenu();
  voice.stop();
  resetSession();
  setPhase('home');
  els.startBtn.focus({ preventScroll: true });
}

function resetSession() {
  clearTimeout(completionTimer);
  completionTimer = null;
  cancelCountdown();
  state.retake = null;
  state.shots = [null, null, null, null];
  state.layout = 'strip';
  state.themeId = 'white';
  state.caption = '';
  state.headcountManual = false;
  state.stableCount = { n: 0, frames: 0 };
  els.captionInput.value = '';
  els.autoShutter.checked = false;
  els.compareToggle.checked = false;
  els.reply.textContent = '';
  els.lastJson.textContent = '아직 없음';
  resetFilter();
  setHeadcount(1, false);
  syncFrameUI();
  updateCutUI();
  showTab('pose');
}

function captureArea(w, h, videoAspect) {
  const viewport = w / h;
  const layout = cellAspect(state.layout);
  // 화면과 인화지의 공통 영역을 표시한다. 휴대폰 세로 화면에서도 프레임 위치가 일치한다.
  const previewW = Math.min(1, viewport / videoAspect);
  const previewH = Math.min(1, videoAspect / viewport);
  const photoW = Math.min(1, layout / videoAspect);
  const photoH = Math.min(1, videoAspect / layout);
  return {
    w: w * Math.min(1, photoW / previewW),
    h: h * Math.min(1, photoH / previewH),
  };
}

let finderSize = '';
function frame(now) {
  requestAnimationFrame(frame);
  const v = els.video;
  if (!state.cameraOn || v.readyState < 2) return;

  const dpr = Math.min(window.devicePixelRatio || 1, 1920 / els.stage.clientWidth, 1440 / els.stage.clientHeight);
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
  const area = captureArea(w, h, v.videoWidth / v.videoHeight);
  const finderW = Math.min(94, 100 * area.w / w);
  const finderH = Math.min(94, 100 * area.h / h);
  const size = `${finderW}:${finderH}`;
  if (size !== finderSize) {
    finderSize = size;
    els.viewfinder.style.setProperty('--finder-w', `${finderW}%`);
    els.viewfinder.style.setProperty('--finder-h', `${finderH}%`);
  }
  const pose = currentPose();
  if (pose) {
    const m = detector.ready ? matchPose(pose, state.people, state.headcount) : null;
    ctx.save();
    ctx.translate((w - area.w) / 2, (h - area.h) / 2);
    ctx.globalAlpha = m?.ok ? 0.85 : 0.48;
    drawGuide(ctx, pose, state.headcount, area.w, area.h, m?.ok);
    ctx.restore();
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

  const armed = state.armed !== null || (els.autoShutter.checked && state.retake === null);
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

function requestCapture({ delay = 3, when } = {}) {
  const idx = state.retake ?? nextIndex();
  if (idx < 0) return '네 컷을 다 찍었어요. “두 번째 컷 다시 찍어”처럼 말해 보세요';
  if (!state.cameraOn) return '카메라가 연결되면 다시 말해 주세요';
  if (state.counting) return '이미 촬영 중이에요. 멈추려면 “촬영 취소”라고 말해 주세요';
  if (when === 'POSE_MATCH') {
    if (!detector.ready) {
      startCountdown(delay, idx);
      return `포즈 인식이 꺼져 있어서 ${delay}초 뒤에 찍을게요`;
    }
    state.armed = idx;
    state.matchSince = 0;
    updateControls();
    return `${idx + 1}번째 컷, 포즈를 맞추면 바로 찍을게요`;
  }
  startCountdown(delay, idx);
  return delay ? `${idx + 1}번째 컷, ${delay}초 뒤에 찍을게요` : '바로 찍을게요';
}

function startCountdown(sec, idx) {
  cancelCountdown();
  state.counting = true;
  updateControls();
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
  // 음성/터치 명령 확인 후 바로 카운트다운을 표시한다.
  state.countdownTimer = setTimeout(step, 0);
}

function cancelCountdown() {
  clearTimeout(state.countdownTimer);
  state.countdownTimer = null;
  state.counting = false;
  state.armed = null;
  state.matchSince = 0;
  els.countdown.textContent = '';
  updateControls();
}

function shoot(idx) {
  const v = els.video;
  cancelCountdown();
  if (!state.cameraOn || v.readyState < 2) {
    reply('카메라를 준비 중이에요. 잠시 후 다시 찍어 주세요', false);
    return;
  }
  const c = document.createElement('canvas');
  c.width = v.videoWidth;
  c.height = v.videoHeight;
  c.getContext('2d').drawImage(v, 0, 0);
  state.shots[idx] = c;
  const replaced = state.retake !== null;
  state.retake = null;
  els.flash.classList.remove('on');
  void els.flash.offsetWidth;
  els.flash.classList.add('on');
  beep(1400, 0.12, 0.12);

  updateCutUI();
  scheduleStrip();
  const next = nextIndex();
  if (next < 0) {
    onAllShot(replaced);
  } else {
    const p = currentPose(next);
    reply(`좋아요. ${next + 1}번째는 ${p.name}`);
  }
}

function onAllShot(replaced) {
  recommendNow(!replaced);
  showTab('frame');
  // 마지막 컷에도 플래시가 보이도록 한 프레임 이상 표시한다.
  completionTimer = setTimeout(() => {
    completionTimer = null;
    showResult();
    reply(replaced
      ? '새 컷으로 바꿨어요. “저장해줘”라고 말해 보세요'
      : '네 컷이 완성됐어요. “다른 프레임”이나 “저장해줘”라고 말해 보세요');
  }, 340);
}

let completionTimer = null;

function showResult() {
  closeMenu();
  setPhase('result');
  els.resultTitle.focus({ preventScroll: true });
  window.scrollTo(0, 0);
}

function cancelCapture() {
  const retaking = state.retake !== null;
  cancelCountdown();
  els.autoShutter.checked = false;
  if (retaking) {
    state.retake = null;
    updateCutUI();
    if (nextIndex() < 0) showResult();
    return '다시 찍기를 취소했어요. 원래 사진은 그대로예요';
  }
  return '촬영을 멈췄어요';
}

function retake(index, delay, fullscreen = false) {
  if (state.counting || completionTimer !== null) return '지금 촬영이 끝난 뒤 다시 말해 주세요';
  const idx = index ?? state.shots.findLastIndex(Boolean);
  if (idx < 0 || !state.shots[idx]) {
    return index == null ? requestCapture({ delay }) : '아직 찍지 않은 컷이에요';
  }
  cancelCountdown();
  state.retake = idx;
  const fromResult = state.phase === 'result';
  if (fromResult) {
    closeMenu();
    setPhase('shoot');
    els.menuBtn.focus({ preventScroll: true });
    if (fullscreen) enterFullscreen();
  }
  updateCutUI();
  if (state.cameraOn) return requestCapture({ delay });
  startCamera().then((ready) => {
    if (ready && state.phase === 'shoot' && state.retake === idx && !state.counting) {
      reply(requestCapture({ delay }), false);
    }
  });
  return `${idx + 1}번째 컷을 다시 찍을게요`;
}

function restart(fullscreen = false) {
  closeMenu();
  resetSession();
  setPhase('shoot');
  els.menuBtn.focus({ preventScroll: true });
  startCamera();
  if (fullscreen) enterFullscreen();
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
  const cur = state.retake ?? nextIndex();
  els.plan.innerHTML = '';
  state.plan.forEach((list, i) => {
    const p = list[state.pick[i] % list.length];
    const li = document.createElement('li');
    li.className = (i === cur ? 'current' : '') + (state.shots[i] ? ' done' : '');
    li.innerHTML = `<button type="button"><span class="n">${i + 1}</span><b></b><small></small></button>`;
    const button = li.firstElementChild;
    button.querySelector('b').textContent = p.name;
    button.querySelector('small').textContent = p.tip;
    button.title = '이 컷의 포즈 바꾸기';
    button.addEventListener('click', () => { state.pick[i]++; renderPlan(); els.plan.children[i].firstElementChild.focus(); });
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
  const active = state.retake ?? nextIndex();
  els.cutNow.textContent = String(active < 0 ? 4 : active + 1).padStart(2, '0');
  const oldCut = els.retakeCut.value;
  els.retakeCut.replaceChildren();
  Array.from(els.cutTrack.children).forEach((cut, i) => {
    cut.classList.toggle('is-done', !!state.shots[i]);
    cut.classList.toggle('is-current', active === i);
    if (active === i) cut.setAttribute('aria-current', 'step');
    else cut.removeAttribute('aria-current');
    cut.setAttribute('aria-label', `${i + 1}번째 컷 ${state.shots[i] ? '완료' : '대기'}`);
    if (state.shots[i]) els.retakeCut.add(new Option(`${i + 1}번째 컷`, i));
  });
  if (oldCut && state.shots[Number(oldCut)]) els.retakeCut.value = oldCut;
  renderPlan();
  updateControls();
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
  els.frameLabel.textContent = `${themeById(state.themeId).name} · ${LAYOUTS[state.layout].name}`;
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
    if (!blob) {
      reply('사진을 저장하지 못했어요. 다시 시도해 주세요', false);
      return;
    }
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
  if (state.phase === 'home') return;
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
  if (msgs.length && state.phase !== 'home') {
    const takingPhoto = actions.some((a) => a.action === 'CAPTURE' || a.action === 'RETAKE');
    reply(msgs.join('. '), !takingPhoto);
  }
}

function execute(a) {
  switch (a.action) {
    case 'START': {
      restart();
      return `새로 시작할게요. 첫 번째는 ${currentPose().name}`;
    }
    case 'HOME':
      goHome();
      return '';
    case 'CANCEL':
      return cancelCapture();
    case 'CAPTURE':
      return requestCapture({ delay: a.delay, when: a.when });
    case 'RETAKE':
      return retake(a.index, a.delay);
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
      const i = state.retake ?? nextIndex();
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

// ---------- 음성 & 터치 메뉴 ----------

const voiceLabels = {
  off: '마이크 꺼짐',
  connecting: '마이크 연결 중',
  listening: '듣는 중',
  speaking: '음성 안내 중',
  denied: '마이크 권한 필요',
  unsupported: '음성 인식 미지원',
  error: '마이크 연결 실패',
};
const voiceMessages = {
  off: '메뉴에서 마이크를 다시 연결할 수 있어요.',
  denied: '마이크 권한을 허용한 뒤 메뉴에서 다시 연결해 주세요.',
  unsupported: '이 브라우저에서는 음성 인식을 지원하지 않아요.',
  error: '마이크나 네트워크 연결을 확인하고 메뉴에서 다시 연결해 주세요.',
};

function updateVoicePrompt() {
  els.voicePrompt.textContent = state.voiceStatus
    ? '오른쪽 메뉴에서 터치로 조작할 수 있어요'
    : state.phase === 'result' ? '“저장해줘”라고 말해 보세요' : '“찍어줘”라고 말해 보세요';
}

const voice = new Voice({
  onFinal: (t) => t && handleUtterance(t),
  onState: (s) => {
    const wasError = !!state.voiceStatus;
    els.voicebar.dataset.state = s;
    els.voiceState.textContent = voiceLabels[s];
    state.voiceStatus = voiceMessages[s] ?? '';
    updateVoicePrompt();
    els.deviceHint.textContent = [state.cameraStatus, state.voiceStatus].filter(Boolean).join(' ');
    if (state.voiceStatus) els.reply.textContent = state.voiceStatus;
    else if (wasError) els.reply.textContent = '';
    els.micBtn.textContent = s === 'listening' || s === 'speaking' || s === 'connecting'
      ? '마이크 끄기' : '마이크 다시 연결';
    els.micBtn.disabled = s === 'unsupported';
  },
});

function closeMenu() {
  if (els.controls.open) els.controls.close();
}

els.startBtn.addEventListener('click', startSession);
els.menuBtn.addEventListener('click', () => {
  els.controls.showModal();
  els.menuBtn.setAttribute('aria-expanded', 'true');
});
els.controls.addEventListener('close', () => els.menuBtn.setAttribute('aria-expanded', String(els.controls.open)));
els.controls.addEventListener('click', (e) => {
  if (e.target !== els.controls) return;
  const rect = els.controls.getBoundingClientRect();
  if (e.clientX < rect.left) closeMenu();
});
els.closeMenuBtn.addEventListener('click', closeMenu);
els.camBtn.addEventListener('click', () => { closeMenu(); startCamera(); });
els.micBtn.addEventListener('click', () => { closeMenu(); if (voice.active) voice.stop(); else voice.start(); });
els.fullscreenBtn.addEventListener('click', () => { closeMenu(); enterFullscreen(); });
document.addEventListener('fullscreenchange', updateControls);
els.typedForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const t = els.typedInput.value.trim();
  if (!t) return;
  els.typedInput.value = '';
  closeMenu();
  handleUtterance(t);
});
els.shootBtn.addEventListener('click', () => { closeMenu(); reply(requestCapture(), false); });
els.cancelBtn.addEventListener('click', () => { closeMenu(); reply(cancelCapture(), false); });
els.retakeBtn.addEventListener('click', () => { closeMenu(); reply(retake(Number(els.retakeCut.value), 3, true), false); });
els.saveBtn.addEventListener('click', () => { closeMenu(); reply(save(), false); });
els.restartBtn.addEventListener('click', () => { restart(true); reply('처음부터 다시 찍어요', false); });
els.homeBtn.addEventListener('click', goHome);
els.poseNextBtn.addEventListener('click', () => reply(execute({ action: 'POSE_NEXT' }), false));
els.resetFilterBtn.addEventListener('click', resetFilter);
els.ttsToggle.addEventListener('change', () => voice.setTtsEnabled(els.ttsToggle.checked));
for (const b of els.headcount.querySelectorAll('button')) {
  b.addEventListener('click', () => setHeadcount(Number(b.dataset.n)));
}
tabs.forEach((t, i) => {
  const tab = $(`tab-${t}`);
  tab.addEventListener('click', () => showTab(t));
  tab.addEventListener('keydown', (event) => {
    const next = {
      ArrowLeft: (i + tabs.length - 1) % tabs.length,
      ArrowRight: (i + 1) % tabs.length,
      Home: 0,
      End: tabs.length - 1,
    }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    showTab(tabs[next]);
    $(`tab-${tabs[next]}`).focus();
  });
});
window.addEventListener('pagehide', () => {
  if (state.phase !== 'home') goHome();
});

buildFilterUI();
buildFrameUI();
setHeadcount(1, false);
syncFrameUI();
updateCutUI();
scheduleStrip();
document.fonts?.ready.then(scheduleStrip);
requestAnimationFrame(frame);

if (DEBUG) window.talkandcut = { state, handleUtterance, POSES };
