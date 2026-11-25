// /link: one song or album link in, a button per service out. Everything here runs in the
// visitor's browser (and in node, for the tests). The only requests are the public catalogue
// lookups named below; nothing is stored and nothing goes to Trackhop.
import { ToolError } from './io.js';

export const TIMEOUT = 8000;
/** Two lengths are the same recording when they are this close. */
export const DURATION_WINDOW_MS = 3000;

// ---------------------------------------------------------------------------------------------
// Reading the pasted link
// ---------------------------------------------------------------------------------------------

/** Short links only the service's own app or a redirect can open: a page cannot follow them. */
const SHORT = new Set(['spotify.link', 'spotify.app.link', 'link.deezer.com', 'deezer.page.link', 'dzr.page.link',
  'apple.co', 'a.co', 'amzn.to', 'amzn.eu', 'bit.ly', 't.co', 'tinyurl.com', 'goo.gl', 'is.gd']);
const APPLE = new Set(['music.apple.com', 'itunes.apple.com', 'geo.music.apple.com', 'geo.itunes.apple.com', 'beta.music.apple.com', 'embed.music.apple.com']);
const MANY = { playlist: 'playlist', artist: 'artist', show: 'podcast', episode: 'podcast', podcast: 'podcast' };

const refuse = (code, detail) => { throw new ToolError(code, detail); };
const digits = (s) => /^\d{1,20}$/.test(s || '');

/**
 * What a pasted text points at: { service: 'apple' | 'deezer' | 'spotify', kind: 'song' | 'album', id, country, url }.
 * Anything else throws a ToolError: empty, not-link, short, site, many (detail: playlist, artist, podcast)
 * or other (a page of a known service that is neither a song nor an album).
 */
