// 웹 src/pose-catalog.js, assets/poses/*.png → 안드로이드 assets (pose-catalog.json, poses/{n}/{id}.webp)
// 실행: cd android/tools && npm install && node convert-poses.mjs
//   --json-only : 카탈로그 JSON만 다시 만든다 (sharp 불필요)
import { mkdir, writeFile, stat } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const out = path.resolve(here, '../app/src/main/assets');

const { POSES_BY_COUNT } = await import(pathToFileURL(path.join(repo, 'src/pose-catalog.js')).href);

// 판정용 필드(arms, group)는 앱에서 쓰지 않는다.
const catalog = {};
for (const [count, poses] of Object.entries(POSES_BY_COUNT)) {
  catalog[count] = poses.map(({ id, name, tip, roles, moods, image, bounds, faceY }) => ({ id, name, tip, roles, moods, image, bounds, faceY }));
}
await mkdir(out, { recursive: true });
await writeFile(path.join(out, 'pose-catalog.json'), JSON.stringify(catalog, null, 2) + '\n');
console.log(`pose-catalog.json: ${Object.values(catalog).flat().length} poses`);

if (process.argv.includes('--json-only')) process.exit(0);

const { default: sharp } = await import('sharp');
let before = 0;
let after = 0;
for (const [count, poses] of Object.entries(catalog)) {
  await mkdir(path.join(out, 'poses', count), { recursive: true });
  for (const pose of poses) {
    const src = path.join(repo, 'assets/poses', count, `${pose.id}.png`);
    const dst = path.join(out, 'poses', count, `${pose.id}.webp`);
    // 크기를 그대로 둬야 bounds/faceY 좌표가 맞는다.
    await sharp(src).webp({ quality: 80, alphaQuality: 90, effort: 6 }).toFile(dst);
    before += (await stat(src)).size;
    after += (await stat(dst)).size;
  }
}
const mb = (n) => (n / 1024 / 1024).toFixed(1);
console.log(`PNG ${mb(before)}MB → WebP ${mb(after)}MB`);
