#!/usr/bin/env node
// The framed store screenshots ("B · Luce"): each plain store screenshot in docs/store/play/<locale>/<n>.jpg
// (1080x1920, Screenshots workflow) placed in a phone on a light ground, with a short headline and one line
// under it (docs/store/frames/titles.json), the Trackhop mark at the top, the beam as a coloured ribbon behind
// the phone and a few round service-letter chips around it. The ribbon tilts -14° on odd shots and +14° on
// even ones, so the pictures side by side join into one zigzag. Output: docs/store/framed/<locale>/<n>.jpg,
// 1080x1920 (9:16: Google Play phone screenshots and Huawei AppGallery). The plain screenshots stay for
// F-Droid and the site.
//
//   node scripts/store_frames.mjs                                  # every locale and shot
//   node scripts/store_frames.mjs --locales it-IT,en-US --shots 1,2
//   node scripts/store_frames.mjs --format png                     # PNG instead of JPEG (about 4x larger)
//   node scripts/store_frames.mjs cover --title "Trackhop 2.1" --subtitle "…" [--locale it-IT] OUT.png
//                                  # the Instagram carousel cover, 1080x1350 (PNG or, by extension, JPEG)
//
// Needs Node and Playwright with its Chromium. Locally `require('playwright')` and `chromium.launch()` are
// enough; in CI: `npm install --no-save playwright && npx playwright install chromium` first.
// Fonts: Inter (OFL, scripts/fonts/OFL.txt) is vendored as woff2 and embedded in the page, so the Latin and
// Cyrillic text is the same on every machine. Japanese, Korean and Chinese fall back to the system's Noto
// Sans CJK: in CI install it first (`sudo apt-get install -y fonts-noto-cjk`), or those titles show boxes.
//
// Titles: docs/store/frames/titles.json, locale -> shot -> {title, sub}. Lines are balanced on their own; a "\n"
// in a title forces the break where the automatic one reads badly (a dangling preposition, a split phrase).
//
// The layout is written in the units of the approved mock-up (a frame 236 units wide, a cover 300), so
// every size below can be compared with it one to one.
import { createRequire } from 'node:module';
import { mkdirSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const FONTS = join(ROOT, 'scripts/fonts');
const IN = join(ROOT, 'docs/store/play');
const OUT = join(ROOT, 'docs/store/framed');
const TITLES = JSON.parse(readFileSync(join(ROOT, 'docs/store/frames/titles.json'), 'utf8'));
const LOCALES = Object.keys(TITLES);
const SHOTS = 10;

// Chips per shot: [letter, x %, y %] of the frame (centres). They sit on the phone's bezel, left or right,
// outside the screen, below the headline; a chip that would still touch the headline is moved down. Where two
// shots meet, the right chips of one and the left chips of the next are at different heights.
const CHIPS = {
  1: [['S', 9, 41], ['Y', 90, 35], ['T', 91, 72]],
  2: [['D', 10, 58], ['S', 90, 44]],
  3: [['Y', 9, 66], ['S', 91, 50]],
  4: [['A', 10, 38], ['Y', 90, 70]],
  5: [['T', 9, 52], ['D', 91, 40]],
  6: [['Y', 9, 66], ['S', 90, 42]],
  7: [['S', 9, 58], ['T', 91, 72]],
  8: [['S', 9, 50], ['T', 90, 39]],
  9: [['Y', 10, 62], ['D', 90, 44]],
  10: [['D', 9, 68], ['Y', 91, 46]],
};
const COVER_CHIPS = [['S', 12, 16], ['Y', 80, 22], ['D', 16, 72], ['T', 78, 70]];

const MARK = `<svg viewBox="0 0 66 66" aria-hidden="true"><g transform="translate(-21 -21)" fill="currentColor" stroke="currentColor" stroke-linecap="round"><path d="M30.6,40.5 A27,27 0 0 1 77.4,40.5" fill="none" stroke-width="5.5"/><path d="M77.4,67.5 A27,27 0 0 1 30.6,67.5" fill="none" stroke-width="5.5"/><path d="M81.85,36.2 L71.45,42.2 L81.4,47.4 Z" stroke="none"/><path d="M26.15,71.8 L36.55,65.8 L26.6,60.6 Z" stroke="none"/><g transform="translate(2.1 1.5)"><path d="M54.08,61.11 A7,5.6 -20 1 1 40.92,65.89 A7,5.6 -20 1 1 54.08,61.11 Z" stroke="none"/><path d="M52.6,62.5 L52.6,37.5" fill="none" stroke-width="3.6"/><path d="M52.6,37.2 C58.5,38.6 63.4,42.4 63.2,49.6 C61.6,45.2 58,42.9 52.6,42.6 Z" stroke="none"/></g></g></svg>`;

const CJK = { ja: 'Noto Sans CJK JP', ko: 'Noto Sans CJK KR', zh: 'Noto Sans CJK SC' };
const fontStack = (lang) => {
  const first = CJK[lang];
  const rest = Object.values(CJK).filter((f) => f !== first);
  return ['"Inter Frames"', ...(first ? [first] : []), ...rest].map((f) => (f.startsWith('"') ? f : `"${f}"`)).join(',') + ',sans-serif';
};

const b64 = (p) => readFileSync(p).toString('base64');
const FACE = [[500, 'Medium'], [700, 'Bold'], [800, 'ExtraBold']]
  .map(([w, n]) => `@font-face{font-family:"Inter Frames";font-weight:${w};font-style:normal;src:url(data:font/woff2;base64,${b64(join(FONTS, `Inter-${n}.woff2`))}) format("woff2")}`)
  .join('\n');
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

// Shared by the frame and the cover: units, ribbon, chips. --u is one mock-up unit in CSS pixels.
const BASE = `${FACE}
:root{--violet:#421AFF;--cyan:#59FFFF;--red:#990000}
html,body{margin:0;width:100vw;height:100vh;overflow:hidden}
body{position:relative;background:#f6f4fa;color:#17141c;-webkit-font-smoothing:antialiased}
.beam{position:absolute;left:-25%;width:150%;top:calc(var(--y) - var(--u) * 34);height:calc(var(--u) * 68);transform:rotate(var(--r));z-index:1;
  background:linear-gradient(180deg,var(--red) 0 30%,var(--violet) 30% 52%,var(--cyan) 52% 80%,#fff 80%)}
.chip{position:absolute;width:calc(var(--u) * 34);height:calc(var(--u) * 34);margin:calc(var(--u) * -17) 0 0 calc(var(--u) * -17);border-radius:50%;
  display:grid;place-items:center;font-weight:800;font-size:calc(var(--u) * 15);line-height:1;z-index:3;
  background:#fff;color:var(--violet);box-shadow:0 calc(var(--u) * 4) calc(var(--u) * 14) rgba(66,26,255,.18)}
:lang(ja) h1,:lang(ja) p{word-break:auto-phrase}
:lang(ko) h1,:lang(ko) p{word-break:keep-all}
`;

const chipsHtml = (list) => list.map(([l, x, y]) => `<span class="chip" style="left:${x}%;top:${y}%">${esc(l)}</span>`).join('');

function frameHtml(locale, n, img) {
  const lang = locale.split('-')[0];
  const t = TITLES[locale][n];
  return `<!doctype html><html lang="${locale}"><meta charset="utf-8"><style>${BASE}
:root{--u:calc(100vw / 236);--y:55%;--r:${n % 2 ? -14 : 14}deg}
body{font-family:${fontStack(lang)}}
.stamp{position:absolute;left:0;right:0;top:4.5%;display:flex;justify-content:center;align-items:center;gap:calc(var(--u) * 6);
  font-size:calc(var(--u) * 11);font-weight:700;line-height:1;z-index:3}
.mk{width:calc(var(--u) * 16);height:calc(var(--u) * 16);display:inline-flex}.mk svg{width:100%;height:100%}
.head{position:absolute;left:0;right:0;top:11%;text-align:center;padding:0 calc(var(--u) * 14);z-index:2}
h1{margin:0;font-size:calc(var(--u) * 21);line-height:1.12;font-weight:800;letter-spacing:-.015em;text-wrap:balance}
p{margin:calc(var(--u) * 6) 0 0;font-size:calc(var(--u) * 11.5);line-height:1.4;font-weight:500;color:#5d5866;text-wrap:balance}
.phone{position:absolute;left:50%;transform:translateX(-50%);top:35%;width:68%;border-radius:calc(var(--u) * 22);padding:calc(var(--u) * 5);
  background:#0b0b0d;z-index:2;box-shadow:0 calc(var(--u) * 18) calc(var(--u) * 40) rgba(0,0,0,.35)}
.phone img{display:block;width:100%;border-radius:calc(var(--u) * 17)}
</style><body><div class="beam"></div>
<div class="head"><h1>${esc(t.title).replace(/\n/g, '<br>')}</h1><p>${esc(t.sub)}</p></div>${chipsHtml(CHIPS[n])}
<div class="phone"><img src="data:image/jpeg;base64,${img}" alt=""></div>
<div class="stamp"><span class="mk">${MARK}</span>Trackhop</div></body></html>`;
}

function coverHtml(title, subtitle, locale) {
  return `<!doctype html><html lang="${locale}"><meta charset="utf-8"><style>${BASE}
:root{--u:calc(100vw / 300);--y:80%;--r:-12deg}
body{font-family:${fontStack(locale.split('-')[0])}}
.cin{position:absolute;inset:0 0 22% 0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:calc(var(--u) * 8);
  text-align:center;z-index:2;padding:0 calc(var(--u) * 20)}
.bigmk{width:calc(var(--u) * 84);height:calc(var(--u) * 84);display:inline-flex;color:var(--violet)}.bigmk svg{width:100%;height:100%}
h1{margin:calc(var(--u) * 6) 0 0;font-size:calc(var(--u) * 34);line-height:1.1;font-weight:800;letter-spacing:-.02em;text-wrap:balance}
p{margin:0;font-size:calc(var(--u) * 14);line-height:1.35;font-weight:500;opacity:.85;text-wrap:balance}
</style><body><div class="beam"></div>${chipsHtml(COVER_CHIPS)}
<div class="cin"><span class="bigmk">${MARK}</span><h1>${esc(title).replace(/\n/g, '<br>')}</h1><p>${esc(subtitle)}</p></div></body></html>`;
}

// In the page, after fonts and image are ready: the headline at most two lines and the line under it one
// (both shrink until they fit, nothing is ever cut), then chips moved down off the headline. Returns notes.
async function settle(page, { titleLines, subLines }) {
  return page.evaluate(async ({ titleLines, subLines }) => {
    await document.fonts.ready;
    await Promise.all([...document.images].map((i) => i.decode()));
    const notes = [];
    const fit = (el, lines, lh) => {
      let fs = parseFloat(getComputedStyle(el).fontSize);
      const start = fs;
      while ((el.getBoundingClientRect().height > lines * fs * lh + 2 || el.scrollWidth > el.clientWidth + 1) && fs > start * 0.6) {
        fs *= 0.97;
        el.style.fontSize = `${fs}px`;
      }
      if (fs !== start) notes.push(`${el.tagName.toLowerCase()} shrunk to ${Math.round((fs / start) * 100)}%`);
    };
    const h1 = document.querySelector('h1');
    const p = document.querySelector('p');
    fit(h1, titleLines, parseFloat(getComputedStyle(h1).lineHeight) / parseFloat(getComputedStyle(h1).fontSize));
    fit(p, subLines, parseFloat(getComputedStyle(p).lineHeight) / parseFloat(getComputedStyle(p).fontSize));
    // Text boxes as drawn (line by line), padded a little.
    const boxes = [h1, p].flatMap((el) => { const r = document.createRange(); r.selectNodeContents(el); return [...r.getClientRects()]; });
    const pad = innerWidth * 0.015;
    const hits = (c) => boxes.some((b) => c.left < b.right + pad && c.right > b.left - pad && c.top < b.bottom + pad && c.bottom > b.top - pad);
    for (const chip of document.querySelectorAll('.chip')) {
      let moved = 0;
      while (hits(chip.getBoundingClientRect()) && moved < 40) {
        chip.style.top = `${parseFloat(chip.style.top) + 1}%`;
        moved++;
      }
      if (moved) notes.push(`chip ${chip.textContent} moved down ${moved}%`);
    }
    const lines = Math.round(h1.getBoundingClientRect().height / parseFloat(getComputedStyle(h1).lineHeight));
    return { notes, lines };
  }, { titleLines, subLines });
}

function parseArgs(argv) {
  const a = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k.startsWith('--')) a[k.slice(2)] = argv[++i];
    else a._.push(k);
  }
  return a;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const browser = await chromium.launch();
  try {
    if (args._[0] === 'cover') {
      const out = args._[1];
      if (!out || !args.title) throw new Error('usage: store_frames.mjs cover --title T [--subtitle S] [--locale L] OUT.png');
      const page = await browser.newPage({ viewport: { width: 1080, height: 1350 } });
      await page.setContent(coverHtml(args.title, args.subtitle || '', args.locale || 'en-US'));
      const { notes } = await settle(page, { titleLines: 2, subLines: 2 });
      mkdirSync(dirname(resolve(out)), { recursive: true });
      const jpg = /\.jpe?g$/i.test(out);
      await page.screenshot({ path: out, type: jpg ? 'jpeg' : 'png', ...(jpg ? { quality: 92 } : {}) });
      console.log(`${out}${notes.length ? ` (${notes.join('; ')})` : ''}`);
      return;
    }
    const locales = args.locales ? args.locales.split(',').filter(Boolean) : LOCALES;
    const shots = args.shots ? args.shots.split(',').filter(Boolean).map(Number) : Array.from({ length: SHOTS }, (_, i) => i + 1);
    const format = (args.format || 'jpg').toLowerCase() === 'png' ? 'png' : 'jpg';
    const quality = Number(args.quality || 90);
    for (const l of locales) if (!TITLES[l]) throw new Error(`no titles for ${l} in docs/store/frames/titles.json`);
    const jobs = [];
    for (const l of locales) for (const n of shots) {
      const src = join(IN, l, `${n}.jpg`);
      if (!existsSync(src)) { console.warn(`skip ${l}/${n}: no ${src}`); continue; }
      if (!TITLES[l][n] || !CHIPS[n]) throw new Error(`no title or chips for ${l} shot ${n}`);
      jobs.push([l, n, src]);
    }
    // A few pages at once; each job is independent.
    const worker = async () => {
      const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
      for (let job; (job = jobs.shift());) {
        const [l, n, src] = job;
        await page.setContent(frameHtml(l, n, b64(src)));
        const { notes, lines } = await settle(page, { titleLines: 2, subLines: 1 });
        const out = join(OUT, l, `${n}.${format}`);
        mkdirSync(dirname(out), { recursive: true });
        await page.screenshot({ path: out, type: format === 'png' ? 'png' : 'jpeg', ...(format === 'png' ? {} : { quality }) });
        console.log(`${l}/${n}.${format} ${lines} line${lines > 1 ? 's' : ''}${notes.length ? ` (${notes.join('; ')})` : ''}`);
      }
      await page.close();
    };
    await Promise.all(Array.from({ length: 4 }, worker));
  } finally {
    await browser.close();
  }
}

main().catch((e) => { console.error(e.message || e); process.exit(1); });