export function parseLink(input) {
  let text = String(input == null ? '' : input).trim();
  if (!text) refuse('empty');
  const uri = /^spotify:(track|album|playlist|artist|show|episode):([A-Za-z0-9]+)$/.exec(text);
  if (uri) text = 'https://open.spotify.com/' + uri[1] + '/' + uri[2];
  // A share text ("Listen to this https://…") carries the link inside it.
  const inside = /https?:\/\/[^\s<>"']+/i.exec(text);
  if (inside) text = inside[0];
  else if (/^[a-z0-9-]+(\.[a-z0-9-]+)+(\/\S*)?$/i.test(text)) text = 'https://' + text;
  else refuse('not-link');
  let u;
  try { u = new URL(text); } catch (e) { refuse('not-link'); }
  const host = u.hostname.toLowerCase().replace(/^www\./, '');
  const parts = u.pathname.split('/').filter(Boolean);
  if (SHORT.has(host)) refuse('short');
  if (host === 'open.spotify.com' || host === 'play.spotify.com') return spotifyLink(parts);
  if (host === 'deezer.com') return deezerLink(parts);
  if (host === 'podcasts.apple.com') refuse('many', 'podcast');
  if (APPLE.has(host)) return appleLink(u, parts);
  return refuse('site');
}

function spotifyLink(parts) {
  let country = null;
  const intl = /^intl-([a-z]{2})$/i.exec(parts[0] || '');
  if (intl) { country = intl[1].toLowerCase(); parts = parts.slice(1); }
  if (parts[0] === 'embed') parts = parts.slice(1);
  if (parts[0] === 'user' && parts[2] === 'playlist') refuse('many', 'playlist');
  const [type, id] = parts;
  if (MANY[type]) refuse('many', MANY[type]);
  if ((type !== 'track' && type !== 'album') || !/^[A-Za-z0-9]{22}$/.test(id || '')) refuse('other');
  return { service: 'spotify', kind: type === 'track' ? 'song' : 'album', id, country, url: 'https://open.spotify.com/' + type + '/' + id };
}

function deezerLink(parts) {
  if (/^[a-z]{2}(-[a-z]{2})?$/i.test(parts[0] || '')) parts = parts.slice(1);
  const [type, id] = parts;
  if (MANY[type]) refuse('many', MANY[type]);
  if ((type !== 'track' && type !== 'album') || !digits(id)) refuse('other');
  return { service: 'deezer', kind: type === 'track' ? 'song' : 'album', id, country: null, url: 'https://www.deezer.com/' + type + '/' + id };
}

function appleLink(u, parts) {
  let country = 'us';
  if (/^[a-z]{2}$/i.test(parts[0] || '')) { country = parts[0].toLowerCase(); parts = parts.slice(1); }
  // Old iTunes links write the number as "id594061854".
  if (parts.length > 1) parts[parts.length - 1] = parts[parts.length - 1].replace(/^id(?=\d)/, '');
  const type = parts[0], last = parts[parts.length - 1];
  if (MANY[type]) refuse('many', MANY[type]);
  const base = 'https://music.apple.com/' + country + '/';
  if (type === 'song' && parts.length > 1 && digits(last)) {
    return { service: 'apple', kind: 'song', id: last, country, url: base + parts.join('/') };
  }
  if (type === 'album' && parts.length > 1 && digits(last)) {
    const track = u.searchParams.get('i');
    const url = base + parts.join('/');
    if (digits(track)) return { service: 'apple', kind: 'song', id: track, country, url: url + '?i=' + track };
    return { service: 'apple', kind: 'album', id: last, country, url };
  }
  return refuse('other');
}

/** The link as the page shows it: without its scheme. */
export const display = (url) => url.replace(/^https?:\/\/(www\.)?/, '');

// ---------------------------------------------------------------------------------------------
// Comparing what two catalogues call the same thing
// ---------------------------------------------------------------------------------------------

const CREDIT = /^(feat|ft|featuring|with)\b/i;
const EDITION = /\b(remaster(ed)?|(album|single|original|lp|studio) version|mono|stereo|deluxe|expanded|anniversary|bonus|edition|explicit|clean)\b/i;
const extra = (s) => CREDIT.test(s.trim()) || EDITION.test(s);

/**
 * The title without what the services add to it: "(feat. X)", "- 2004 Remaster", "(Deluxe Edition)",
 * "- Single". A live take, a remix or a radio edit keeps its words: those are other recordings.
 */
export function plainTitle(title) {
  let t = String(title || '');
  t = t.replace(/\s*[([]([^()[\]]*)[)\]]/g, (all, inner) => (extra(inner) ? '' : all));
  for (;;) {
    const cut = /\s+[-–—]\s+([^-–—]+)$/.exec(t);
    if (!cut || !(extra(cut[1]) || /^(single|ep)$/i.test(cut[1].trim()))) break;
    t = t.slice(0, cut.index);
  }
  t = t.replace(/\s+(feat\.?|ft\.?|featuring)\s+.*$/i, '');
  return t.trim() || String(title || '').trim();
}

const fold = (s) => String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase()
  .replace(/[&+]/g, ' and ').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/** A title as it is compared: plain, without accents, case and punctuation. */
export const titleKey = (title) => fold(plainTitle(title)) || String(title || '').trim().toLowerCase();

const artistKey = (name) => fold(name).replace(/^the /, '');
const firstArtist = (name) => artistKey(String(name || '').split(/\s*(?:,|;|\/|&|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*/i)[0]);

/** The same artist: the names agree, or the first name of each does ("A & B" and "A feat. B"). */
export function sameArtist(a, b) {
  const ka = artistKey(a), kb = artistKey(b);
  if (!ka || !kb) return false;
  return ka === kb || firstArtist(a) === firstArtist(b);
}

const gap = (want, c) => {
  if (want.kind === 'album') return want.count && c.count ? Math.abs(want.count - c.count) : Infinity;
  return want.durationMs && c.durationMs ? Math.abs(want.durationMs - c.durationMs) : Infinity;
};
const close = (want, c) => (want.kind === 'album' ? gap(want, c) === 0 : gap(want, c) <= DURATION_WINDOW_MS);

/** The candidates with the title and the artist of [want], the closest in length first (for an album, in number of songs). */
export function matches(want, candidates) {
  const key = titleKey(want.title);
  return candidates.filter((c) => titleKey(c.title) === key && sameArtist(c.artist, want.artist))
    .map((c, at) => ({ c, at })).sort((x, y) => (gap(want, x.c) - gap(want, y.c) || 0) || x.at - y.at).map((x) => x.c);
}

/**
 * The candidate that is [want] on another service. Title and artist must agree; among those the
 * closest length wins (for an album, the number of songs). 'exact' only when the length agrees
 * too, otherwise 'guess'. null when no candidate has the title and the artist.
 */
export function pickMatch(want, candidates) {
  const best = matches(want, candidates)[0];
  return best ? { item: best, mark: close(want, best) ? 'exact' : 'guess' } : null;
}

// ---------------------------------------------------------------------------------------------
// The services
// ---------------------------------------------------------------------------------------------

export const SERVICES = [
  { id: 'apple', name: 'Apple Music' },
  { id: 'deezer', name: 'Deezer' },
  { id: 'spotify', name: 'Spotify' },
  { id: 'ytmusic', name: 'YouTube Music' },
  { id: 'youtube', name: 'YouTube' },
  { id: 'tidal', name: 'TIDAL' },
  { id: 'amazon', name: 'Amazon Music' },
  { id: 'soundcloud', name: 'SoundCloud' },
];
export const serviceName = (id) => (SERVICES.find((s) => s.id === id) || { name: id }).name;

const path = (q) => encodeURIComponent(q);
const plus = (q) => encodeURIComponent(q).replace(/%20/g, '+');

/** The service's own search, with [q] already typed in. */
export function searchUrl(id, q) {
  switch (id) {
    case 'apple': return 'https://music.apple.com/search?term=' + plus(q);
    case 'deezer': return 'https://www.deezer.com/search/' + path(q);
    case 'spotify': return 'https://open.spotify.com/search/' + path(q);
    case 'ytmusic': return 'https://music.youtube.com/search?q=' + plus(q);
    case 'youtube': return 'https://www.youtube.com/results?search_query=' + plus(q);
    case 'tidal': return 'https://tidal.com/search?q=' + plus(q);
    case 'amazon': return 'https://music.amazon.com/search/' + path(q);
    case 'soundcloud': return 'https://soundcloud.com/search?q=' + plus(q);
    default: throw new Error('unknown service ' + id);
  }
}

/** What the searches are filled in with: "artist title". */
export const queryOf = (item) => [item.artist, plainTitle(item.title)].filter(Boolean).join(' ');

/**
 * One entry per service, in the order of SERVICES. [found] maps a service to { url, mark, note }
 * for the ones that open the song itself; every other service is a search.
 */
export function buildServices(item, found) {
  const q = queryOf(item);
  return SERVICES.map((s) => (found[s.id]
    ? { id: s.id, name: s.name, ...found[s.id] }
    : { id: s.id, name: s.name, url: searchUrl(s.id, q), mark: 'search', note: null }));
}

/** The plain text "Copy all links" puts on the clipboard. [words] are the page's: { guess, search }. */
export function copyText(result, words) {
  const head = [[result.title, result.artist].filter(Boolean).join(' – ')];
  if (result.kind === 'song' && result.album) head.push(result.album);
  const lines = result.services.map((s) => {
    const tag = s.mark === 'guess' ? ' (' + words.guess + ')' : s.mark === 'search' ? ' (' + words.search + ')' : '';
    return s.name + tag + ': ' + s.url;
  });
  return head.join('\n') + '\n\n' + lines.join('\n');
}

/** 257000 -> "4:17". */
export function clock(ms) {
  const s = Math.round(ms / 1000);
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

// ---------------------------------------------------------------------------------------------
// Asking: fetch for the services that allow it, a script tag (JSONP) for Deezer
// ---------------------------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * JSON from [url]. 400 and 404 are "not-found"; no answer within [timeout], a network error or a
 * body that is not JSON are "no-answer" with the service's [name]. A 403, 429 or 5xx is asked again
 * up to [retries] times within the same timeout: the iTunes search refuses a request now and then.
 * [signal] aborted ends it at once, as "no-answer".
 */
export async function getJson(url, { name = '', timeout = TIMEOUT, retries = 0, fetch = globalThis.fetch, wait = sleep, signal } = {}) {
  const control = new AbortController();
  const stop = () => control.abort();
  const timer = setTimeout(stop, timeout);
  if (signal) { if (signal.aborted) stop(); else signal.addEventListener('abort', stop); }
  try {
    for (let attempt = 0; ; attempt++) {
      let res;
      try {
        res = await fetch(url, { signal: control.signal, credentials: 'omit' });
      } catch (e) {
        throw new ToolError('no-answer', name);
      }
      if (res.status === 400 || res.status === 404) throw new ToolError('not-found', name);
      if (res.ok) {
        try { return await res.json(); } catch (e) { throw new ToolError('no-answer', name); }
      }
      if (attempt >= retries) throw new ToolError('no-answer', name);
      await wait(700 * (attempt + 1));
      if (control.signal.aborted) throw new ToolError('no-answer', name);
    }
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', stop);
  }
}

let serial = 0;

/**
 * Deezer's API has no CORS headers, so it is called the way it offers: a script tag whose answer
 * calls a function. A script that fails to load, or no call within [timeout], is "no-answer";
 * Deezer's own "no data" is "not-found"; [signal] aborted is "no-answer". The tag and the function
 * are removed either way.
 */
export function jsonp(url, { name = '', timeout = TIMEOUT, doc = globalThis.document, scope = globalThis, signal } = {}) {
  return new Promise((resolve, reject) => {
    if (signal && signal.aborted) { reject(new ToolError('no-answer', name)); return; }
    const cb = 'trackhopLink' + (++serial);
    const script = doc.createElement('script');
    let done = false;
    const finish = (late) => {
      done = true;
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', give);
      script.remove();
      // An answer that comes after the timeout must find a function, or it is an error in the console.
      if (late) scope[cb] = () => { delete scope[cb]; }; else delete scope[cb];
    };
    const give = () => { if (!done) { finish(true); reject(new ToolError('no-answer', name)); } };
    const timer = setTimeout(give, timeout);
    if (signal) signal.addEventListener('abort', give);
    scope[cb] = (data) => {
      if (done) return;
      finish(false);
      const err = data && data.error;
      if (err) reject(new ToolError(err.code === 800 ? 'not-found' : 'no-answer', name));
      else resolve(data);
    };
    script.onerror = () => { if (!done) { finish(false); reject(new ToolError('no-answer', name)); } };
    script.async = true;
    script.src = url + (url.includes('?') ? '&' : '?') + 'output=jsonp&callback=' + cb;
    doc.head.append(script);
  });
}

// ---------------------------------------------------------------------------------------------
// Covers: the same artwork on two services
// ---------------------------------------------------------------------------------------------

/** A cover is compared as a grid of this many cells a side. */
export const COVER_GRID = 16;
/**
 * Two covers this close are the same artwork; and no other artist's cover may be nearly as close.
 * Measured on real covers (Spotify's 300 px picture against Deezer's 56 px and Apple's 100 px,
 * 17 songs and albums, about 1700 pairs):
 *   the same artwork        0.002 to 0.10  (0.002-0.008 the same file; 0.02-0.10 another scan,
 *                                           crop or remaster: A Night at the Opera 0.08-0.10,
 *                                           Whenever You Need Somebody 2022 against 1987 0.10)
 *   another cover           0.19 and more  (the same photo redrawn: Rumours Deluxe 0.19,
 *                                           Thriller 25 0.22; unrelated ones 0.29 to 1.4)
 *   the artwork borrowed by another artist: a piano cover of The Dark Side of the Moon 0.066
 *                                           against the original's 0.008, hence the margin.
 * The plain colour difference was no use: two dark covers are 10 apart, one cover in two
 * masterings 32.
 */
export const COVER_SAME = 0.14;
/** Another artist's cover must be at least this much farther, and at least twice as far. */
export const COVER_MARGIN = 0.05;
/** At most this many pictures are fetched for the title's candidates, and this many more for the artists' albums. */
export const COVER_MAX = 60;
export const COVER_MORE = 40;
export const COVER_PARALLEL = 8;

/**
 * What a cover looks like from far away: [rgba] ([w] x [h] pixels, four bytes each) averaged into
 * a 16 x 16 grid of colours (768 numbers). null for a picture with nothing in it (one flat
 * colour, or what a browser that guards its canvas hands out): it would look like every other one.
 */
export function coverSignature(rgba, w, h) {
  const n = COVER_GRID;
  if (!rgba || w < n || h < n || rgba.length < w * h * 4) return null;
  const sig = new Float32Array(n * n * 3);
  let low = 255, high = 0;
  for (let cy = 0; cy < n; cy++) {
    const y0 = Math.floor(cy * h / n), y1 = Math.floor((cy + 1) * h / n);
    for (let cx = 0; cx < n; cx++) {
      const x0 = Math.floor(cx * w / n), x1 = Math.floor((cx + 1) * w / n);
      let r = 0, g = 0, b = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0, at = (y * w + x0) * 4; x < x1; x++, at += 4) { r += rgba[at]; g += rgba[at + 1]; b += rgba[at + 2]; }
      }
      const count = (y1 - y0) * (x1 - x0), cell = (cy * n + cx) * 3;
      sig[cell] = r / count; sig[cell + 1] = g / count; sig[cell + 2] = b / count;
      const grey = (r * 0.299 + g * 0.587 + b * 0.114) / count;
      if (grey < low) low = grey;
      if (grey > high) high = grey;
    }
  }
  return high - low < 6 ? null : sig;
}

/**
 * How far two signatures are: 0 the same picture, about 1 two unrelated ones. It is 1 minus the
 * correlation of the two grids, each colour around its own mean, so a cover that is a little
 * darker, lighter or tinted on one service is still itself.
 */
export function coverDistance(a, b) {
  const n = a.length / 3, ma = [0, 0, 0], mb = [0, 0, 0];
  for (let i = 0; i < a.length; i++) { ma[i % 3] += a[i] / n; mb[i % 3] += b[i] / n; }
  let ab = 0, aa = 0, bb = 0;
  for (let i = 0; i < a.length; i++) {
    const x = a[i] - ma[i % 3], y = b[i] - mb[i % 3];
    ab += x * y; aa += x * x; bb += y * y;
  }
  return aa && bb ? 1 - ab / Math.sqrt(aa * bb) : 1;
}

/**
 * Of [scored] ({ artist, d }: a candidate's artist and its cover's distance from the wanted one;
 * d null when there was no picture) the one that is the wanted cover, or null: the closest must be
 * the same artwork, and no other artist's cover may be nearly as close. Covers of the same artist
 * may be: the album and its single share one.
 */
export function pickCover(scored) {
  let best = null;
  for (const s of scored) if (s.d != null && (!best || s.d < best.d)) best = s;
  if (!best || best.d > COVER_SAME) return null;
  const clear = Math.max(best.d * 2, best.d + COVER_MARGIN);
  for (const s of scored) if (s.d != null && s.d < clear && !sameArtist(s.artist, best.artist)) return null;
  return best;
}

/**
 * A picture fetched and read in the browser: its signature, or null (no picture, a host that does
 * not let a page read it, no answer within [timeout], [signal] aborted). Never throws.
 */
export function loadCover(url, { timeout = TIMEOUT, signal, doc = globalThis.document } = {}) {
  return new Promise((resolve) => {
    if (!doc || (signal && signal.aborted)) { resolve(null); return; }
    const img = doc.createElement('img');
    let done = false;
    const finish = (sig) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', stop);
      img.onload = img.onerror = null;
      if (!sig) img.src = ''; // lets go of a download under way
      resolve(sig);
    };
    const stop = () => finish(null);
    const timer = setTimeout(stop, timeout);
    if (signal) signal.addEventListener('abort', stop);
    img.onerror = stop;
    img.onload = () => {
      try {
        // No larger than Spotify's 300 px; the grid is averaged here, not by the browser's scaling.
        const scale = Math.min(1, 320 / Math.max(img.naturalWidth, img.naturalHeight));
        const w = Math.round(img.naturalWidth * scale), h = Math.round(img.naturalHeight * scale);
        const canvas = doc.createElement('canvas');
        canvas.width = w; canvas.height = h;
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(img, 0, 0, w, h);
        finish(coverSignature(ctx.getImageData(0, 0, w, h).data, w, h));
      } catch (e) {
        stop();
      }
    };
    img.crossOrigin = 'anonymous';
    img.referrerPolicy = 'no-referrer';
    img.decoding = 'async';
    img.src = url;
  });
}

