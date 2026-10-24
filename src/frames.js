// 프레임 템플릿, 추천, 최종 합성.

export const LAYOUTS = {
  // 인생네컷 기본: 2x6인치 세로 스트립
  strip: { name: '세로 네컷', w: 600, h: 1800, cols: 1, rows: 4, margin: 40, gap: 22, top: 40, bottom: 250 },
  // 4x6인치 2×2
  grid: { name: '2×2 네컷', w: 1200, h: 1800, cols: 2, rows: 2, margin: 44, gap: 28, top: 44, bottom: 280 },
};

export function cellRects(layoutId) {
  const L = LAYOUTS[layoutId];
  const cw = (L.w - L.margin * 2 - L.gap * (L.cols - 1)) / L.cols;
  const ch = (L.h - L.top - L.bottom - L.gap * (L.rows - 1)) / L.rows;
  const rects = [];
  for (let r = 0; r < L.rows; r++) {
    for (let c = 0; c < L.cols; c++) {
      rects.push({ x: L.margin + c * (cw + L.gap), y: L.top + r * (ch + L.gap), w: cw, h: ch });
    }
  }
  return rects;
}

export function cellAspect(layoutId) {
  const r = cellRects(layoutId)[0];
  return r.w / r.h;
}

// moods: 이 프레임이 어울리는 감성 id (emotion.js MOODS 기준)
export const THEMES = [
  { id: 'white', name: '클래식 화이트', bg: '#FFFFFF', ink: '#1A1A1A', pattern: 'none', moods: ['bright', 'calm', 'fresh', 'happy'] },
  { id: 'black', name: '클래식 블랙', bg: '#141414', ink: '#F5F5F5', pattern: 'none', moods: ['chic', 'mono', 'moody', 'cool'] },
  { id: 'film', name: '필름 스트립', bg: '#1C1A17', ink: '#E9C46A', pattern: 'film', moods: ['retro', 'moody', 'autumn', 'mono'] },
  { id: 'peach', name: '복숭아 우유', bg: '#FFD9CF', ink: '#7A3B2E', pattern: 'dots', moods: ['romantic', 'warm', 'happy', 'bright'] },
  { id: 'lavender', name: '라벤더 안개', bg: '#D9D2F3', ink: '#3F3668', pattern: 'sparkle', moods: ['dreamy', 'romantic', 'calm'] },
  { id: 'sky', name: '여름 하늘', bg: '#BFE3F7', ink: '#14456B', pattern: 'stripes', moods: ['fresh', 'cool', 'happy'] },
  { id: 'lemon', name: '레몬 소다', bg: '#FFF06A', ink: '#2B2B12', pattern: 'check', moods: ['happy', 'vivid', 'bright'] },
  { id: 'forest', name: '숲속 오후', bg: '#3E5641', ink: '#F1E9D2', pattern: 'none', moods: ['autumn', 'calm', 'warm', 'gloomy'] },
  { id: 'brick', name: '벽돌 레트로', bg: '#B5523B', ink: '#FFF3E4', pattern: 'stripes', moods: ['retro', 'autumn', 'warm', 'vivid'] },
  { id: 'midnight', name: '한밤 블루', bg: '#1F2A5A', ink: '#DDE4FF', pattern: 'sparkle', moods: ['gloomy', 'cool', 'dreamy', 'moody'] },
];

export function themeById(id) {
  return THEMES.find((t) => t.id === id) ?? THEMES[0];
}

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => v / 255);
}

export function rgbToHsl([r, g, b]) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h;
  if (max === r) h = ((g - b) / d + (g < b ? 6 : 0));
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return [h * 60, s, l];
}

function hueName(h) {
  if (h < 20 || h >= 340) return '붉은';
  if (h < 50) return '주황빛';
  if (h < 70) return '노란';
  if (h < 160) return '초록빛';
  if (h < 250) return '푸른';
  if (h < 300) return '보랏빛';
  return '분홍빛';
}

