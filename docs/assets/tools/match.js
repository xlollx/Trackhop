// Songs compared as the Trackhop app compares them (Matcher.kt and Duplicates.kt, ported): the same
// title and version and the same artists, whatever the capitals, accents, "feat." or "Remastered",
// or the same ISRC. Used by /compare (two playlists) and /duplicates (one playlist), in the browser.
import { artistLine } from './playlist.js';

const BRACKET_NOISE = /\s*[(\[][^)\]]*\b(feat\.?|ft\.?|featuring|remaster\w*|rimasterizzat\w*|version|versione|edit|(?<!re)mix|live|mono|stereo|deluxe|bonus|explicit|clean|official|video|audio|lyrics?|hd|4k|visuali[sz]er|radio|single|album|original|from|soundtrack|ost|anniversary|edition|\d{4})\b[^)\]]*[)\]]/giu;
const CREDIT_BRACKET = /\s*[(\[]\s*(with|con|w\/|prod\.?|produced\s+by)\s[^)\]]*[)\]]/giu;
const FEAT_TAIL = /\s+([-–—]\s+)?(feat\.?|ft\.?|featuring|w\/|prod\.?\s+by|prod\.)\s+.*$/iu;
const DASH_TAIL = /\s*[-–—]\s*(((\d{4}|digital(ly)?|single|new(ly)?|\d+[- ]?bit|versi[oó]n)\s+)*remast[eé]r.*|radio edit.*|single version.*|album version.*|live.*|mono.*|stereo.*|deluxe.*|bonus track.*|extended.*|original mix.*|rimasterizzat.*|versione\s.*|[^-–—]*\bversion|(from|de|dal)\s.*|\d{4}\s.*)$/iu;
const ARTIST_TAIL = /\s+(feat\.?|ft\.?|featuring|vs\.?)\s+.*$/iu;
const ARTIST_PARTS = /\s*(?:,|&|\+|\/|\s+x\s+|\s+and\s+|\s+e\s+)\s*/iu;
const NON_ALNUM = /[^\p{L}\p{N}\s]/gu;
const ACRONYM = /(?<![\p{L}\p{N}.])\p{L}(?:\.\p{L})+\.?(?![\p{L}\p{N}])/gu;
const APOSTROPHES = /[’'`´]/g;
const SPACES = /\s+/g;
const VERSION_MARKERS = /\b(live|karaoke|instrumental|cover|tribute|sped\s*up|slowed|nightcore|8d|acoustic|acustic[ao]?|unplugged|demo|remix|rmx|a\s*cappella|acapella|reverb|lofi|lo-fi|parody|reaction|remaster(ed)?|mono|stereo)\b/giu;
const CONCERT = /\b(festival|concerts?|sessions?|lounge|mtv|on tour|tour edition|on stage|en vivo|ao vivo|dal vivo|en directo|en concierto|en concert|in concert|konzert|live at|live from|live in|live on)\b/iu;
const SUBTITLE = /\s*(?:[(\[]|\s[-–—]\s).*$/u;
const PART = /\b(?:pts?|parts?|parte|partie|teil)\s+(\d+|[ivxlc]+)\b/gu;
/** Words that do not make another version: the same song remastered, or in mono. */
const NEUTRAL = new Set(['remaster', 'mono', 'stereo']);
const TOLERANCE_MS = 8000;
const TOLERANCE_RATIO = 0.05;

export const stripDiacritics = (s) => s.normalize('NFD').replace(/\p{Mn}+/gu, '');
const joinInitials = (s) => s.replace(ACRONYM, (m) => m.replace(/\./g, ''));

function roman(s) {
  const v = { i: 1, v: 5, x: 10, l: 50, c: 100 };
  let total = 0;
  for (let i = 0; i < s.length; i++) {
    const a = v[s[i]], b = v[s[i + 1]] || 0;
    if (!a) return null;
    total += a < b ? -a : a;
  }
  return total >= 1 && total <= 199 && toRoman(total) === s ? total : null;
}
function toRoman(n) {
  let out = '';
  for (const [value, sym] of [[100, 'c'], [90, 'xc'], [50, 'l'], [40, 'xl'], [10, 'x'], [9, 'ix'], [5, 'v'], [4, 'iv'], [1, 'i']]) {
    while (n >= value) { out += sym; n -= value; }
  }
  return out;
}

export function normalizeTitle(s) {
  let t = stripDiacritics(String(s || '')).toLowerCase();
  t = t.replace(BRACKET_NOISE, ' ').replace(CREDIT_BRACKET, ' ').replace(FEAT_TAIL, ' ').replace(DASH_TAIL, ' ');
  t = t.replace(/&/g, ' and ').replace(/\$/g, 's').replace(APOSTROPHES, '');
  t = joinInitials(t).replace(NON_ALNUM, ' ').replace(SPACES, ' ').trim();
  // "Oxygene, Pt. 4", "Oxygene (Part IV)" and "Oxygene Part 4" are one title.
  return t.replace(PART, (m, n) => {
    const k = /^\d+$/.test(n) ? Number(n) : roman(n);
    return k == null ? m : 'part ' + k;
  });
}

export function normalizeArtist(s) {
  let a = stripDiacritics(String(s || '')).toLowerCase().replace(ARTIST_TAIL, ' ');
  a = a.replace(/&/g, ' and ').replace(/\$/g, 's').replace(APOSTROPHES, '');
  a = joinInitials(a).replace(NON_ALNUM, ' ').replace(SPACES, ' ').trim();
  return a.replace(/^the /, '').trim();
}

/** The version words of a title ("live", "remix", "acoustic"…), remastered and mono left out. */
export function versionOf(title) {
  const words = new Set();
  for (const m of stripDiacritics(String(title || '')).toLowerCase().matchAll(VERSION_MARKERS)) {
    const w = m[1].replace(/\s+/g, '').replace(/-/g, '').replace(/ed$/, '').replace(/ao$/, '').replace(/a$/, '');
    if (!NEUTRAL.has(w)) words.add(w);
  }
  const sub = (SUBTITLE.exec(String(title || '')) || [''])[0];
  if (CONCERT.test(stripDiacritics(sub))) words.add('live');
  return [...words].sort().join(',');
}

/** Each artist of a song, split and normalized: "Beyoncé & JAY-Z" → ["beyonce", "jay z"]. */
export function artistSet(t) {
  const out = new Set();
  for (const a of t.artists || []) {
    for (const p of String(a).split(ARTIST_PARTS)) {
      const n = normalizeArtist(p);
      if (n) out.add(n);
    }
  }
  return out;
}

export const isrcOf = (t) => {
  const s = t.isrc ? String(t.isrc).trim().toUpperCase() : '';
  return s.length >= 10 ? s : null;
};

export function sameDuration(a, b) {
  if (!(a > 0) || !(b > 0)) return true;
  return Math.abs(a - b) <= Math.max(TOLERANCE_MS, Math.trunc(Math.max(a, b) * TOLERANCE_RATIO));
}

/** What the comparisons need of a song, worked out once. */
export function keyed(t, index) {
  const artists = artistSet(t);
  return {
    track: t, index, isrc: isrcOf(t), dur: t.durationMs || 0,
    title: normalizeTitle(t.title) + '|' + versionOf(t.title),
    artists, artistKey: [...artists].sort().join(','),
  };
}

const shareArtist = (a, b) => !a.artists.size || !b.artists.size || [...a.artists].some((x) => b.artists.has(x));

/**
 * The songs that are in a playlist more than once, as the app's "Remove duplicates" finds them:
 * the same ISRC, or the same title, version and artists with a compatible length. "Throne" and
 * "Throne - Live" are two songs. Each group is in playlist order, its first song the one to keep.
 * [{ keep: index, extra: [index…], why: 'isrc' | 'same' }], indexes into [tracks].
 */
export function duplicateGroups(tracks) {
  const byKey = new Map(), keyOfIsrc = new Map();
  tracks.forEach((t, i) => {
    const k = keyed(t, i);
    const text = k.title + '|' + k.artistKey;
    const key = (k.isrc && keyOfIsrc.get(k.isrc)) || text;
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(k);
    if (k.isrc && !keyOfIsrc.has(k.isrc)) keyOfIsrc.set(k.isrc, key);
  });
  const groups = [];
  for (const list of byKey.values()) {
    if (list.length < 2) continue;
    // The same words but lengths far apart: another recording (a long live take), not a copy.
    const clusters = [];
    for (const k of list) {
      const home = clusters.find((c) => c.some((o) => (o.isrc && o.isrc === k.isrc) || sameDuration(o.dur, k.dur)));
      if (home) home.push(k); else clusters.push([k]);
    }
    for (const c of clusters) {
      if (c.length < 2) continue;
      const first = c[0];
      groups.push({
        keep: first.index,
        extra: c.slice(1).map((k) => k.index),
        why: c.slice(1).every((k) => k.isrc && k.isrc === first.isrc) ? 'isrc' : 'same',
      });
    }
  }
  return groups.sort((a, b) => a.keep - b.keep);
}

/** [tracks] without the songs at [drop] (a Set of indexes), in their order. */
export const without = (tracks, drop) => tracks.filter((_, i) => !drop.has(i));

/**
 * Two playlists side by side: { onlyA, onlyB, both } (both: pairs [a, b]). A song of A is in B when
 * B has one with the same ISRC, or with the same title and version, one artist in common and a
 * compatible length; each song of B answers for one song of A only, so a song twice in A and once
 * in B is once "in both" and once "only in A".
 */
export function compare(a, b) {
  const ka = a.map(keyed), kb = b.map(keyed);
  const used = new Set();
  const byIsrc = new Map(), byTitle = new Map();
  kb.forEach((k) => {
    if (k.isrc) { if (!byIsrc.has(k.isrc)) byIsrc.set(k.isrc, []); byIsrc.get(k.isrc).push(k); }
    if (!byTitle.has(k.title)) byTitle.set(k.title, []);
    byTitle.get(k.title).push(k);
  });
  const free = (list, ok) => (list || []).find((k) => !used.has(k.index) && ok(k));
  const both = [], onlyA = [];
  // ISRC first, for every song, so a looser match never takes the exact one of another song.
  const pending = [];
  for (const x of ka) {
    const hit = x.isrc && free(byIsrc.get(x.isrc), () => true);
    if (hit) { used.add(hit.index); both.push([x.track, hit.track]); } else pending.push(x);
  }
  for (const x of pending) {
    const hit = free(byTitle.get(x.title), (k) => shareArtist(x, k) && sameDuration(x.dur, k.dur));
    if (hit) { used.add(hit.index); both.push([x.track, hit.track]); } else onlyA.push(x.track);
  }
  const onlyB = kb.filter((k) => !used.has(k.index)).map((k) => k.track);
  return { onlyA, onlyB, both };
}

/** "Artist - Title", as the lists show a song. */
export const label = (t) => (t.artists && t.artists.length ? artistLine(t) + ' - ' : '') + t.title;