/**
 * [json], with the requests to [host] kept [gap] ms apart: MusicBrainz asks for no more than one
 * a second. One that would wait more than [patience] is not made ("no-answer"): nothing here
 * waits on MusicBrainz.
 */
export function spaced(json, { host = 'musicbrainz.org', gap = 1100, patience = 2500, now = Date.now, wait = sleep } = {}) {
  let free = 0;
  return async (url, opts) => {
    if (new URL(url).hostname === host) {
      const t = now(), start = Math.max(t, free);
      if (start - t > patience) throw new ToolError('no-answer', (opts && opts.name) || '');
      free = start + gap;
      if (start > t) await wait(start - t);
    }
    return json(url, opts);
  };
}

/** The ways of asking, as lookup() takes them. The tests hand in their own. */
export const browserNet = { json: spaced(getJson), jsonp, cover: loadCover };

/** How long one link may take, all its requests together. */
export const OVERALL = 12000;

/**
 * [net] for one link: every request gets what is left of [limit] as its timeout (and none is made
 * once it is over) and ends when [signal] is aborted, which is when another link is pasted.
 */
function bounded(net, { signal, limit = OVERALL } = {}) {
  if (net.bounded) return net;
  const until = Date.now() + limit;
  const opts = (o = {}) => {
    const left = until - Date.now();
    if (left <= 0 || (signal && signal.aborted)) throw new ToolError('no-answer', o.name || '');
    return { ...o, timeout: Math.min(o.timeout || TIMEOUT, left), signal };
  };
  return {
    bounded: true,
    json: async (url, o) => net.json(url, opts(o)),
    jsonp: async (url, o) => net.jsonp(url, opts(o)),
    // A picture that does not come is no picture: never an error.
    cover: net.cover ? async (url) => { try { return await net.cover(url, opts()); } catch (e) { return null; } } : null,
  };
}


// ---------------------------------------------------------------------------------------------
// The catalogues
// ---------------------------------------------------------------------------------------------

