// /compare: two playlist files read in the browser and set side by side (match.js). This script
// makes no request and stores nothing.
import { readFile, encode, safeName } from './playlist.js';
import { compare, label } from './match.js';
import { ToolError } from './io.js';
import { strings, fmt, num, el, message, dropArea, progress, save, tabs } from './ui.js';

const T = strings.compare;
const root = document.getElementById('compare-tool');
const errors = root.querySelector('.tool-errors');
const bar = progress(root.querySelector('.tool-progress'));
const result = root.querySelector('.cmp-result');
const TYPES = { csv: 'text/csv', m3u8: 'audio/x-mpegurl', xspf: 'application/xspf+xml', jspf: 'application/json', text: 'text/plain' };
const EXT = { csv: 'csv', m3u8: 'm3u8', xspf: 'xspf', jspf: 'jspf', text: 'txt' };
const sides = { a: null, b: null };
let busy = false, shown = 'onlyA', output = 'csv', last = null;

const songs = (n) => fmt(strings.songs_n, { n }).replace(String(n), num(n));

/** The tracks of a file as one list: a library or a playlist export alike. */
function tracksOf(read) {
  return read.library ? read.library.asOneList() : read.tracks;
}

async function pick(side, files) {
  if (busy || !files.length) return;
  busy = true;
  errors.replaceChildren();
  const file = files[0];
  const drop = root.querySelector(`.tool-drop[data-side="${side}"]`);
  try {
    const read = await readFile(file, { signal: bar.start(file.name), onProgress: (d, t) => bar.update(d, t) });
    const tracks = tracksOf(read);
    if (!tracks || !tracks.length) throw new ToolError('empty');
    sides[side] = { name: file.name.replace(/\.[^.]+$/, '') || file.name, tracks };
    const info = drop.querySelector('.cmp-file');
    info.textContent = file.name + ' · ' + songs(tracks.length);
    info.hidden = false;
    drop.querySelector('.cmp-hint').hidden = true;
    drop.querySelector('.tool-file span').textContent = strings.another;
  } catch (e) {
    const text = message(e, file.name);
    if (text) errors.append(el('p', { text }));
  }
  bar.stop();
  busy = false;
  render();
}

function render() {
  if (!sides.a || !sides.b) { result.hidden = true; return; }
  last = compare(sides.a.tracks, sides.b.tracks);
  const lists = { onlyA: last.onlyA, onlyB: last.onlyB, both: last.both.map(([x]) => x) };
  const names = { onlyA: T.only_a, onlyB: T.only_b, both: T.both };
  const list = result.querySelector('.tool-tabs');
  list.replaceChildren(...Object.keys(lists).map((k) => el('button', {
    type: 'button', role: 'tab', class: 'tool-tab', 'data-value': k, 'aria-selected': String(k === shown), tabindex: k === shown ? 0 : -1,
    text: names[k] + ' · ' + num(lists[k].length),
  })));
  if (!list.dataset.ready) { list.dataset.ready = '1'; tabs(list, (v) => { shown = v; show(); }); }
  result.hidden = false;
  show();
}

function show() {
  const lists = { onlyA: last.onlyA, onlyB: last.onlyB, both: last.both.map(([x]) => x) };
  const tracks = lists[shown];
  const ol = result.querySelector('.cmp-list');
  ol.replaceChildren(...tracks.map((t) => el('li', {}, el('span', { text: label(t) }), t.album ? el('small', { class: 'tool-muted', text: ' · ' + t.album }) : null)));
  const note = result.querySelector('.cmp-note');
  note.textContent = !last.onlyA.length && !last.onlyB.length ? T.same : tracks.length ? (shown === 'both' ? T.both_note : '') : T.nothing;
  const box = result.querySelector('.cmp-save');
  const select = el('select', { 'aria-label': strings.as, onchange: (e) => { output = e.target.value; } },
    ...Object.keys(EXT).map((o) => el('option', { value: o, selected: o === output, text: strings.outputs[o] })));
  const name = { onlyA: T.only_a, onlyB: T.only_b, both: T.both }[shown];
  const button = el('button', { type: 'button', class: 'tool-btn', text: strings.download, disabled: !tracks.length, onclick: () => {
    const title = sides.a.name + ' - ' + name;
    save(new Blob([encode(output, title, tracks)], { type: TYPES[output] + ';charset=utf-8' }), safeName(title) + '.' + EXT[output]);
  } });
  box.replaceChildren(button, el('span', { text: strings.as }), select);
}

for (const side of ['a', 'b']) dropArea(root.querySelector(`.tool-drop[data-side="${side}"]`), (files) => pick(side, files));
