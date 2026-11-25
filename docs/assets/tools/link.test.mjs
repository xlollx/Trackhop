// node --test docs/assets/tools/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseLink, display, plainTitle, titleKey, sameArtist, pickMatch, searchUrl, queryOf, buildServices, copyText, clock,
  getJson, jsonp, lookup, spotifyBase, spotifyResult, SERVICES, matches, albumSong, spaced, coverSignature, coverDistance, pickCover,
  COVER_GRID, COVER_SAME, COVER_MARGIN, serviceLink, relLinks, wikidataQuery, wikidataLinks, wikidataId, lbIds, addKnown, WD_TIMEOUT } from './link.js';
import { ToolError } from './io.js';

const refuses = (fn, code, detail) => assert.throws(fn, (e) => e instanceof ToolError && e.code === code && (detail == null || e.detail === detail));
const rejects = (p, code, detail) => assert.rejects(p, (e) => e instanceof ToolError && e.code === code && (detail == null || e.detail === detail));

// ---- Links ----

test('Apple Music: a song is an album link with ?i=, or a /song/ link', () => {
  assert.deepEqual(parseLink('https://music.apple.com/us/album/dreams/594061854?i=594061857'),
    { service: 'apple', kind: 'song', id: '594061857', country: 'us', url: 'https://music.apple.com/us/album/dreams/594061854?i=594061857' });
  assert.deepEqual(parseLink('https://music.apple.com/it/song/dreams/594061857'),
    { service: 'apple', kind: 'song', id: '594061857', country: 'it', url: 'https://music.apple.com/it/song/dreams/594061857' });
  const tracked = parseLink('https://music.apple.com/gb/album/dreams/594061854?i=594061857&uo=4&at=abc');
  assert.equal(tracked.url, 'https://music.apple.com/gb/album/dreams/594061854?i=594061857');
  assert.equal(tracked.country, 'gb');
});

test('Apple Music: an album, with or without its name, the storefront, iTunes and geo hosts', () => {
  assert.deepEqual(parseLink('https://music.apple.com/us/album/rumours/594061854'),
    { service: 'apple', kind: 'album', id: '594061854', country: 'us', url: 'https://music.apple.com/us/album/rumours/594061854' });
  assert.equal(parseLink('https://music.apple.com/album/594061854').country, 'us');
  assert.equal(parseLink('https://music.apple.com/album/594061854').id, '594061854');
  assert.equal(parseLink('https://itunes.apple.com/de/album/rumours/id594061854').kind, 'album');
  assert.equal(parseLink('https://itunes.apple.com/de/album/rumours/594061854?i=594061857').kind, 'song');
  assert.equal(parseLink('https://geo.music.apple.com/fr/album/rumours/594061854?app=music').country, 'fr');
  assert.equal(parseLink('music.apple.com/us/album/rumours/594061854').kind, 'album');
});

test('Deezer: track and album, with a language, www or not', () => {
  assert.deepEqual(parseLink('https://www.deezer.com/track/63480987'),
    { service: 'deezer', kind: 'song', id: '63480987', country: null, url: 'https://www.deezer.com/track/63480987' });
  assert.deepEqual(parseLink('https://deezer.com/it/album/6237061?utm_source=x'),
    { service: 'deezer', kind: 'album', id: '6237061', country: null, url: 'https://www.deezer.com/album/6237061' });
  assert.equal(parseLink('https://www.deezer.com/en/track/63480987').id, '63480987');
  assert.equal(parseLink('deezer.com/track/63480987').kind, 'song');
});

test('Spotify: track and album, intl-xx, ?si=, a URI, a share text', () => {
  assert.deepEqual(parseLink('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc'),
    { service: 'spotify', kind: 'song', id: '4uLU6hMCjMI75M1A2tKUQC', country: null, url: 'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC' });
  assert.deepEqual(parseLink('https://open.spotify.com/intl-it/album/4LH4d3cOWNNsVw41Gqt2kv'),
    { service: 'spotify', kind: 'album', id: '4LH4d3cOWNNsVw41Gqt2kv', country: 'it', url: 'https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv' });
  assert.equal(parseLink('spotify:track:4uLU6hMCjMI75M1A2tKUQC').url, 'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC');
  assert.equal(parseLink('  Listen to this!\nhttps://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=1 so good ').id, '4uLU6hMCjMI75M1A2tKUQC');
  assert.equal(parseLink('https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC').kind, 'song');
});

test('refused: playlists, artists and podcasts are more than one song', () => {
  for (const url of ['https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M', 'https://open.spotify.com/user/abc/playlist/37i9dQZF1DXcBWIGoYBM5M',
    'spotify:playlist:37i9dQZF1DXcBWIGoYBM5M', 'https://www.deezer.com/en/playlist/908622995', 'https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb']) {
    refuses(() => parseLink(url), 'many', 'playlist');
  }
  for (const url of ['https://open.spotify.com/artist/08GQAI4eElDnROBrJRGE0X', 'https://www.deezer.com/artist/169', 'https://music.apple.com/us/artist/fleetwood-mac/158038']) {
    refuses(() => parseLink(url), 'many', 'artist');
  }
  for (const url of ['https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL', 'https://open.spotify.com/episode/0Q86acNRm6V9GYx55SXKwf', 'https://www.deezer.com/show/1234',
    'https://www.deezer.com/episode/1234', 'https://podcasts.apple.com/us/podcast/x/id123']) {
    refuses(() => parseLink(url), 'many', 'podcast');
  }
});

test('refused: short links, other sites, other pages, no link at all', () => {
  for (const url of ['https://spotify.link/abc123', 'https://link.deezer.com/s/abc', 'https://deezer.page.link/abc', 'https://a.co/d/abc', 'https://amzn.to/abc', 'https://apple.co/abc', 'spotify.link/abc']) {
    refuses(() => parseLink(url), 'short');
  }
  for (const url of ['https://example.com/song/123', 'https://music.youtube.com/watch?v=abc', 'https://tidal.com/browse/track/1', 'https://soundcloud.com/a/b',
    'https://music.amazon.com/albums/B0', 'https://spotify.com/', 'https://evil.example/open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC']) {
    refuses(() => parseLink(url), 'site');
  }
  for (const url of ['https://open.spotify.com/', 'https://open.spotify.com/search/dreams', 'https://open.spotify.com/track/short', 'https://www.deezer.com/track/abc',
    'https://www.deezer.com/us/', 'https://music.apple.com/us/browse', 'https://music.apple.com/us/station/x/ra.1', 'https://music.apple.com/us/music-video/x/123',
    'https://music.apple.com/library/albums/l.abc', 'https://music.apple.com/us/album/x/594061854abc']) {
    refuses(() => parseLink(url), 'other');
  }
  refuses(() => parseLink(''), 'empty');
  refuses(() => parseLink('   '), 'empty');
  refuses(() => parseLink(null), 'empty');
  refuses(() => parseLink('dreams fleetwood mac'), 'not-link');
  refuses(() => parseLink('javascript:alert(1)'), 'not-link');
  refuses(() => parseLink('http://'), 'not-link');
});

test('display: the link without its scheme', () => {
  assert.equal(display('https://www.deezer.com/track/1'), 'deezer.com/track/1');
  assert.equal(display('https://open.spotify.com/track/x'), 'open.spotify.com/track/x');
});

// ---- Comparing ----

test('plainTitle: what the services add goes, what makes another recording stays', () => {
  assert.equal(plainTitle('Dreams (2004 Remaster)'), 'Dreams');
  assert.equal(plainTitle('Dreams - 2004 Remaster'), 'Dreams');
  assert.equal(plainTitle('Dreams – Remastered 2011'), 'Dreams');
  assert.equal(plainTitle('Under Pressure (feat. David Bowie) [Remastered]'), 'Under Pressure');
  assert.equal(plainTitle('Stay (with Justin Bieber)'), 'Stay');
  assert.equal(plainTitle('Stay With Me'), 'Stay With Me');
  assert.equal(plainTitle('Lean On feat. MØ'), 'Lean On');
  assert.equal(plainTitle('Space Oddity - 2015 Remaster - Mono'), 'Space Oddity');
  assert.equal(plainTitle('Rumours (Deluxe Edition)'), 'Rumours');
  assert.equal(plainTitle('Flowers - Single'), 'Flowers');
  assert.equal(plainTitle('Dreams (Live)'), 'Dreams (Live)');
  assert.equal(plainTitle('Dreams - Live at the Forum'), 'Dreams - Live at the Forum');
  assert.equal(plainTitle('Blue Monday (Radio Edit)'), 'Blue Monday (Radio Edit)');
  assert.equal(plainTitle("Love Story (Taylor's Version)"), "Love Story (Taylor's Version)");
  assert.equal(plainTitle('(Remastered)'), '(Remastered)');
});

test('titleKey: accents, case and punctuation do not count', () => {
  assert.equal(titleKey('Déjà Vu'), titleKey('DEJA VU'));
  assert.equal(titleKey("Don't Stop"), titleKey('Don’t  Stop'));
  assert.equal(titleKey('Rock & Roll'), titleKey('Rock and Roll'));
  assert.equal(titleKey('Dreams (2004 Remaster)'), 'dreams');
  assert.notEqual(titleKey('Dreams (Live)'), titleKey('Dreams'));
  assert.equal(titleKey('夜に駆ける'), '夜に駆ける');
  assert.equal(titleKey('!!!'), '!!!');
});

test('sameArtist: accents, "The", and the first of several names', () => {
  assert.ok(sameArtist('Beyoncé', 'BEYONCE'));
  assert.ok(sameArtist('The Beatles', 'Beatles'));
  assert.ok(sameArtist('Queen & David Bowie', 'Queen'));
  assert.ok(sameArtist('Major Lazer, DJ Snake', 'Major Lazer feat. MØ'));
  assert.ok(sameArtist('Florence + the Machine', 'Florence and The Machine'));
  assert.ok(sameArtist('Simon & Garfunkel', 'Simon and Garfunkel'));
  assert.ok(!sameArtist('Fleetwood Mac', 'The Cranberries'));
  assert.ok(!sameArtist('', 'Fleetwood Mac'));
  assert.ok(!sameArtist('Fleetwood Mac', null));
});

const song = (title, artist, seconds, more = {}) => ({ kind: 'song', title, artist, durationMs: seconds * 1000, url: 'u:' + title + ':' + artist + ':' + seconds, ...more });

test('pickMatch: exact needs title, artist and a length within 3 seconds', () => {
  const want = song('Dreams', 'Fleetwood Mac', 257.4);
  const list = [song('Dreams', 'The Cranberries', 257), song('Dreams (Live)', 'Fleetwood Mac', 257), song('Dreams (2004 Remaster)', 'Fleetwood Mac', 257), song('Dreams', 'Fleetwood Mac', 254)];
  const m = pickMatch(want, list);
  assert.equal(m.mark, 'exact');
  assert.equal(m.item, list[2]);
  assert.equal(pickMatch(want, [song('Dreams', 'Fleetwood Mac', 260.4)]).mark, 'exact');
  assert.equal(pickMatch(want, [song('Dreams', 'Fleetwood Mac', 254.3)]).mark, 'guess');
  assert.equal(pickMatch(want, [song('Dreams', 'Fleetwood Mac', 260.5)]).mark, 'guess');
});

test('pickMatch: the closest length wins; without title and artist there is nothing', () => {
  const want = song('Dreams', 'Fleetwood Mac', 257);
  const far = song('Dreams', 'Fleetwood Mac', 300), near = song('DREAMS', 'fleetwood mac', 262);
  assert.deepEqual(pickMatch(want, [far, near]), { item: near, mark: 'guess' });
  assert.equal(pickMatch(want, [song('Dreams', 'Beck', 257), song('Dream', 'Fleetwood Mac', 257)]), null);
  assert.equal(pickMatch(want, []), null);
  // A length that is not known cannot make it exact.
  assert.equal(pickMatch(want, [{ kind: 'song', title: 'Dreams', artist: 'Fleetwood Mac', durationMs: null, url: 'x' }]).mark, 'guess');
  assert.equal(pickMatch({ ...want, durationMs: null }, [far]).mark, 'guess');
});

test('pickMatch: an album is exact when the number of songs agrees', () => {
  const want = { kind: 'album', title: 'Rumours', artist: 'Fleetwood Mac', count: 11 };
  const deluxe = { kind: 'album', title: 'Rumours (Deluxe Edition)', artist: 'Fleetwood Mac', count: 31, url: 'd' };
  const plain = { kind: 'album', title: 'Rumours', artist: 'Fleetwood Mac', count: 11, url: 'p' };
  assert.deepEqual(pickMatch(want, [deluxe, plain]), { item: plain, mark: 'exact' });
  assert.deepEqual(pickMatch(want, [deluxe]), { item: deluxe, mark: 'guess' });
});

// ---- Buttons and the copied text ----