const ITUNES = 'https://itunes.apple.com/';
const DEEZER = 'https://api.deezer.com/';
const MB = 'https://musicbrainz.org/ws/2/';
/** MusicBrainz is asked first and is optional: it gets this long, not the whole timeout. */
const MB_TIMEOUT = 2500;
const year = (date) => (/^\d{4}/.exec(date || '') || [null])[0];
const clean = (url) => String(url || '').replace(/[?&]uo=\d+/, '');
const upcOk = (code) => /^\d{8,14}$/.test(code || '');
const isrcOk = (code) => /^[A-Z]{2}[A-Z0-9]{3}\d{7}$/i.test(code || '');

function fromItunes(r) {
  const album = r.wrapperType === 'collection';
  return {
    kind: album ? 'album' : 'song',
    id: (album ? r.collectionId : r.trackId) || null,
    title: album ? r.collectionName : r.trackName,
    artist: r.artistName || '',
    album: album ? null : r.collectionName || null,
    durationMs: album ? null : r.trackTimeMillis || null,
    count: album ? r.trackCount || null : null,
    disc: album ? null : r.discNumber || null,
    track: album ? null : r.trackNumber || null,
    year: year(r.releaseDate),
    art: r.artworkUrl100 ? r.artworkUrl100.replace(/\/\d+x\d+bb\./, '/600x600bb.') : null,
    thumb: r.artworkUrl100 || null,
    url: clean(album ? r.collectionViewUrl : r.trackViewUrl),
  };
}
const itunesItems = (data, kind) => ((data && data.results) || [])
  .filter((r) => (kind === 'album' ? r.wrapperType === 'collection' : r.wrapperType === 'track' && r.kind === 'song'))
  .map(fromItunes).filter((i) => i.title && i.url);

function fromDeezer(r, kind) {
  const album = kind === 'album';
  const cover = album ? r : r.album || {};
  return {
    kind,
    id: r.id,
    title: r.title || '',
    artist: (r.artist && r.artist.name) || '',
    album: album ? null : (r.album && r.album.title) || null,
    albumId: album ? r.id : (r.album && r.album.id) || null,
    durationMs: album ? null : r.duration ? r.duration * 1000 : null,
    count: album ? r.nb_tracks || null : null,
    disc: album ? null : r.disk_number || null,
    track: album ? null : r.track_position || null,
    year: year(r.release_date),
    art: cover.cover_big || cover.cover_medium || null,
    thumb: cover.cover_small || null,
    url: 'https://www.deezer.com/' + (album ? 'album' : 'track') + '/' + r.id,
    isrc: r.isrc || null,
    upc: r.upc || null,
  };
}

async function appleGet(link, net) {
  const entity = link.kind === 'album' ? 'album' : 'song';
  const data = await net.json(ITUNES + 'lookup?id=' + link.id + '&entity=' + entity + '&country=' + link.country, { name: 'Apple Music', retries: 2 });
  const item = itunesItems(data, link.kind)[0];
  if (!item) throw new ToolError('not-found', 'Apple Music');
  return item;
}

async function deezerGet(link, net) {
  const data = await net.jsonp(DEEZER + (link.kind === 'album' ? 'album/' : 'track/') + link.id, { name: 'Deezer' });
  if (!data || !data.id || !data.title) throw new ToolError('not-found', 'Deezer');
  return { ...fromDeezer(data, link.kind), whole: true };
}

async function itunesSearch(term, kind, country, net, limit = 25) {
  const url = ITUNES + 'search?term=' + plus(term) + '&entity=' + (kind === 'album' ? 'album' : 'song') + '&country=' + (country || 'us') + '&limit=' + limit;
  return itunesItems(await net.json(url, { name: 'Apple Music', retries: 2 }), kind);
}

// The plain query: Deezer's artist:"…" track:"…" form came back empty when it was tried.
async function deezerSearch(term, kind, net, limit = 10) {
  const data = await net.jsonp(DEEZER + (kind === 'album' ? 'search/album' : 'search') + '?q=' + path(term) + '&limit=' + limit, { name: 'Deezer' });
  return ((data && data.data) || []).filter((r) => r && r.id && r.title).map((r) => fromDeezer(r, kind));
}

/** A source that is asked on top of the others: when it says no, or nothing, it is null. */
const maybe = (asked) => Promise.resolve(asked).catch((e) => { if (e instanceof ToolError) return null; throw e; });

/**
 * Deezer's album with this barcode, or its song with this ISRC; null when it has none. Deezer keeps
 * a barcode without the zeros in front, MusicBrainz with them: both are tried.
 */
async function deezerByCode(kind, code, net) {
  const album = kind === 'album';
  const forms = album ? [...new Set([code.replace(/^0+/, ''), code])] : [code.toUpperCase()];
  for (const form of forms) {
    const data = await maybe(net.jsonp(DEEZER + (album ? 'album/upc:' : 'track/isrc:') + form, { name: 'Deezer' }));
    if (data && data.id && data.title) return { ...fromDeezer(data, kind), whole: true };
  }
  return null;
}

/** Apple's albums with this barcode, or (for a song) the songs on them. The iTunes lookup knows barcodes, not ISRCs. */
async function itunesByUpc(upc, kind, country, net) {
  const url = ITUNES + 'lookup?upc=' + upc + (kind === 'song' ? '&entity=song' : '') + '&country=' + (country || 'us');
  return itunesItems(await maybe(net.json(url, { name: 'Apple Music', retries: 1 })), kind);
}

/**
 * The song [want] among the [songs] of its album on another service: the one in the same place
 * with the same title or the same length; failing that the only one with the title, or the only
 * one with the title and the length. null when the album has no such song, or more than one.
 */
export function albumSong(want, songs) {
  const key = titleKey(want.title);
  const placed = songs.filter((s) => want.track && s.track === want.track && (s.disc || 1) === (want.disc || 1));
  const there = placed.find((s) => titleKey(s.title) === key) || placed.find((s) => close(want, s));
  if (there) return there;
  const named = songs.filter((s) => titleKey(s.title) === key);
  if (named.length === 1) return named[0];
  const near = named.filter((s) => close(want, s));
  return near.length === 1 ? near[0] : null;
}

/**
 * What Apple has under the barcode of the album of [dz], Deezer's song or album: { at, items },
 * its albums or the songs on them, and [dz] whole. A song from a search result is first asked
 * for whole: only then it says where on the album it is. Apple may have one barcode twice (two
 * pressings of an album), so there can be more albums than one.
 */
async function underBarcode(dz, kind, country, net) {
  let at = dz;
  if (kind === 'song' && !at.whole && at.id) at = (await maybe(deezerGet({ kind, id: at.id }, net))) || at;
  let upc = at.upc;
  if (!upc && at.albumId) upc = ((await maybe(net.jsonp(DEEZER + 'album/' + at.albumId, { name: 'Deezer' }))) || {}).upc;
  return { at, upc, items: upcOk(upc) ? await itunesByUpc(upc, kind, country, net) : [] };
}

/** Apple's page of what Deezer has as [dz]: the album with its barcode, or the song in its place on it. null when a link of the chain is missing. */
async function appleByBarcode(dz, kind, country, net) {
  const { at, items } = await underBarcode(dz, kind, country, net);
  return (kind === 'album' ? items[0] : albumSong(at, items)) || null;
}

/**
 * What MusicBrainz has on a Spotify link: the barcodes of the album, or the ISRCs of the song
 * (few songs have their Spotify link there, most albums do). [] when it does not know, is busy
 * or does not answer in its short time.
 */
