// 감성 표현 → 보정 파라미터 변환 (규칙 기반).
// 출력 스키마는 나중에 온디바이스 LLM이 그대로 대체할 수 있도록 고정해 둔다.
//   { mode: 'set' | 'adjust', moodIds: [], deltas: {param: value}, label }

export const PARAM_META = {
  brightness: { name: '밝기', min: -100, max: 100 },
  contrast: { name: '대비', min: -100, max: 100 },
  saturation: { name: '채도', min: -100, max: 100 },
  warmth: { name: '색온도', min: -100, max: 100 },
  tint: { name: '색조', min: -100, max: 100 },
  fade: { name: '페이드', min: 0, max: 100 },
  vignette: { name: '비네팅', min: 0, max: 100 },
  grain: { name: '그레인', min: 0, max: 100 },
};

export const NEUTRAL = Object.freeze({
  brightness: 0, contrast: 0, saturation: 0, warmth: 0,
  tint: 0, fade: 0, vignette: 0, grain: 0,
});

export const MOODS = [
  { id: 'dreamy', label: '몽환적인', words: /몽환|꿈\s?같|꿈결|아련|신비/, vec: { saturation: -15, fade: 40, warmth: 8, tint: 15, contrast: -20, brightness: 10 } },
  { id: 'warm', label: '따뜻한', words: /따뜻|따듯|포근|아늑|훈훈/, vec: { warmth: 35, saturation: 5, fade: 10, brightness: 5 } },
  { id: 'cool', label: '차가운', words: /차갑|차가|시원|쿨한|서늘|냉정/, vec: { warmth: -35, contrast: 10, saturation: -5 } },
  { id: 'gloomy', label: '쓸쓸한', words: /우울|쓸쓸|외로|슬픈|슬프|울적|비\s?오는/, vec: { saturation: -40, warmth: -15, brightness: -10, contrast: -5, fade: 15 } },
  { id: 'happy', label: '신나는', words: /행복|신나|신난|발랄|기분\s?좋|즐거|상큼|경쾌|활기/, vec: { saturation: 25, brightness: 10, contrast: 10, warmth: 10 } },
  { id: 'romantic', label: '설레는', words: /설레|설렘|사랑|로맨틱|러블리|달달|핑크/, vec: { warmth: 15, tint: 22, fade: 15, brightness: 10, saturation: -5 } },
  { id: 'retro', label: '레트로', words: /레트로|빈티지|필름|옛날|추억|90년대|구십년대|아날로그|복고/, vec: { fade: 35, grain: 40, warmth: 20, saturation: -20, vignette: 30, contrast: -5 } },
  { id: 'chic', label: '시크한', words: /시크|도시|모던|세련|무심|힙한|힙하/, vec: { saturation: -25, contrast: 25, warmth: -10 } },
  { id: 'mono', label: '흑백', words: /흑백|모노|무채색/, vec: { saturation: -100, contrast: 15 } },
  { id: 'vivid', label: '선명한', words: /강렬|선명|쨍|화려|비비드|진하게/, vec: { saturation: 35, contrast: 30 } },
  { id: 'calm', label: '차분한', words: /차분|잔잔|평온|편안|고요|담백/, vec: { saturation: -15, contrast: -15, fade: 15, warmth: 5 } },
  { id: 'fresh', label: '청량한', words: /청량|여름|바다|시원한\s?여름|맑은|투명/, vec: { warmth: -20, saturation: 20, brightness: 15, tint: -5 } },
  { id: 'autumn', label: '가을', words: /가을|단풍|낙엽|노을|석양/, vec: { warmth: 30, saturation: -5, tint: 5, contrast: 5, vignette: 15 } },
  { id: 'bright', label: '화사한', words: /화사|뽀샤시|뽀얗|환하/, vec: { brightness: 25, fade: 10, contrast: -5 } },
  { id: 'moody', label: '분위기 있는', words: /분위기|무드|어두운\s?느낌|감성적|감성/, vec: { brightness: -15, vignette: 40, contrast: 10, saturation: -10 } },
];

// 특정 파라미터를 직접 가리키는 표현 ("채도 올려", "밝게")
const DIRECT = [
  { key: 'brightness', up: /밝게|밝아|환하게|밝기\s?(을|를)?\s?(올|높)/, down: /어둡게|어두워|밝기\s?(을|를)?\s?(내|낮|줄)/ },
  { key: 'saturation', up: /(채도|색감|색)\s?(을|를|이)?\s?(올|높|진하|더\s?진)/, down: /(채도|색감)\s?(을|를|이)?\s?(내|낮|빼|줄|연하)|색\s?(을|를)?\s?(빼|줄)/ },
  { key: 'contrast', up: /대비\s?(를|을)?\s?(올|높|강하|세게)/, down: /대비\s?(를|을)?\s?(내|낮|약하|줄)/ },
  { key: 'grain', up: /(그레인|노이즈|입자)\s?(을|를)?\s?(넣|올|추가|더)/, down: /(그레인|노이즈|입자)\s?(을|를)?\s?(빼|없|줄)/ },
  { key: 'vignette', up: /(비네팅|가장자리)\s?(를|을)?\s?(넣|어둡|올|더)/, down: /(비네팅)\s?(을|를)?\s?(빼|없|줄)/ },
];