test('searchUrl: each service’s own search, filled in', () => {
  const q = 'Fleetwood Mac Dreams';
  assert.equal(searchUrl('spotify', q), 'https://open.spotify.com/search/Fleetwood%20Mac%20Dreams');
  assert.equal(searchUrl('ytmusic', q), 'https://music.youtube.com/search?q=Fleetwood+Mac+Dreams');
  assert.equal(searchUrl('youtube', q), 'https://www.youtube.com/results?search_query=Fleetwood+Mac+Dreams');
  assert.equal(searchUrl('tidal', q), 'https://tidal.com/search?q=Fleetwood+Mac+Dreams');
  assert.equal(searchUrl('amazon', q), 'https://music.amazon.com/search/Fleetwood%20Mac%20Dreams');
  assert.equal(searchUrl('soundcloud', q), 'https://soundcloud.com/search?q=Fleetwood+Mac+Dreams');
  assert.equal(searchUrl('deezer', q), 'https://www.deezer.com/search/Fleetwood%20Mac%20Dreams');
  assert.equal(searchUrl('tidal', 'AC/DC T.N.T. & more?'), 'https://tidal.com/search?q=AC%2FDC+T.N.T.+%26+more%3F');
  assert.equal(searchUrl('spotify', 'AC/DC #1'), 'https://open.spotify.com/search/AC%2FDC%20%231');
});

test('buildServices: exact, best guess and search, in a fixed order', () => {
  const item = { title: 'Dreams (2004 Remaster)', artist: 'Fleetwood Mac' };
  assert.equal(queryOf(item), 'Fleetwood Mac Dreams');
  const list = buildServices(item, { apple: { url: 'A', mark: 'exact', note: 'pasted' }, deezer: { url: 'D', mark: 'guess', note: 'differs' } });
  assert.deepEqual(list.map((s) => s.id), SERVICES.map((s) => s.id));
  assert.deepEqual(list.map((s) => s.mark), ['exact', 'guess', 'search', 'search', 'search', 'search', 'search', 'search']);
  assert.equal(list[2].url, 'https://open.spotify.com/search/Fleetwood%20Mac%20Dreams');
  // Nothing found on Deezer: its button is a search too.
  assert.equal(buildServices(item, {})[1].url, 'https://www.deezer.com/search/Fleetwood%20Mac%20Dreams');
});

test('copyText: the block "Copy all links" copies', () => {
  const services = buildServices({ title: 'Dreams', artist: 'Fleetwood Mac' }, {
    apple: { url: 'https://music.apple.com/us/album/dreams/594061854?i=594061857', mark: 'exact' },
    deezer: { url: 'https://www.deezer.com/track/63480987', mark: 'guess' },
  });
  const text = copyText({ kind: 'song', title: 'Dreams', artist: 'Fleetwood Mac', album: 'Rumours', services }, { guess: 'best guess', search: 'search' });
  assert.equal(text, [
    'Dreams – Fleetwood Mac',
    'Rumours',
    '',
    'Apple Music: https://music.apple.com/us/album/dreams/594061854?i=594061857',
    'Deezer (best guess): https://www.deezer.com/track/63480987',
    'Spotify (search): https://open.spotify.com/search/Fleetwood%20Mac%20Dreams',
    'YouTube Music (search): https://music.youtube.com/search?q=Fleetwood+Mac+Dreams',
    'YouTube (search): https://www.youtube.com/results?search_query=Fleetwood+Mac+Dreams',
    'TIDAL (search): https://tidal.com/search?q=Fleetwood+Mac+Dreams',
    'Amazon Music (search): https://music.amazon.com/search/Fleetwood%20Mac%20Dreams',
    'SoundCloud (search): https://soundcloud.com/search?q=Fleetwood+Mac+Dreams',
  ].join('\n'));
  const album = copyText({ kind: 'album', title: 'Rumours', artist: '', album: null, services: [{ name: 'Spotify', mark: 'exact', url: 'S' }] }, { guess: 'ipotesi', search: 'ricerca' });
  assert.equal(album, 'Rumours\n\nSpotify: S');
});

test('clock', () => {
  assert.equal(clock(257400), '4:17');
  assert.equal(clock(59600), '1:00');
  assert.equal(clock(3000), '0:03');
});

// ---- Asking ----

const answer = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => { if (body === undefined) throw new SyntaxError('no json'); return body; } });
const noWait = () => Promise.resolve();

test('getJson: an answer, not found, and a body that is not JSON', async () => {
  const seen = [];
  const fetch = async (url, init) => { seen.push([url, init.credentials]); return answer(200, { a: 1 }); };
  assert.deepEqual(await getJson('https://x/y', { fetch }), { a: 1 });
  assert.deepEqual(seen, [['https://x/y', 'omit']]);
  await rejects(getJson('u', { name: 'Spotify', fetch: async () => answer(404) }), 'not-found', 'Spotify');
  await rejects(getJson('u', { name: 'Spotify', fetch: async () => answer(400) }), 'not-found');
  await rejects(getJson('u', { name: 'Spotify', fetch: async () => answer(200) }), 'no-answer', 'Spotify');
});

test('getJson: a network error, a refusal, and a refusal that passes when asked again', async () => {
  await rejects(getJson('u', { name: 'Apple Music', fetch: async () => { throw new TypeError('Failed to fetch'); } }), 'no-answer', 'Apple Music');
  let calls = 0;
  await rejects(getJson('u', { name: 'Apple Music', fetch: async () => { calls++; return answer(403); }, wait: noWait }), 'no-answer');
  assert.equal(calls, 1);
  calls = 0;
  await rejects(getJson('u', { retries: 2, fetch: async () => { calls++; return answer(503); }, wait: noWait }), 'no-answer');
  assert.equal(calls, 3);
  calls = 0;
  assert.deepEqual(await getJson('u', { retries: 2, fetch: async () => (++calls < 2 ? answer(403) : answer(200, [1])), wait: noWait }), [1]);
  assert.equal(calls, 2);
});

