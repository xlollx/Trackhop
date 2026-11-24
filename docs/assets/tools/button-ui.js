// /button: the page around button.js. The link is read here, in the browser; this script makes no
// request and stores nothing.
import { target, buttonCode, buttonParts } from './button.js';
import { strings } from './ui.js';

const T = strings.button;
const root = document.getElementById('button-tool');
const form = root.querySelector('.btn-form'), input = root.querySelector('.btn-input'), err = form.querySelector('.tool-error');
const result = root.querySelector('.btn-result'), textInput = root.querySelector('.btn-text');
const preview = root.querySelector('.btn-preview'), code = root.querySelector('.btn-code'), done = root.querySelector('.btn-done');
let href = null;

function draw() {
  if (!href) return;
  const style = (root.querySelector('input[name="btn-style"]:checked') || {}).value || 'dark';
  const options = { style, text: textInput.value || root.dataset.text };
  code.value = buttonCode(href, options);
  // The same button, built piece by piece: the visitor's text only ever goes in as text.
  const p = buttonParts(href, options);
  const a = document.createElement('a');
  a.href = p.href; a.target = '_blank'; a.rel = 'noopener';
  a.setAttribute('style', p.css);
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  for (const [k, v] of Object.entries({ width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' })) svg.setAttribute(k, v);
  for (const [tag, attrs] of [['path', { d: 'M9 18V6l10-2v12' }], ['circle', { cx: 6.5, cy: 18, r: 2.5 }], ['circle', { cx: 16.5, cy: 16, r: 2.5 }]]) {
    const n = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
    svg.append(n);
  }
  a.append(svg, p.label);
  preview.replaceChildren(a);
  done.textContent = '';
}

form.addEventListener('submit', (e) => {
  e.preventDefault();
  try {
    href = target(input.value, root.dataset.lang);
    err.hidden = true;
    result.hidden = false;
    draw();
  } catch (x) {
    href = null;
    result.hidden = true;
    err.textContent = T.err_link;
    err.hidden = false;
  }
});
result.addEventListener('change', draw);
textInput.addEventListener('input', draw);

async function copy(text) {
  try { await navigator.clipboard.writeText(text); } catch (e) { code.select(); document.execCommand('copy'); }
  done.textContent = T.copied;
}
root.querySelector('.btn-copy').addEventListener('click', () => copy(code.value));
root.querySelector('.btn-copylink').addEventListener('click', () => copy(href));