const RELATIVE = /더|덜|조금\s?만|좀\s?만|아까보다|보다|약간\s?더|한\s?단계/;
const LESS = /덜|약하게|빼\s?줘|줄여/;
const SOFT = /살짝|조금|약간|좀|은은|가볍게/;
const STRONG = /많이|아주|완전|엄청|진짜|훨씬|확|세게|강하게|제대로/;

export const RESET_WORDS = /초기화|원본|원래대로|필터\s?(꺼|빼|없|해제)|보정\s?(꺼|빼|없|해제)|자연스럽게|노\s?필터/;

function clampParams(p) {
  const out = {};
  for (const [k, meta] of Object.entries(PARAM_META)) {
    out[k] = Math.round(Math.max(meta.min, Math.min(meta.max, p[k] ?? 0)));
  }
  return out;
}

/**
 * 발화에서 감성/보정 의도를 해석한다. 해당 없으면 null.
 * @param {string} text
 * @param {{hasCurrent:boolean}} ctx
 */
export function interpretMood(text, ctx = {}) {
  const moods = MOODS.filter((m) => m.words.test(text));
  const directs = [];
  for (const d of DIRECT) {
    if (d.down.test(text)) directs.push({ key: d.key, sign: -1 });
    else if (d.up.test(text)) directs.push({ key: d.key, sign: 1 });
  }
  if (!moods.length && !directs.length) return null;

  const less = LESS.test(text);
  const relative = ctx.hasCurrent && (RELATIVE.test(text) || less);
  let scale = STRONG.test(text) ? 1.5 : SOFT.test(text) ? 0.6 : 1;

  const deltas = {};
  const add = (k, v) => { deltas[k] = (deltas[k] ?? 0) + v; };

  // 여러 감성이 섞이면 각각 약하게 합성 ("따뜻하고 몽환적인")
  const mix = moods.length > 1 ? 0.7 : 1;
  for (const m of moods) {
    for (const [k, v] of Object.entries(m.vec)) {
      add(k, v * scale * mix * (relative ? 0.7 : 1) * (less && relative ? -1 : 1));
    }
  }
  for (const d of directs) add(d.key, 20 * d.sign * scale);

  for (const k of Object.keys(deltas)) deltas[k] = Math.round(deltas[k]);

  // 감성 단어 없이 "밝게"만 말한 경우는 항상 현재 값 기준 조정
  const mode = relative || (!moods.length && directs.length) ? 'adjust' : 'set';
  const label = moods.length
    ? moods.map((m) => m.label).join(' + ')
    : directs.map((d) => `${PARAM_META[d.key].name} ${d.sign > 0 ? '올림' : '내림'}`).join(', ');
  const direction = relative ? (less ? 'less' : 'more') : null;

  return { mode, moodIds: moods.map((m) => m.id), deltas, label, direction };
}

/** 해석 결과를 현재 파라미터에 적용 */
export function applyMood(current, result) {
  if (result.mode === 'set') {
    return clampParams({ ...NEUTRAL, ...result.deltas });
  }
  const next = { ...current };
  for (const [k, v] of Object.entries(result.deltas)) next[k] = (next[k] ?? 0) + v;
  return clampParams(next);
}

/** 사용자에게 보여줄 "보정 이유" 문장 */
export function explain(result, before, after) {
  const changes = Object.keys(PARAM_META)
    .map((k) => ({ k, d: after[k] - before[k] }))
    .filter((c) => c.d !== 0)
    .sort((a, b) => Math.abs(b.d) - Math.abs(a.d))
    .slice(0, 4)
    .map((c) => `${PARAM_META[c.k].name} ${c.d > 0 ? '+' : '−'}${Math.abs(c.d)}`);
  if (!changes.length) return `${result.label}: 더 바꿀 수 있는 범위를 넘었어요`;
  const list = changes.join(', ');
  if (!result.moodIds?.length) return `${result.label}: ${list}`;
  if (result.direction === 'more') return `${result.label} 쪽으로 조금 더: ${list}`;
  if (result.direction === 'less') return `${result.label} 느낌을 덜어서: ${list}`;
  return `${result.label} 느낌으로 ${list}`;
}

/** 현재 파라미터에서 가장 가까운 감성 id (포즈/프레임 추천용) */
export function nearestMood(params) {
  let best = null;
  let bestScore = 0;
  for (const m of MOODS) {
    let dot = 0, n1 = 0, n2 = 0;
    for (const k of Object.keys(PARAM_META)) {
      const a = params[k] ?? 0, b = m.vec[k] ?? 0;
      dot += a * b; n1 += a * a; n2 += b * b;
    }
    if (!n1 || !n2) continue;
    const cos = dot / Math.sqrt(n1 * n2);
    if (cos > bestScore) { bestScore = cos; best = m.id; }
  }
  return bestScore > 0.5 ? best : null;
}
