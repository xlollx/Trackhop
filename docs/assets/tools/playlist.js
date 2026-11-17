// Playlist files, read and written as the Trackhop app does (CsvCodec.kt, PlaylistFiles.kt and
// ItunesLibrary.kt, ported): same column names, same rules for "Artist - Title" names and library
// paths. Every reader takes its text a piece at a time, so a library of any size can be read.
import { ToolError, Lines, blobBytes, headText, streamText, own } from './io.js';
import { JsonStream } from './json-stream.js';

export const FORMATS = ['csv', 'm3u', 'pls', 'itunes', 'rekordbox', 'wpl', 'xspf', 'opml', 'json', 'text'];
export const OUTPUTS = { csv: 'csv', m3u8: 'm3u8', xspf: 'xspf', jspf: 'jspf', wpl: 'wpl', zpl: 'zpl', rekordbox: 'xml', text: 'txt' };

// ---------------------------------------------------------------- small Kotlin look-alikes

const blank = (s) => s == null || s.trim() === '';
export function toDouble(s) {
  const t = String(s).trim();
  return /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(t) ? Number(t) : null;
}
export function toLong(s) {
  return /^[+-]?\d+$/.test(String(s)) ? Number(s) : null;
}

export function track(title, artists = [], album = '', durationMs = 0, isrc = null) {
  return { title: own(title), artists: artists.map(own), album: own(album), durationMs, isrc: isrc == null ? null : own(isrc), feedUrl: null, itunesId: null };
}
export const artistLine = (t) => t.artists.join(', ');
const isrcNorm = (t) => {
  const s = t.isrc ? t.isrc.trim().toUpperCase() : '';
  return s.length >= 10 ? s : null;
};

export function splitArtists(s) {
  return s.split(/\s*[,;/]\s*|\s+&\s+/).map((a) => a.trim()).filter((a) => a.length);
}

const ISO_DURATION = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/;
function parseIso8601(s) {
  const m = ISO_DURATION.exec(s.trim());
  if (!m) return 0;
  return (((Number(m[1] || 0) * 24 + Number(m[2] || 0)) * 60 + Number(m[3] || 0)) * 60 * 1000) + Math.trunc(Number(m[4] || 0) * 1000);
}

export function parseDuration(s) {
  if (blank(s)) return 0;
  if (s.includes(':')) {
    const p = s.split(':').map((x) => toDouble(x.trim()) ?? 0);
    if (p.length === 2) return Math.trunc((p[0] * 60 + p[1]) * 1000);
    if (p.length === 3) return Math.trunc(((p[0] * 60 + p[1]) * 60 + p[2]) * 1000);
    return 0;
  }
  if (s.startsWith('P')) return parseIso8601(s);
  const n = toDouble(s);
  if (n == null) return 0;
  return n > 10000 ? Math.trunc(n) : Math.trunc(n * 1000); // milliseconds or seconds
}

const DASH = /\s+[-–—]\s+/;
/** Split at the first dash only: what is after it stays whole. */
function dash2(s) {
  const m = DASH.exec(s);
  return m ? [s.slice(0, m.index), s.slice(m.index + m[0].length)] : [s];
}

/** "Artist - Title", by far the most common shape of a name. */
export function splitArtistTitle(label) {
  const parts = dash2(label.trim());
  return parts.length === 2 && !blank(parts[0]) && !blank(parts[1])
    ? [splitArtists(parts[0]), parts[1].trim()]
    : [[], label.trim()];
}

