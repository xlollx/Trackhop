// /button: the "Listen on any service" button a blog or a site pastes in. Plain HTML with its style
// written in: no script, no image and no font from Trackhop, no cookie. Nothing reaches Trackhop
// until a reader clicks it, and then only the song's link, which trackhop.org/link looks up.
import { parseLink } from './link.js';

export const SITE = 'https://trackhop.org';
export const STYLES = {
  dark: { bg: '#16161a', fg: '#ffffff', border: '#16161a' },
  light: { bg: '#ffffff', fg: '#16161a', border: '#d0d0d6' },
  violet: { bg: '#5b45c8', fg: '#ffffff', border: '#5b45c8' },
};
const NOTE = '<path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>';

const escape = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** The address the button opens: trackhop.org/link with the song's own link (the canonical one). */
export function target(input, lang = 'en') {
  const link = parseLink(input); // throws a ToolError when the text is not a song or album link
  const path = lang && lang !== 'en' ? '/' + lang + '/link' : '/link';
  return SITE + path + '?u=' + encodeURIComponent(link.url);
}

/** The code to paste: one <a> with its style inline. */
export function buttonCode(href, { style = 'dark', text = 'Listen on any service' } = {}) {
  const c = STYLES[style] || STYLES.dark;
  const css = `display:inline-flex;align-items:center;gap:8px;padding:8px 16px 8px 12px;border-radius:999px;`
    + `border:1px solid ${c.border};background:${c.bg};color:${c.fg};font:600 15px/1.2 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;text-decoration:none`;
  const svg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${NOTE}</svg>`;
  const label = String(text || '').trim() || 'Listen on any service';
  return `<a href="${escape(href)}" target="_blank" rel="noopener" style="${css}">${svg}${escape(label)}</a>`;
}