async function mbCodes(link, net, out = {}) {
  const ask = (what) => net.json(MB + what + '&fmt=json', { name: 'MusicBrainz', timeout: MB_TIMEOUT });
  const few = (codes, ok) => [...new Set(codes.filter(ok))].slice(0, 3);
  try {
    const album = link.kind === 'album';
    const data = await ask('url?resource=' + encodeURIComponent(link.url) + '&inc=' + (album ? 'release-rels' : 'recording-rels'));
    const rels = standing((data && data.relations) || []);
    if (album) {
      const codes = few(rels.map((r) => r.release && r.release.barcode), upcOk);
      // The release's own links to the other services: one request more, which may come too late or not at all.
      const id = rels.map((r) => r.release && r.release.id).find(Boolean);
      if (id) out.rels = ask('release/' + encodeURIComponent(id) + '?inc=url-rels').then((r) => (r && r.relations) || [], () => []);
      return codes;
    }
    const id = rels.map((r) => r.recording && r.recording.id).find(Boolean);
    if (!id) return [];
    const recording = await ask('recording/' + encodeURIComponent(id) + '?inc=isrcs+url-rels');
    out.rels = (recording && recording.relations) || [];
    return few((recording && recording.isrcs) || [], isrcOk);
  } catch (e) {
    return [];
  }
}

/** A link MusicBrainz marks as ended comes after the ones that still stand. */
const standing = (rels) => rels.filter(Boolean).sort((a, b) => (a.ended ? 1 : 0) - (b.ended ? 1 : 0));

// ---------------------------------------------------------------------------------------------
// More exact buttons: the links MusicBrainz and Wikidata keep for a recording or a release
// ---------------------------------------------------------------------------------------------

const YT_ID = /^[A-Za-z0-9_-]{11}$/;
const ASIN = /^B[0-9A-Z]{9}$/;
const SLUG = /^[A-Za-z0-9_-]{1,100}$/;
const TIDAL_HOSTS = new Set(['tidal.com', 'www.tidal.com', 'listen.tidal.com']);
const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com']);
const AMAZON_HOSTS = new Set(['music.amazon.com', 'music.amazon.co.uk', 'music.amazon.de', 'music.amazon.fr', 'music.amazon.it', 'music.amazon.es',
  'music.amazon.ca', 'music.amazon.com.au', 'music.amazon.co.jp', 'music.amazon.com.br', 'music.amazon.com.mx', 'music.amazon.in']);
const SOUNDCLOUD_HOSTS = new Set(['soundcloud.com', 'www.soundcloud.com', 'm.soundcloud.com']);
const SOUNDCLOUD_PAGES = new Set(['search', 'discover', 'stream', 'you', 'upload', 'charts', 'pages', 'settings', 'messages', 'notifications', 'tags',
  'popular', 'mobile', 'people', 'terms-of-use', 'jobs', 'imprint', 'connect', 'signin', 'logout', 'feed', 'sets']);

/**
 * The service and the page of a link to [kind] (song or album), rebuilt from its parts: { id, url },
 * or null for anything else. Only the hosts named here, only http(s), no user or port, and only a
 * page of a song (or of an album) whose id has the form the service gives it.
 */
export function serviceLink(raw, kind) {
  let u;
  try { u = new URL(String(raw)); } catch (e) { return null; }
  if ((u.protocol !== 'https:' && u.protocol !== 'http:') || u.username || u.password || u.port) return null;
  const host = u.hostname.toLowerCase();
  const parts = u.pathname.split('/').filter(Boolean);
  const song = kind === 'song';
  const q = (k) => u.searchParams.get(k) || '';
  const same = (link) => (link && link.kind === kind ? { id: link.service, url: link.url } : null);
  const quietly = (read) => { try { return same(read()); } catch (e) { return null; } };
  if (TIDAL_HOSTS.has(host)) {
    const p = parts[0] === 'browse' ? parts.slice(1) : parts;
    const type = song ? 'track' : 'album';
    return p.length === 2 && p[0] === type && digits(p[1]) ? { id: 'tidal', url: 'https://tidal.com/browse/' + type + '/' + p[1] } : null;
  }
  if (host === 'music.youtube.com') {
    if (song) return parts.length === 1 && parts[0] === 'watch' && YT_ID.test(q('v')) ? { id: 'ytmusic', url: 'https://music.youtube.com/watch?v=' + q('v') } : null;
    if (parts.length === 1 && parts[0] === 'playlist' && /^OLAK5uy_[A-Za-z0-9_-]{10,60}$/.test(q('list'))) return { id: 'ytmusic', url: 'https://music.youtube.com/playlist?list=' + q('list') };
    if (parts.length === 2 && parts[0] === 'browse' && /^MPREb_[A-Za-z0-9_-]{5,60}$/.test(parts[1])) return { id: 'ytmusic', url: 'https://music.youtube.com/browse/' + parts[1] };
    return null;
  }
  if (YOUTUBE_HOSTS.has(host) || host === 'youtu.be') {
    const v = host === 'youtu.be' ? (parts.length === 1 ? parts[0] : '') : parts.length === 1 && parts[0] === 'watch' ? q('v') : '';
    return song && YT_ID.test(v) ? { id: 'youtube', url: 'https://www.youtube.com/watch?v=' + v } : null;
  }
  if (AMAZON_HOSTS.has(host)) {
    const type = song ? 'tracks' : 'albums';
    // An album page with ?trackAsin= points at one of its songs: it is neither the song's page nor quite the album's.
    return parts.length === 2 && parts[0] === type && ASIN.test(parts[1]) && !q('trackAsin') ? { id: 'amazon', url: 'https://' + host + '/' + type + '/' + parts[1] } : null;
  }
  if (SOUNDCLOUD_HOSTS.has(host)) {
    if (!parts.every((p) => SLUG.test(p)) || SOUNDCLOUD_PAGES.has(parts[0].toLowerCase())) return null;
    const ok = song ? parts.length === 2 && parts[1] !== 'sets' : parts.length === 3 && parts[1] === 'sets';
    return ok ? { id: 'soundcloud', url: 'https://soundcloud.com/' + parts.join('/') } : null;
  }
  if (host === 'music.apple.com' || host === 'geo.music.apple.com') return quietly(() => appleLink(u, parts));
  if (host === 'deezer.com' || host === 'www.deezer.com') return quietly(() => deezerLink(parts));
  if (host === 'open.spotify.com') return quietly(() => spotifyLink(parts));
  return null;
}

/** MusicBrainz's url-rels of a recording or a release, as { service: page }: the first link that still stands for each. */
export function relLinks(relations, kind) {
  const out = {};
  for (const r of standing(relations || [])) {
    const found = r && r.url && serviceLink(r.url.resource, kind);
    if (found && !out[found.id]) out[found.id] = found.url;
  }
  return out;
}

const WD = 'https://query.wikidata.org/sparql';
/** Wikidata gets this long, and is asked only when MusicBrainz left buttons a search. */
export const WD_TIMEOUT = 3000;
/** What Wikidata keeps of a recording (by ISRC, P1243) or of an album (by barcode, P3962), and how each becomes a page. */
const WD_PROPS = {
  song: {
    key: 'P1243',
    props: { tidal: 'P4578', amazon: 'P13398', apple: 'P10110', youtube: 'P1651', deezer: 'P2724', spotify: 'P2207' },
    page: {
      tidal: (v) => 'https://tidal.com/browse/track/' + v,
      amazon: (v) => 'https://music.amazon.com/tracks/' + v,
      // Apple's track ID is written "album?i=track".
      apple: (v) => { const m = /^(\d{1,20})\?i=(\d{1,20})$/.exec(v); return m ? 'https://music.apple.com/us/album/' + m[1] + '?i=' + m[2] : ''; },
      youtube: (v) => 'https://www.youtube.com/watch?v=' + v,
      deezer: (v) => 'https://www.deezer.com/track/' + v,
      spotify: (v) => 'https://open.spotify.com/track/' + v,
    },
  },
  album: {
    key: 'P3962',
    props: { tidal: 'P4577', apple: 'P2281', deezer: 'P2723', spotify: 'P2205' },
    page: {
      tidal: (v) => 'https://tidal.com/browse/album/' + v,
      apple: (v) => 'https://music.apple.com/us/album/' + v,
      deezer: (v) => 'https://www.deezer.com/album/' + v,
      spotify: (v) => 'https://open.spotify.com/album/' + v,
    },
  },
};

