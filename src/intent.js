// 발화 → 명령 JSON 목록.
// 한 발화에 여러 명령이 들어갈 수 있다: "따뜻한 느낌으로 3초 뒤에 찍어줘" → [FILTER, CAPTURE]
// 스키마(온디바이스 LLM으로 교체 시 동일하게 출력하도록 학습):
//   { action: 'CAPTURE', delay: number, when?: 'POSE_MATCH' }
//   { action: 'RETAKE', index: 0..3 | null, delay }
//   { action: 'START' }
//   { action: 'FILTER', mode, moodIds, deltas, label }
//   { action: 'RESET_FILTER' }
//   { action: 'POSE_NEXT' } | { action: 'POSE_PLAN' }
//   { action: 'HEADCOUNT', count }
//   { action: 'FRAME_RECOMMEND' } | { action: 'FRAME_NEXT' } | { action: 'LAYOUT', layout }
//   { action: 'CAPTION', text }
//   { action: 'SAVE' }
//   { action: 'CANCEL' }

import { interpretMood, RESET_WORDS } from './emotion.js';

const KOR_NUM = { 한: 1, 하나: 1, 두: 2, 둘: 2, 세: 3, 셋: 3, 석: 3, 네: 4, 넷: 4, 다섯: 5, 여섯: 6, 일곱: 7, 열: 10 };
const ORDINAL = { 첫: 0, 1: 0, 한: 0, 두: 1, 2: 1, 세: 2, 3: 2, 네: 3, 4: 3, 마지막: 3 };

function toNumber(tok) {
  if (/^\d+$/.test(tok)) return Number(tok);
  return KOR_NUM[tok] ?? null;
}

export function normalize(text) {
  return text.replace(/[.,!?~]/g, ' ').replace(/\s+/g, ' ').trim();
}

const CAPTURE = /찍어|찍자|찍을래|찍어줘|찍어 줘|촬영|찰칵|치즈|김치|셔터|찍기/;
const RETAKE = /다시\s?찍|재촬영|다시\s?촬영|다시 해|다시할래/;

function parseDelay(t) {
  if (/바로|지금|즉시|당장/.test(t)) return 0;
  const m = t.match(/(\d+|한|두|세|석|네|다섯|여섯|일곱|열)\s?초/);
  if (m) return Math.min(10, toNumber(m[1]) ?? 3);
  return 3;
}

function parseOrdinal(t) {
  const m = t.match(/(첫|마지막|[1-4]|한|두|세|네)\s?(번째|번|째)?\s?(컷|사진|장)/);
  if (!m) return null;
  return ORDINAL[m[1]] ?? null;
}

/**
 * @param {string} raw
 * @param {{hasFilter:boolean}} ctx
 * @returns {Array<object>}
 */
export function parseUtterance(raw, ctx = {}) {
  const t = normalize(raw);
  if (!t) return [];
  const actions = [];

  // 문구 지정은 내용에 다른 키워드가 섞일 수 있으니 가장 먼저, 단독으로 처리
  const cap = t.match(/(문구|글씨|텍스트|캡션)\s?(는|은|를|을)?\s?["'“]?(.+?)["'”]?\s?(으로|로|라고)\s?(해|바꿔|넣어|써|적어)/);
  if (cap) return [{ action: 'CAPTION', text: cap[3].trim() }];
  if (/(문구|글씨|캡션)\s?(지워|빼|없애)/.test(t)) return [{ action: 'CAPTION', text: '' }];

  if (/취소|그만|멈춰|스톱|중지/.test(t)) return [{ action: 'CANCEL' }];

  if (/(처음부터|새로)\s?(다시|시작|찍)|다시\s?시작|^시작|시작하자|시작해/.test(t)) {
    actions.push({ action: 'START' });
  }

  // 인원수
  const hc = t.match(/(혼자|둘이|셋이|넷이|(\d|한|두|세|네|다섯)\s?명)/);
  if (hc) {
    const map = { 혼자: 1, 둘이: 2, 셋이: 3, 넷이: 4 };
    const count = map[hc[1]] ?? toNumber(hc[2]);
    if (count) actions.push({ action: 'HEADCOUNT', count: Math.min(4, count) });
  }

  // 보정
  if (RESET_WORDS.test(t)) {
    actions.push({ action: 'RESET_FILTER' });
  } else {
    const mood = interpretMood(t, { hasCurrent: !!ctx.hasFilter });
    if (mood) actions.push({ action: 'FILTER', ...mood });
  }

  // 포즈
  if (/포즈/.test(t)) {
    if (/다른|바꿔|다음|별로|또/.test(t)) actions.push({ action: 'POSE_NEXT' });
    else if (/추천|알려|뭐|어떻게|골라/.test(t)) actions.push({ action: 'POSE_PLAN' });
  }

  // 프레임
  if (/프레임|테두리/.test(t)) {
    if (/다른|바꿔|다음|별로/.test(t)) actions.push({ action: 'FRAME_NEXT' });
    else actions.push({ action: 'FRAME_RECOMMEND' });
  }
  if (/세로\s?(로|형|네컷|한\s?줄)|한\s?줄|스트립/.test(t)) actions.push({ action: 'LAYOUT', layout: 'strip' });
  else if (/(2|두)\s?(x|바이|곱하기)\s?(2|두)|격자|정사각|네모\s?칸|큰\s?사진/.test(t)) actions.push({ action: 'LAYOUT', layout: 'grid' });

  if (/저장|다운로드|다운|뽑아|인화|출력/.test(t)) actions.push({ action: 'SAVE' });

  // 촬영 (마지막에: 보정 먼저 반영하고 찍는다)
  if (RETAKE.test(t)) {
    actions.push({ action: 'RETAKE', index: parseOrdinal(t), delay: parseDelay(t) });
  } else if (CAPTURE.test(t) && !/찍은|찍었/.test(t)) {
    const a = { action: 'CAPTURE', delay: parseDelay(t) };
    if (/포즈\s?(맞추|맞으|맞춰|되)|(맞추|맞으)면|자세\s?(잡|되)/.test(t)) {
      a.when = 'POSE_MATCH';
      // 포즈 단어 때문에 붙은 POSE_PLAN은 제거
      const i = actions.findIndex((x) => x.action === 'POSE_PLAN');
      if (i >= 0) actions.splice(i, 1);
    }
    actions.push(a);
  }

  return actions;
}
