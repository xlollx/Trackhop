// /convert: the page around playlist.js. Files are read and written here, in the browser; this
// script makes no request and stores nothing.
import { readFile, encode, dedupe, safeName, OUTPUTS } from './playlist.js';
import { zipParts } from './zip.js';
import { ToolError } from './io.js';
import { strings, fmt, num, el, message, dropArea, progress, save, sizeText } from './ui.js';

const T = strings.convert;
const root = document.getElementById('convert-tool');
const list = root.querySelector('.tool-files'), errors = root.querySelector('.tool-errors');
const bar = progress(root.querySelector('.tool-progress'));
const ICONS = { csv: 'CSV', m3u: 'M3U', pls: 'PLS', itunes: 'XML', rekordbox: 'XML', wpl: 'WPL', xspf: 'XSPF', opml: 'OPML', json: 'JSON', text: 'TXT' };
const TYPES = { csv: 'text/csv', m3u8: 'audio/x-mpegurl', xspf: 'application/xspf+xml', jspf: 'application/json', wpl: 'application/vnd.ms-wpl', zpl: 'application/vnd.ms-wpl', text: 'text/plain' };

let busy = false, serial = 0;
const count = (text, n) => fmt(text, { n }).replace(String(n), num(n));

/** The lists a card gives with its choices as they stand: [{ name, tracks }]. */
function lists(item) {
  const clean = (tracks) => (item.dedupe ? dedupe(tracks) : tracks);
  if (!item.picks) return [{ name: item.base, tracks: clean(item.tracks) }];
  const chosen = item.picks.filter((p) => item.selected.has(p.key));
  if (item.mode === 'one') {
    return chosen.length ? [{ name: item.base, tracks: clean(chosen.flatMap((p) => item.library.tracksOf(p))) }] : [];
  }
  return chosen.map((p) => ({ name: p.name, tracks: clean(item.library.tracksOf(p)) }));
}

function download(item) {
  const out = lists(item);
  if (!out.length) return;
  const ext = '.' + OUTPUTS[item.output];
  try {
    if (out.length === 1) {
      const name = safeName(out[0].name);
      save(new Blob([encode(item.output, out[0].name, out[0].tracks)], { type: TYPES[item.output] + ';charset=utf-8' }), name + ext);
      return;
    }
    if (out.length > 65535) throw new ToolError('too-big');
    const enc = new TextEncoder();
    const files = out.map((l) => ({ name: safeName(l.name) + ext, data: enc.encode(encode(item.output, l.name, l.tracks)) }));
    save(new Blob(zipParts(files), { type: 'application/zip' }), safeName(item.base) + '.zip');
  } catch (e) {
    fail(e, item.name);
  }
}

function fail(e, name) {
  const text = message(e, name);
  if (text) errors.append(el('p', { text }));
}