export function safeName(name) {
  const s = name.replace(/[\\/:*?"<>|]/g, '_').trim().replace(/^\.+|\.+$/g, '').slice(0, 80);
  return blank(s) ? 'playlist' : s;
}

// One pass, so "&amp;lt;" stays "&lt;" (unescaping in steps would turn it into "<").
const XML_ENTITIES = { lt: '<', gt: '>', quot: '"', apos: "'", amp: '&', '#38': '&' };
const unescapeXml = (s) => s.replace(/&(lt|gt|quot|apos|amp|#38);/g, (_, e) => XML_ENTITIES[e]);
const escapeXml = (s) => s
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

// ---------------------------------------------------------------- which format

/** The format of a file from its name and its first few thousand characters, as the app tells it. */
export function detect(fileName, text) {
  const name = fileName.toLowerCase();
  const head = text.slice(0, 4000).replace(/^[\s﻿]+/, '');
  const starts = (s) => head.toLowerCase().startsWith(s);
  if (head.startsWith('#EXTM3U') || name.endsWith('.m3u') || name.endsWith('.m3u8')) return 'm3u';
  if (name.endsWith('.pls') || starts('[playlist]')) return 'pls';
  if (name.endsWith('.xspf') || (head.startsWith('<') && /<playlist\b[^>]*xspf\.org/i.test(head))) return 'xspf';
  if (name.endsWith('.opml') || (head.startsWith('<') && head.toLowerCase().includes('<opml'))) return 'opml';
  // Told by what is inside, whatever the name: rekordbox writes a plain ".xml", as Apple does.
  if (head.startsWith('<') && head.includes('<DJ_PLAYLISTS')) return 'rekordbox';
  if (looksLikeWpl(name, head)) return 'wpl';
  if (head.includes('<plist') || (name.endsWith('.xml') && head.startsWith('<'))) return 'itunes';
  if (head.startsWith('{') || head.startsWith('[') || name.endsWith('.json')) return 'json';
  // Shazam's export opens with a title line above the header: look at the first few lines.
  if (name.endsWith('.csv') || name.endsWith('.tsv') ||
    text.split(/\r\n|\n|\r/).filter((l) => !blank(l)).slice(0, 3).some((l) => /[,;\t]/.test(l))) return 'csv';
  return 'text';
}

/**
 * A Windows Media Player playlist (.wpl) or a Zune one (.zpl): both open with their own processing
 * instruction above a `smil` element. A ".zpl" that is not XML is Zoom Player's (a text list).
 */
function looksLikeWpl(name, head) {
  if (name.endsWith('.wpl')) return true;
  if (!head.startsWith('<')) return false;
  const h = head.toLowerCase();
  return h.startsWith('<?wpl') || h.startsWith('<?zpl') ||
    ((name.endsWith('.zpl') || h.slice(0, 400).includes('<smil')) && h.includes('<smil'));
}

// ---------------------------------------------------------------- CSV

const CSV_HEADER = ['title', 'artist', 'album', 'duration_ms', 'isrc'];
const CSV_KEYS = {
  title: new Set(['title', 'trackname', 'name', 'track', 'song', 'songname', 'tracktitle', 'titolo', 'brano']),
  artist: new Set(['artist', 'artists', 'artistname', 'artistnames', 'artista', 'artisti', 'performer']),
  album: new Set(['album', 'albumname', 'albumtitle']),
  duration: new Set(['durationms', 'duration', 'length', 'time', 'durata']),
  isrc: new Set(['isrc']),
  // Only these exact columns (Trackhop's own): a file of songs from elsewhere is read as it always was.
  feed: new Set(['feedurl']),
  itunes: new Set(['itunesid']),
};
const norm = (h) => h.toLowerCase().replace(/[^a-z]/g, '');

const quote = (v) => (/[,"\n\r;]/.test(v) ? '"' + v.replaceAll('"', '""') + '"' : v);

export function encodeCsv(tracks) {
  const shows = tracks.some((t) => !blank(t.feedUrl) || !blank(t.itunesId));
  const out = [(shows ? [...CSV_HEADER, 'feed_url', 'itunes_id'] : CSV_HEADER).join(',') + '\r\n'];
  for (const t of tracks) {
    const cells = [t.title, artistLine(t), t.album, t.durationMs > 0 ? String(t.durationMs) : '', t.isrc || ''];
    if (shows) cells.push(t.feedUrl || '', t.itunesId || '');
    out.push(cells.map(quote).join(',') + '\r\n');
  }
  return out.join('');
}

/**
 * RFC 4180 rows (quotes, fields over several lines) from text that comes in pieces. The separator
 * (comma, semicolon or TAB) is read off the first lines that have one. A file that ends inside a
 * quoted field was cut short: end() throws ToolError('incomplete').
 */
export class CsvRows {
  constructor(onRow) {
    this.onRow = onRow;
    this.sep = 0;
    this.head = '';
    this.held = '';
    this.field = '';
    this.row = [];
    this.inQuotes = false;
  }

  push(text) {
    if (!this.sep) {
      // Wait for three lines with something on them (or a good amount of text) to choose the separator.
      this.head += text;
      const lines = this.head.split(/\r\n|\n|\r/);
      if (lines.filter((l) => !blank(l)).length < 4 && this.head.length < 65536) return;
      text = this.start();
    }
    this.feed(text, false);
  }

  start() {
    const first = this.head.replace(/^﻿/, '');
    const lines = first.split(/\r\n|\n|\r/).filter((l) => !blank(l)).slice(0, 3).join('\n');
    let best = ',', most = 0;
    for (const c of [',', ';', '\t']) {
      const n = lines.split(c).length - 1;
      if (n > most) { most = n; best = c; }
    }
    this.sep = best.charCodeAt(0);
    this.head = '';
    return first;
  }

  feed(chunk, final) {
    let text = this.held + chunk;
    this.held = '';
    if (!final) {
      // Quotes at the very end may be half of a doubled quote: they wait for the next piece.
      let q = text.length;
      while (q > 0 && text.charCodeAt(q - 1) === 34) q--;
      if (q < text.length) { this.held = text.slice(q); text = text.slice(0, q); }
    }
    const sep = this.sep, n = text.length;
    let field = this.field, row = this.row, inQuotes = this.inQuotes, seg = 0;
    for (let i = 0; i < n; i++) {
      const c = text.charCodeAt(i);
      if (inQuotes) {
        if (c !== 34) continue;
        if (text.charCodeAt(i + 1) === 34) { field += text.slice(seg, i + 1); i++; seg = i + 1; }
        else { field += text.slice(seg, i); seg = i + 1; inQuotes = false; }
      } else if (c === 34) { field += text.slice(seg, i); seg = i + 1; inQuotes = true; }
      else if (c === sep) { row.push(field + text.slice(seg, i)); field = ''; seg = i + 1; }
      else if (c === 13) { field += text.slice(seg, i); seg = i + 1; }
      else if (c === 10) {
        row.push(field + text.slice(seg, i)); field = ''; seg = i + 1;
        if (row.some((v) => !blank(v))) this.onRow(row);
        row = [];
      }
    }
    this.field = field + text.slice(seg);
    this.row = row;
    this.inQuotes = inQuotes;
  }

  end() {
    this.feed(this.sep ? '' : this.start(), true);
    if (this.inQuotes) throw new ToolError('incomplete', 'csv');
    if (this.field.length || this.row.length) {
      this.row.push(this.field);
      if (this.row.some((v) => !blank(v))) this.onRow(this.row);
    }
    this.field = ''; this.row = [];
  }
}

/** CSV rows as tracks: our own columns, and the headers of the common exports (Exportify, TuneMyMusic, Soundiiz, Shazam). */
class CsvReader {
  constructor() {
    this.tracks = [];
    this.first = [];
    this.cols = null;
    this.source = null;
    this.rows = new CsvRows((r) => this.row(r));
  }
  push(text) { this.rows.push(text); }
  end() {
    this.rows.end();
    if (!this.cols && this.first.length) this.header();
    return { tracks: this.tracks, source: this.source };
  }

  row(r) {
    if (this.cols) return this.add(r);
    this.first.push(r);
    if (this.first.length === 2) this.header();
  }

  header() {
    let rows = this.first;
    const hasTitle = (r) => r.map(norm).some((h) => CSV_KEYS.title.has(h));
    // Shazam's export opens with a one-cell line ("Shazam Library") above the real header.
    if (rows.length > 1 && rows[0].length === 1 && rows[1].length > 1 && hasTitle(rows[1])) {
      rows = rows.slice(1);
      this.source = 'shazam';
    }
    const header = rows[0].map(norm);
    const col = (keys) => header.findIndex((h) => keys.has(h));
    const c = {
      title: col(CSV_KEYS.title), artist: col(CSV_KEYS.artist), album: col(CSV_KEYS.album), dur: col(CSV_KEYS.duration),
      isrc: col(CSV_KEYS.isrc), feed: col(CSV_KEYS.feed), itunes: col(CSV_KEYS.itunes),
    };
    // "duration_ms" (ours, Exportify's "Duration (ms)") is milliseconds whatever the size.
    c.inMs = c.dur >= 0 && header[c.dur].endsWith('ms');
    // Without a header we know: title,artist,album,duration,isrc.
    c.hasHeader = c.title >= 0;
    if (!c.hasHeader) Object.assign(c, { title: 0, artist: 1, album: 2, dur: 3, isrc: 4, feed: -1, itunes: -1 });
    else if (!this.source && header.includes('trackuri') && header.includes('trackname')) this.source = 'exportify';
    this.cols = c;
    for (const r of c.hasHeader ? rows.slice(1) : rows) this.add(r);
    this.first = [];
  }

  add(r) {
    const c = this.cols;
    const at = (i) => (i >= 0 && r[i] != null ? r[i].trim() : '');
    const title = at(c.title);
    if (!title) return;
    const rawDur = at(c.dur);
    let dur;
    if (c.inMs) { const d = toDouble(rawDur); dur = d != null ? Math.trunc(d) : parseDuration(rawDur); } else dur = parseDuration(rawDur);
    const t = track(title, splitArtists(at(c.artist)), at(c.album), dur, at(c.isrc) || null);
    t.feedUrl = own(at(c.feed)) || null;
    t.itunesId = own(at(c.itunes)) || null;
    this.tracks.push(t);
  }
}

// ---------------------------------------------------------------- names and paths (M3U, PLS, text)

/** Folder names that are a music root, not an artist or an album. */
const ROOT_FOLDERS = new Set([
  'music', 'my music', 'musik', 'musique', 'musica', 'música', 'muziek', 'mp3', 'audio', 'songs', 'download', 'downloads',
  'storage', 'emulated', 'sdcard', 'media', 'mnt', 'home', 'users', 'volumes', 'various artists', 'various', 'va', 'compilations',
]);
/** What comes before the folders: "http://host", "file://", "file:", "C:". */
const URL_OR_DRIVE = /^(?:[A-Za-z][\w+.-]*:\/\/[^/]*|file:|[A-Za-z]:(?=\/))/;
// The track number a file name starts with: "01 - ", "01. ", "1-01-" always; "01 " before
// "Artist - Title" only when zero-padded ("50 Cent - …" is an artist); "12 " before a bare title.
const NUMBER_DASH = /^(?:\d{1,2}[-.])?\d{1,3}\s*(?:[-.)]\s+|-(?!\d))(?=.*\p{L})/u;
const PADDED_NUMBER = /^(?:\d{1,2}[-.])?0\d\s+(?=.*\p{L})/u;
const NUMBER = /^(?:\d{1,2}[-.])?\d{1,3}\s+(?=.*\p{L})/u;

function withoutTrackNumber(name) {
  if (NUMBER_DASH.test(name)) return name.replace(NUMBER_DASH, '').trim();
  if (DASH.test(name)) return name.replace(PADDED_NUMBER, '').trim();
  return name.replace(NUMBER, '').trim();
}

/** The folder a music library starts in: the next folder is the artist's. */
const MUSIC_ROOTS = new Set(['music', 'my music', 'musik', 'musique', 'musica', 'música', 'muziek', 'mp3']);
/** "(Disc 1) …", "CD2", "Disk 3": a disc of the album above it, not an album. */
const DISC_FOLDER = /^[(\[]?\s*(?:disc|disk|cd)\s*\d+\b/i;
/** "1977 - ", "2016.12 - ", "[1993] ": the year a folder name starts with, before the album's. */
const YEAR_PREFIX = /^(?:\d{4}(?:[.-]\d{1,2}){0,2}\s+[-–—]\s+|[\[(]\d{4}[\])]\s*[-–—]?\s*)(?=\S)/;

function same(a, b) {
  const key = (s) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
  return key(a).length > 0 && key(a) === key(b);
}

/**
 * A track from a path without #EXTINF: "Artist - Title.mp3" as named, or a library layout:
 * Music/Artist/Album/01 Title.mp3, with folders between the artist and the album or a disc folder
 * below the album, "1977 - " years before album names, and files named "Artist - Album - 02 Title".
 */
export function fromPath(path) {
  let p = path;
  if (path.includes('://') || path.startsWith('file:')) {
    try { p = decodeURIComponent(path); } catch (e) { p = path; }
  }
  p = p.replaceAll('\\', '/').replace(URL_OR_DRIVE, '');
  const parts = p.split('/').filter((s) => !blank(s));
  const last = parts.length ? parts[parts.length - 1] : '';
  const dot = last.lastIndexOf('.');
  const name = withoutTrackNumber((dot >= 0 ? last.slice(0, dot) : last).replaceAll('_', ' ').trim());
  const folders = parts.slice(0, -1);
  // "0" of /storage/emulated/0 and drive letters are not names; "1989" can be an album.
  const generic = (f) => ROOT_FOLDERS.has(f.toLowerCase()) || (f.length < 4 && /^[\d:]*$/.test(f));

  let root = -1;
  for (let i = folders.length - 1; i >= 0; i--) if (MUSIC_ROOTS.has(folders[i].toLowerCase())) { root = i; break; }
  const below = root >= 0 ? folders.slice(root + 1) : folders;
  let albumAt = below.length - 1;
  if (albumAt >= 1 && DISC_FOLDER.test(below[albumAt])) albumAt--;
  let artistFolder = root >= 0 ? (albumAt >= 1 ? below[0] : undefined) : below[albumAt - 1];
  if (artistFolder == null || generic(artistFolder)) artistFolder = null;
  let albumFolder = '';
  const af = below[albumAt];
  if (af != null && artistFolder != null && !generic(af)) {
    const noYear = af.replace(YEAR_PREFIX, '').trim();
    // "SAULT - 'NINE'" in the folder of SAULT: the album is 'NINE'.
    const d = dash2(noYear);
    albumFolder = d.length === 2 && same(d[0], artistFolder) ? d[1].trim() : noYear;
  }

  // "Artist - Album - 02 Title": three parts whose artist or album the folders confirm.
  const dashed = name.split(DASH);
  if (dashed.length >= 3 && artistFolder != null && (same(dashed[0], artistFolder) || same(dashed[1], albumFolder))) {
    const rest = dashed.slice(2).join(' - ');
    const title = withoutTrackNumber(rest);
    return track(blank(title) ? rest : title, splitArtists(dashed[0]), dashed[1].trim());
  }
  const [artists, title] = splitArtistTitle(name);
  if (artists.length) {
    return track(title, artists, artistFolder != null && artists.some((a) => same(a, artistFolder)) ? albumFolder : '');
  }
  if (artistFolder == null || !albumFolder) return track(title);
  return track(title, [artistFolder], albumFolder);
}

function fromLabel(label) {
  const [artists, title] = splitArtistTitle(label);
  return track(title, artists);
}

/**
 * How a name with three parts, "A - B - C", reads where only a name is given: the album layout
 * ("Artist - Album - Title") when at least two names and four in five of them have a third part.
 * Tracks with an album already (a library path) or without an artist are left as they are.
 */
export function withLayout(tracks) {
  const splittable = (t) => {
    if (!t.artists.length || !blank(t.album)) return false;
    const d = dash2(t.title);
    return d.length === 2 && d.every((s) => !blank(s));
  };
  const named = tracks.filter((t) => t.artists.length && blank(t.album)).length;
  const three = tracks.filter(splittable).length;
  if (three < 2 || three * 5 < named * 4) return tracks;
  return tracks.map((t) => {
    if (!splittable(t)) return t;
    const [album, rest] = dash2(t.title);
    const title = withoutTrackNumber(rest.trim());
    return { ...t, title: blank(title) ? rest.trim() : title, album: album.trim() };
  });
}

const EXTINF = /^#EXTINF:\s*(-?\d+(?:\.\d+)?)?\s*(?:[^,]*)?,\s*(.*)$/;

class M3uReader {
  constructor() {
    this.tracks = [];
    this.seconds = 0;
    this.label = null;
    this.lines = new Lines((l) => this.line(l));
  }
  push(text) { this.lines.push(text); }
  end() { this.lines.end(); return { tracks: withLayout(this.tracks) }; }
  line(raw) {
    const line = raw.trim();
    if (!line) return;
    if (line.startsWith('#')) {
      const m = EXTINF.exec(line);
      if (!m) return;
      const s = m[1] ? toDouble(m[1]) : null;
      this.seconds = s != null && s > 0 ? Math.trunc(s) : 0;
      this.label = blank(m[2]) ? null : m[2].trim();
      return;
    }
    const t = this.label != null ? fromLabel(this.label) : fromPath(line);
    if (!blank(t.title)) { t.durationMs = this.seconds * 1000; this.tracks.push(t); }
    this.seconds = 0;
    this.label = null;
  }
}

const PLS_ENTRY = /^(file|title|length)(\d+)\s*=\s*(.*)$/i;

/** PLS (Winamp, VLC, Clementine): File1=…, Title1=Artist - Title, Length1=seconds. */
class PlsReader {
  constructor() {
    this.entries = new Map();
    this.lines = new Lines((raw) => {
      const m = PLS_ENTRY.exec(raw.trim());
      if (!m) return;
      const n = Number(m[2]);
      if (!this.entries.has(n)) this.entries.set(n, {});
      this.entries.get(n)[m[1].toLowerCase()] = m[3].trim();
    });
  }
  push(text) { this.lines.push(text); }
  end() {
    this.lines.end();
    const tracks = [];
    for (const n of [...this.entries.keys()].sort((a, b) => a - b)) {
      const e = this.entries.get(n);
      const t = !blank(e.title) ? fromLabel(e.title) : e.file != null ? fromPath(e.file) : null;
      if (!t || blank(t.title)) continue;
      const secs = e.length != null ? toLong(e.length) : null;
      t.durationMs = (secs != null && secs > 0 ? secs : 0) * 1000;
      tracks.push(t);
    }
    return { tracks: withLayout(tracks) };
  }
}

const LIST_PREFIX = /^\s*\d+\s*[.)\-]\s+/;

/** Plain text, one song a line: "Artist - Title", with or without "1." in front. */
class TextReader {
  constructor() {
    this.tracks = [];
    this.lines = new Lines((raw) => {
      const line = raw.trim().replace(LIST_PREFIX, '').trim();
      if (!line || line.startsWith('#')) return;
      // JSON punctuation or a JSON object is not a track name.
      if ('[]{}'.includes(line[0]) || line.startsWith('```')) return;
      const t = fromLabel(line);
      if (!blank(t.title)) this.tracks.push(t);
    });
  }
  push(text) { this.lines.push(text); }
  end() { this.lines.end(); return { tracks: withLayout(this.tracks) }; }
}

// ---------------------------------------------------------------- XSPF and OPML

const MAX_PENDING = 16 << 20;

function xspfField(body, tag) {
  const m = new RegExp('<' + tag + '>([\\s\\S]*?)</' + tag + '>').exec(body);
  if (!m) return null;
  const s = unescapeXml(m[1]).trim();
  return s.length ? s : null;
}

/** XSPF, one <track> at a time. A file without its closing </playlist>, or cut inside a track, is incomplete. */
class XspfReader {
  constructor() { this.tracks = []; this.buf = ''; this.tail = ''; this.closed = false; this.opened = false; }
  push(text) {
    const seen = this.tail + text;
    if (seen.includes('<playlist')) this.opened = true;
    if (seen.includes('</playlist')) this.closed = true;
    this.tail = seen.slice(-16);
    let buf = this.buf + text;
    let pos = 0;
    for (;;) {
      const a = buf.indexOf('<track>', pos);
      if (a < 0) { pos = Math.max(pos, buf.length - 6); break; }
      const b = buf.indexOf('</track>', a + 7);
      if (b < 0) { pos = a; break; }
      this.one(buf.slice(a + 7, b));
      pos = b + 8;
    }
    this.buf = buf.slice(pos);
    if (this.buf.length > MAX_PENDING) throw new ToolError('unreadable', 'xspf');
  }
  one(body) {
    const title = xspfField(body, 'title');
    if (title == null) return;
    const isrc = /<identifier>\s*isrc:([A-Za-z0-9]+)\s*<\/identifier>/.exec(body);
    const dur = xspfField(body, 'duration');
    this.tracks.push(track(title, splitArtists(xspfField(body, 'creator') || ''), xspfField(body, 'album') || '',
      (dur != null ? toLong(dur) : null) ?? 0, isrc ? isrc[1] : null));
  }
  end() {
    if (this.buf.includes('<track>') || (this.opened && !this.closed)) throw new ToolError('incomplete', 'xspf');
    return { tracks: this.tracks };
  }
}

const OPML_OUTLINE = /<outline\b((?:[^>"']|"[^"]*"|'[^']*')*)>/iy;
const OPML_ATTR = /([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
const NUMERIC_ENTITY = /&#(x[0-9a-fA-F]+|\d+);/g;
const APPLE_LINK = /(?:podcasts|itunes)\.apple\.com\/.*?\bid(\d{3,})/i;

/** An Apple podcast id: digits only, from a bare id or a podcasts.apple.com / itunes.apple.com link. */
function itunesId(raw) {
  const s = raw ? raw.trim() : '';
  if (!s) return null;
  if (/^\d+$/.test(s)) return s.replace(/^0+/, '') || null;
  const m = APPLE_LINK.exec(s);
  return m ? m[1] : null;
}

/** An attribute's value as written in XML: entities read, line breaks and runs of spaces as one space. */
function xmlText(s) {
  const t = s.replace(NUMERIC_ENTITY, (m, v) => {
    const code = /^x/i.test(v) ? parseInt(v.slice(1), 16) : parseInt(v, 10);
    try { return String.fromCodePoint(code); } catch (e) { return m; }
  });
  return unescapeXml(t).replace(/\s+/g, ' ').trim();
}

/**
 * OPML of podcast subscriptions (AntennaPod, Pocket Casts, Podcast Addict, Apple Podcasts…): one
 * show per outline with a feed. Groups, comments and outlines without a name are skipped.
 */
class OpmlReader {
  constructor() { this.tracks = []; this.buf = ''; this.tail = ''; this.closed = false; this.opened = false; }
  push(text) { this.scan(text, false); }
  scan(text, final) {
    const seen = (this.tail + text).toLowerCase();
    if (seen.includes('<opml')) this.opened = true;
    if (seen.includes('</opml')) this.closed = true;
    this.tail = seen.slice(-16);
    const buf = this.buf + text;
    let pos = 0;
    for (;;) {
      const lt = buf.indexOf('<', pos);
      if (lt < 0) { pos = buf.length; break; }
      pos = lt;
      if (buf.length - lt < 9 && !final) break;
      if (buf.startsWith('<!--', lt)) {
        const e = buf.indexOf('-->', lt + 4);
        if (e < 0) { if (final) pos = buf.length; break; }
        pos = e + 3;
        continue;
      }
      if (/^<outline\b/i.test(buf.slice(lt, lt + 9))) {
        OPML_OUTLINE.lastIndex = lt;
        const m = OPML_OUTLINE.exec(buf);
        if (!m) { if (final) pos = buf.length; break; }
        this.outline(m[1]);
        pos = lt + m[0].length;
        continue;
      }
      pos = lt + 1;
    }
    this.buf = buf.slice(pos);
    if (this.buf.length > MAX_PENDING) throw new ToolError('unreadable', 'opml');
  }
  outline(body) {
    const attrs = {};
    for (const a of body.matchAll(OPML_ATTR)) attrs[a[1].toLowerCase()] = xmlText(a[2] != null ? a[2] : a[3]);
    // Group headers are outlines too; only the ones pointing at a feed are shows.
    const feed = !blank(attrs.xmlurl) ? attrs.xmlurl
      : !blank(attrs.url) && (attrs.type || '').toLowerCase() === 'rss' ? attrs.url : null;
    if (feed == null) return;
    const title = [attrs.text, attrs.title].find((v) => !blank(v));
    if (title == null) return;
    const publisher = [attrs['itunes:author'], attrs.author].find((v) => !blank(v));
    const t = track(title, publisher != null ? [publisher] : []);
    t.feedUrl = own(feed);
    t.itunesId = itunesId(attrs['itunes:id']) || itunesId(attrs.htmlurl);
    this.tracks.push(t);
  }
  end() {
    this.scan('', true);
    if (this.opened && !this.closed) throw new ToolError('incomplete', 'opml');
    return { tracks: this.tracks };
  }
}

// ---------------------------------------------------------------- JSON (and JSPF)

const JSON_KEYS = {
  title: ['title', 'trackname', 'name', 'track', 'song', 'songname', 'tracktitle', 'titolo', 'brano'],
  artist: ['artist', 'artists', 'artistname', 'artistnames', 'albumartist', 'artista', 'artisti', 'performer', 'creator'],
  album: ['album', 'albumname', 'albumtitle'],
  duration: ['durationms', 'duration', 'length', 'time', 'totaltime', 'durata'],
  isrc: ['isrc'],
};
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/** A field can be a string, a number, a list of strings or a list of objects { name: … }. */
function flatten(v) {
  if (v === null || v === undefined) return null;
  if (Array.isArray(v)) {
    const s = v.map(flatten).filter((x) => !blank(x)).join(', ');
    return blank(s) ? null : s;
  }
  if (typeof v === 'object') {
    for (const k of ['name', 'title', 'artistName']) if (v[k] !== undefined) return flatten(v[k]);
    return null;
  }
  const s = String(v);
  return s === 'null' ? null : s;
}

/** The value of the first known key (compared without capitals, spaces or underscores). */
function pick(normalized, keys) {
  for (const k of keys) {
    if (!normalized.has(k)) continue;
    const s = flatten(normalized.get(k));
    if (!blank(s)) return s;
  }
  return null;
}

function trackOfJson(o) {
  const n = new Map();
  for (const k of Object.keys(o)) n.set(k.toLowerCase().replace(/[^a-z]/g, ''), o[k]);
  const title = (pick(n, JSON_KEYS.title) || '').trim();
  if (!title) return null;
  let isrc = pick(n, JSON_KEYS.isrc);
  // JSPF keeps the ISRC among the identifiers ("isrc:…"), where our own export writes it.
  if (isrc == null && n.has('identifier')) {
    const m = /\bisrc:([A-Za-z0-9]+)/i.exec(flatten(n.get('identifier')) || '');
    if (m) isrc = m[1];
  }
  return track(title, splitArtists(pick(n, JSON_KEYS.artist) || ''), pick(n, JSON_KEYS.album) || '',
    parseDuration(pick(n, JSON_KEYS.duration) || ''), isrc);
}

/**
 * JSON of any shape: the longest array of objects that looks like a list of tracks (one of them
 * has a title key) is the playlist. Arrays are weighed as they close and only their tracks are
 * kept; a file that does not parse to its end (cut short, or not JSON) gives nothing.
 */
class JsonReader {
  constructor() {
    this.best = null;
    let order = 0;
    this.stream = new JsonStream({
      onArrayStart: (f) => { f.meta = { order: order++, objects: 0, tracks: [] }; },
      onArrayItem: (f, v) => {
        if (isObject(v)) {
          f.meta.objects++;
          const t = trackOfJson(v);
          if (t) f.meta.tracks.push(t);
        }
        // What a later field may still need of an inner array (a few artists' names), not all of it.
        return f.value.length < 64;
      },
      onArrayEnd: (f) => {
        const m = f.meta, b = this.best;
        if (!m.objects || !m.tracks.length) return;
        if (!b || m.objects > b.objects || (m.objects === b.objects && m.order < b.order)) this.best = m;
      },
    });
  }
  push(text) { this.stream.push(text); }
  end() {
    const root = this.stream.end();
    const jspf = isObject(root) && isObject(root.playlist);
    return { tracks: this.best ? this.best.tracks : [], source: jspf ? 'jspf' : null };
  }
}

// ---------------------------------------------------------------- Apple Music / iTunes library

const ENTITY = /&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g;
const NAMED = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
function unescapePlist(s) {
  if (!s.includes('&')) return s;
  return s.replace(ENTITY, (m, e) => {
    if (NAMED[e]) return NAMED[e];
    try { return String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)); } catch (x) { return m; }
  });
}

const MAX_TEXT = 64 * 1024;

/**
 * A plist read as events: dictionaries, arrays, keys and values. Only what a plist holds is
 * understood; <data> (smart playlists' rules, artwork) is skipped without being kept, and a value
 * longer than 64 K characters is cut, so no single element can fill the memory.
 */
export class PlistEvents {
  constructor(sink) { this.sink = sink; this.buf = ''; this.open = null; this.skipping = false; this.text = ''; }
  push(chunk) { this.scan(chunk, false); }
  end() { this.scan('', true); }
  scan(chunk, final) {
    const buf = this.buf ? this.buf + chunk : chunk;
    const sink = this.sink;
    let pos = 0;
    for (;;) {
      const lt = buf.indexOf('<', pos);
      const upTo = lt < 0 ? buf.length : lt;
      if (this.open !== null && !this.skipping && this.text.length < MAX_TEXT && upTo > pos) this.text += buf.slice(pos, Math.min(upTo, pos + MAX_TEXT));
      pos = upTo;
      if (lt < 0) break;
      // A tag: to its '>' (a comment ends at "-->", a declaration at the first '>').
      if (buf.length - lt < 4 && !final) break;
      let gt;
      if (buf.startsWith('<!-', lt)) {
        const e = buf.indexOf('-->', lt + 3);
        if (e < 0) { if (final) pos = buf.length; break; }
        pos = e + 3;
        continue;
      }
      gt = buf.indexOf('>', lt + 1);
      if (gt < 0) { if (final) pos = buf.length; break; }
      pos = gt + 1;
      const first = buf.charCodeAt(lt + 1);
      if (first === 33 || first === 63) continue; // <!DOCTYPE …>, <?xml …?>
      const raw = buf.slice(lt + 1, gt).trim();
      const closing = raw.startsWith('/');
      const selfClosing = raw.endsWith('/');
      let name = raw;
      if (closing) name = name.slice(1);
      if (selfClosing && name.endsWith('/')) name = name.slice(0, -1);
      const sp = name.indexOf(' ');
      name = (sp >= 0 ? name.slice(0, sp) : name).trim();
      if (closing && name === this.open) {
        // <data> says only that it is there (a smart playlist's rules): its content is never kept.
        if (this.skipping) sink.value(name, '');
        else {
          const value = unescapePlist(this.text);
          if (name === 'key') sink.key(value); else sink.value(name, value);
        }
        this.open = null; this.skipping = false; this.text = '';
      } else if (closing) {
        if (name === 'dict') sink.endDict(); else if (name === 'array') sink.endArray();
      } else if (selfClosing) {
        if (name === 'true' || name === 'false') sink.value(name, '');
        else if (name === 'dict') { sink.startDict(); sink.endDict(); }
        else if (name === 'array') { sink.startArray(); sink.endArray(); }
        else if (name === 'string') sink.value('string', '');
      } else if (name === 'dict') sink.startDict();
      else if (name === 'array') sink.startArray();
      else if (name === 'plist') { /* the wrapper */ }
      else { this.open = name; this.text = ''; this.skipping = name === 'data'; }
    }
    this.buf = buf.slice(pos);
    if (this.buf.length > MAX_PENDING) throw new ToolError('unreadable', 'plist');
  }
}

/**
 * A library or playlist exported by Apple Music / iTunes (File › Library › Export Library or
 * Export Playlist): a "Tracks" dictionary (one entry per song) and a "Playlists" array (each with
 * its "Playlist Items"). Kinds: user, smart, folder (a heading, never exported) and system (the
 * library's own lists: Library, Music, Podcasts, Downloaded…).
 */
export class ItunesLibrary {
  constructor(tracks, playlists) {
    this.tracks = tracks;
    /** Every playlist of the file, in its order, folders and the library's own lists included. */
    this.playlists = playlists;
  }
  get trackCount() { return this.tracks.size; }
  /** The playlists a person picks from: theirs and smart ones, not folders or the library's own. */
  get choices() { return this.playlists.filter((p) => p.kind === 'user' || p.kind === 'smart'); }
  /** A whole library (it lists the library's own lists), not one exported playlist. */
  get isLibrary() { return this.playlists.some((p) => p.kind === 'system'); }
  /** True when there is something to pick: a library with playlists of its own, or a file of several playlists. */
  get worthPicking() { const c = this.choices; return c.length > 1 || (this.isLibrary && c.length > 0); }
  /** The songs of a playlist in its order; an item whose song is not in the file is skipped. */
  tracksOf(p) {
    const out = [];
    for (const id of p.trackIds) { const i = this.tracks.get(id); if (i) out.push(ItunesLibrary.track(i)); }
    return out;
  }
  /** The file as one list: the only playlist of an exported playlist, in its order, or every song of the library. */
  asOneList() {
    const c = this.choices;
    if (c.length === 1 && !this.isLibrary && c[0].trackIds.length > 0) return this.tracksOf(c[0]);
    return [...this.tracks.values()].map(ItunesLibrary.track);
  }
  static track(i) { return track(i.title, splitArtists(i.artist), i.album, i.durationMs); }
}

class ItunesReader {
  constructor() {
    this.tracks = new Map();
    this.raws = [];
    // The open containers: 'd' or 'a', and for a dictionary the key its next value goes under.
    this.kinds = [];
    this.keys = [];
    this.track = null;
    this.playlist = null;
    this.items = [];
    this.itemDepth = -1;
    this.sawRoot = false;
    this.events = new PlistEvents(this);
  }
  push(text) { this.events.push(text); }

  startDict() {
    const depth = this.kinds.length, k = this.kinds;
    if (depth === 0) this.sawRoot = true;
    // root dict = level 0; Tracks dict = level 1 under key "Tracks"; a song = level 2 inside it.
    if (depth === 2 && k[0] === 'd' && k[1] === 'd' && this.keys[0] === 'Tracks') this.track = {};
    // Playlists array = level 1 under key "Playlists"; a playlist = level 2 inside it.
    if (depth === 2 && k[0] === 'd' && k[1] === 'a' && this.keys[0] === 'Playlists') { this.playlist = {}; this.items = []; }
    if (this.playlist && depth === 4 && k[3] === 'a' && this.keys[2] === 'Playlist Items') this.itemDepth = depth;
    k.push('d'); this.keys.push(null);
  }
  endDict() {
    const depth = this.kinds.length - 1;
    if (depth < 0) return;
    if (depth === 2 && this.track) { this.addTrack(this.track); this.track = null; }
    if (depth === 2 && this.playlist) { this.addPlaylist(this.playlist); this.playlist = null; }
    if (depth === this.itemDepth) this.itemDepth = -1;
    this.kinds.pop(); this.keys.pop();
  }
  startArray() { this.kinds.push('a'); this.keys.push(null); }
  endArray() { if (this.kinds.length) { this.kinds.pop(); this.keys.pop(); } }
  key(name) { if (this.keys.length) this.keys[this.keys.length - 1] = name; }
  value(type, text) {
    const depth = this.kinds.length - 1;
    const key = depth >= 0 ? this.keys[depth] : null;
    if (key == null) return;
    const v = type === 'true' ? 'true' : type === 'false' ? 'false' : text;
    if (depth === 2 && this.track) this.track[key] = v;
    else if (depth === 2 && this.playlist) this.playlist[key] = v;
    else if (this.itemDepth >= 0 && depth === this.itemDepth && key === 'Track ID') {
      const id = toLong(v.trim());
      if (id != null) this.items.push(id);
    }
  }

  addTrack(f) {
    const id = f['Track ID'] != null ? toLong(f['Track ID'].trim()) : null;
    if (id == null) return;
    const name = (f.Name || '').trim();
    if (!name) return;
    const time = f['Total Time'] != null ? toLong(f['Total Time'].trim()) : null;
    this.tracks.set(id, {
      title: own(name),
      artist: own((f.Artist != null ? f.Artist : f['Album Artist'] || '').trim()),
      album: own((f.Album || '').trim()),
      durationMs: time ?? 0,
    });
  }
  addPlaylist(f) {
    const has = (k) => Object.prototype.hasOwnProperty.call(f, k);
    const kind = f.Folder === 'true' ? 'folder'
      : f.Master === 'true' || has('Distinguished Kind') || f.Visible === 'false' ? 'system'
        : has('Smart Info') || has('Smart Criteria') ? 'smart' : 'user';
    const key = f['Playlist Persistent ID'] ?? f['Playlist ID'] ?? '#' + this.raws.length;
    this.raws.push({ key: own(String(key)), name: own((f.Name || '').trim() || 'Playlist'), kind, parent: f['Parent Persistent ID'] != null ? own(f['Parent Persistent ID']) : null, trackIds: Int32Array.from(this.items) });
  }

  end() {
    this.events.end();
    // A file cut short (a copy or a download that stopped half way) leaves containers open:
    // nothing is taken from it, rather than a library that silently lacks its end.
    if (this.kinds.length || !this.sawRoot) throw new ToolError('incomplete', 'library');
    const folders = new Map(this.raws.filter((r) => r.kind === 'folder').map((r) => [r.key, r.name]));
    const playlists = this.raws.map((r) => ({ key: r.key, name: r.name, kind: r.kind, folder: r.parent != null ? folders.get(r.parent) ?? null : null, trackIds: r.trackIds }));
    return { library: new ItunesLibrary(this.tracks, playlists) };
  }
}

// ---------------------------------------------------------------- rekordbox XML, WPL and ZPL

const TAG_ATTR = /([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;

/** The attributes of a tag, names as written, values with their entities read. */
function tagAttrs(s) {
  const out = {};
  TAG_ATTR.lastIndex = 0;
  for (let m; (m = TAG_ATTR.exec(s));) out[m[1]] = unescapeXml((m[2] ?? m[3]).replace(NUMERIC_ENTITY, (x, v) => {
    const code = /^x/i.test(v) ? parseInt(v.slice(1), 16) : parseInt(v, 10);
    try { return String.fromCodePoint(code); } catch (e) { return x; }
  }));
  return out;
}

/**
 * XML read as tags, a piece at a time (the app's XmlTokens): open(name, attrs, selfClosing) and
 * close(name) for each tag, the text between them passed over; comments and declarations skipped.
 * `whole` says the file ended where its first element did: one cut short does not.
 */
class XmlTags {
  constructor(sink, kind) { this.sink = sink; this.kind = kind; this.buf = ''; this.root = null; this.rootClosed = false; }
  push(text) {
    const buf = this.buf + text;
    let pos = 0;
    for (;;) {
      const lt = buf.indexOf('<', pos);
      if (lt < 0) { pos = buf.length; break; }
      pos = lt;
      if (buf.length - lt < 4) break;
      if (buf.startsWith('<!--', lt)) {
        const e = buf.indexOf('-->', lt + 4);
        if (e < 0) break;
        pos = e + 3;
        continue;
      }
      // The tag's '>': one inside a quoted attribute is not its end.
      let end = -1, quote = '';
      for (let i = lt + 1; i < buf.length; i++) {
        const c = buf[i];
        if (quote) { if (c === quote) quote = ''; } else if (c === '"' || c === "'") quote = c; else if (c === '>') { end = i; break; }
      }
      if (end < 0) break;
      const raw = buf.slice(lt + 1, end).trim();
      pos = end + 1;
      if (raw.startsWith('!') || raw.startsWith('?')) continue;
      const closing = raw.startsWith('/');
      const selfClosing = raw.endsWith('/');
      const body = closing ? raw.slice(1) : raw;
      const name = /^[^\s/]*/.exec(body)[0];
      if (!name) continue;
      if (closing) {
        this.rootClosed = this.root != null && name.toLowerCase() === this.root.toLowerCase();
        this.sink.close(name);
      } else {
        if (this.root == null) this.root = name;
        this.rootClosed = selfClosing && name.toLowerCase() === this.root.toLowerCase();
        this.sink.open(name, body.slice(name.length), selfClosing);
      }
    }
    this.buf = buf.slice(pos);
    if (this.buf.length > MAX_PENDING) throw new ToolError('unreadable', this.kind);
  }
  end() {
    if (this.root == null || !this.rootClosed || this.buf.includes('<')) throw new ToolError('incomplete', this.kind);
  }
}

/**
 * rekordbox's XML (File › Export Collection in xml format), as AlphaTheta publishes it: a COLLECTION
 * of TRACKs (TrackID, Name, Artist, Album, TotalTime in seconds, Location…) and a PLAYLISTS tree of
 * NODEs, folders (Type 0) and playlists (Type 1) whose TRACK Key entries name a track by its TrackID
 * (KeyType 0) or its Location (KeyType 1). Read into the same library as an Apple Music file, so its
 * playlists are offered to pick from. Beat grids and cue points go by unread.
 */
class RekordboxReader {
  constructor() {
    this.tracks = new Map();
    this.byLocation = new Map();
    this.nodes = [];
    this.open_ = [];
    this.inCollection = false;
    this.inPlaylists = false;
    this.made = 0;
    this.xml = new XmlTags(this, 'rekordbox');
  }
  push(text) { this.xml.push(text); }
  open(name, attrs, selfClosing) {
    if (name === 'COLLECTION') { this.inCollection = !selfClosing; return; }
    if (name === 'PLAYLISTS') { this.inPlaylists = !selfClosing; return; }
    if (this.inCollection && name === 'TRACK') {
      const a = tagAttrs(attrs);
      const title = (a.Name || '').trim();
      // A track without an id of its own still counts: it gets one no real track has.
      const id = (a.TrackID != null ? toLong(a.TrackID.trim()) : null) ?? --this.made;
      if (!blank(a.Location)) this.byLocation.set(own(a.Location), id);
      if (!title) return;
      // Seconds, written as a whole number or with decimals.
      const secs = a.TotalTime != null ? toDouble(a.TotalTime.trim().replace(',', '.')) : null;
      this.tracks.set(id, {
        title: own(title), artist: own((a.Artist || '').trim()), album: own((a.Album || '').trim()),
        durationMs: secs != null && secs > 0 && secs < 1e7 ? Math.trunc(secs * 1000) : 0,
      });
    } else if (this.inPlaylists && name === 'NODE') {
      const a = tagAttrs(attrs);
      // The first NODE is the root folder ("ROOT"): a container, not a folder to show.
      let node = null;
      if (this.open_.length) {
        const parent = this.open_[this.open_.length - 1];
        node = { folder: (a.Type || '').trim() !== '1', name: own((a.Name || '').trim() || 'Playlist'), byLocation: (a.KeyType || '').trim() === '1', parent: parent ? parent.name : null, ids: [] };
        this.nodes.push(node);
      }
      if (!selfClosing) this.open_.push(node);
    } else if (this.inPlaylists && name === 'TRACK') {
      const node = this.open_[this.open_.length - 1];
      if (!node || node.folder) return;
      const key = tagAttrs(attrs).Key;
      if (key == null) return;
      const id = node.byLocation ? this.byLocation.get(key) : toLong(key.trim());
      if (id != null) node.ids.push(id);
    }
  }
  close(name) {
    if (name === 'COLLECTION') this.inCollection = false;
    else if (name === 'PLAYLISTS') this.inPlaylists = false;
    else if (name === 'NODE' && this.inPlaylists && this.open_.length) this.open_.pop();
  }
  end() {
    this.xml.end();
    const playlists = this.nodes.map((n, i) => ({ key: '#' + i, name: n.name, kind: n.folder ? 'folder' : 'user', folder: n.parent, trackIds: Int32Array.from(n.ids) }));
    return { library: new ItunesLibrary(this.tracks, playlists) };
  }
}

/**
 * WPL (Windows Media Player) and ZPL (Zune), the same SMIL: smil › body › seq › media, a media at a
 * time. Windows Media Player writes only `src`, the file's path, read as a path; Groove and Zune add
 * trackTitle, trackArtist, albumTitle, albumArtist and duration (ms), taken as they are.
 */
class WplReader {
  constructor() { this.tracks = []; this.inSeq = 0; this.xml = new XmlTags(this, 'wpl'); }
  push(text) { this.xml.push(text); }
  open(name, attrs, selfClosing) {
    const n = name.toLowerCase();
    if (n === 'seq') { if (!selfClosing) this.inSeq++; return; }
    if (!this.inSeq || n !== 'media') return;
    const a = {};
    for (const [k, v] of Object.entries(tagAttrs(attrs))) a[k.toLowerCase()] = v;
    const title = (a.tracktitle || '').trim();
    if (!title) {
      const src = (a.src || '').trim();
      const t = src ? fromPath(src) : null;
      if (t && !blank(t.title)) this.tracks.push(t);
      return;
    }
    const artist = (a.trackartist || '').trim() || (a.albumartist || '').trim();
    const dur = a.duration != null ? toLong(a.duration.trim()) : null;
    this.tracks.push(track(own(title), splitArtists(artist), own((a.albumtitle || '').trim()), dur != null && dur > 0 ? dur : 0));
  }
  close(name) { if (name.toLowerCase() === 'seq' && this.inSeq > 0) this.inSeq--; }
  end() { this.xml.end(); return { tracks: this.tracks }; }
}

// ---------------------------------------------------------------- reading a file

const READERS = { csv: CsvReader, m3u: M3uReader, pls: PlsReader, itunes: ItunesReader, rekordbox: RekordboxReader, wpl: WplReader, xspf: XspfReader, opml: OpmlReader, json: JsonReader, text: TextReader };

/** A reader for [format]: { push(text), end() → { tracks } or { library } }. */
export function reader(format) {
  return new READERS[format]();
}

/** All of [text] read at once (the tests, and small pasted lists): { format, tracks?, library?, source? }. */
export function parseText(fileName, text) {
  const format = detect(fileName, text);
  const r = reader(format);
  r.push(text);
  return { format, ...r.end() };
}

/**
 * A picked file read to its end, a piece at a time: { format, tracks } or, for an Apple Music /
 * iTunes file, { format, library }. onProgress(bytesRead, bytesInAll); signal stops the reading.
 */
export async function readFile(file, { signal, onProgress, chunk } = {}) {
  const format = detect(file.name || '', await headText(file));
  const result = await streamText(
    () => {
      let done = 0;
      return blobBytes(file, { signal, chunk, onBytes: (n) => { done += n; if (onProgress) onProgress(done, file.size); } });
    },
    () => reader(format),
  );
  return { format, ...result };
}

// ---------------------------------------------------------------- writing

/** The same song twice in a list (same title and artists, whatever the capitals and spaces): the first one stays. */
export function dedupe(tracks) {
  const seen = new Set();
  return tracks.filter((t) => {
    const key = (t.title + '\u0001' + artistLine(t)).toLowerCase().replace(/\s+/g, '');
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function toM3u(tracks) {
  const out = ['#EXTM3U\r\n'];
  for (const t of tracks) {
    const secs = t.durationMs > 0 ? Math.trunc(t.durationMs / 1000) : -1;
    const label = (t.artists.length ? artistLine(t) + ' - ' : '') + t.title;
    // No local file: a line to search for, which players skip or show.
    out.push('#EXTINF:' + secs + ',' + label + '\r\n' + label + '\r\n');
  }
  return out.join('');
}

/** Plain text, one "Artist - Title" line a song: it pastes anywhere, a chat too. */
export function toText(tracks) {
  return tracks.map((t) => (t.artists.length ? artistLine(t) + ' - ' + t.title : t.title)).join('\n') + '\n';
}

/** JSPF (XSPF in JSON), the format of ListenBrainz: creator/title/album/duration and the ISRC among the identifiers. */
export function toJspf(name, tracks) {
  const list = tracks.map((t) => {
    const o = { title: t.title };
    if (t.artists.length) o.creator = artistLine(t);
    if (!blank(t.album)) o.album = t.album;
    if (t.durationMs > 0) o.duration = t.durationMs;
    const isrc = isrcNorm(t);
    if (isrc) o.identifier = ['isrc:' + isrc];
    return o;
  });
  return JSON.stringify({ playlist: { title: name, track: list } }, null, 4) + '\n';
}

/** XSPF, the open XML playlist that VLC, Strawberry, Clementine and others read. */
export function toXspf(name, tracks) {
  const out = ['<?xml version="1.0" encoding="UTF-8"?>\n<playlist version="1" xmlns="http://xspf.org/ns/0/">\n  <title>' + escapeXml(name) + '</title>\n  <trackList>\n'];
  for (const t of tracks) {
    let s = '    <track><title>' + escapeXml(t.title) + '</title>';
    if (t.artists.length) s += '<creator>' + escapeXml(artistLine(t)) + '</creator>';
    if (!blank(t.album)) s += '<album>' + escapeXml(t.album) + '</album>';
    if (t.durationMs > 0) s += '<duration>' + t.durationMs + '</duration>';
    const isrc = isrcNorm(t);
    if (isrc) s += '<identifier>isrc:' + escapeXml(isrc) + '</identifier>';
    out.push(s + '</track>\n');
  }
  out.push('  </trackList>\n</playlist>\n');
  return out.join('');
}

const XML_INVALID = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g;
/** An attribute's value: nothing XML refuses, line breaks and runs of spaces as one space, entities written. */
const xmlAttr = (s) => escapeXml(s.replace(XML_INVALID, '').replace(/\s+/g, ' ').trim());

/**
 * A Windows Media Player playlist, or with [zune] a Zune one (the same list under Zune's header).
 * There is no file behind a list of songs, so `src`, which the format requires, is the same search
 * line an M3U gets ("Artist - Title"); title, artist, album and length go in the attributes Groove
 * and Zune write.
 */
export function toWpl(name, tracks, zune = false) {
  const out = [(zune ? '<?zpl version="2.0"?>' : '<?wpl version="1.0"?>') + '\r\n<smil>\r\n  <head>\r\n    <meta name="Generator" content="Trackhop"/>\r\n' +
    '    <meta name="ItemCount" content="' + tracks.length + '"/>\r\n    <title>' + xmlAttr(name) + '</title>\r\n  </head>\r\n  <body>\r\n    <seq>\r\n'];
  for (const t of tracks) {
    let s = '      <media src="' + xmlAttr(t.artists.length ? artistLine(t) + ' - ' + t.title : t.title) + '" trackTitle="' + xmlAttr(t.title) + '"';
    if (t.artists.length) s += ' trackArtist="' + xmlAttr(artistLine(t)) + '"';
    if (!blank(t.album)) s += ' albumTitle="' + xmlAttr(t.album) + '"';
    if (t.durationMs > 0) s += ' duration="' + t.durationMs + '"';
    out.push(s + '/>\r\n');
  }
  out.push('    </seq>\r\n  </body>\r\n</smil>\r\n');
  return out.join('');
}

/**
 * A rekordbox XML with one playlist, [name], under the root folder: each song once in the COLLECTION
 * (TrackID from 1), the playlist's entries by TrackID in order. No Location: there is no file behind
 * a list of songs, so rekordbox lists them but has nothing to load.
 */
export function toRekordboxXml(name, tracks) {
  const ids = new Map();
  const unique = [];
  const entries = tracks.map((t) => {
    const key = [t.title, artistLine(t), t.album, Math.trunc(t.durationMs / 1000)].join('\u0001');
    if (!ids.has(key)) { unique.push(t); ids.set(key, unique.length); }
    return ids.get(key);
  });
  const attr = (s) => xmlAttr(s).replaceAll("'", '&apos;');
  const out = ['<?xml version="1.0" encoding="UTF-8"?>\n\n<DJ_PLAYLISTS Version="1.0.0">\n  <PRODUCT Name="Trackhop" Version="1.0.0" Company="Trackhop"/>\n  <COLLECTION Entries="' + unique.length + '">\n'];
  unique.forEach((t, i) => {
    out.push('    <TRACK TrackID="' + (i + 1) + '" Name="' + attr(t.title) + '" Artist="' + attr(artistLine(t)) + '" Album="' + attr(t.album || '') +
      '" TotalTime="' + (t.durationMs > 0 ? Math.trunc(t.durationMs / 1000) : 0) + '"/>\n');
  });
  out.push('  </COLLECTION>\n  <PLAYLISTS>\n    <NODE Type="0" Name="ROOT" Count="1">\n      <NODE Name="' + (attr(name) || 'Playlist') + '" Type="1" KeyType="0" Entries="' + entries.length + '">\n');
  for (const id of entries) out.push('        <TRACK Key="' + id + '"/>\n');
  out.push('      </NODE>\n    </NODE>\n  </PLAYLISTS>\n</DJ_PLAYLISTS>\n');
  return out.join('');
}

/** A list in one of the output formats (csv, m3u8, xspf, jspf, wpl, zpl, rekordbox, text), as text. */
export function encode(output, name, tracks) {
  switch (output) {
    case 'csv': return encodeCsv(tracks);
    case 'm3u8': return toM3u(tracks);
    case 'xspf': return toXspf(name, tracks);
    case 'jspf': return toJspf(name, tracks);
    case 'wpl': return toWpl(name, tracks);
    case 'zpl': return toWpl(name, tracks, true);
    case 'rekordbox': return toRekordboxXml(name, tracks);
    case 'text': return toText(tracks);
    default: throw new ToolError('unreadable', 'output ' + output);
  }
}