/** The forms a barcode may be kept in: with or without the zeros in front, as a UPC or as a GTIN of 13 or 14 digits. */
const barcodeForms = (code) => {
  const bare = code.replace(/^0+/, '');
  return [...new Set([code, bare, ...[12, 13, 14].map((n) => bare.padStart(n, '0'))])].filter((c) => c.length >= 8);
};

/** The one SPARQL query for [codes] (ISRCs of a song, or a barcode of an album). */
export function wikidataQuery(kind, codes) {
  const { key, props } = WD_PROPS[kind];
  const forms = kind === 'album' ? codes.flatMap(barcodeForms) : codes.map((c) => c.toUpperCase());
  const values = [...new Set(forms)].filter((c) => /^[A-Z0-9]{8,14}$/.test(c)).map((c) => '"' + c + '"').join(' ');
  const opt = Object.entries(props).map(([id, p]) => 'OPTIONAL{?i wdt:' + p + ' ?' + id + '}').join(' ');
  return 'SELECT ' + Object.keys(props).map((id) => '?' + id).join(' ') + ' WHERE{VALUES ?c{' + values + '} ?i wdt:' + key + ' ?c. ' + opt + '} LIMIT 20';
}

/** Wikidata's answer as { service: page }: the first value of each that makes a page of the right kind. */
export function wikidataLinks(data, kind) {
  const out = {};
  const { page } = WD_PROPS[kind];
  for (const row of (data && data.results && data.results.bindings) || []) {
    for (const id of Object.keys(page)) {
      const v = row && row[id] && row[id].value;
      if (out[id] || typeof v !== 'string') continue;
      const found = serviceLink(page[id](v), kind);
      if (found && found.id === id) out[id] = found.url;
    }
  }
  return out;
}

/**
 * [found] with what MusicBrainz and Wikidata know turned into exact buttons. MusicBrainz: [rels]
 * (url-rels already in hand, or a promise of them), or for a song with an [isrc] and no rels one
 * request by the ISRC. Its pages replace any button not yet exact. Wikidata: one query by the ISRC
 * or the barcode, only for the services still a search. Neither answering changes nothing.
 */
export async function addKnown(kind, found, { rels, isrc, upc } = {}, net) {
  const open = (ids) => ids.filter((id) => !found[id] || found[id].mark !== 'exact');
  const put = (links, note, may) => { for (const [id, url] of Object.entries(links)) if (may(id)) found[id] = { url, mark: 'exact', note }; };
  try {
    let list = await rels;
    if (!list && kind === 'song' && isrcOk(isrc) && open(SERVICES.map((s) => s.id)).length) {
      const data = await net.json(MB + 'isrc/' + isrc.toUpperCase() + '?inc=url-rels&fmt=json', { name: 'MusicBrainz', timeout: MB_TIMEOUT });
      list = ((data && data.recordings) || []).flatMap((r) => r.relations || []);
    }
    put(relLinks(list, kind), 'mb', (id) => open([id]).length);
  } catch (e) { /* MusicBrainz is on top of the rest */ }
  const code = kind === 'song' ? (isrcOk(isrc) ? isrc : null) : (upcOk(upc) ? upc : null);
  const left = Object.keys(WD_PROPS[kind].props).filter((id) => !found[id]);
  if (!code || !left.length) return found;
  try {
    const data = await net.json(WD + '?format=json&query=' + encodeURIComponent(wikidataQuery(kind, [code])), { name: 'Wikidata', timeout: WD_TIMEOUT });
    put(wikidataLinks(data, kind), 'wd', (id) => left.includes(id) && !found[id]);
  } catch (e) { /* no answer in its short time: the buttons stay a search */ }
  return found;
}

// ---------------------------------------------------------------------------------------------
// The lookup
// ---------------------------------------------------------------------------------------------

const pasted = (link) => ({ url: link.url, mark: 'exact', note: 'pasted' });
/** A page that is certainly the same thing, and why: 'barcode', 'isrc', 'cover' or 'same'. */
const exact = (item, note) => ({ url: item.url, mark: 'exact', note });

const silent = (e) => e instanceof ToolError && e.code === 'no-answer';
/**
 * The other catalogue not answering is lived with: its button becomes a search, and says so. (The
 * iTunes search refuses a request now and then.) The service the link is from must answer.
 */
const quiet = (search) => search.catch((e) => { if (silent(e)) return []; throw e; });

/** The two covers are the same artwork. false when either cannot be had. */
async function sameCover(a, b, net) {
  if (!net.cover || !a.thumb || !b.thumb) return false;
  const [x, y] = await Promise.all([net.cover(a.thumb), net.cover(b.thumb)]);
  return !!x && !!y && coverDistance(x, y) <= COVER_SAME;
}

/**
 * The button for the best of [fits] (candidates with the title and the artist of [want], the
 * closest first). Exact when the length agrees; for a song also when the cover does. An album with
 * another number of songs is another edition whatever its cover.
 */
async function judged(want, fits, net) {
  const c = fits[0];
  if (!c) return null;
  if (close(want, c)) return exact(c, 'same');
  if (want.kind === 'song' && await sameCover(want, c, net)) return exact(c, 'cover');
  return { url: c.url, mark: 'guess', note: 'differs' };
}

/**
 * Everything the page shows for a link, or a ToolError and nothing: there is no half result.
 * { kind, source, sourceUrl, title, artist, album, durationMs, count, year, art, guess, fix, alts, services, query }.
 * From Spotify only the title and the cover are known: when neither a barcode nor the cover
 * settles the artist it is a guess, [artist] (the visitor's correction) replaces it, and [alts]
 * are the other artists with a song of that title. [signal] aborted ends the requests.
 */
export async function lookup(link, net = browserNet, { artist, signal } = {}) {
  net = bounded(net, { signal });
  if (link.service === 'spotify') return spotifyResult(await spotifyBase(link, net), artist, net);
  const services = {};
  let item, codes = {};
  if (link.service === 'apple') {
    item = await appleGet(link, net);
    services.apple = pasted(link);
    const fits = matches(item, await quiet(deezerSearch(queryOf(item), item.kind, net)));
    // Deezer's album has a barcode, and Apple can be asked what has that barcode: when the very
    // page that was pasted is there (for a song, in Deezer's place on the album), nothing is left to doubt.
    let match = null;
    for (const c of fits.slice(0, 2)) {
      const { at, upc, items } = await underBarcode(c, item.kind, link.country, net);
      const back = items.filter((i) => i.id === item.id);
      if (item.kind === 'album' ? back.length : albumSong(at, back)) { match = exact(c, 'barcode'); codes = { isrc: at.isrc, upc }; break; }
    }
    match = match || await judged(item, fits, net);
    if (match) services.deezer = match;
    if (!codes.isrc && !codes.upc) codes = await exactDeezerCodes(item.kind, match, null, net);
  } else {
    item = await deezerGet(link, net);
    codes = { isrc: item.isrc, upc: item.upc };
    services.deezer = pasted(link);
    const twin = await appleByBarcode(item, item.kind, 'us', net);
    const match = twin ? exact(twin, 'barcode') : await judged(item, matches(item, await quiet(itunesSearch(queryOf(item), item.kind, 'us', net))), net);
    if (match) services.apple = match;
  }
  await addKnown(item.kind, services, codes, net);
  return result(link, item, services, { guess: false, fix: false, alts: [] });
}