/**
 * 프레임 추천.
 * @param {{avgRgb:number[]|null, moodId:string|null, headcount:number}} input
 * @returns {Array<{themeId, layout, score, reasons:string[]}>}
 */
export function recommendFrames({ avgRgb, moodId, headcount }) {
  const photo = avgRgb ? rgbToHsl(avgRgb) : null;
  const layout = headcount >= 3 ? 'grid' : 'strip';
  const layoutReason = headcount >= 3
    ? `${headcount}명이라 칸이 넓은 2×2가 얼굴이 덜 잘려요`
    : headcount === 2 ? '둘이면 세로 네컷 한 칸에 딱 맞아요' : '혼자면 세로 네컷이 인물에 집중돼요';

  const results = THEMES.map((t) => {
    let score = 0;
    const reasons = [];
    const [fh, fs, fl] = rgbToHsl(hexToRgb(t.bg));

    if (moodId && t.moods.includes(moodId)) {
      score += 3;
      reasons.push('지금 보정한 분위기와 같은 계열');
    }
    if (photo) {
      const [ph, ps, pl] = photo;
      if (fs < 0.12) {
        score += 1;
      } else if (ps > 0.06) {
        const dh = Math.min(Math.abs(fh - ph), 360 - Math.abs(fh - ph));
        if (dh < 40) { score += 2; reasons.push(`사진의 ${hueName(ph)} 톤과 같은 색 계열`); }
        else if (dh > 150) { score += 1; reasons.push(`${hueName(ph)} 사진과 대비돼서 또렷해요`); }
      }
      if (pl > 0.55 && fl < 0.35) { score += 1.5; reasons.push('밝은 사진이 어두운 바탕에서 돋보여요'); }
      if (pl < 0.4 && fl > 0.7) { score += 1.5; reasons.push('어두운 사진을 밝은 바탕이 받쳐줘요'); }
      if (ps < 0.08 && fs < 0.12) { score += 1; reasons.push('흑백·저채도 사진엔 무채색 프레임'); }
    }
    if (!reasons.length) reasons.push('무난하게 어울리는 기본 프레임');
    return { themeId: t.id, layout, score, reasons: [...reasons.slice(0, 2), layoutReason] };
  });
  return results.sort((a, b) => b.score - a.score);
}

// ---------- 합성 ----------

function drawPattern(ctx, theme, L) {
  const { w, h } = L;
  ctx.save();
  ctx.globalAlpha = 0.18;
  ctx.fillStyle = theme.ink;
  ctx.strokeStyle = theme.ink;
  if (theme.pattern === 'dots') {
    for (let y = 18; y < h; y += 36) for (let x = (y / 36) % 2 ? 18 : 0; x < w; x += 36) {
      ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fill();
    }
  } else if (theme.pattern === 'stripes') {
    ctx.lineWidth = 14;
    for (let x = -h; x < w; x += 48) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x + h, h); ctx.stroke(); }
  } else if (theme.pattern === 'check') {
    const s = 40;
    for (let y = 0; y < h; y += s) for (let x = 0; x < w; x += s) if (((x + y) / s) % 2 === 0) ctx.fillRect(x, y, s, s);
  } else if (theme.pattern === 'sparkle') {
    let seed = 7;
    const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
    ctx.globalAlpha = 0.35;
    for (let i = 0; i < w * h / 9000; i++) {
      const x = rnd() * w, y = rnd() * h, r = 2 + rnd() * 6;
      ctx.beginPath();
      ctx.moveTo(x, y - r); ctx.lineTo(x + r * 0.3, y - r * 0.3); ctx.lineTo(x + r, y);
      ctx.lineTo(x + r * 0.3, y + r * 0.3); ctx.lineTo(x, y + r); ctx.lineTo(x - r * 0.3, y + r * 0.3);
      ctx.lineTo(x - r, y); ctx.lineTo(x - r * 0.3, y - r * 0.3); ctx.closePath(); ctx.fill();
    }
  }
  ctx.restore();
  if (theme.pattern === 'film') {
    ctx.save();
    ctx.fillStyle = '#F4EBD9';
    ctx.globalAlpha = 0.85;
    const holeW = 14, holeH = 22;
    for (let y = 12; y < h - 20; y += 40) {
      ctx.fillRect(10, y, holeW, holeH);
      ctx.fillRect(w - 10 - holeW, y, holeW, holeH);
    }
    ctx.restore();
  }
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * @param {HTMLCanvasElement} out
 * @param {{layout, themeId, shots, params, caption, renderer}} o
 *   renderer: 오프스크린 FilterRenderer (보정 적용용)
 */
