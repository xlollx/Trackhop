// node --test docs/assets/tools/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeTitle, versionOf, duplicateGroups, compare, without, label } from './match.js';
import { buttonCode, target } from './button.js';
import { track } from './playlist.js';

const t = (artist, title, ms = 0, isrc = null, album = '') => track(title, artist ? [artist] : [], album, ms, isrc);

test('titles: feat., Remastered and parts fold, versions stay', () => {
  assert.equal(normalizeTitle('Kerala (feat. Someone) - 2011 Remaster'), 'kerala');
  assert.equal(normalizeTitle('Oxygène, Pt. 4'), normalizeTitle('Oxygene (Part IV)'));
  assert.equal(versionOf('Throne - Live at the Royal Albert Hall'), 'live');
  assert.equal(versionOf('Song (Remastered)'), '');
  assert.equal(versionOf('Song (Acoustic)'), 'acoustic');
});

test('duplicates: same ISRC, same words, never another version', () => {
  const list = [
    t('Bonobo', 'Kerala', 245000, 'GBCFB1700001'),
    t('Bryan Adams', 'Throne', 200000),
    t('Bonobo', 'Kerala - Single Version', 247000, 'GBCFB1700001'),
    t('bryan adams', 'Throne (Remastered 2011)', 201000),
    t('Bryan Adams', 'Throne - Live', 205000),
    t('Bonobo', 'Kerala', 420000),
  ];
  const groups = duplicateGroups(list);
  assert.deepEqual(groups.map((g) => [g.keep, g.extra, g.why]), [[0, [2], 'isrc'], [1, [3], 'same']]);
  const drop = new Set(groups.flatMap((g) => g.extra));
  assert.deepEqual(without(list, drop).map(label), ['Bonobo - Kerala', 'Bryan Adams - Throne', 'Bryan Adams - Throne - Live', 'Bonobo - Kerala']);
});

test('duplicates: a length far apart is another recording, an unknown one is not', () => {
  assert.equal(duplicateGroups([t('A', 'Song', 240000), t('A', 'Song', 420000)]).length, 0);
  assert.equal(duplicateGroups([t('A', 'Song', 240000), t('A', 'Song', 0)]).length, 1);
});

test('compare: what is missing on each side, a repeat counted once each', () => {
  const a = [t('Bonobo', 'Kerala', 245000), t('Little Simz', 'Gorilla'), t('Khruangbin', 'Time (You and I)'), t('Bonobo', 'Kerala', 245000)];
  const b = [t('Bonobo, Someone', 'Kerala (feat. Someone)', 246000), t('Little Simz', 'Gorilla - Live'), t('Fred again..', 'Delilah')];
  const r = compare(a, b);
  assert.deepEqual(r.both.map(([x, y]) => [label(x), label(y)]), [['Bonobo - Kerala', 'Bonobo, Someone - Kerala (feat. Someone)']]);
  assert.deepEqual(r.onlyA.map(label), ['Little Simz - Gorilla', 'Khruangbin - Time (You and I)', 'Bonobo - Kerala']);
  assert.deepEqual(r.onlyB.map(label), ['Little Simz - Gorilla - Live', 'Fred again.. - Delilah']);
});

test('compare: the ISRC wins over the words, and no artist in common is another song', () => {
  const r = compare([t('X', 'Intro', 0, 'USAAA0000001'), t('Y', 'Intro')], [t('Z', 'Different title', 0, 'USAAA0000001'), t('W', 'Intro')]);
  assert.equal(r.both.length, 1);
  assert.equal(r.onlyA.length, 1);
  assert.equal(r.onlyB.length, 1);
});

test('button: a plain link with its style inline, the song link encoded', () => {
  const href = target('Listen https://open.spotify.com/track/0ofHAoxe9vBkTCp2UQIavz?si=abc');
  assert.equal(href, 'https://trackhop.org/link?u=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F0ofHAoxe9vBkTCp2UQIavz');
  assert.equal(target('https://www.deezer.com/track/63480987', 'it'), 'https://trackhop.org/it/link?u=https%3A%2F%2Fwww.deezer.com%2Ftrack%2F63480987');
  const code = buttonCode(href, { style: 'light', text: 'Ascolta <ovunque>' });
  assert.match(code, /^<a href="https:\/\/trackhop\.org\/link\?u=[^"]+" target="_blank" rel="noopener" style="[^"]+"><svg/);
  assert.ok(code.includes('Ascolta &lt;ovunque&gt;</a>'));
  assert.ok(!/<script|<img|src=/.test(code));
  assert.throws(() => target('not a link'));
});