function result(link, item, services, more) {
  return {
    kind: link.kind, source: link.service, sourceUrl: link.url,
    title: item.title, artist: item.artist || '', album: item.album || null,
    durationMs: item.durationMs || null, count: item.count || null, year: item.year || null, art: item.art || null,
    ...more,
    services: buildServices(item, services), query: queryOf(item),
  };
}

/** [sigOf](url): the signature of a picture, each asked once, [max] in all, a few at a time. */
function coverQueue(net, max) {
  const asked = new Map(), waiting = [];
  let active = 0;
  const next = () => {
    while (active < COVER_PARALLEL && waiting.length) {
      active++;
      waiting.shift()().then(() => { active--; next(); });
    }
  };
  return (url) => {
    if (!net.cover || !url) return Promise.resolve(null);
    if (asked.has(url)) return asked.get(url);
    if (asked.size >= max) return Promise.resolve(null);
    const sig = new Promise((resolve) => { waiting.push(() => net.cover(url).catch(() => null).then(resolve)); });
    asked.set(url, sig);
    next();
    return sig;
  };
}

/** [items] with how far each one's cover is from [want]. */
const scoredBy = (want, items, sigOf) => Promise.all(items.map(async (item) => {
  const sig = await sigOf(item.thumb);
  return { item, artist: item.artist, d: sig ? coverDistance(want, sig) : null };
}));

/**
 * What a barcode or an ISRC from MusicBrainz leads to: { by: 'code', deezer, apple }, each
 * { item, note } or null; null when it leads nowhere. An album's barcode is asked of both
 * catalogues. A song's ISRC is asked of Deezer (Apple cannot be asked for one), and Apple's song is
 * then the one in the same place on the album with the barcode of Deezer's.
 */
async function byCodes(link, codes, net) {
  let deezer = null, apple = null;
  for (const code of codes) {
    if (link.kind === 'album') {
      const [d, a] = await Promise.all([deezer || deezerByCode('album', code, net), apple || itunesByUpc(code, 'album', link.country, net).then((list) => list[0] || null)]);
      deezer = d; apple = a;
    } else if (!deezer) {
      deezer = await deezerByCode('song', code, net);
    }
    if (deezer && (apple || link.kind === 'song')) break;
  }
  if (deezer && link.kind === 'song') apple = await appleByBarcode(deezer, 'song', link.country, net);
  if (!deezer && !apple) return null;
  return {
    by: 'code',
    deezer: deezer && { item: deezer, note: link.kind === 'album' ? 'barcode' : 'isrc' },
    apple: apple && { item: apple, note: 'barcode' },
  };
}

/** The artists whose albums are looked through when no candidate has Spotify's cover, and the covers looked at for each. */
const COVER_ARTISTS = 6;
const COVER_EACH = 6;

/**
 * Deezer's search shows a song once, often on its single, while Spotify's link may be to the
 * album: the cover is then on none of the candidates. So the albums of the likeliest artists are
 * looked through for the cover (or those of the one artist [names], when Apple's candidates
 * have settled who it is), and the song is taken from the album that has it. { item, d } or null.
 */
async function onAlbums(base, want, net, names = ranked(base).slice(0, COVER_ARTISTS)) {
  const key = titleKey(base.title);
  const sigOf = coverQueue(net, COVER_MORE);
  const lists = await Promise.all(names.map(async (name) => {
    const albums = ((await maybe(deezerSearch(name, 'album', net, 25))) || []).filter((a) => sameArtist(a.artist, name) && a.thumb)
      .sort((x, y) => (y.count || 0) - (x.count || 0));
    const covers = [...new Set(albums.map((a) => a.thumb))].slice(0, COVER_EACH);
    return albums.filter((a) => covers.includes(a.thumb));
  }));
  const scored = await scoredBy(want, lists.flat(), sigOf);
  const best = pickCover(scored);
  if (!best) return null;
  const albums = scored.filter((s) => s.d != null && s.d <= COVER_SAME && sameArtist(s.artist, best.artist)).slice(0, 3);
  for (const a of albums) {
    const data = await maybe(net.jsonp(DEEZER + 'album/' + a.item.id, { name: 'Deezer' }));
    const tracks = (data && data.tracks && data.tracks.data) || [];
    const at = tracks.findIndex((t) => titleKey(t.title) === key);
    if (at < 0) continue;
    const song = fromDeezer({ ...tracks[at], album: data, release_date: data.release_date }, 'song');
    // The album lists its songs in order, without their numbers: the place is right on a single disc.
    return { item: { ...song, whole: true, upc: data.upc || null, track: data.nb_tracks === tracks.length ? at + 1 : null }, d: a.d };
  }
  return null;
}

/**
 * The candidate with Spotify's own cover: { by: 'cover', deezer, apple }, each { item, note } or
 * null; null when no cover is clearly it (or the pictures cannot be read: the page then guesses
 * as it did before). Deezer's match also leads to Apple's page by the barcode of its album.
 */
async function byCover(base, target, sigOf, net) {
  const { link, title } = base;
  const want = await target;
  if (!want) return null;
  const from = (list, service) => scoredBy(want, list || [], sigOf).then((scored) => scored.map((s) => ({ ...s, service })));
  const scored = [...await from(base.deezer, 'deezer'), ...await from(base.apple, 'apple')];
  const best = pickCover(scored);
  let deezer = null, apple = null;
  if (best) {
    // Of an artist's releases with this cover (the album and its deluxe edition), the one named as on Spotify.
    const named = (s) => (fold(s.item.title) === fold(title) ? 0 : 1);
    const of = (service) => scored.filter((s) => s.service === service && s.d != null && s.d <= COVER_SAME && sameArtist(s.artist, best.artist))
      .sort((x, y) => named(x) - named(y) || x.d - y.d)[0] || null;
    deezer = of('deezer'); apple = of('apple');
    // Apple has the cover and Deezer's search does not show the song on that album: the album is asked for.
    if (!deezer && link.kind === 'song' && base.deezer) deezer = await onAlbums(base, want, net, [best.artist]);
  } else if (link.kind === 'song' && base.deezer) {
    // The cover may be on a result with another title: a remix on the same album. That settles the artist too.
    const near = pickCover([...scored, ...await scoredBy(want, base.loose, sigOf)]);
    if (near) deezer = await onAlbums(base, want, net, [near.artist]);
    deezer = deezer || await onAlbums(base, want, net);
  }
  if (!deezer && !apple) return null;
  const twin = deezer ? await appleByBarcode(deezer.item, link.kind, link.country, net) : null;
  return {
    by: 'cover',
    deezer: deezer && { item: deezer.item, note: 'cover' },
    apple: twin ? { item: twin, note: 'barcode' } : apple && { item: apple.item, note: 'cover' },
  };
}

/**
 * What Spotify gives (the title and a cover), what the two open catalogues have under that
 * title (the candidates for the artist; [loose] is what else their searches brought), and [sure]: the pages that are certainly it, found by
 * a barcode or an ISRC (MusicBrainz knows the link) or by the cover; null when neither settles it.
 * One catalogue not answering is lived with (its button becomes a search, and says so); both
 * silent and nothing sure is an error.
 */
