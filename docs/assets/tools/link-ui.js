// /link: the page around link.js. The pasted link is looked up from this browser on the public
// catalogues of Apple (iTunes), Deezer, Spotify, MusicBrainz, ListenBrainz and Wikidata, and their cover pictures, and nowhere else. Nothing is stored: no
// cookies, no storage, no statistics. The address bar gets ?u=<link>, so the result can be shared.
import { parseLink, lookup, spotifyBase, spotifyResult, copyText, clock, display, serviceName, browserNet, isName, searchSongs } from './link.js';
import { ToolError } from './io.js';
import { strings, fmt, el } from './ui.js';

const T = strings.link;
const root = document.getElementById('link-tool');
const $ = (sel) => root.querySelector(sel);
const form = $('.lk-paste'), input = $('#lk-in'), go = $('.lk-go'), pasteBtn = $('.lk-pastebtn'), errBox = $('#lk-err');
const empty = $('.lk-two'), loaded = $('.lk-loaded'), box = $('.lk-result'), status = $('.lk-sr'), pickBox = $('.lk-pick');
const goText = go.textContent;

const SVG = 'http://www.w3.org/2000/svg';
function icon(name) {
  const svg = document.createElementNS(SVG, 'svg'), use = document.createElementNS(SVG, 'use');
  svg.setAttribute('class', 'i');
  svg.setAttribute('aria-hidden', 'true');
  use.setAttribute('href', '#lk-i-' + name);
  svg.append(use);
  return svg;
}
const MARK_ICON = { exact: 'check', guess: 'guess', search: 'search' };
const mark = (m) => el('span', { class: 'lk-mark' + (m === 'search' ? '' : ' ' + m) }, icon(MARK_ICON[m]), T.marks[m]);

let run = 0;        // the lookup under way; a newer one makes the older one's answer void
let going = null;   // its AbortController: a newer lookup also ends the older one's requests
function fresh() {
  if (going) going.abort();
  going = new AbortController();
  return going.signal;
}
let current = null; // { result, base } of what is shown

// ---- Errors ----

function errorOf(e) {
  if (!(e instanceof ToolError)) { console.error(e); return { code: 'unknown', service: '' }; }
  if (e.code === 'many') return { code: e.detail, service: '' };
  if (e.code === 'no-answer' && navigator.onLine === false) return { code: 'offline', service: '' };
  if (e.code === 'nothing') return { code: 'nothing', service: '', q: e.detail };
  return { code: e.code, service: e.detail };
}

/** The message of an error: a headline, then a sentence or a short list. */
function errorParts(e) {
  const { code, service, q } = errorOf(e);
  const text = T.err[code] || T.err.unknown;
  const parts = [icon('alert'), el('b', { text: fmt(text.h, { service, q }) })];
  if (text.p) parts.push(el('p', { text: text.p }));
  if (text.tips) {
    parts.push(el('ul', {}, ...text.tips.map((tip) => {
      const [before, after] = tip.split('{get}');
      return after == null ? el('li', { text: tip }) : el('li', {}, before, el('a', { href: T.home, text: T.get }), after);
    })));
  }
  return parts;
}

function showError(e) {
  errBox.replaceChildren(...errorParts(e));
  errBox.hidden = false;
  input.setAttribute('aria-invalid', 'true');
  input.setAttribute('aria-describedby', 'lk-err');
}
function clearError() {
  errBox.hidden = true;
  errBox.replaceChildren();
  input.removeAttribute('aria-invalid');
  input.setAttribute('aria-describedby', 'lk-priv');
}

// ---- The result ----

const pageUrl = (result) => {
  const u = new URL(location.pathname, location.origin);
  u.searchParams.set('u', result.sourceUrl);
  if (result.guess && current && current.artist) u.searchParams.set('a', current.artist);
  return u;
};