test('getJson: no answer within the timeout', async () => {
  const hang = (url, { signal }) => new Promise((res, rej) => signal.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError'))));
  const t0 = Date.now();
  await rejects(getJson('u', { name: 'Spotify', timeout: 30, fetch: hang }), 'no-answer', 'Spotify');
  assert.ok(Date.now() - t0 < 1000);
  // The timeout also ends the waiting between two tries.
  await rejects(getJson('u', { timeout: 30, retries: 5, fetch: async () => answer(403), wait: () => new Promise((r) => setTimeout(r, 60)) }), 'no-answer');
});

/** A document that keeps the script tags it is given, and a window for the callbacks. */
function fakePage() {
  const scripts = [];
  const doc = {
    createElement: () => ({ remove() { this.removed = true; } }),
    head: { append: (s) => scripts.push(s) },
  };
  return { doc, scope: {}, scripts, callbackOf: (s) => /callback=(\w+)/.exec(s.src)[1] };
}

test('jsonp: the answer, and the tag and the function gone after it', async () => {
  const page = fakePage();
  const p = jsonp('https://api.deezer.com/track/1', { doc: page.doc, scope: page.scope });
  const s = page.scripts[0];
  assert.match(s.src, /^https:\/\/api\.deezer\.com\/track\/1\?output=jsonp&callback=trackhopLink\d+$/);
  page.scope[page.callbackOf(s)]({ id: 1, title: 'x' });
  assert.deepEqual(await p, { id: 1, title: 'x' });
  assert.ok(s.removed);
  assert.deepEqual(Object.keys(page.scope), []);
  const q = jsonp('https://api.deezer.com/search?q=a', { doc: page.doc, scope: page.scope });
  assert.match(page.scripts[1].src, /search\?q=a&output=jsonp&callback=/);
  page.scope[page.callbackOf(page.scripts[1])]({ data: [] });
  assert.deepEqual(await q, { data: [] });
});

test('jsonp: Deezer’s errors, a script that does not load, and a timeout', async () => {
  const page = fakePage();
  const missing = jsonp('u', { name: 'Deezer', doc: page.doc, scope: page.scope });
  page.scope[page.callbackOf(page.scripts[0])]({ error: { type: 'DataException', message: 'no data', code: 800 } });
  await rejects(missing, 'not-found', 'Deezer');
  const quota = jsonp('u', { name: 'Deezer', doc: page.doc, scope: page.scope });
  page.scope[page.callbackOf(page.scripts[1])]({ error: { type: 'Exception', message: 'Quota limit exceeded', code: 4 } });
  await rejects(quota, 'no-answer', 'Deezer');
  const broken = jsonp('u', { name: 'Deezer', doc: page.doc, scope: page.scope });
  page.scripts[2].onerror();
  await rejects(broken, 'no-answer', 'Deezer');
  assert.ok(page.scripts[2].removed);
  assert.deepEqual(Object.keys(page.scope), []);

  const slow = jsonp('u', { name: 'Deezer', timeout: 20, doc: page.doc, scope: page.scope });
  await rejects(slow, 'no-answer', 'Deezer');
  assert.ok(page.scripts[3].removed);
  // The answer that comes too late finds a function, does nothing, and takes the function away.
  const late = page.callbackOf(page.scripts[3]);
  assert.equal(typeof page.scope[late], 'function');
  page.scope[late]({ id: 1 });
  assert.deepEqual(Object.keys(page.scope), []);
});

// ---- The lookup, with the services played by the test ----

const itunesSong = (id, name, artist, ms, album = 'Rumours') => ({ wrapperType: 'track', kind: 'song', trackId: id, trackName: name, artistName: artist, collectionName: album,
  trackTimeMillis: ms, releaseDate: '1977-02-04T08:00:00Z', artworkUrl100: 'https://is1-ssl.mzstatic.com/a/100x100bb.jpg', trackViewUrl: 'https://music.apple.com/us/album/x/1?i=' + id + '&uo=4' });
const deezerSong = (id, title, artist, seconds) => ({ id, title, duration: seconds, artist: { name: artist }, album: { title: 'Rumours', cover_big: 'https://cdn-images.dzcdn.net/c.jpg' }, isrc: 'USWB10400046' });

/** What is asked on top of the rest (MusicBrainz, a barcode, a Deezer song or album whole): a test that gives no answer for it gets "not found". */
const OPTIONAL = /musicbrainz\.org|query\.wikidata\.org|labs\.api\.listenbrainz\.org|lookup\?upc=|api\.deezer\.com\/(track|album)\//;

/**
 * A net that answers by the first matching piece of the URL, and keeps what it was asked: in
 * [asked], or in [extra] the optional requests the test has no answer for. [covers] maps a
 * picture's URL to its signature; without it the net reads no pictures.
 */
function fakeNet(routes, covers) {
  const asked = [], extra = [];
  const ask = (via) => async (url, opts) => {
    for (const [piece, reply] of routes) {
      if (!url.includes(piece)) continue;
      asked.push(via + ' ' + url);
      if (reply instanceof Error) throw reply;
      return typeof reply === 'function' ? reply(url, opts) : reply;
    }
    if (OPTIONAL.test(url)) { extra.push(via + ' ' + url); throw new ToolError('not-found', 'optional'); }
    throw new Error('unexpected request ' + url);
  };
  const net = { json: ask('fetch'), jsonp: ask('jsonp'), asked, extra, routes };
  if (covers) net.cover = async (url) => { asked.push('cover ' + url); return covers[url] || null; };
  return net;
}
const marks = (r) => Object.fromEntries(r.services.map((s) => [s.id, s.mark]));

test('from Apple Music: Deezer is exact when title, artist and length agree', async () => {
  const net = fakeNet([
    ['itunes.apple.com/lookup', { resultCount: 1, results: [itunesSong(594061857, 'Dreams', 'Fleetwood Mac', 257800)] }],
    ['api.deezer.com/search', { data: [deezerSong(1, 'Dreams', 'The Cranberries', 257), deezerSong(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 257)] }],
  ]);
  const r = await lookup(parseLink('https://music.apple.com/gb/album/dreams/594061854?i=594061857'), net);
  assert.deepEqual(net.asked, [
    'fetch https://itunes.apple.com/lookup?id=594061857&entity=song&country=gb',
    'jsonp https://api.deezer.com/search?q=Fleetwood%20Mac%20Dreams&limit=10',
  ]);
  assert.deepEqual(marks(r), { apple: 'exact', deezer: 'exact', spotify: 'search', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });
  assert.equal(r.services[0].url, 'https://music.apple.com/gb/album/dreams/594061854?i=594061857');
  assert.equal(r.services[0].note, 'pasted');
  assert.deepEqual(r.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/track/63480987', mark: 'exact', note: 'same' });
  assert.equal(r.title, 'Dreams');
  assert.equal(r.artist, 'Fleetwood Mac');
  assert.equal(r.album, 'Rumours');
  assert.equal(r.durationMs, 257800);
  assert.equal(r.art, 'https://is1-ssl.mzstatic.com/a/600x600bb.jpg');
  assert.equal(r.query, 'Fleetwood Mac Dreams');
  assert.equal(r.guess, false);
});

test('from Apple Music: another length is a best guess, nothing fitting is a search', async () => {
  const apple = ['itunes.apple.com/lookup', { results: [itunesSong(1, 'Dreams', 'Fleetwood Mac', 257800)] }];
  const guess = await lookup(parseLink('https://music.apple.com/us/song/dreams/1'), fakeNet([apple, ['api.deezer.com/search', { data: [deezerSong(7, 'Dreams', 'Fleetwood Mac', 301)] }]]));
  assert.deepEqual(guess.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/track/7', mark: 'guess', note: 'differs' });
  const none = await lookup(parseLink('https://music.apple.com/us/song/dreams/1'), fakeNet([apple, ['api.deezer.com/search', { data: [deezerSong(7, 'Dreams', 'Beck', 257)] }]]));
  assert.deepEqual(none.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/search/Fleetwood%20Mac%20Dreams', mark: 'search', note: null });
  const empty = await lookup(parseLink('https://music.apple.com/us/song/dreams/1'), fakeNet([apple, ['api.deezer.com/search', { data: [] }]]));
  assert.equal(empty.services[1].mark, 'search');
});

test('from Apple Music: an album asks the album search and compares the number of songs', async () => {
  const net = fakeNet([
    ['itunes.apple.com/lookup', { results: [{ wrapperType: 'collection', collectionName: 'Rumours', artistName: 'Fleetwood Mac', trackCount: 11, releaseDate: '1977-02-04T08:00:00Z',
      artworkUrl100: 'https://x/100x100bb.jpg', collectionViewUrl: 'https://music.apple.com/us/album/rumours/594061854?uo=4' }] }],
    ['api.deezer.com/search/album', { data: [{ id: 9, title: 'Rumours (Deluxe Edition)', nb_tracks: 31, artist: { name: 'Fleetwood Mac' } }, { id: 6237061, title: 'Rumours', nb_tracks: 11, artist: { name: 'Fleetwood Mac' }, cover_big: 'c' }] }],
  ]);
  const r = await lookup(parseLink('https://music.apple.com/us/album/rumours/594061854'), net);
  assert.deepEqual(net.asked, ['fetch https://itunes.apple.com/lookup?id=594061854&entity=album&country=us', 'jsonp https://api.deezer.com/search/album?q=Fleetwood%20Mac%20Rumours&limit=10']);
  assert.equal(r.kind, 'album');
  assert.equal(r.count, 11);
  assert.equal(r.year, '1977');
  assert.deepEqual(r.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/album/6237061', mark: 'exact', note: 'same' });
});

test('from Deezer: Apple Music through the iTunes search', async () => {
  const net = fakeNet([
    ['api.deezer.com/track/63480987', deezerSong(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 257)],
    ['itunes.apple.com/search', { results: [itunesSong(5, 'Dreams (Live)', 'Fleetwood Mac', 257000), itunesSong(202272624, 'Dreams', 'Fleetwood Mac', 254453), itunesSong(594061857, 'Dreams', 'Fleetwood Mac', 257800)] }],
  ]);
  const r = await lookup(parseLink('https://www.deezer.com/it/track/63480987'), net);
  assert.deepEqual(net.asked, ['jsonp https://api.deezer.com/track/63480987', 'fetch https://itunes.apple.com/search?term=Fleetwood+Mac+Dreams&entity=song&country=us&limit=25']);
  assert.deepEqual(r.services[0], { id: 'apple', name: 'Apple Music', url: 'https://music.apple.com/us/album/x/1?i=594061857', mark: 'exact', note: 'same' });
  assert.deepEqual(r.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/track/63480987', mark: 'exact', note: 'pasted' });
  assert.equal(r.title, 'Dreams (2004 Remaster)');
  assert.equal(r.durationMs, 257000);
  assert.equal(r.art, 'https://cdn-images.dzcdn.net/c.jpg');
});

test('a song that is not there, a service that does not answer: an error and no result', async () => {
  const link = parseLink('https://music.apple.com/us/song/dreams/1');
  await rejects(lookup(link, fakeNet([['itunes.apple.com/lookup', { resultCount: 0, results: [] }]])), 'not-found', 'Apple Music');
  await rejects(lookup(link, fakeNet([['itunes.apple.com/lookup', new ToolError('no-answer', 'Apple Music')]])), 'no-answer', 'Apple Music');
  // The song was read, but the other catalogue is silent: a whole result, where that button is a search.
  const alone = await lookup(link, fakeNet([['itunes.apple.com/lookup', { results: [itunesSong(1, 'Dreams', 'Fleetwood Mac', 257800)] }], ['api.deezer.com', new ToolError('no-answer', 'Deezer')]]));
  assert.deepEqual(alone.services.slice(0, 2).map((x) => x.mark), ['exact', 'search']);
  await rejects(lookup(link, fakeNet([['itunes.apple.com/lookup', { results: [itunesSong(1, 'Dreams', 'Fleetwood Mac', 257800)] }], ['api.deezer.com', new ToolError('not-found', 'Deezer')]])), 'not-found');
  const deezer = parseLink('https://www.deezer.com/track/5');
  await rejects(lookup(deezer, fakeNet([['api.deezer.com/track/5', new ToolError('not-found', 'Deezer')]])), 'not-found', 'Deezer');
  await rejects(lookup(deezer, fakeNet([['api.deezer.com/track/5', {}]])), 'not-found', 'Deezer');
  const refused = await lookup(deezer, fakeNet([['api.deezer.com/track/5', deezerSong(5, 'Dreams', 'Fleetwood Mac', 257)], ['itunes.apple.com/search', new ToolError('no-answer', 'Apple Music')]]));
  assert.deepEqual(refused.services.slice(0, 2).map((x) => x.mark), ['search', 'exact']);
  assert.equal(refused.services[0].url, 'https://music.apple.com/search?term=Fleetwood+Mac+Dreams');
  await rejects(lookup(deezer, fakeNet([['api.deezer.com/track/5', new ToolError('no-answer', 'Deezer')]])), 'no-answer', 'Deezer');
  await rejects(lookup(parseLink('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'), fakeNet([['open.spotify.com/oembed', new ToolError('not-found', 'Spotify')]])), 'not-found', 'Spotify');
});

const spotifyRoutes = () => [
  ['open.spotify.com/oembed', { title: 'Dreams - 2004 Remaster', thumbnail_url: 'https://image-cdn-fa.spotifycdn.com/image/x' }],
  ['term=Dreams&', { results: [itunesSong(13, 'Dreams', 'The Cranberries', 271000, 'Everybody Else Is Doing It'), itunesSong(11, 'Dreams', 'Fleetwood Mac', 257800), itunesSong(12, 'Dreams (Live)', 'Fleetwood Mac', 250000),
    itunesSong(14, 'Dreams', 'Fleetwood Mac', 254000, 'Greatest Hits'), itunesSong(15, 'Dreams', 'Beck', 297000, 'Colors'), itunesSong(16, 'Sweet Dreams', 'Eurythmics', 216000)] }],
  ['api.deezer.com/search?q=Dreams&', { data: [deezerSong(90, 'Dreams', 'Nobody Famous', 200), deezerSong(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 257), deezerSong(91, 'Dreams', 'Fleetwood Mac', 300), deezerSong(77, 'Dreams', 'The Cranberries', 271)] }],
  ['term=Beck+Dreams', { results: [] }],
  ['api.deezer.com/search?q=Beck%20Dreams', { data: [deezerSong(55, 'Dreams', 'Beck', 297)] }],
  ['term=Gabrielle+Dreams', { results: [itunesSong(21, 'Dreams', 'Gabrielle', 223000, 'Find Your Way')] }],
  ['api.deezer.com/search?q=Gabrielle%20Dreams', { data: [] }],
  ['term=Nobody+Dreams', { results: [] }],
  ['api.deezer.com/search?q=Nobody%20Dreams', { data: [] }],
];

test('from Spotify: only the title is known, so the artist is a best guess', async () => {
  const net = fakeNet(spotifyRoutes());
  const r = await lookup(parseLink('https://open.spotify.com/intl-it/track/0ofHAoxe9vBkTCp2UQIavz?si=1'), net);
  assert.deepEqual(net.asked, [
    'fetch https://open.spotify.com/oembed?url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F0ofHAoxe9vBkTCp2UQIavz',
    'fetch https://itunes.apple.com/search?term=Dreams&entity=song&country=it&limit=50',
    'jsonp https://api.deezer.com/search?q=Dreams&limit=50',
  ]);
  // MusicBrainz was asked first, and did not know the link.
  assert.equal(net.extra[0], 'fetch https://musicbrainz.org/ws/2/url?resource=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F0ofHAoxe9vBkTCp2UQIavz&inc=recording-rels&fmt=json');
  // Then Wikidata, by the pasted Spotify ID only (the artist is a guess: nothing is asked by it).
  assert.equal(net.extra.length, 2);
  assert.ok(decodeURIComponent(net.extra[1]).includes('?i wdt:P2207 "0ofHAoxe9vBkTCp2UQIavz".'));
  assert.equal(r.fix, true);
  assert.equal(r.guess, true);
  assert.equal(r.title, 'Dreams - 2004 Remaster');
  // High in both catalogues, and there more than once: before the first of either list.
  assert.equal(r.artist, 'Fleetwood Mac');
  assert.equal(r.album, 'Rumours');
  assert.equal(r.art, 'https://image-cdn-fa.spotifycdn.com/image/x');
  assert.deepEqual(r.alts, ['The Cranberries', 'Nobody Famous', 'Beck']);
  assert.deepEqual(marks(r), { apple: 'guess', deezer: 'guess', spotify: 'exact', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });
  assert.deepEqual(r.services[2], { id: 'spotify', name: 'Spotify', url: 'https://open.spotify.com/track/0ofHAoxe9vBkTCp2UQIavz', mark: 'exact', note: 'pasted' });
  assert.equal(r.services[0].url, 'https://music.apple.com/us/album/x/1?i=11');
  // Of Deezer's two, the one as long as Apple's.
  assert.deepEqual(r.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/track/63480987', mark: 'guess', note: 'guessed', label: 'Dreams, Fleetwood Mac' });
  assert.equal(r.services[3].url, 'https://music.youtube.com/search?q=Fleetwood+Mac+Dreams');
});

test('from Spotify: the visitor corrects the artist', async () => {
  const net = fakeNet(spotifyRoutes());
  const base = await spotifyBase(parseLink('https://open.spotify.com/track/0ofHAoxe9vBkTCp2UQIavz'), net);
  assert.equal(base.apple.length, 4);
  assert.equal(base.deezer.length, 4);
  net.asked.length = 0;

  // One of the "Or was it" artists that both catalogues brought up: nothing more is asked.
  const other = await spotifyResult(base, 'the cranberries', net);
  assert.deepEqual(net.asked, []);
  assert.equal(other.artist, 'The Cranberries');
  assert.equal(other.album, 'Everybody Else Is Doing It');
  assert.equal(other.services[0].url, 'https://music.apple.com/us/album/x/1?i=13');
  assert.equal(other.services[1].url, 'https://www.deezer.com/track/77');
  assert.deepEqual(other.alts, ['Fleetwood Mac', 'Nobody Famous', 'Beck']);
  assert.equal(other.query, 'The Cranberries Dreams');

  // One that only Apple brought up: Deezer is asked for it.
  const beck = await spotifyResult(base, 'Beck', net);
  assert.deepEqual(net.asked, ['jsonp https://api.deezer.com/search?q=Beck%20Dreams&limit=10']);
  assert.equal(beck.services[1].url, 'https://www.deezer.com/track/55');

  // An artist the title searches did not bring up: a search with the name, on both sides.
  net.asked.length = 0;
  const typed = await spotifyResult(base, 'Gabrielle', net);
  assert.deepEqual(net.asked, ['fetch https://itunes.apple.com/search?term=Gabrielle+Dreams&entity=song&country=us&limit=25', 'jsonp https://api.deezer.com/search?q=Gabrielle%20Dreams&limit=10']);
  assert.deepEqual(marks(typed), { apple: 'guess', deezer: 'search', spotify: 'exact', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });
  assert.equal(typed.services[1].url, 'https://www.deezer.com/search/Gabrielle%20Dreams');

  // An artist nobody has: the name is kept for the searches, and nothing pretends to be a song page.
  const nobody = await spotifyResult(base, 'Nobody', net);
  assert.equal(nobody.artist, 'Nobody');
  assert.equal(nobody.album, null);
  assert.deepEqual(marks(nobody), { apple: 'search', deezer: 'search', spotify: 'exact', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });
  assert.equal(nobody.services[0].url, 'https://music.apple.com/search?term=Nobody+Dreams');
});

test('from Spotify: an album, and an artist only one catalogue has under that title', async () => {
  const album = (id, name, artist, n) => ({ wrapperType: 'collection', collectionName: name, artistName: artist, trackCount: n, collectionViewUrl: 'https://music.apple.com/us/album/x/' + id });
  const net = fakeNet([
    ['open.spotify.com/oembed', { title: 'The Dark Side of the Moon' }],
    ['itunes.apple.com/search', { results: [album(1, 'The Dark Side of the Moon - Single', 'Chimera', 2), album(2, 'The Dark Side of the Moon', 'Pink Floyd', 10)] }],
    ['api.deezer.com/search/album', { data: [{ id: 12114240, title: 'The Dark Side of the Moon', nb_tracks: 10, artist: { name: 'Pink Floyd' } }] }],
  ]);
  const r = await lookup(parseLink('https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv'), net);
  assert.deepEqual(net.asked.slice(1), ['fetch https://itunes.apple.com/search?term=The+Dark+Side+of+the+Moon&entity=album&country=us&limit=50', 'jsonp https://api.deezer.com/search/album?q=The%20Dark%20Side%20of%20the%20Moon&limit=50']);
  assert.equal(r.artist, 'Pink Floyd');
  assert.equal(r.services[0].url, 'https://music.apple.com/us/album/x/2');
  assert.equal(r.services[1].url, 'https://www.deezer.com/album/12114240');
  assert.deepEqual(r.alts, ['Chimera']);
  assert.equal(r.services[0].mark, 'guess');
});

test('from Spotify: one catalogue not answering leaves its button a search; both is an error', async () => {
  const oembed = ['open.spotify.com/oembed', { title: 'Dreams - 2004 Remaster' }];
  const deezer = ['api.deezer.com/search?q=Dreams&', { data: [deezerSong(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 257), deezerSong(77, 'Dreams', 'The Cranberries', 271), deezerSong(79, 'Dreams', 'Fleetwood Mac', 254)] }];
  const link = parseLink('https://open.spotify.com/track/0ofHAoxe9vBkTCp2UQIavz');
  const net = fakeNet([oembed, ['itunes.apple.com/search', new ToolError('no-answer', 'Apple Music')], deezer]);
  const r = await lookup(link, net);
  assert.equal(net.asked.length, 3);
  assert.equal(r.artist, 'Fleetwood Mac');
  assert.deepEqual(r.alts, ['The Cranberries']);
  assert.deepEqual(marks(r), { apple: 'search', deezer: 'guess', spotify: 'exact', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });
  assert.equal(r.services[1].url, 'https://www.deezer.com/track/63480987');
  assert.equal(r.services[0].url, 'https://music.apple.com/search?term=Fleetwood+Mac+Dreams');

  const noDeezer = await lookup(link, fakeNet([oembed, ['itunes.apple.com/search', { results: [itunesSong(11, 'Dreams', 'Fleetwood Mac', 257800)] }], ['api.deezer.com', new ToolError('no-answer', 'Deezer')]]));
  assert.deepEqual(marks(noDeezer), { apple: 'guess', deezer: 'search', spotify: 'exact', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });

  await rejects(lookup(link, fakeNet([oembed, ['itunes.apple.com/search', new ToolError('no-answer', 'Apple Music')], ['api.deezer.com', new ToolError('no-answer', 'Deezer')]])), 'no-answer');

  // Apple answers for the title, then not for the corrected artist: still a whole result, with a search for Apple.
  const flaky = fakeNet([oembed, ['term=Dreams&', { results: [itunesSong(11, 'Dreams', 'Fleetwood Mac', 257800)] }], deezer, ['term=Gabrielle', new ToolError('no-answer', 'Apple Music')], ['api.deezer.com/search?q=Gabrielle', { data: [] }]]);
  const typed = await lookup(link, flaky, { artist: 'Gabrielle' });
  assert.equal(typed.artist, 'Gabrielle');
  assert.equal(typed.services[0].mark, 'search');
});

test('from Spotify: a title no catalogue song has leaves the artist empty', async () => {
  const net = fakeNet([['open.spotify.com/oembed', { title: 'Zzyzx Qwv' }], ['itunes.apple.com/search', { results: [itunesSong(1, 'Something Else', 'Someone', 1000)] }], ['api.deezer.com/search', { data: [] }]]);
  const r = await lookup(parseLink('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'), net);
  assert.equal(net.asked.length, 3);
  assert.equal(r.artist, '');
  assert.deepEqual(r.alts, []);
  assert.equal(r.query, 'Zzyzx Qwv');
  assert.deepEqual(marks(r), { apple: 'search', deezer: 'search', spotify: 'exact', ytmusic: 'search', youtube: 'search', tidal: 'search', amazon: 'search', soundcloud: 'search' });
  await rejects(lookup(parseLink('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'), fakeNet([['open.spotify.com/oembed', {}]])), 'not-found', 'Spotify');
});

// ---- Covers ----

/** A made-up cover, [size] pixels a side: coloured shapes placed by [seed], then [change](r, g, b, x, y) on every pixel. */
function picture(seed, size, change = (r, g, b) => [r, g, b]) {
  let state = seed;
  const rnd = () => { state = (state * 1103515245 + 12345) % 2147483648; return state / 2147483648; };
  const shapes = Array.from({ length: 7 }, () => ({ x: rnd(), y: rnd(), r: 0.1 + rnd() * 0.3, c: [rnd() * 255, rnd() * 255, rnd() * 255] }));
  const back = [rnd() * 255, rnd() * 255, rnd() * 255];
  const rgba = new Uint8ClampedArray(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let c = back;
      for (const s of shapes) if (Math.hypot((x + 0.5) / size - s.x, (y + 0.5) / size - s.y) < s.r) c = s.c;
      rgba.set([...change(c[0], c[1], c[2], x, y), 255], (y * size + x) * 4);
    }
  }
  return coverSignature(rgba, size, size);
}
const noise = (amount, seed = 7) => { let s = seed; return (r, g, b) => [r, g, b].map((v) => { s = (s * 1103515245 + 12345) % 2147483648; return v + (s / 2147483648 - 0.5) * amount; }); };

test('coverSignature: a 16 x 16 grid of colours; nothing for a flat or too small picture', () => {
  const sig = picture(1, 300);
  assert.equal(sig.length, COVER_GRID * COVER_GRID * 3);
  const half = new Uint8ClampedArray(32 * 32 * 4);
  for (let i = 0; i < 32 * 32; i++) half.set(i % 32 < 16 ? [200, 100, 0, 255] : [0, 100, 200, 255], i * 4);
  const two = coverSignature(half, 32, 32);
  assert.deepEqual([...two.slice(0, 3)], [200, 100, 0]);
  assert.deepEqual([...two.slice(15 * 3, 16 * 3)], [0, 100, 200]);
  // One flat colour (and the noise a guarded canvas hands out, which averages to one) is no cover.
  assert.equal(coverSignature(new Uint8ClampedArray(64 * 64 * 4).fill(255), 64, 64), null);
  assert.equal(picture(1, 64, () => noise(4)(128, 128, 128)), null);
  assert.equal(coverSignature(new Uint8ClampedArray(8 * 8 * 4), 8, 8), null);
  assert.equal(coverSignature(null, 64, 64), null);
});

test('coverDistance: the same artwork in another size, compression, tint or crop is close; another cover is far', () => {
  const spotify = picture(1, 300);
  assert.equal(coverDistance(spotify, spotify), 0);
  // Deezer's 56 px and Apple's 100 px of the same file, with the grain of a JPEG.
  assert.ok(coverDistance(spotify, picture(1, 56, noise(24))) < 0.05);
  assert.ok(coverDistance(spotify, picture(1, 100, noise(24, 3))) < 0.05);
  // Darker and warmer, as another mastering of the scan is.
  assert.ok(coverDistance(spotify, picture(1, 100, (r, g, b) => [r * 0.8 + 20, g * 0.75, b * 0.7])) < COVER_SAME);
  // Moved by a hundredth of its width: another crop.
  const moved = picture(1, 300, (r, g, b) => [r, g, b]);
  const shifted = coverSignature(shift(1, 300, 3), 300, 300);
  assert.ok(coverDistance(moved, shifted) < COVER_SAME);
  for (const other of [2, 3, 4, 5, 6, 7, 8, 9]) assert.ok(coverDistance(spotify, picture(other, 56)) > COVER_SAME * 2, 'cover ' + other);
});

/** picture(seed, size) as pixels, moved [by] pixels to the right and down. */
function shift(seed, size, by) {
  let state = seed;
  const rnd = () => { state = (state * 1103515245 + 12345) % 2147483648; return state / 2147483648; };
  const shapes = Array.from({ length: 7 }, () => ({ x: rnd(), y: rnd(), r: 0.1 + rnd() * 0.3, c: [rnd() * 255, rnd() * 255, rnd() * 255] }));
  const back = [rnd() * 255, rnd() * 255, rnd() * 255];
  const rgba = new Uint8ClampedArray(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let c = back;
      for (const s of shapes) if (Math.hypot((x - by + 0.5) / size - s.x, (y - by + 0.5) / size - s.y) < s.r) c = s.c;
      rgba.set([c[0], c[1], c[2], 255], (y * size + x) * 4);
    }
  }
  return rgba;
}

test('pickCover: the same artwork, clearly; never a near miss, never one of two artists', () => {
  const c = (artist, d) => ({ artist, d });
  const own = c('Saint Asonia', 0.042);
  assert.equal(pickCover([c('Poppy Fusée', 0.71), own, c('Rachel Platten', 0.64), c('Nobody', null)]), own);
  // Another scan of the same cover, at the far end of what was measured.
  assert.equal(pickCover([c('Rick Astley', 0.103), c('Addal', 0.56)]).artist, 'Rick Astley');
  // The closest is not the same artwork: no match, however far the others are.
  assert.equal(pickCover([c('Fleetwood Mac', 0.19), c('Shindy', 0.73)]), null);
  assert.equal(pickCover([c('A', COVER_SAME + 0.001)]), null);
  assert.equal(pickCover([c('A', COVER_SAME)]).artist, 'A');
  // Two artists with the same artwork (a compilation, a tribute that copies it): ambiguous.
  assert.equal(pickCover([c('Pink Floyd', 0.04), c('Tribute Band', 0.06)]), null);
  assert.equal(pickCover([c('Queen', 0.096), c('Marc Martel', 0.18)]), null);
  // The borrowed artwork is told from the original when the original is that much closer.
  assert.equal(pickCover([c('Pink Floyd', 0.008), c('Ranjit Souri', 0.066)]).artist, 'Pink Floyd');
  assert.equal(pickCover([c('Pink Floyd', 0.008), c('Ranjit Souri', 0.05)]), null);
  // The album, its deluxe edition and its single share a cover: one artist, no doubt.
  assert.equal(pickCover([c('Fleetwood Mac', 0.006), c('Fleetwood Mac', 0.007), c('The Fleetwood Mac', 0.19), c('Beck', 0.8)]).d, 0.006);
  assert.equal(pickCover([c('A', null), c('B', null)]), null);
  assert.equal(pickCover([]), null);
  assert.equal(COVER_SAME, 0.14);
  assert.equal(COVER_MARGIN, 0.05);
});

test('albumSong: the song in the same place on the album, else the only one with the title', () => {
  const s = (track, title, seconds, disc = 1) => ({ title, track, disc, durationMs: seconds * 1000 });
  const album = [s(1, 'Second Hand News', 176), s(2, 'Dreams', 258), s(3, 'Never Going Back Again', 134), s(1, 'Dreams (Live)', 250, 2), s(2, 'Dreams', 300, 2)];
  assert.equal(albumSong({ title: 'Dreams - 2004 Remaster', track: 2, disc: 1, durationMs: 257000 }, album), album[1]);
  assert.equal(albumSong({ title: 'Dreams', track: 2, disc: 2, durationMs: 300000 }, album), album[4]);
  // The same place under another spelling counts when the length agrees.
  assert.equal(albumSong({ title: 'Never Goin’ Back', track: 3, disc: 1, durationMs: 135000 }, album), album[2]);
  assert.equal(albumSong({ title: 'Never Goin’ Back', track: 3, disc: 1, durationMs: 200000 }, album), null);
  // No place known: the title alone, when one song has it; two need the length to tell them apart.
  assert.equal(albumSong({ title: 'Second Hand News', durationMs: null }, album), album[0]);
  assert.equal(albumSong({ title: 'Dreams', durationMs: null }, album), null);
  assert.equal(albumSong({ title: 'Dreams', durationMs: 299000 }, album), album[4]);
  assert.equal(albumSong({ title: 'Go Your Own Way', track: 5, durationMs: 1 }, album), null);
  assert.equal(albumSong({ title: 'Dreams', track: 2 }, []), null);
});

test('matches: title and artist, the closest length first', () => {
  const want = { kind: 'song', title: 'Dreams', artist: 'Fleetwood Mac', durationMs: 257000 };
  const c = (id, title, artist, ms) => ({ id, title, artist, durationMs: ms });
  const list = [c(1, 'Dreams', 'Fleetwood Mac', 300000), c(2, 'Dreams', 'Beck', 257000), c(3, 'Dreams (Remastered)', 'Fleetwood Mac', 258000), c(4, 'Dreams', 'Fleetwood Mac', null)];
  assert.deepEqual(matches(want, list).map((x) => x.id), [3, 1, 4]);
  assert.deepEqual(matches({ ...want, durationMs: null }, list).map((x) => x.id), [1, 3, 4]);
});

test('spaced: MusicBrainz is asked once a second at most, and never waited on for long', async () => {
  let now = 1000;
  const waits = [], sent = [];
  const json = spaced(async (url) => { sent.push(url + ' @' + now); return 1; }, { now: () => now, wait: async (ms) => { waits.push(ms); now += ms; } });
  await json('https://musicbrainz.org/ws/2/url?x=1');
  await json('https://itunes.apple.com/lookup?id=1');
  await json('https://musicbrainz.org/ws/2/recording/1');
  assert.deepEqual(waits, [1100]);
  assert.deepEqual(sent, ['https://musicbrainz.org/ws/2/url?x=1 @1000', 'https://itunes.apple.com/lookup?id=1 @1000', 'https://musicbrainz.org/ws/2/recording/1 @2100']);
  // Three more at once: the second waits its second, the third and fourth would wait too long and are not sent.
  now = 2200;
  const still = spaced(async () => 1, { now: () => now, wait: async () => {} });
  const all = await Promise.allSettled([1, 2, 3, 4, 5].map((n) => still('https://musicbrainz.org/ws/2/url?x=' + n, { name: 'MusicBrainz' })));
  assert.deepEqual(all.map((x) => x.status), ['fulfilled', 'fulfilled', 'fulfilled', 'rejected', 'rejected']);
  assert.equal(all[4].reason.code, 'no-answer');
});

test('getJson and jsonp: an aborted signal ends the request as "no answer"', async () => {
  const gone = new AbortController();
  gone.abort();
  await rejects(getJson('https://x/', { signal: gone.signal, fetch: (url, { signal }) => (signal.aborted ? Promise.reject(new Error('aborted')) : Promise.resolve({ ok: true, status: 200, json: async () => 1 })) }), 'no-answer');
  const later = new AbortController();
  const hanging = getJson('https://x/', { signal: later.signal, fetch: (url, { signal }) => new Promise((ok, fail) => signal.addEventListener('abort', () => fail(new Error('aborted')))) });
  later.abort();
  await rejects(hanging, 'no-answer');
  const page = fakePage();
  await rejects(jsonp('https://api.deezer.com/track/1', { doc: page.doc, scope: page.scope, signal: gone.signal }), 'no-answer');
  assert.equal(page.scripts.length, 0);
  const mid = new AbortController();
  const asked = jsonp('https://api.deezer.com/track/1', { doc: page.doc, scope: page.scope, signal: mid.signal, name: 'Deezer' });
  mid.abort();
  await rejects(asked, 'no-answer', 'Deezer');
  assert.ok(page.scripts[0].removed);
  await rejects(lookup(parseLink('https://www.deezer.com/track/5'), fakeNet([]), { signal: gone.signal }), 'no-answer');
});

// ---- The chains that make a button exact ----

const SPOTIFY_ALBUM = 'https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv';
const SPOTIFY_SONG = 'https://open.spotify.com/track/0NWn0F8r2PHaqcMDnGDKvj';
const itunesAlbum = (id, name, artist, n) => ({ wrapperType: 'collection', collectionId: id, collectionName: name, artistName: artist, trackCount: n, releaseDate: '1973-03-01T08:00:00Z',
  artworkUrl100: 'https://is1-ssl.mzstatic.com/' + id + '/100x100bb.jpg', collectionViewUrl: 'https://music.apple.com/us/album/x/' + id + '?uo=4' });
const placed = (song, disc, track, collectionId) => ({ ...song, discNumber: disc, trackNumber: track, collectionId });
const dz = (id, title, artist, seconds, album) => ({ id, title, duration: seconds, artist: { name: artist }, album: { id: album.id, title: album.title, cover_small: 'https://cdn-images.dzcdn.net/' + album.cover + '/56.jpg', cover_big: 'https://cdn-images.dzcdn.net/' + album.cover + '/500.jpg' } });
const note = (r, id) => { const s = r.services.find((x) => x.id === id); return s.mark + (s.note ? ' ' + s.note : ''); };

test('from Spotify, an album MusicBrainz knows: its barcode opens the album on both catalogues', async () => {
  const net = fakeNet([
    ['open.spotify.com/oembed', { title: 'The Dark Side of the Moon', thumbnail_url: 'https://i.scdn.co/image/x' }],
    ['musicbrainz.org/ws/2/url', { relations: [{ ended: true, release: { barcode: '5099902987620' } }, { ended: false, release: { barcode: '0886445613261', title: 'The Dark Side of the Moon' } }, { release: { barcode: null } }] }],
    ['api.deezer.com/album/upc:886445613261', { id: 12114240, title: 'The Dark Side of the Moon', upc: '886445613261', nb_tracks: 10, release_date: '1973-03-01', artist: { name: 'Pink Floyd' }, cover_big: 'big' }],
    ['lookup?upc=0886445613261', { results: [itunesAlbum(1065973699, 'The Dark Side of the Moon', 'Pink Floyd', 10)] }],
    ['itunes.apple.com/search', { results: [itunesAlbum(1, 'The Dark Side of the Moon', 'Chimera', 2)] }],
    ['api.deezer.com/search/album', { data: [] }],
  ]);
  const r = await lookup(parseLink(SPOTIFY_ALBUM), net);
  assert.equal(net.asked[0], 'fetch https://musicbrainz.org/ws/2/url?resource=https%3A%2F%2Fopen.spotify.com%2Falbum%2F4LH4d3cOWNNsVw41Gqt2kv&inc=release-rels&fmt=json');
  // The release whose link still stands is asked first; Deezer keeps the barcode without its zero, Apple takes it as it is.
  assert.ok(net.asked.includes('jsonp https://api.deezer.com/album/upc:886445613261'));
  assert.ok(net.asked.includes('fetch https://itunes.apple.com/lookup?upc=0886445613261&country=us'));
  assert.ok(!net.asked.some((x) => x.includes('5099902987620')));
  assert.equal(note(r, 'apple'), 'exact barcode');
  assert.equal(note(r, 'deezer'), 'exact barcode');
  assert.equal(r.services[0].url, 'https://music.apple.com/us/album/x/1065973699');
  assert.equal(r.services[1].url, 'https://www.deezer.com/album/12114240');
  assert.deepEqual([r.artist, r.count, r.year, r.guess, r.fix, r.art], ['Pink Floyd', 10, '1973', false, false, 'https://i.scdn.co/image/x']);
  assert.deepEqual(r.alts, []);
  // The visitor's own artist still comes first, and is a guess.
  const typed = await lookup(parseLink(SPOTIFY_ALBUM), net, { artist: 'Chimera' });
  assert.deepEqual([typed.artist, typed.guess, note(typed, 'apple')], ['Chimera', true, 'guess guessed']);
});

test('from Spotify, an album only one catalogue has under the barcode: the other goes by title, artist and songs', async () => {
  const routes = (count) => [
    ['open.spotify.com/oembed', { title: 'Rumours' }],
    ['musicbrainz.org/ws/2/url', { relations: [{ release: { barcode: '603497925797' } }] }],
    ['api.deezer.com/album/upc:603497925797', { id: 6237061, title: 'Rumours', upc: '603497925797', nb_tracks: 11, artist: { name: 'Fleetwood Mac' } }],
    ['lookup?upc=', { results: [] }],
    ['itunes.apple.com/search', { results: [itunesAlbum(5, 'Rumours', 'Fleetwood Mac', count), itunesAlbum(6, 'Rumours', 'Gully Bop', 11)] }],
    ['api.deezer.com/search/album', { data: [] }],
  ];
  const same = await lookup(parseLink(SPOTIFY_ALBUM), fakeNet(routes(11)));
  assert.deepEqual([note(same, 'deezer'), note(same, 'apple'), same.artist], ['exact barcode', 'exact same', 'Fleetwood Mac']);
  const other = await lookup(parseLink(SPOTIFY_ALBUM), fakeNet(routes(31)));
  assert.equal(note(other, 'apple'), 'guess differs');
  // Both catalogues' searches silent, the barcode found: still a whole result.
  const quietNet = fakeNet([...routes(11).slice(0, 4), ['itunes.apple.com/search', new ToolError('no-answer', 'Apple Music')], ['api.deezer.com/search', new ToolError('no-answer', 'Deezer')]]);
  const alone = await lookup(parseLink(SPOTIFY_ALBUM), quietNet);
  assert.deepEqual([note(alone, 'deezer'), note(alone, 'apple')], ['exact barcode', 'search']);
});

test('from Spotify, a song MusicBrainz knows: its ISRC opens Deezer, and the album’s barcode Apple', async () => {
  const album = { id: 14996073, title: 'Shape of You', cover: 'soy' };
  const net = fakeNet([
    ['open.spotify.com/oembed', { title: 'Shape of You' }],
    ['musicbrainz.org/ws/2/url', { relations: [{ recording: { id: 'd7500dd6' } }] }],
    ['musicbrainz.org/ws/2/recording/d7500dd6?inc=isrcs+url-rels', { isrcs: ['not-one', 'GBAHS1600463'], relations: [
      { ended: true, url: { resource: 'https://tidal.com/browse/track/1' } }, { url: { resource: 'https://tidal.com/track/67189507' } },
      { url: { resource: 'https://music.youtube.com/watch?v=JGwWNGJdvx8' } }, { url: { resource: 'https://www.deezer.com/track/999' } }] }],
    ['api.deezer.com/track/isrc:GBAHS1600463', { ...dz(142986206, 'Shape of You', 'Ed Sheeran', 233, album), isrc: 'GBAHS1600463', track_position: 1, disk_number: 1 }],
    ['api.deezer.com/album/14996073', { id: 14996073, title: 'Shape of You', upc: '190295854126' }],
    ['lookup?upc=190295854126&entity=song', { results: [itunesAlbum(77, 'Shape of You - Single', 'Ed Sheeran', 1), placed(itunesSong(78, 'Shape of You', 'Ed Sheeran', 233712, 'Shape of You - Single'), 1, 1, 77)] }],
    ['itunes.apple.com/search', { results: [] }],
    ['api.deezer.com/search', { data: [dz(9, 'Shape of You', 'Someone Else', 200, { id: 1, title: 'Covers', cover: 'c' })] }],
  ]);
  const r = await lookup(parseLink(SPOTIFY_SONG), net);
  assert.deepEqual([note(r, 'deezer'), note(r, 'apple')], ['exact isrc', 'exact barcode']);
  assert.equal(r.services[0].url, 'https://music.apple.com/us/album/x/1?i=78');
  assert.equal(r.services[1].url, 'https://www.deezer.com/track/142986206');
  assert.deepEqual([r.artist, r.album, r.guess, r.fix], ['Ed Sheeran', 'Shape of You - Single', false, false]);
  // The recording's own links, asked with its ISRCs: TIDAL and YouTube Music open the song; Deezer, already exact by the ISRC, stays.
  assert.deepEqual([note(r, 'tidal'), note(r, 'ytmusic'), note(r, 'youtube')], ['exact mb', 'exact mb', 'search']);
  assert.equal(r.services.find((x) => x.id === 'tidal').url, 'https://tidal.com/browse/track/67189507');
  assert.equal(r.services[1].url, 'https://www.deezer.com/track/142986206');
  assert.ok(!net.asked.concat(net.extra).some((x) => x.includes('/ws/2/isrc/')));
  // Wikidata is asked once, by the ISRC, for what is still a search.
  assert.equal(net.extra.filter((x) => new URL(x.split(' ')[1]).hostname === 'query.wikidata.org').length, 1);
  // MusicBrainz busy: the lookup goes on without it.
  const busy = await lookup(parseLink(SPOTIFY_SONG), fakeNet([['musicbrainz.org', new ToolError('no-answer', 'MusicBrainz')], ...net.routes.filter((x) => !x[0].includes('musicbrainz'))]));
  assert.deepEqual([busy.guess, busy.artist, note(busy, 'deezer')], [true, 'Someone Else', 'guess guessed']);
});

test('from Spotify, by the cover: the candidate with Spotify’s artwork is the song', async () => {
  const single = { id: 10268286, title: 'Better Place', cover: 'single' }, own = { id: 10683608, title: 'Saint Asonia', cover: 'own' };
  const covers = {
    'https://i.scdn.co/image/better': picture(1, 300),
    'https://cdn-images.dzcdn.net/own/56.jpg': picture(1, 56, noise(20)),
    'https://cdn-images.dzcdn.net/poppy/56.jpg': picture(2, 56),
    'https://cdn-images.dzcdn.net/rachel/56.jpg': picture(3, 56),
    'https://is1-ssl.mzstatic.com/a/100x100bb.jpg': picture(4, 100),
  };
  const routes = (asonia) => [
    ['open.spotify.com/oembed', { title: 'Better Place', thumbnail_url: 'https://i.scdn.co/image/better' }],
    ['api.deezer.com/search?q=Better%20Place&', { data: [dz(1, 'Better Place', 'Poppy Fusée', 200, { id: 2, title: 'P', cover: 'poppy' }), dz(3, 'Better Place', 'Rachel Platten', 210, { id: 4, title: 'R', cover: 'rachel' }), asonia] }],
    ['term=Better+Place&', { results: [itunesSong(31, 'Better Place', 'Poppy Fusée', 200000, 'P')] }],
    ['term=Rachel+Platten', { results: [] }],
    ['api.deezer.com/track/102829828', { ...dz(102829828, 'Better Place', 'Saint Asonia', 215, own), track_position: 1, disk_number: 1 }],
    ['api.deezer.com/album/10683608', { id: 10683608, title: 'Saint Asonia', upc: '886445313543' }],
    ['lookup?upc=886445313543&entity=song', { results: [placed(itunesSong(41, 'Better Place', 'Saint Asonia', 215500, 'Saint Asonia'), 1, 1, 40), placed(itunesSong(42, 'Blow Me Wide Open', 'Saint Asonia', 225000, 'Saint Asonia'), 1, 2, 40)] }],
  ];
  const net = fakeNet(routes(dz(102829828, 'Better Place', 'Saint Asonia', 215, own)), covers);
  const r = await lookup(parseLink(SPOTIFY_SONG), net);
  // Not the artist highest in the lists: the one whose cover Spotify shows.
  assert.deepEqual([r.artist, r.album, r.guess, r.fix], ['Saint Asonia', 'Saint Asonia', false, true]);
  assert.deepEqual(r.alts, ['Poppy Fusée', 'Rachel Platten']);
  assert.deepEqual(r.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/track/102829828', mark: 'exact', note: 'cover' });
  // Deezer's song says where on which album it is, the album its barcode, and Apple what has that barcode.
  assert.deepEqual(r.services[0], { id: 'apple', name: 'Apple Music', url: 'https://music.apple.com/us/album/x/1?i=41', mark: 'exact', note: 'barcode' });
  // Each picture is fetched once.
  const pictures = net.asked.filter((x) => x.startsWith('cover '));
  assert.equal(pictures.length, new Set(pictures).size);
  assert.equal(pictures[0], 'cover https://i.scdn.co/image/better');

  // The visitor says it is someone else: their artist, as a guess.
  const typed = await lookup(parseLink(SPOTIFY_SONG), fakeNet(routes(dz(102829828, 'Better Place', 'Saint Asonia', 215, own)), covers), { artist: 'Rachel Platten' });
  assert.deepEqual([typed.artist, typed.guess, note(typed, 'deezer')], ['Rachel Platten', true, 'guess guessed']);

  // Apple does not know the barcode: its button is a search, since its title search had no Saint Asonia.
  const noBarcode = await lookup(parseLink(SPOTIFY_SONG), fakeNet([...routes(dz(102829828, 'Better Place', 'Saint Asonia', 215, own)).slice(0, 6), ['lookup?upc=', { results: [] }], ['term=Saint+Asonia+Better+Place', { results: [] }]], covers));
  assert.deepEqual([note(noBarcode, 'deezer'), note(noBarcode, 'apple')], ['exact cover', 'search']);

  // No picture can be read (a guarded canvas, a host that is down): the page guesses as it did before.
  const blind = await lookup(parseLink(SPOTIFY_SONG), fakeNet([...routes(dz(102829828, 'Better Place', 'Saint Asonia', 215, own)), ['api.deezer.com/search/album', { data: [] }]], {}));
  assert.deepEqual([blind.artist, blind.guess, blind.fix, note(blind, 'deezer')], ['Poppy Fusée', true, true, 'guess guessed']);

  // Two artists with the same artwork: nobody is picked.
  const twice = await lookup(parseLink(SPOTIFY_SONG), fakeNet([...routes(dz(102829828, 'Better Place', 'Saint Asonia', 215, own)), ['api.deezer.com/search/album', { data: [] }]],
    { ...covers, 'https://cdn-images.dzcdn.net/rachel/56.jpg': picture(1, 56, noise(30, 5)) }));
  assert.deepEqual([twice.artist, twice.guess], ['Poppy Fusée', true]);

  // Deezer's search shows the song on its single, with another cover: the artists' albums are looked
  // through, and the song is taken from the album that has Spotify's cover.
  const albums = fakeNet([
    ...routes(dz(100280712, 'Better Place', 'Saint Asonia', 215, single)).slice(0, 3),
    ['api.deezer.com/search/album?q=Saint%20Asonia', { data: [
      { id: 10268286, title: 'Better Place', nb_tracks: 1, artist: { name: 'Saint Asonia' }, cover_small: 'https://cdn-images.dzcdn.net/single/56.jpg' },
      { id: 10683608, title: 'Saint Asonia', nb_tracks: 2, artist: { name: 'Saint Asonia' }, cover_small: 'https://cdn-images.dzcdn.net/own/56.jpg' },
      { id: 99, title: 'Saint Asonia Tribute', nb_tracks: 9, artist: { name: 'Someone' }, cover_small: 'https://cdn-images.dzcdn.net/own/56.jpg' }] }],
    ['api.deezer.com/search/album', { data: [] }],
    ['api.deezer.com/album/10683608', { id: 10683608, title: 'Saint Asonia', upc: '886445313543', nb_tracks: 2, release_date: '2015-07-31', cover_small: 'https://cdn-images.dzcdn.net/own/56.jpg',
      tracks: { data: [{ id: 102829830, title: 'Blow Me Wide Open', duration: 225, artist: { name: 'Saint Asonia' } }, { id: 102829828, title: 'Better Place', duration: 215, artist: { name: 'Saint Asonia' } }] } }],
    ['lookup?upc=886445313543&entity=song', { results: [placed(itunesSong(42, 'Blow Me Wide Open', 'Saint Asonia', 225000, 'Saint Asonia'), 1, 1, 40), placed(itunesSong(41, 'Better Place', 'Saint Asonia', 215500, 'Saint Asonia'), 1, 2, 40)] }],
  ], covers);
  const deep = await lookup(parseLink(SPOTIFY_SONG), albums);
  assert.deepEqual([deep.artist, deep.album, deep.guess], ['Saint Asonia', 'Saint Asonia', false]);
  assert.deepEqual([note(deep, 'deezer'), deep.services[1].url], ['exact cover', 'https://www.deezer.com/track/102829828']);
  assert.deepEqual([note(deep, 'apple'), deep.services[0].url], ['exact barcode', 'https://music.apple.com/us/album/x/1?i=41']);
});

test('from Deezer: the album’s barcode opens Apple’s album, and the song in its place on it', async () => {
  const album = { id: 6237061, title: 'Rumours', cover: 'rumours' };
  const net = fakeNet([
    ['api.deezer.com/track/63480987', { ...dz(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 257, album), track_position: 2, disk_number: 1, isrc: 'USWB10400046' }],
    ['api.deezer.com/album/6237061', { id: 6237061, title: 'Rumours', upc: '603497925797', nb_tracks: 11, artist: { name: 'Fleetwood Mac' } }],
    ['lookup?upc=603497925797&entity=song', { results: [itunesAlbum(594061854, 'Rumours', 'Fleetwood Mac', 11), placed(itunesSong(594061855, 'Second Hand News', 'Fleetwood Mac', 176000), 1, 1, 594061854), placed(itunesSong(594061857, 'Dreams', 'Fleetwood Mac', 257800), 1, 2, 594061854)] }],
    ['lookup?upc=603497925797&country', { results: [itunesAlbum(594061854, 'Rumours', 'Fleetwood Mac', 11)] }],
  ]);
  const song = await lookup(parseLink('https://www.deezer.com/track/63480987'), net);
  assert.deepEqual(net.asked, ['jsonp https://api.deezer.com/track/63480987', 'jsonp https://api.deezer.com/album/6237061', 'fetch https://itunes.apple.com/lookup?upc=603497925797&entity=song&country=us']);
  assert.deepEqual(song.services[0], { id: 'apple', name: 'Apple Music', url: 'https://music.apple.com/us/album/x/1?i=594061857', mark: 'exact', note: 'barcode' });
  net.asked.length = 0;
  const whole = await lookup(parseLink('https://www.deezer.com/album/6237061'), net);
  assert.deepEqual(net.asked, ['jsonp https://api.deezer.com/album/6237061', 'fetch https://itunes.apple.com/lookup?upc=603497925797&country=us']);
  assert.deepEqual(whole.services[0], { id: 'apple', name: 'Apple Music', url: 'https://music.apple.com/us/album/x/594061854', mark: 'exact', note: 'barcode' });
});

test('from Apple Music: Deezer’s barcode leading back to the pasted page is certain; the cover vouches for a song of another length', async () => {
  const album = { id: 6237061, title: 'Rumours', cover: 'rumours' };
  const apple = ['itunes.apple.com/lookup?id=', { results: [placed(itunesSong(594061857, 'Dreams', 'Fleetwood Mac', 257800), 1, 2, 594061854)] }];
  const search = ['api.deezer.com/search', { data: [dz(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 263, album)] }];
  const whole = ['api.deezer.com/track/63480987', { ...dz(63480987, 'Dreams (2004 Remaster)', 'Fleetwood Mac', 263, album), track_position: 2, disk_number: 1 }];
  const upc = ['api.deezer.com/album/6237061', { id: 6237061, upc: '603497925797' }];
  const back = (id) => ['lookup?upc=603497925797&entity=song&country=gb', { results: [placed(itunesSong(id, 'Dreams', 'Fleetwood Mac', 257800), 1, 2, 594061854)] }];
  const link = parseLink('https://music.apple.com/gb/album/dreams/594061854?i=594061857');
  const sure = await lookup(link, fakeNet([apple, search, whole, upc, back(594061857)]));
  assert.deepEqual(sure.services[1], { id: 'deezer', name: 'Deezer', url: 'https://www.deezer.com/track/63480987', mark: 'exact', note: 'barcode' });
  // Apple has the barcode twice (two pressings): the pasted page being one of them is enough.
  const two = ['lookup?upc=603497925797', { results: [placed(itunesSong(1, 'Dreams', 'Fleetwood Mac', 257800), 1, 2, 1483708628), placed(itunesSong(594061857, 'Dreams', 'Fleetwood Mac', 257800), 1, 2, 594061854)] }];
  assert.equal(note(await lookup(link, fakeNet([apple, search, whole, upc, two])), 'deezer'), 'exact barcode');
  // The barcode leads to another Apple page (another pressing): back to title, artist and length, which differs here.
  const other = await lookup(link, fakeNet([apple, search, whole, upc, back(1)]));
  assert.equal(note(other, 'deezer'), 'guess differs');
  // ... unless the two covers are the same artwork.
  const covers = { 'https://is1-ssl.mzstatic.com/a/100x100bb.jpg': picture(5, 100), 'https://cdn-images.dzcdn.net/rumours/56.jpg': picture(5, 56, noise(20)) };
  const seen = await lookup(link, fakeNet([apple, search, whole, upc, back(1)], covers));
  assert.equal(note(seen, 'deezer'), 'exact cover');
  const unlike = await lookup(link, fakeNet([apple, search, whole, upc, back(1)], { ...covers, 'https://cdn-images.dzcdn.net/rumours/56.jpg': picture(6, 56) }));
  assert.equal(note(unlike, 'deezer'), 'guess differs');
});

// ---- More exact buttons: MusicBrainz and Wikidata ----

test('serviceLink: the pages of a song on each service, rebuilt from their parts', () => {
  const song = (u) => serviceLink(u, 'song');
  assert.deepEqual(song('https://tidal.com/track/490851477'), { id: 'tidal', url: 'https://tidal.com/browse/track/490851477' });
  assert.deepEqual(song('http://listen.tidal.com/browse/track/490851477?u'), { id: 'tidal', url: 'https://tidal.com/browse/track/490851477' });
  assert.deepEqual(song('https://music.youtube.com/watch?v=Om61naQC8is&feature=share'), { id: 'ytmusic', url: 'https://music.youtube.com/watch?v=Om61naQC8is' });
  assert.deepEqual(song('https://www.youtube.com/watch?v=dQw4w9WgXcQ'), { id: 'youtube', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
  assert.deepEqual(song('https://youtu.be/dQw4w9WgXcQ'), { id: 'youtube', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
  assert.deepEqual(song('https://music.amazon.de/tracks/B07D3KH4ZB'), { id: 'amazon', url: 'https://music.amazon.de/tracks/B07D3KH4ZB' });
  assert.deepEqual(song('https://soundcloud.com/rick-astley-official/never-gonna-give-you-up-4'), { id: 'soundcloud', url: 'https://soundcloud.com/rick-astley-official/never-gonna-give-you-up-4' });
  assert.deepEqual(song('https://music.apple.com/gb/song/1438556832'), { id: 'apple', url: 'https://music.apple.com/gb/song/1438556832' });
  assert.deepEqual(song('https://www.deezer.com/track/3750996072'), { id: 'deezer', url: 'https://www.deezer.com/track/3750996072' });
  assert.deepEqual(song('https://open.spotify.com/track/1Ojc3QD0dfJ5HG8uzLsfTg'), { id: 'spotify', url: 'https://open.spotify.com/track/1Ojc3QD0dfJ5HG8uzLsfTg' });
  // An album asks for album pages, and a song's page is not one.
  const album = (u) => serviceLink(u, 'album');
  assert.deepEqual(album('https://tidal.com/browse/album/77646150'), { id: 'tidal', url: 'https://tidal.com/browse/album/77646150' });
  assert.deepEqual(album('https://music.youtube.com/playlist?list=OLAK5uy_kdpPEVnR3XYjslKeEaw9ZcVnu0kLfS1J0'),
    { id: 'ytmusic', url: 'https://music.youtube.com/playlist?list=OLAK5uy_kdpPEVnR3XYjslKeEaw9ZcVnu0kLfS1J0' });
  assert.deepEqual(album('https://music.youtube.com/browse/MPREb_9nqEki4ZDpp'), { id: 'ytmusic', url: 'https://music.youtube.com/browse/MPREb_9nqEki4ZDpp' });
  assert.deepEqual(album('https://music.amazon.com/albums/B07D3KH4ZB'), { id: 'amazon', url: 'https://music.amazon.com/albums/B07D3KH4ZB' });
  assert.deepEqual(album('https://soundcloud.com/artist/sets/the-album'), { id: 'soundcloud', url: 'https://soundcloud.com/artist/sets/the-album' });
  assert.equal(album('https://tidal.com/browse/track/490851477'), null);
  assert.equal(album('https://www.youtube.com/watch?v=dQw4w9WgXcQ'), null);
  assert.equal(song('https://tidal.com/browse/album/77646150'), null);
  assert.equal(song('https://music.amazon.com/albums/B07D3KH4ZB?trackAsin=B07D3KH4ZC'), null);
  assert.equal(song('https://soundcloud.com/artist/sets/the-album'), null);
});

test('serviceLink: look-alike hosts, other schemes and odd pages are refused', () => {
  for (const u of ['https://tidal.com.evil.example/browse/track/1', 'https://evil-tidal.com/browse/track/1', 'https://tidal.co/browse/track/1',
    'https://music.youtube.com.evil.example/watch?v=dQw4w9WgXcQ', 'https://youtube.co/watch?v=dQw4w9WgXcQ', 'https://music-youtube.com/watch?v=dQw4w9WgXcQ',
    'https://music.amazon.evil.example/tracks/B07D3KH4ZB', 'https://amazon.com/tracks/B07D3KH4ZB', 'https://soundcloud.com.evil.example/a/b',
    'https://music.apple.com.evil.example/gb/song/1', 'https://deezer.com.evil.example/track/1',
    'javascript:alert(1)//tidal.com/track/1', 'data:text/html,tidal.com', 'ftp://tidal.com/track/1', 'https://user:pw@tidal.com/track/1',
    'https://tidal.com:8443/track/1', 'https://tidal.com/track/1x', 'https://tidal.com/browse/track/1/extra', 'https://www.youtube.com/watch?v=short',
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ"><script>', 'https://www.youtube.com/embed/dQw4w9WgXcQ', 'https://music.youtube.com/watch',
    'https://music.amazon.com/tracks/not-an-asin', 'https://soundcloud.com/search/sounds', 'https://soundcloud.com/a', 'https://soundcloud.com/a/b/c',
    'not a url', '', null, undefined]) {
    assert.equal(serviceLink(u, 'song'), null, String(u));
  }
});

test('relLinks: MusicBrainz url-rels as one page per service, a standing link before an ended one', () => {
  const rels = [
    { type: 'streaming', ended: true, url: { resource: 'https://tidal.com/track/1' } },
    { type: 'free streaming', url: { resource: 'https://music.youtube.com/watch?v=Om61naQC8is' } },
    { type: 'free streaming', url: { resource: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' } },
    { type: 'other databases', url: { resource: 'https://rateyourmusic.com/song/rick-astley/never-gonna-give-you-up/' } },
    { type: 'streaming', url: { resource: 'https://music.apple.com/gb/song/1438556832' } },
    { type: 'streaming', url: { resource: 'https://music.apple.com/gb/song/1624945512' } },
    { type: 'streaming', url: { resource: 'https://tidal.com/track/490851477' } },
    { type: 'streaming', url: { resource: 'https://tidal.com.evil.example/track/2' } },
    { type: 'free streaming', url: { resource: 'javascript:alert(1)' } },
    { type: 'free streaming' }, null,
  ];
  assert.deepEqual(relLinks(rels, 'song'), {
    ytmusic: 'https://music.youtube.com/watch?v=Om61naQC8is', youtube: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
    apple: 'https://music.apple.com/gb/song/1438556832', tidal: 'https://tidal.com/browse/track/490851477',
  });
  assert.deepEqual(relLinks(undefined, 'song'), {});
  assert.deepEqual(relLinks(rels, 'album'), {});
});

/** Wikidata's answer to wikidataQuery, as the SPARQL endpoint gives it. */
const wdAnswer = (rows) => ({ head: { vars: ['tidal', 'amazon', 'apple', 'youtube', 'deezer', 'spotify'] },
  results: { bindings: rows.map((r) => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, { type: 'literal', value: v }]))) } });

test('Wikidata: one query by the ISRC or the barcode, and its answer read strictly', () => {
  const q = wikidataQuery('song', ['uswb12205362']);
  assert.match(q, /VALUES \?c\{"USWB12205362"\} \?i wdt:P1243 \?c\./);
  for (const p of ['P4578', 'P13398', 'P10110', 'P1651', 'P2724', 'P2207']) assert.ok(q.includes('wdt:' + p + ' '), p);
  assert.equal(wikidataQuery('song', ['"} ; DROP']), null);
  const a = wikidataQuery('album', ['0886445613261']);
  assert.match(a, /wdt:P3962/);
  for (const form of ['"0886445613261"', '"886445613261"', '"00886445613261"']) assert.ok(a.includes(form), form);
  assert.deepEqual(wikidataLinks(wdAnswer([
    { tidal: '251443347', apple: '1647736563?i=1647736879', youtube: 'Y3I42iaiH04', amazon: 'B0BJ2WRV1Q' },
    { tidal: '999', youtube: 'not-a-video-id-at-all' },
    { apple: 'javascript:alert(1)', spotify: 'short', deezer: '12x' },
  ]), 'song'), {
    tidal: 'https://tidal.com/browse/track/251443347', apple: 'https://music.apple.com/us/album/1647736563?i=1647736879',
    youtube: 'https://www.youtube.com/watch?v=Y3I42iaiH04', amazon: 'https://music.amazon.com/tracks/B0BJ2WRV1Q',
  });
  assert.deepEqual(wikidataLinks(wdAnswer([{ tidal: '77646150', apple: '1440650711', spotify: '4LH4d3cOWNNsVw41Gqt2kv' }]), 'album'), {
    tidal: 'https://tidal.com/browse/album/77646150', apple: 'https://music.apple.com/us/album/1440650711', spotify: 'https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv',
  });
  assert.deepEqual(wikidataLinks(null, 'song'), {});
  assert.deepEqual(wikidataLinks({ results: {} }, 'album'), {});
});

test('addKnown: MusicBrainz by the ISRC first, then Wikidata only for what is still a search', async () => {
  const asked = [];
  const net = { json: async (url, opts) => {
    asked.push([url, opts.timeout]);
    if (url.includes('musicbrainz.org/ws/2/isrc/USWB12205362?inc=url-rels')) {
      return { recordings: [{ relations: [{ url: { resource: 'https://tidal.com/track/1' } }] }, { relations: [{ url: { resource: 'https://music.apple.com/us/song/7' } }] }] };
    }
    if (url.startsWith('https://query.wikidata.org/sparql?format=json&query=')) return wdAnswer([{ tidal: '2', youtube: 'Y3I42iaiH04', apple: '3?i=4', deezer: '5' }]);
    throw new Error('unexpected ' + url);
  } };
  const found = { deezer: { url: 'https://www.deezer.com/track/9', mark: 'exact', note: 'pasted' }, apple: { url: 'a', mark: 'guess', note: 'differs' } };
  await addKnown('song', found, { isrc: 'USWB12205362' }, net);
  // MusicBrainz's Apple page replaces a best guess; Wikidata's TIDAL does not replace MusicBrainz's, nor its Deezer the pasted one.
  assert.deepEqual(found, {
    deezer: { url: 'https://www.deezer.com/track/9', mark: 'exact', note: 'pasted' },
    apple: { url: 'https://music.apple.com/us/song/7', mark: 'exact', note: 'mb' },
    tidal: { url: 'https://tidal.com/browse/track/1', mark: 'exact', note: 'mb' },
    youtube: { url: 'https://www.youtube.com/watch?v=Y3I42iaiH04', mark: 'exact', note: 'wd' },
  });
  assert.equal(asked.length, 2);
  assert.equal(asked[1][1], WD_TIMEOUT);
  // Nothing to ask by, or nothing left to find: no request.
  asked.length = 0;
  await addKnown('song', {}, {}, net);
  await addKnown('album', {}, { upc: 'nope' }, net);
  assert.equal(asked.length, 0);
});

test('addKnown: MusicBrainz or Wikidata silent, late or wrong leaves the buttons as they were', async () => {
  for (const fail of [new ToolError('no-answer', 'Wikidata'), new ToolError('not-found', 'MusicBrainz'), new Error('boom')]) {
    const found = { apple: { url: 'a', mark: 'guess', note: 'differs' } };
    const net = { json: async () => { throw fail; } };
    await addKnown('song', found, { isrc: 'USWB12205362' }, net);
    assert.deepEqual(found, { apple: { url: 'a', mark: 'guess', note: 'differs' } });
  }
  // A timeout, as getJson gives it: the request is cut at Wikidata's own short time.
  const found = {};
  const timeouts = [];
  const late = { json: (url, { timeout }) => { timeouts.push(timeout); return getJson(url, { name: 'Wikidata', timeout: 5, fetch: (u, { signal }) => new Promise((ok, no) => signal.addEventListener('abort', () => no(new Error('aborted')))) }); } };
  await addKnown('album', found, { rels: Promise.resolve([]), upc: '886445613261' }, late);
  assert.deepEqual(found, {});
  assert.deepEqual(timeouts, [WD_TIMEOUT]);
});

test('from Deezer: MusicBrainz is asked by the song’s ISRC, Wikidata for the rest', async () => {
  const net = fakeNet([
    ['api.deezer.com/track/3750996072', { ...deezerSong(3750996072, 'Never Gonna Give You Up', 'Rick Astley', 213), isrc: 'GBARL9300135' }],
    ['itunes.apple.com/search', { results: [itunesSong(1, 'Never Gonna Give You Up', 'Rick Astley', 214000, 'Whenever You Need Somebody')] }],
    ['musicbrainz.org/ws/2/isrc/GBARL9300135?inc=url-rels&fmt=json', { recordings: [{ relations: [
      { url: { resource: 'https://music.youtube.com/watch?v=Om61naQC8is' } }, { url: { resource: 'https://tidal.com/track/490851477' } },
      { url: { resource: 'https://open.spotify.com/track/6JEK0CvvjDjjMUBFoXShNZ' } }] }] }],
    ['query.wikidata.org', wdAnswer([{ youtube: 'dQw4w9WgXcQ', tidal: '1' }])],
  ]);
  const r = await lookup(parseLink('https://www.deezer.com/track/3750996072'), net);
  assert.deepEqual(Object.fromEntries(r.services.map((s) => [s.id, note(r, s.id)])), {
    apple: 'exact same', deezer: 'exact pasted', spotify: 'exact mb', ytmusic: 'exact mb', youtube: 'exact wd', tidal: 'exact mb', amazon: 'search', soundcloud: 'search',
  });
  assert.equal(r.services.find((x) => x.id === 'youtube').url, 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
});

test('from Spotify unknown to MusicBrainz: the ISRC of an exact Deezer match leads to MusicBrainz’s links; a best guess never does', async () => {
  const own = { id: 10683608, title: 'Saint Asonia', cover: 'own' };
  const covers = {
    'https://i.scdn.co/image/better': picture(1, 300),
    'https://cdn-images.dzcdn.net/own/56.jpg': picture(1, 56, noise(20)),
    'https://cdn-images.dzcdn.net/poppy/56.jpg': picture(2, 56),
    'https://cdn-images.dzcdn.net/rachel/56.jpg': picture(3, 56),
    'https://is1-ssl.mzstatic.com/a/100x100bb.jpg': picture(4, 100),
  };
  const routes = [
    ['musicbrainz.org/ws/2/url', new ToolError('not-found', 'MusicBrainz')],
    ['musicbrainz.org/ws/2/isrc/USAT21500999?inc=url-rels&fmt=json', { recordings: [{ relations: [
      { url: { resource: 'https://tidal.com/browse/track/42' } }, { url: { resource: 'https://music.youtube.com/watch?v=Om61naQC8is' } }] }] }],
    ['query.wikidata.org', wdAnswer([{ amazon: 'B0BJ2WRV1Q' }])],
    ['open.spotify.com/oembed', { title: 'Better Place', thumbnail_url: 'https://i.scdn.co/image/better' }],
    ['api.deezer.com/search?q=Better%20Place&', { data: [dz(1, 'Better Place', 'Poppy Fusée', 200, { id: 2, title: 'P', cover: 'poppy' }), dz(3, 'Better Place', 'Rachel Platten', 210, { id: 4, title: 'R', cover: 'rachel' }), dz(102829828, 'Better Place', 'Saint Asonia', 215, own)] }],
    ['term=Better+Place&', { results: [itunesSong(31, 'Better Place', 'Poppy Fusée', 200000, 'P')] }],
    ['term=', { results: [] }],
    ['api.deezer.com/search/album', { data: [] }],
    ['api.deezer.com/search', { data: [] }],
    ['api.deezer.com/track/102829828', { ...dz(102829828, 'Better Place', 'Saint Asonia', 215, own), track_position: 1, disk_number: 1, isrc: 'USAT21500999' }],
    ['api.deezer.com/album/10683608', { id: 10683608, title: 'Saint Asonia', upc: '886445313543' }],
    ['lookup?upc=', { results: [] }],
  ];
  const net = fakeNet(routes, covers);
  const r = await lookup(parseLink(SPOTIFY_SONG), net);
  assert.equal(note(r, 'deezer'), 'exact cover');
  assert.deepEqual([note(r, 'tidal'), note(r, 'ytmusic'), note(r, 'amazon'), note(r, 'youtube')], ['exact mb', 'exact mb', 'exact wd', 'search']);
  assert.equal(net.asked.filter((x) => x.includes('/ws/2/isrc/')).length, 1);

  // No cover to go by: Deezer's button is a best guess, and its ISRC is never asked about.
  const blind = fakeNet(routes, {});
  const g = await lookup(parseLink(SPOTIFY_SONG), blind);
  assert.equal(note(g, 'deezer'), 'guess guessed');
  const sent = blind.asked.concat(blind.extra).map(decodeURIComponent);
  assert.ok(!sent.some((x) => x.includes('/ws/2/isrc/') || x.includes('listenbrainz') || x.includes('P1243')));
  // Wikidata is still asked by the pasted Spotify ID, which is no guess.
  assert.ok(sent.some((x) => x.includes('wdt:P2207 "0NWn0F8r2PHaqcMDnGDKvj"')));
  assert.deepEqual([note(g, 'tidal'), note(g, 'ytmusic')], ['search', 'search']);
});

// ---- ListenBrainz, Wikidata by the pasted ID, MusicBrainz by barcode ----

/** A net for addKnown: answers by the first matching piece, keeps every URL asked. */
function routeNet(routes) {
  const asked = [];
  return { asked, json: async (url, opts) => {
    asked.push(decodeURIComponent(url));
    for (const [piece, reply] of routes) {
      if (!decodeURIComponent(url).includes(piece)) continue;
      if (reply instanceof Error) throw reply;
      return typeof reply === 'function' ? reply(url, opts) : reply;
    }
    throw new ToolError('not-found', 'test');
  } };
}
const MBID_RICK = '8f3471b5-7e6a-48da-86a9-c1c07a0f47ae';

test('wikidataId and wikidataQuery: the pasted page’s own ID, in the same single query as the code', () => {
  assert.equal(wikidataId(parseLink('https://open.spotify.com/track/6JEK0CvvjDjjMUBFoXShNZ')), '6JEK0CvvjDjjMUBFoXShNZ');
  assert.equal(wikidataId(parseLink('https://www.deezer.com/album/6237061')), '6237061');
  assert.equal(wikidataId(parseLink('https://music.apple.com/us/album/rumours/594061854')), '594061854');
  assert.equal(wikidataId(parseLink('https://music.apple.com/gb/album/dreams/594061854?i=594061857')), '594061854?i=594061857');
  // A /song/ link does not say its album: Wikidata's "album?i=track" cannot be written.
  assert.equal(wikidataId(parseLink('https://music.apple.com/us/song/dreams/594061857')), null);
  const q = wikidataQuery('song', ['GBARL9300135'], parseLink('https://open.spotify.com/track/6JEK0CvvjDjjMUBFoXShNZ'));
  assert.ok(q.includes('{VALUES ?c{"GBARL9300135"} ?i wdt:P1243 ?c.} UNION {?i wdt:P2207 "6JEK0CvvjDjjMUBFoXShNZ".}'));
  const a = wikidataQuery('album', [], parseLink('https://www.deezer.com/album/6237061'));
  assert.ok(a.includes('{?i wdt:P2723 "6237061".}') && !a.includes('UNION') && a.includes('wdt:P4300 ?ytmusic'));
  assert.ok(wikidataQuery('album', [], parseLink('https://music.apple.com/us/album/rumours/594061854')).includes('wdt:P2281 "594061854"'));
  // A song's link is not asked about as an album, and nothing at all is no query.
  assert.equal(wikidataQuery('album', [], parseLink('https://www.deezer.com/track/1')), null);
  assert.equal(wikidataQuery('song', [], null), null);
  // An album's YouTube playlist is its YouTube Music page only when it is the album's own.
  assert.deepEqual(wikidataLinks(wdAnswer([{ ytmusic: 'PLabcdefghijklmnop' }, { ytmusic: 'OLAK5uy_kdpPEVnR3XYjslKeEaw9ZcVnu0kLfS1J0' }]), 'album'),
    { ytmusic: 'https://music.youtube.com/playlist?list=OLAK5uy_kdpPEVnR3XYjslKeEaw9ZcVnu0kLfS1J0' });
});

test('lbIds: by the recording, and by artist, album and title when the recording gives several', async () => {
  const net = routeNet([
    ['spotify-id-from-mbid', [{ spotify_track_ids: ['6bnZlZvEmocCLdp0622Idi', '0y8ngXGlfJ8kLa8h1d2MPr', 'bad id', 7] }]],
    ['spotify-id-from-metadata', [{ spotify_track_ids: ['0y8ngXGlfJ8kLa8h1d2MPr'] }]],
    ['apple-music-id-from-mbid', [{ apple_music_track_ids: ['1559885421'] }]],
    ['apple-music-id-from-metadata', new ToolError('no-answer', 'ListenBrainz')],
  ]);
  const known = { mbid: MBID_RICK, artist: 'Rick Astley', title: 'Never Gonna Give You Up', album: 'Whenever You Need Somebody' };
  assert.deepEqual(await lbIds('spotify', known, net), ['0y8ngXGlfJ8kLa8h1d2MPr', '6bnZlZvEmocCLdp0622Idi']);
  assert.ok(net.asked.includes('https://labs.api.listenbrainz.org/spotify-id-from-metadata/json?artist_name=Rick+Astley&release_name=Whenever+You+Need+Somebody&track_name=Never+Gonna+Give+You+Up'));
  // One ID by the recording is enough; no recording and no album is nothing to ask by.
  net.asked.length = 0;
  assert.deepEqual(await lbIds('apple', known, net), ['1559885421']);
  assert.equal(net.asked.length, 1);
  assert.deepEqual(await lbIds('apple', { artist: 'Rick Astley', title: 'x' }, net), []);
  assert.deepEqual(await lbIds('apple', { ...known, mbid: null }, net), []);
});

test('addKnown, from Apple or Deezer: ListenBrainz finds the recording, MusicBrainz its links, ListenBrainz Spotify', async () => {
  const net = routeNet([
    ['recording-search/json?query=Rick+Astley+Never+Gonna+Give+You+Up', [
      { recording_name: 'Never Gonna Give You Up (live)', recording_mbid: '00000000-0000-0000-0000-000000000000', artist_credit_name: 'Rick Astley' },
      { recording_name: 'Never Gonna Give You Up', recording_mbid: MBID_RICK, artist_credit_name: 'Rick Astley' }]],
    ['musicbrainz.org/ws/2/recording/' + MBID_RICK + '?inc=isrcs+url-rels&fmt=json', { isrcs: ['GBARL9300135'], relations: [
      { url: { resource: 'https://tidal.com/track/490851477' } }, { url: { resource: 'https://music.youtube.com/watch?v=Om61naQC8is' } }] }],
    ['spotify-id-from-mbid', [{ spotify_track_ids: ['6JEK0CvvjDjjMUBFoXShNZ'] }]],
    ['query.wikidata.org', wdAnswer([{ youtube: 'dQw4w9WgXcQ', tidal: '1' }])],
  ]);
  const found = { apple: { url: 'https://music.apple.com/us/song/1', mark: 'exact', note: 'pasted' }, deezer: { url: 'd', mark: 'guess', note: 'differs' } };
  await addKnown('song', found, { artist: 'Rick Astley', title: 'Never Gonna Give You Up', link: parseLink('https://music.apple.com/us/song/x/1') }, net);
  assert.deepEqual(Object.fromEntries(Object.entries(found).map(([id, f]) => [id, f.mark + ' ' + f.note])), {
    apple: 'exact pasted', deezer: 'guess differs', tidal: 'exact mb', ytmusic: 'exact mb', spotify: 'exact lb', youtube: 'exact wd',
  });
  assert.equal(found.spotify.url, 'https://open.spotify.com/track/6JEK0CvvjDjjMUBFoXShNZ');
  // Wikidata is asked by the ISRC MusicBrainz gave.
  assert.ok(net.asked.some((u) => u.includes('VALUES ?c{"GBARL9300135"}')));
  // Without a sure artist nothing is searched by name.
  const none = routeNet([]);
  await addKnown('song', {}, { title: 'Never Gonna Give You Up' }, none);
  assert.deepEqual(none.asked, []);
});

test('addKnown: several Apple songs from ListenBrainz, the one on the album is taken', async () => {
  const net = routeNet([
    ['apple-music-id-from-mbid', [{ apple_music_track_ids: ['11', '22'] }]],
    ['itunes.apple.com/lookup?id=11,22&country=us', { results: [itunesSong(11, 'Dreams', 'Fleetwood Mac', 257800, 'Greatest Hits'), itunesSong(22, 'Dreams', 'Fleetwood Mac', 257800, 'Rumours')] }],
  ]);
  const found = { spotify: { url: 's', mark: 'exact', note: 'pasted' } };
  await addKnown('song', found, { rels: [], mbid: MBID_RICK, artist: 'Fleetwood Mac', title: 'Dreams', album: 'Rumours (Deluxe)', durationMs: 257000 }, net);
  assert.deepEqual(found.apple, { url: 'https://music.apple.com/us/album/x/1?i=22', mark: 'exact', note: 'lb' });
});

test('addKnown, an album: MusicBrainz’s release by its barcode, then the release’s links', async () => {
  const net = routeNet([
    ['musicbrainz.org/ws/2/release/?query=barcode:603497925797 OR barcode:0603497925797 OR barcode:00603497925797', { releases: [
      { id: '11111111-1111-1111-1111-111111111111', barcode: '5099999999999' }, { id: '22222222-2222-2222-2222-222222222222', barcode: '603497925797' }] }],
    ['musicbrainz.org/ws/2/release/22222222-2222-2222-2222-222222222222?inc=url-rels&fmt=json', { relations: [
      { url: { resource: 'https://tidal.com/browse/album/77646150' } }, { url: { resource: 'https://open.spotify.com/album/1bt6q2SruMsBtcerNVtpZB' } }] }],
  ]);
  const found = { deezer: { url: 'https://www.deezer.com/album/6237061', mark: 'exact', note: 'pasted' } };
  await addKnown('album', found, { upc: '603497925797', artist: 'Fleetwood Mac', title: 'Rumours', link: parseLink('https://www.deezer.com/album/6237061') }, net);
  assert.deepEqual([found.tidal.url, found.tidal.note, found.spotify.note], ['https://tidal.com/browse/album/77646150', 'mb', 'mb']);
  // Albums never ask ListenBrainz's song mappers.
  assert.ok(!net.asked.some((u) => u.includes('listenbrainz')));
  // MusicBrainz busy (503, which getJson gives as "no answer"): nothing changes, Wikidata is still asked.
  const busy = routeNet([['musicbrainz.org', new ToolError('no-answer', 'MusicBrainz')], ['query.wikidata.org', wdAnswer([])]]);
  const same = { deezer: { url: 'x', mark: 'exact', note: 'pasted' } };
  await addKnown('album', same, { upc: '603497925797', link: parseLink('https://www.deezer.com/album/6237061') }, busy);
  assert.deepEqual(same, { deezer: { url: 'x', mark: 'exact', note: 'pasted' } });
  assert.equal(busy.asked.filter((u) => u.includes('musicbrainz')).length, 1);
});

test('addKnown: ListenBrainz silent or answering nonsense changes nothing', async () => {
  for (const reply of [new ToolError('no-answer', 'ListenBrainz'), new Error('boom'), {}, [{}], [{ spotify_track_ids: ['javascript:alert(1)'], apple_music_track_ids: ['1x'] }]]) {
    const found = { deezer: { url: 'd', mark: 'exact', note: 'pasted' } };
    await addKnown('song', found, { rels: [], mbid: MBID_RICK, artist: 'A', title: 'B', album: 'C' }, routeNet([['listenbrainz', reply]]));
    assert.deepEqual(found, { deezer: { url: 'd', mark: 'exact', note: 'pasted' } });
  }
});
