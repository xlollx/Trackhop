// /duplicates: a playlist file read in the browser, its duplicates grouped (match.js) and the file
// written again without them. This script makes no request and stores nothing.
import { readFile, encode, safeName } from './playlist.js';
import { duplicateGroups, without, label } from './match.js';
import { ToolError } from './io.js';
import { strings, fmt, num, el, message, dropArea, progress, save } from './ui.js';

const T = strings.duplicates;
const root = document.getElementById('duplicates-tool');
const errors = root.querySelector('.tool-errors');
const bar = progress(root.querySelector('.tool-progress'));
const result = root.querySelector('.dup-result');
const TYPES = { csv: 'text/csv', m3u8: 'audio/x-mpegurl', xspf: 'application/xspf+xml', jspf: 'application/json', text: 'text/plain' };
const EXT = { csv: 'csv', m3u8: 'm3u8', xspf: 'xspf', jspf: 'jspf', text: 'txt' };
/** The output that keeps the kind of file that came in, where we write it. */
const SAME = { csv: 'csv', m3u: 'm3u8', pls: 'm3u8', xspf: 'xspf', json: 'jspf', text: 'text' };
let busy = false;

async function read(files) {
  if (busy || !files.length) return;
  busy = true;
  errors.replaceChildren();
  const file = files[0];
  try {
    const r = await readFile(file, { signal: bar.start(file.name), onProgress: (d, t) => bar.update(d, t) });
    const tracks = r.library ? r.library.asOneList() : r.tracks;
    if (!tracks || !tracks.length) throw new ToolError('empty');
    show(file.name, tracks, SAME[r.format] || 'csv');
  } catch (e) {
    const text = message(e, file.name);
    if (text) errors.append(el('p', { text }));
  }
  bar.stop();
  busy = false;
}

function show(fileName, tracks, start) {
  const base = fileName.replace(/\.[^.]+$/, '') || fileName;
  const groups = duplicateGroups(tracks);
  // The copy kept in each group: the first, unless the visitor picks another.
  const keep = groups.map((g) => g.keep);
  let output = start;
  result.querySelector('.dup-summary').textContent = groups.length
    ? fmt(T.found, { n: groups.length }).replace(String(groups.length), num(groups.length))
    : fmt(T.none, { name: fileName });
  const box = result.querySelector('.dup-groups');
  box.replaceChildren(...groups.map((g, gi) => {
    const all = [g.keep, ...g.extra];
    return el('fieldset', { class: 'dup-group' },
      el('legend', {}, el('b', { text: label(tracks[g.keep]) }), el('small', { class: 'tool-muted', text: ' · ' + (g.why === 'isrc' ? T.why_isrc : T.why_same) })),
      ...all.map((i) => el('label', { class: 'tool-chk' },
        el('input', { type: 'radio', name: 'dup' + gi, value: i, checked: i === g.keep, onchange: () => { keep[gi] = i; } }),
        el('span', { class: 't', text: fmt(T.position, { n: num(i + 1) }) + ' · ' + label(tracks[i]) },
          tracks[i].album ? el('small', { text: ' · ' + tracks[i].album }) : null),
        el('span', { class: 'n', text: T.keep }))));
  }));
  const select = el('select', { 'aria-label': strings.as, onchange: (e) => { output = e.target.value; } },
    ...Object.keys(EXT).map((o) => el('option', { value: o, selected: o === output, text: strings.outputs[o] })));
  const button = el('button', { type: 'button', class: 'tool-btn', text: T.download, disabled: !groups.length, onclick: () => {
    const drop = new Set();
    groups.forEach((g, gi) => [g.keep, ...g.extra].forEach((i) => { if (i !== keep[gi]) drop.add(i); }));
    const clean = without(tracks, drop);
    save(new Blob([encode(output, base, clean)], { type: TYPES[output] + ';charset=utf-8' }), safeName(base) + '.' + EXT[output]);
  } });
  result.querySelector('.dup-save').replaceChildren(button, el('span', { text: strings.as }), select);
  result.hidden = false;
  result.scrollIntoView({ block: 'nearest' });
}

dropArea(root.querySelector('.tool-drop'), read);