export function composite(out, { layout, themeId, shots, params, caption, renderer, date = new Date() }) {
  const L = LAYOUTS[layout];
  const theme = themeById(themeId);
  out.width = L.w;
  out.height = L.h;
  const ctx = out.getContext('2d');
  ctx.fillStyle = theme.bg;
  ctx.fillRect(0, 0, L.w, L.h);
  drawPattern(ctx, theme, L);

  const rects = cellRects(layout);
  rects.forEach((r, i) => {
    const shot = shots[i];
    ctx.save();
    roundRect(ctx, r.x, r.y, r.w, r.h, 6);
    ctx.clip();
    if (shot) {
      renderer.setSize(Math.round(r.w), Math.round(r.h));
      renderer.render(shot, shot.width, shot.height, params, { flip: true, seed: i * 3.1 });
      ctx.drawImage(renderer.canvas, r.x, r.y, r.w, r.h);
    } else {
      ctx.fillStyle = theme.ink;
      ctx.globalAlpha = 0.12;
      ctx.fillRect(r.x, r.y, r.w, r.h);
      ctx.globalAlpha = 0.45;
      ctx.font = `${Math.round(r.h * 0.28)}px "Black Han Sans", sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(String(i + 1), r.x + r.w / 2, r.y + r.h / 2);
    }
    ctx.restore();
  });

  // 하단 문구 영역
  const baseY = L.h - L.bottom;
  ctx.fillStyle = theme.ink;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const cx = L.w / 2;
  if (caption) {
    ctx.font = `${layout === 'grid' ? 64 : 46}px "Black Han Sans", sans-serif`;
    fitText(ctx, caption, cx, baseY + L.bottom * 0.38, L.w - L.margin * 2);
  }
  ctx.globalAlpha = 0.8;
  ctx.font = `500 ${layout === 'grid' ? 30 : 24}px "IBM Plex Sans KR", sans-serif`;
  const d = `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`;
  ctx.fillText(`talk & cut  ${d}`, cx, baseY + L.bottom * (caption ? 0.72 : 0.5));
  ctx.globalAlpha = 1;
}

function fitText(ctx, text, x, y, maxW) {
  let size = parseInt(ctx.font, 10);
  const family = ctx.font.replace(/^\d+px\s*/, '');
  while (ctx.measureText(text).width > maxW && size > 18) {
    size -= 2;
    ctx.font = `${size}px ${family}`;
  }
  ctx.fillText(text, x, y);
}

/** 보정 적용된 사진들의 평균 색 (프레임 추천 입력) */
export function averageColor(shots, params, renderer) {
  const valid = shots.filter(Boolean);
  if (!valid.length) return null;
  const tmp = document.createElement('canvas');
  tmp.width = 24; tmp.height = 18;
  const c2 = tmp.getContext('2d', { willReadFrequently: true });
  let r = 0, g = 0, b = 0, n = 0;
  for (const s of valid) {
    renderer.setSize(24, 18);
    renderer.render(s, s.width, s.height, params, { flip: true });
    c2.drawImage(renderer.canvas, 0, 0);
    const data = c2.getImageData(0, 0, 24, 18).data;
    for (let i = 0; i < data.length; i += 4) { r += data[i]; g += data[i + 1]; b += data[i + 2]; n++; }
  }
  return [r / n / 255, g / n / 255, b / n / 255];
}
