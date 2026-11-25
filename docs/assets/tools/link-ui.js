// /link: the page around link.js. The pasted link is looked up from this browser on the public
// catalogues of Apple (iTunes), Deezer, Spotify, MusicBrainz and Wikidata, and their cover pictures, and nowhere else. Nothing is stored: no
// cookies, no storage, no statistics. The address bar gets ?u=<link>, so the result can be shared.
import { parseLink, lookup, spotifyBase, spotifyResult, copyText, clock, display, serviceName, browserNet } from './link.js';
import { ToolError } from './io.js';
import { strings, fmt, el } from './ui.js';

const T = strings.link;
const root = document.getElementById('link-tool');
const $ = (sel) => root.querySelector(sel);
const form = $('.lk-paste'), input = $('#lk-in'), go = $('.lk-go'), pasteBtn = $('.lk-pastebtn'), errBox = $('#lk-err');
const empty = $('.lk-two'), loaded = $('.lk-loaded'), box = $('.lk-result'), status = $('.lk-sr');
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
  return { code: e.code, service: e.detail };
}

/** The message of an error: a headline, then a sentence or a short list. */
function errorParts(e) {
  const { code, service } = errorOf(e);
  const text = T.err[code] || T.err.unknown;
  const parts = [icon('alert'), el('b', { text: fmt(text.h, { service }) })];
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

function cover(result) {
  const blank = () => el('span', { class: 'lk-art lk-noart', 'aria-hidden': 'true' }, icon('note'));
  if (!result.art) return blank();
  const img = el('img', { class: 'lk-art', src: result.art, alt: fmt(T.art, { title: result.album || result.title }), width: 600, height: 600, referrerpolicy: 'no-referrer', decoding: 'async' });
  img.addEventListener('error', () => img.replaceWith(blank()));
  return img;
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
    showError(e);
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
  clearError();
  input.value = '';
  status.textContent = '';
  try { history.replaceState(null, '', location.pathname); } catch (e) { /* a page opened from a file */ }
  input.focus();
});

// /link?u=<link> looks the link up as the page opens, so a result can be passed on.
const params = new URLSearchParams(location.search);
if (params.get('u')) {
  input.value = params.get('u');
  find(params.get('u'), params.get('a') || '');
}