// ---- The cover: a picture disc on a turntable ----
// The song's own cover is printed across the record, which turns (one turn in 2.4 s) while the arm leans in
// towards the centre and back, as on the home page; both stay still where motion is reduced (tools.css).
// Drawn here in SVG, nothing fetched but the cover itself. On a narrow screen the cover's column is small:
// the record alone is shown, closer.
const svgEl = (tag, attrs = {}, ...kids) => {
  const e = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  for (const c of kids) if (c) e.append(c);
  return e;
};
const narrow = window.matchMedia('(max-width: 860px)');
const deckBox = () => (narrow.matches ? '3.6 3.6 35.8 35.8' : '2.6 2.6 54.8 40.8');
let shownDeck = null;
narrow.addEventListener('change', () => { if (shownDeck) shownDeck.setAttribute('viewBox', deckBox()); });

function cover(result) {
  const C = 21.5, R = 16.3;
  const art = /^https:\/\//.test(result.art || '') ? result.art : null;
  const stop = (offset, color, opacity = 1) => svgEl('stop', { offset, 'stop-color': color, 'stop-opacity': opacity });
  const defs = svgEl('defs', {},
    svgEl('linearGradient', { id: 'lkd-p', x1: 0, y1: 0, x2: 1, y2: 1 }, stop(0, '#dcdfe4'), stop(0.5, '#bfc3ca'), stop(1, '#a3a8b1')),
    svgEl('linearGradient', { id: 'lkd-s', x1: 0, y1: 0, x2: 1, y2: 1 }, stop(0, '#55515e'), stop(1, '#0f0d12')),
    svgEl('pattern', { id: 'lkd-b', width: 60, height: 0.8, patternUnits: 'userSpaceOnUse' },
      svgEl('rect', { width: 60, height: 0.12, fill: '#fff', 'fill-opacity': 0.35 }),
      svgEl('rect', { y: 0.4, width: 60, height: 0.12, fill: '#8d929b', 'fill-opacity': 0.18 })),
    svgEl('radialGradient', { id: 'lkd-d', cx: 0.5, cy: 0.5, r: 0.5 }, stop(0.62, '#000', 0), stop(1, '#000', 0.28)),
    svgEl('clipPath', { id: 'lkd-c' }, svgEl('circle', { cx: C, cy: C, r: R })));
  // The record: the cover, then its grooves (fine rings, dark and light, closer towards the edge).
  const grooves = svgEl('g', { fill: 'none' });
  for (let r = 6.6, i = 0; r < 16.1; r += r > 8 ? 0.28 : 0.6, i++) {
    const dark = i % 2 === 0;
    grooves.append(svgEl('circle', { cx: C, cy: C, r: r.toFixed(2), stroke: dark ? '#000' : '#fff', 'stroke-opacity': dark ? 0.3 : 0.1, 'stroke-width': dark ? 0.16 : 0.09 }));
  }
  let picture = null;
  if (art) {
    picture = svgEl('image', { href: art, x: C - R, y: C - R, width: 2 * R, height: 2 * R, preserveAspectRatio: 'xMidYMid slice', 'clip-path': 'url(#lkd-c)' });
    picture.addEventListener('error', () => picture.remove());
  }
  const record = svgEl('g', { class: 'lk-spin' }, svgEl('circle', { cx: C, cy: C, r: R, fill: '#0f0d12' }), picture, grooves);
  const arm = svgEl('g', { class: 'lk-arm' },
    svgEl('rect', { x: 47.2, y: 3.9, width: 2.8, height: 3.6, rx: 0.7, fill: 'url(#lkd-s)' }),
    svgEl('path', { d: 'M48.6 10.4 L45.6 27.2 L38.4 30.6', fill: 'none', stroke: 'url(#lkd-s)', 'stroke-width': 1.1, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
    svgEl('rect', { x: 34.2, y: 29.4, width: 4.6, height: 2.6, rx: 0.5, transform: 'rotate(-25 36.5 30.7)', fill: '#1d1a22', stroke: '#77737f', 'stroke-width': 0.2 }));
  const knob = (cx, cy, r) => svgEl('circle', { cx, cy, r, fill: '#2a2630' });
  const svg = svgEl('svg', { class: 'lk-art lk-deck', viewBox: deckBox(), role: 'img', 'aria-label': fmt(T.art, { title: result.album || result.title }) },
    defs,
    svgEl('rect', { x: 3, y: 3, width: 54, height: 40, rx: 5, fill: 'url(#lkd-p)' }),
    svgEl('rect', { x: 3, y: 3, width: 54, height: 40, rx: 5, fill: 'url(#lkd-b)' }),
    svgEl('path', { d: 'M3 3 L30 3 L16 43 L3 43 Z', fill: '#fff', opacity: 0.14 }),
    svgEl('path', { d: 'M38 3 L44 3 L30 43 L24 43 Z', fill: '#fff', opacity: 0.12 }),
    svgEl('rect', { x: 3, y: 3, width: 54, height: 40, rx: 5, fill: 'none', stroke: '#8d929b', 'stroke-opacity': 0.8, 'stroke-width': 0.4 }),
    svgEl('circle', { cx: C, cy: C, r: 17.4, fill: '#2a2630' }),
    record,
    // The light the grooves catch stays where it is while the record turns.
    svgEl('circle', { cx: C, cy: C, r: R, fill: 'url(#lkd-d)' }),
    svgEl('path', { d: 'M21.5 21.5 L12.2 8.2 A16.3 16.3 0 0 1 17.6 5.7 Z', fill: '#fff', opacity: 0.13 }),
    svgEl('path', { d: 'M21.5 21.5 L30.8 34.8 A16.3 16.3 0 0 1 25.4 37.3 Z', fill: '#fff', opacity: 0.08 }),
    svgEl('circle', { cx: C, cy: C, r: R, fill: 'none', stroke: '#000', 'stroke-opacity': 0.45, 'stroke-width': 0.5 }),
    svgEl('circle', { cx: C, cy: C, r: 1.3, fill: '#d9dce2' }),
    svgEl('circle', { cx: C, cy: C, r: 0.5, fill: '#111' }),
    knob(48.6, 10.4, 4.2),
    svgEl('circle', { cx: 48.6, cy: 10.4, r: 2.6, fill: 'url(#lkd-s)', stroke: '#77737f', 'stroke-width': 0.25 }),
    arm,
    knob(51.6, 28.6, 1.5),
    svgEl('rect', { x: 39.4, y: 37, width: 5, height: 2.4, rx: 1.2, fill: '#2a2630' }),
    svgEl('circle', { cx: 43.2, cy: 38.2, r: 0.9, fill: '#dcdfe4' }),
    knob(48.6, 38.2, 1.7), knob(53.2, 38.2, 1.7));
  shownDeck = svg;
  return svg;
}

function details(result) {
  if (result.kind === 'song') return [result.album, result.durationMs ? clock(result.durationMs) : null].filter(Boolean).join(' · ');
  return [result.count ? fmt(T.songs_n, { n: result.count }) : null, result.year].filter(Boolean).join(' · ');
}

function guessBox(result) {
  const known = !!result.artist;
  const field = el('input', { id: 'lk-artist', type: 'text', value: result.artist, autocomplete: 'off', autocapitalize: 'words', spellcheck: 'false' });
  const update = el('button', { class: 'tool-btn', type: 'submit', text: T.update });
  const problem = el('div', { class: 'tool-error lk-err', role: 'alert', hidden: true });
  const fix = el('form', { class: 'lk-fix', novalidate: true },
    el('label', { for: 'lk-artist', text: T.artist }),
    el('div', { class: 'lk-fix-row' }, field, update));
  const again = async (artist) => {
    artist = artist.trim();
    if (!artist || update.disabled) return;
    update.disabled = true;
    box.setAttribute('aria-busy', 'true');
    const mine = ++run, base = current.base, signal = fresh();
    try {
      if (navigator.onLine === false) throw new ToolError('offline');
      const next = await spotifyResult(base, artist, browserNet, { signal });
      if (mine !== run) return;
      current = { result: next, base, artist };
      render(next, true);
    } catch (e) {
      // The result on the page stays as it was; only the message is new.
      if (mine !== run) return;
      problem.replaceChildren(...errorParts(e));
      problem.hidden = false;
    } finally {
      update.disabled = false;
      box.removeAttribute('aria-busy');
    }
  };
  fix.addEventListener('submit', (e) => { e.preventDefault(); again(field.value); });
  const parts = [
    el('div', { class: 'lk-guess-h' }, icon('guess'),
      el('div', {}, el('b', { text: known ? T.guess_h : T.guess_none_h }),
        el('span', { text: fmt((known ? T.guess_p : T.guess_none_p)[result.kind], { title: result.title }) }))),
    fix,
  ];
  if (result.alts.length) {
    parts.push(el('div', { class: 'lk-alts', role: 'group', 'aria-label': T.alts_label }, el('span', { text: T.alts }),
      ...result.alts.map((name) => el('button', { type: 'button', text: name, onclick: () => again(name) }))));
  }
  parts.push(problem);
  if (result.guess) return el('div', { class: 'lk-guess' }, ...parts);
  // Found by its cover: the artist is not a guess, but the visitor can still say it is someone else.
  return el('div', { class: 'lk-sure' }, ...parts.slice(2, -1), el('details', {}, el('summary', { text: T.fix_open }), fix), problem);
}

function group(id, title, sub, services) {
  if (!services.length) return null;
  const h = el('h3', { id }, title);
  if (sub) h.append(' ', el('small', { text: sub }));
  return el('section', { class: 'lk-group', 'aria-labelledby': id }, h,
    el('ul', { class: 'lk-svcs' }, ...services.map((s) => {
      const name = el('span', { class: 'nm', text: s.name });
      const words = s.note ? T.notes[s.note] : null;
      const note = s.note === 'guessed' ? s.label : words && typeof words === 'object' ? words[s.kind] : words;
      if (note) name.append(el('small', { text: note }));
      return el('li', {}, el('a', { class: 'lk-svc' + (s.mark === 'exact' ? ' exact' : ''), href: s.url, target: '_blank', rel: 'noopener noreferrer' },
        el('span', { class: 'ic' }, icon(s.mark === 'search' ? 'search' : 'open')), name, mark(s.mark)));
    })));
}

function render(result, refined) {
  const services = result.services.map((s) => ({ ...s, kind: result.kind }));
  const of = (m) => services.filter((s) => s.mark === m);
  const text = copyText(result, { guess: T.copy_guess, search: T.copy_search });
  const what = [result.title, result.artist].filter(Boolean).join(' – ');

  const title = el('h2', { class: 'lk-title', id: 'lk-title', tabindex: '-1', text: result.title });
  const kind = T.kind[result.kind];
  const meta = el('div', { class: 'lk-meta' },
    el('p', { class: 'lk-kind', text: result.guess && result.artist ? fmt(T.kind_guess, { kind }) : kind }), title);
  if (result.artist) meta.append(el('p', { class: 'lk-artist', text: result.artist }));
  if (details(result)) meta.append(el('p', { class: 'lk-album', text: details(result) }));

  const pre = el('pre', { text });
  const peek = el('details', { class: 'lk-copy' }, el('summary', { text: T.copy_what }), pre);
  const copyLabel = el('span', { text: T.copy });
  const copy = el('button', { class: 'tool-btn', type: 'button' }, icon('copy'), copyLabel);
  let back = 0;
  copy.addEventListener('click', async () => {
    let done = false;
    try { await navigator.clipboard.writeText(text); done = true; } catch (e) { /* no clipboard here: the text is shown to be copied by hand */ }
    if (!done) {
      peek.open = true;
      const range = document.createRange();
      range.selectNodeContents(pre);
      const sel = getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
    copyLabel.textContent = done ? T.copied : T.copy_failed;
    status.textContent = copyLabel.textContent;
    clearTimeout(back);
    back = setTimeout(() => { copyLabel.textContent = T.copy; }, done ? 2000 : 6000);
  });
  const acts = el('div', { class: 'lk-acts' }, copy);
  if (typeof navigator.share === 'function') {
    acts.append(el('button', { class: 'tool-btn ghost', type: 'button', onclick: () => {
      navigator.share({ title: what, text: what, url: pageUrl(result).href }).catch(() => {});
    } }, icon('share'), T.share));
  }

  const song = el('article', { class: 'lk-song', 'aria-labelledby': 'lk-title' }, cover(result), meta);
  if (result.fix) song.append(guessBox(result));
  song.append(acts);

  const guessed = result.guess;
  const main = el('div', { class: 'lk-main' },
    group('lk-g-exact', T.opens[result.kind], null, of('exact')),
    group('lk-g-guess', T.opens_guess, (guessed ? T.guess_sub : T.differs_sub)[result.kind], of('guess')),
    group('lk-g-search', T.opens_search, fmt(guessed ? T.search_sub_guess : T.search_sub[result.kind], { q: result.query }), of('search')));

  box.replaceChildren(el('div', { class: 'lk-side' }, song, peek), main);
  loaded.querySelector('b').textContent = fmt(T.from, { service: serviceName(result.source) });
  loaded.querySelector('.lk-from span:last-child').textContent = display(result.sourceUrl);
  const focused = document.activeElement;
  form.hidden = true;
  empty.hidden = true;
  pickBox.hidden = true;
  pickBox.replaceChildren();
  loaded.hidden = false;
  box.hidden = false;
  status.textContent = fmt(T.found, { what });
  try { history.replaceState(null, '', pageUrl(result)); } catch (e) { /* a page opened from a file */ }
  // The field the visitor was in is gone: the keyboard goes on from the song's title.
  if (refined && box.querySelector('#lk-artist')) box.querySelector('#lk-artist').focus({ preventScroll: true });
  else if (form.contains(focused)) title.focus();
}

// ---- Looking up ----

function busy(on) {
  go.disabled = on;
  go.textContent = on ? T.finding : goText;
  if (on) form.setAttribute('aria-busy', 'true'); else form.removeAttribute('aria-busy');
}

async function find(text, artist) {
  const mine = ++run, signal = fresh();
  clearError();
  let link;
  try {
    link = parseLink(text);
  } catch (e) {
    if (e.code === 'not-link' && isName(text)) pick(text.trim(), mine, signal);
    else showError(e);
    return;
  }
  busy(true);
  try {
    if (navigator.onLine === false) throw new ToolError('offline');
    let next;
    if (link.service === 'spotify') {
      const base = await spotifyBase(link, browserNet, { signal });
      next = { base, artist: artist || '', result: await spotifyResult(base, artist, browserNet, { signal }) };
    } else {
      next = { base: null, artist: '', result: await lookup(link, browserNet, { signal }) };
    }
    if (mine !== run) return;
    // Complete, or not at all: the page changes only here.
    current = next;
    render(next.result, false);
  } catch (e) {
    if (mine === run) showError(e);
  } finally {
    if (mine === run) busy(false);
  }
}

// ---- A song by its name: "Which one?" ----

// The visitor's own country's store first: the same song has other names and other albums elsewhere.
const country = (/^[a-z]{2,3}-([a-z]{2})\b/i.exec(navigator.language || '') || [, strings.lang === 'it' ? 'it' : 'us'])[1].toLowerCase();

function setAddress(params) {
  const u = new URL(location.pathname, location.origin);
  for (const [k, v] of Object.entries(params)) u.searchParams.set(k, v);
  try { history.replaceState(null, '', u); } catch (e) { /* a page opened from a file */ }
}

function pickRow(song) {
  const art = /^https:\/\//.test(song.thumb || '') ? song.thumb : null;
  const pic = art ? el('img', { src: art, alt: '', width: 48, height: 48, loading: 'lazy', referrerpolicy: 'no-referrer' }) : el('span', { class: 'lk-noart' }, icon('note'));
  if (art) pic.addEventListener('error', () => pic.replaceWith(el('span', { class: 'lk-noart' }, icon('note'))));
  const more = [song.album, song.durationMs ? clock(song.durationMs) : null].filter(Boolean).join(' · ');
  const text = el('span', { class: 'lk-pick-t' }, el('b', { text: song.title }), el('span', { text: song.artist }));
  if (more) text.append(el('small', { text: more }));
  const button = el('button', { type: 'button', class: 'lk-pick-row' }, pic, text,
    el('span', { class: 'lk-pick-src', text: song.sources.map(serviceName).join(' · ') }));
  button.addEventListener('click', () => { input.value = song.url; find(song.url); });
  return el('li', {}, button);
}

async function pick(term, mine, signal) {
  busy(true);
  pickBox.setAttribute('aria-busy', 'true');
  try {
    if (navigator.onLine === false) throw new ToolError('offline');
    const songs = await searchSongs(term, browserNet, { country, signal });
    if (mine !== run) return;
    if (!songs.length) throw new ToolError('nothing', term);
    const h = el('h2', { id: 'lk-pick-h', tabindex: '-1', text: T.pick_h });
    pickBox.replaceChildren(h, el('p', { class: 'tool-muted', text: fmt(T.pick_for, { q: term }) }),
      el('ul', { class: 'lk-pick-list', 'aria-labelledby': 'lk-pick-h' }, ...songs.map(pickRow)),
      el('p', { class: 'tool-muted lk-pick-foot', text: T.pick_foot }));
    pickBox.hidden = false;
    empty.hidden = true;
    status.textContent = fmt(T.picked, { n: songs.length, q: term });
    setAddress({ q: term });
  } catch (e) {
    if (mine !== run) return;
    pickBox.hidden = true;
    pickBox.replaceChildren();
    empty.hidden = false;
    showError(e);
  } finally {
    if (mine === run) { busy(false); pickBox.removeAttribute('aria-busy'); }
  }
}

form.addEventListener('submit', (e) => { e.preventDefault(); find(input.value); });
input.addEventListener('input', () => { if (!errBox.hidden) clearError(); });
// A link pasted into the field is looked up at once; other text waits for the button.
input.addEventListener('paste', (e) => {
  const text = e.clipboardData ? e.clipboardData.getData('text') : '';
  try { parseLink(text); } catch (err) { if (err.code === 'empty' || err.code === 'not-link') return; }
  e.preventDefault();
  input.value = text.trim();
  find(text);
});

// The Paste button is there only where the browser lets a page read the clipboard on a click.
if (navigator.clipboard && typeof navigator.clipboard.readText === 'function') {
  pasteBtn.hidden = false;
  form.classList.add('has-paste');
  pasteBtn.addEventListener('click', async () => {
    let text = '';
    try {
      text = await navigator.clipboard.readText();
    } catch (e) {
      // Not allowed here: the button goes, and the field is ready for the keyboard's paste.
      pasteBtn.hidden = true;
      form.classList.remove('has-paste');
      input.focus();
      return;
    }
    if (!text.trim()) { input.focus(); return; }
    input.value = text.trim();
    find(text);
  });
}

$('.lk-again').addEventListener('click', () => {
  run++;
  fresh();
  current = null;
  busy(false);
  box.hidden = true;
  box.replaceChildren();
  loaded.hidden = true;
  form.hidden = false;
  empty.hidden = false;
  pickBox.hidden = true;
  pickBox.replaceChildren();
  clearError();
  input.value = '';
  status.textContent = '';
  try { history.replaceState(null, '', location.pathname); } catch (e) { /* a page opened from a file */ }
  input.focus();
});

// /link?u=<link> looks the link up as the page opens, so a result can be passed on; /link?q=<name> searches.
const params = new URLSearchParams(location.search);
if (params.get('u')) {
  input.value = params.get('u');
  find(params.get('u'), params.get('a') || '');
} else if (params.get('q')) {
  input.value = params.get('q');
  find(params.get('q'));
}