function card(item) {
  const id = 'f' + (++serial);
  const button = el('button', { type: 'button', class: 'tool-btn', onclick: () => download(item) });
  const refresh = () => {
    const out = item.picks && item.mode === 'each' ? item.picks.filter((p) => item.selected.has(p.key)).length : item.picks ? Math.min(1, item.selected.size) : 1;
    button.disabled = out === 0;
    button.textContent = out === 0 ? T.nothing
      : out === 1 ? fmt(T.download_file, { name: safeName(item.picks && item.mode === 'each' ? item.picks.find((p) => item.selected.has(p.key)).name : item.base) + '.' + OUTPUTS[item.output] })
        : count(T.download_zip, out);
  };

  const output = el('select', { 'aria-label': T.output_label, onchange: (e) => { item.output = e.target.value; refresh(); } },
    ...Object.keys(OUTPUTS).map((o) => el('option', { value: o, selected: o === item.output, text: T.outputs[o] })));
  const sel = el('div', { class: 'tool-sel' }, el('span', { text: T.into }), output);
  if (item.picks) {
    sel.append(el('select', { 'aria-label': T.mode_label, onchange: (e) => { item.mode = e.target.value; refresh(); } },
      el('option', { value: 'each', selected: true, text: T.each }), el('option', { value: 'one', text: T.one })));
  }

  const meta = [T.formats[item.kind]];
  meta.push(count(T.songs_n, item.count));
  if (item.picks) meta.push(count(T.playlists_n, item.picks.length));
  meta.push(sizeText(item.size));

  const box = el('div', { class: 'card' },
    el('div', { class: 'tool-det' },
      el('span', { class: 'ic', 'aria-hidden': 'true', text: ICONS[item.format] }),
      el('div', {}, el('b', { text: item.name }), el('div', { class: 'tool-muted', text: meta.join(' · ') })),
      el('button', { type: 'button', class: 'x', 'aria-label': fmt(T.remove, { name: item.name }), text: '×', onclick: () => box.remove() })),
    sel,
    el('label', { class: 'tool-chk lone' },
      el('input', { type: 'checkbox', onchange: (e) => { item.dedupe = e.target.checked; } }),
      el('span', { class: 't', text: T.dedupe }), el('span', { class: 'n', text: T.dedupe_hint })),
  );

  if (item.picks) {
    const pick = el('fieldset', { class: 'tool-pick' }, el('legend', { text: T.pick }));
    const boxes = [];
    let folder = null;
    for (const p of item.picks) {
      // A folder of the library is a heading over its playlists.
      if (p.folder !== folder) { folder = p.folder; if (folder) pick.append(el('h3', { text: folder })); }
      const input = el('input', { type: 'checkbox', checked: item.selected.has(p.key), onchange: (e) => {
        if (e.target.checked) item.selected.add(p.key); else item.selected.delete(p.key);
        refresh();
      } });
      boxes.push([input, p]);
      const name = el('span', { class: 't', text: p.name });
      if (p.kind === 'smart') name.append(' ', el('small', { text: '· ' + T.smart }));
      pick.append(el('label', { class: 'tool-chk' }, input, name, el('span', { class: 'n', text: num(p.trackIds.length) })));
    }
    const setAll = (on) => {
      for (const [input, p] of boxes) { input.checked = on; if (on) item.selected.add(p.key); else item.selected.delete(p.key); }
      refresh();
    };
    box.append(
      el('div', { class: 'tool-pick-all' },
        el('button', { type: 'button', class: 'tool-btn ghost small', text: T.all, onclick: () => setAll(true) }),
        el('button', { type: 'button', class: 'tool-btn ghost small', text: T.none, onclick: () => setAll(false) })),
      pick);
  }
  box.append(el('div', {}, button));
  box.id = id;
  refresh();
  return box;
}

/** What was read from a file, as the card's state. */
function itemOf(file, read) {
  const base = file.name.replace(/\.[^.]+$/, '') || file.name;
  const item = { name: file.name, base, size: file.size, format: read.format, output: read.format === 'm3u' ? 'csv' : 'm3u8', mode: 'each', dedupe: false };
  if (read.library && read.library.worthPicking) {
    const lib = read.library;
    item.library = lib;
    item.picks = lib.choices;
    // Smart playlists start unticked: their songs are only what they held when exported.
    item.selected = new Set(lib.choices.filter((p) => p.kind === 'user' && p.trackIds.length).map((p) => p.key));
    item.count = lib.trackCount;
    item.kind = read.format === 'rekordbox' ? 'rekordbox' : 'itunes_library';
  } else {
    item.tracks = read.library ? read.library.asOneList() : read.tracks;
    item.count = item.tracks.length;
    item.kind = read.format === 'rekordbox' ? 'rekordbox' : read.library ? (read.library.isLibrary ? 'itunes_library' : 'itunes_playlist') : read.source || read.format;
  }
  return item;
}

async function read(files) {
  if (busy) return;
  busy = true;
  errors.replaceChildren();
  let first = null;
  for (const file of files) {
    const signal = bar.start(file.name);
    try {
      const result = await readFile(file, { signal, onProgress: (done, total) => bar.update(done, total) });
      const item = itemOf(file, result);
      if (!item.count) throw new ToolError('empty');
      const box = card(item);
      list.prepend(box);
      first = first || box;
    } catch (e) {
      // A file cut short or unreadable gives its message and no card: nothing half-made to download.
      if (e instanceof ToolError && e.code === 'cancelled') break;
      fail(e, file.name);
    }
  }
  bar.stop();
  busy = false;
  if (first) first.scrollIntoView({ block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
}

dropArea(root.querySelector('.tool-drop'), read);
