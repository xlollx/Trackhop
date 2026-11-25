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
// The Trackhop mark (docs/assets/mark.svg, the app's ic_brand_mark) in the text's colour.
const MARK = '<g transform="translate(-21 -21)" fill="currentColor" stroke="currentColor" stroke-linecap="round">'
  + '<path d="M30.6,40.5 A27,27 0 0 1 77.4,40.5" fill="none" stroke-width="5.5"/>'
  + '<path d="M77.4,67.5 A27,27 0 0 1 30.6,67.5" fill="none" stroke-width="5.5"/>'
  + '<path d="M81.85,36.2 L71.45,42.2 L81.4,47.4 Z" stroke="none"/>'
  + '<path d="M26.15,71.8 L36.55,65.8 L26.6,60.6 Z" stroke="none"/>'
  + '<g transform="translate(2.1 1.5)">'
  + '<path d="M54.08,61.11 A7,5.6 -20 1 1 40.92,65.89 A7,5.6 -20 1 1 54.08,61.11 Z" stroke="none"/>'
  + '<path d="M52.6,62.5 L52.6,37.5" fill="none" stroke-width="3.6"/>'
  + '<path d="M52.6,37.2 C58.5,38.6 63.4,42.4 63.2,49.6 C61.6,45.2 58,42.9 52.6,42.6 Z" stroke="none"/>'
  + '</g></g>';
/** The mark as a whole <svg>, 20 px. */
export const MARK_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 66 66" aria-hidden="true">${MARK}</svg>`;
/** The brand after the text, smaller and softer. */
export const BRAND = 'Trackhop';
export const BRAND_CSS = 'font-weight:500;font-size:12px;opacity:.75;padding-left:8px;margin-left:2px;border-left:1px solid currentColor';

const escape = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** The address the button opens: trackhop.org/link with the song's own link (the canonical one). */
export function target(input, lang = 'en') {
  const link = parseLink(input); // throws a ToolError when the text is not a song or album link
  // /link is in English and Italian only.
  const path = lang === 'it' ? '/it/link' : '/link';
  return SITE + path + '?u=' + encodeURIComponent(link.url);
}

/** What the button is made of: its address, its inline style, its text, the Trackhop mark and name. */
export function buttonParts(href, { style = 'dark', text = 'Listen on any service', brand = 'name' } = {}) {
  const c = STYLES[style] || STYLES.dark;
  const css = `display:inline-flex;align-items:center;gap:8px;padding:8px 16px 8px 12px;border-radius:999px;`
    + `border:1px solid ${c.border};background:${c.bg};color:${c.fg};font:600 15px/1.2 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;text-decoration:none`;
  return { href, css, label: String(text || '').trim() || 'Listen on any service', icon: MARK_SVG, brand: brand === 'logo' ? '' : BRAND, brandCss: BRAND_CSS };
}

/** The code to paste: one <a> with its style inline. */
export function buttonCode(href, options) {
  const p = buttonParts(href, options);
  return `<a href="${escape(p.href)}" target="_blank" rel="noopener" title="Trackhop" style="${p.css}">${p.icon}${escape(p.label)}${p.brand ? `<span style="${p.brandCss}">${p.brand}</span>` : ''}</a>`;
}