export async function spotifyBase(link, net = browserNet, { signal } = {}) {
  net = bounded(net, { signal });
  const mb = {};
  const codes = mbCodes(link, net, mb).then((list) => { mb.codes = list; return list.length ? byCodes(link, list, net) : null; });
  const data = await net.json('https://open.spotify.com/oembed?url=' + encodeURIComponent(link.url), { name: 'Spotify' });
  if (!data || !data.title) throw new ToolError('not-found', 'Spotify');
  const key = titleKey(data.title), term = plainTitle(data.title);
  const sigOf = coverQueue(net, COVER_MAX);
  const target = sigOf(data.thumbnail_url);
  const loose = [];
  const ask = async (search) => {
    try {
      const all = await search(), list = all.filter((c) => titleKey(c.title) === key);
      for (const c of list) sigOf(c.thumb); // the pictures start coming while the other catalogue is still answering
      loose.push(...all.filter((c) => !list.includes(c)));
      return list;
    } catch (e) { if (silent(e)) return null; throw e; }
  };
  const [apple, deezer, coded] = await Promise.all([
    ask(() => itunesSearch(term, link.kind, link.country, net, 50)),
    ask(() => deezerSearch(term, link.kind, net, 50)),
    codes,
  ]);
  const base = { link, title: data.title, art: data.thumbnail_url || null, apple, deezer, loose, sure: coded, mb };
  if (!base.sure) base.sure = await byCover(base, target, sigOf, net);
  if (!apple && !deezer && !base.sure) throw new ToolError('no-answer', 'Apple Music');
  return base;
}

/**
 * The artists a title alone may mean, the likeliest first. Both catalogues list what is played
 * most near the top, and a well-known song is there more than once (the album, a best-of, a
 * remaster): each release counts for more the higher it stands, and the counts add up.
 */
function ranked(base) {
  const apple = base.apple || [], deezer = base.deezer || [];
  const names = [];
  for (const c of [...deezer, ...apple]) if (c.artist && !names.some((n) => sameArtist(n, c.artist))) names.push(c.artist);
  const weight = (list, name) => list.reduce((sum, c, at) => (sameArtist(c.artist, name) ? sum + 1 / (at + 1) : sum), 0);
  // Apple's spelling of the name where it has one: it is the one shown.
  const shown = (name) => (apple.find((c) => sameArtist(c.artist, name)) || { artist: name }).artist;
  return names.map((name, at) => ({ name: shown(name), score: weight(apple, name) + weight(deezer, name), at }))
    .sort((x, y) => y.score - x.score || x.at - y.at).map((x) => x.name);
}

/**
 * The result for a Spotify link: what is sure when something is, otherwise the most likely
 * artist as a guess. [artist], the visitor's own, comes before both.
 */
export async function spotifyResult(base, artist, net = browserNet, { signal } = {}) {
  net = bounded(net, { signal });
  const { link, title } = base;
  const order = ranked(base);
  const typed = String(artist || '').trim();
  const services = { spotify: pasted(link) };
  const others = (name) => order.filter((n) => !sameArtist(n, name)).slice(0, 4);
  if (!typed && base.sure) {
    const { deezer, apple, by } = base.sure;
    const known = (apple || deezer).item;
    const want = { kind: link.kind, title, artist: known.artist, durationMs: known.durationMs, count: known.count, thumb: known.thumb };
    // The catalogue that has no sure page gets the one with the title, the artist and the length, where it has one.
    const other = async (list, search) => {
      if (!list) return null;
      const fits = matches(want, list);
      return judged(want, fits.length ? fits : matches(want, await quiet(search(queryOf(want)))), net);
    };
    const d = deezer ? exact(deezer.item, deezer.note) : await other(base.deezer, (q) => deezerSearch(q, link.kind, net));
    const a = apple ? exact(apple.item, apple.note) : await other(base.apple, (q) => itunesSearch(q, link.kind, link.country, net));
    if (d) services.deezer = d;
    if (a) services.apple = a;
    const item = { ...known, title, art: base.art || known.art };
    // Deezer's sure page has the ISRC or the barcode where MusicBrainz had none.
    // MusicBrainz did not know the link: the ISRC (or barcode) of Deezer's page, when that page is exact, never from a guess.
    const said = mbKnown(base);
    const dz = said.isrc || said.upc ? {} : await exactDeezerCodes(link.kind, d, deezer && deezer.item, net);
    await addKnown(link.kind, services, { rels: said.rels, isrc: said.isrc || dz.isrc, upc: said.upc || dz.upc }, net);
    // Found by the cover, the visitor can still say it is someone else; a barcode leaves no one else.
    return result(link, item, services, { guess: false, fix: by === 'cover', alts: by === 'cover' ? others(item.artist) : [] });
  }
  const name = typed || order[0] || '';
  const by = (list) => (name && list ? list.find((c) => sameArtist(c.artist, name)) || null : null);
  let apple = by(base.apple), deezer = null;
  if (name) {
    const first = apple || by(base.deezer);
    // The catalogue's spelling of the name, when it has one.
    const want = { kind: link.kind, title, artist: first ? first.artist : name, durationMs: apple && apple.durationMs, count: apple && apple.count };
    const q = queryOf(want);
    const match = (list) => (pickMatch(want, list) || {}).item || null;
    // An artist the title searches did not bring up gets a search of its own, where the catalogue answers.
    if (!apple && base.apple) apple = match(await quiet(itunesSearch(q, link.kind, link.country, net)));
    deezer = match(base.deezer || []);
    if (!deezer && base.deezer) deezer = match(await quiet(deezerSearch(q, link.kind, net)));
  }
  // Both are real pages of a song with this title by the guessed artist: a guess, never "exact".
  const guessed = (c) => ({ url: c.url, mark: 'guess', note: 'guessed', label: plainTitle(c.title) + ', ' + c.artist });
  if (apple) services.apple = guessed(apple);
  if (deezer) services.deezer = guessed(deezer);
  const known = apple || deezer || {};
  const item = {
    title, artist: known.artist || name,
    album: known.album, durationMs: known.durationMs, count: known.count, year: known.year, art: base.art || known.art,
  };
  // What MusicBrainz keeps for the pasted link is sure, whoever the artist turns out to be.
  await addKnown(link.kind, services, mbKnown(base), net);
  return result(link, item, services, { guess: true, fix: true, alts: others(item.artist) });
}

/**
 * The ISRC (of a song) or the barcode (of an album) of Deezer's button [row], only when it is exact:
 * from [item] when it has it, else Deezer's page asked whole (search results carry neither). {} otherwise.
 */
async function exactDeezerCodes(kind, row, item, net) {
  if (!row || row.mark !== 'exact') return {};
  let at = item;
  if (!at || !(kind === 'song' ? at.isrc : at.upc)) {
    const m = /\/(track|album)\/(\d{1,20})$/.exec(row.url || '');
    at = m ? await maybe(deezerGet({ kind, id: m[2] }, net)) : null;
  }
  return at ? { isrc: at.isrc || null, upc: at.upc || null } : {};
}

/** What MusicBrainz said of the pasted Spotify link: its links (when it knew the link), and the code Wikidata can be asked by. */
function mbKnown(base) {
  const mb = base.mb || {}, code = (mb.codes || [])[0];
  return { rels: mb.rels || null, isrc: base.link.kind === 'song' ? code : null, upc: base.link.kind === 'album' ? code : null };
}
