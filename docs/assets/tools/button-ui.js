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
  const brand = (root.querySelector('input[name="btn-brand"]:checked') || {}).value || 'name';
  const options = { style, brand, text: textInput.value || root.dataset.text };
  code.value = buttonCode(href, options);
  // The same button, built piece by piece: the visitor's text only ever goes in as text.
  const p = buttonParts(href, options);
  const a = document.createElement('a');
  a.href = p.href; a.target = '_blank'; a.rel = 'noopener';
  a.setAttribute('style', p.css);
  a.title = 'Trackhop';
  // The mark is our own constant (button.js), never the visitor's text.
  const svg = new DOMParser().parseFromString(p.icon, 'image/svg+xml').documentElement;
  a.append(document.importNode(svg, true), p.label);
  if (p.brand) {
    const name = document.createElement('span');
    name.setAttribute('style', p.brandCss);
    name.textContent = p.brand;
    a.append(name);
  }
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
