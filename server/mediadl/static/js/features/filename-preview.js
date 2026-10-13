import { el } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { buildFilename } from './filenames.js';

const SAMPLE = {
  platform: 'example',
  id: '0000000000000000000',
  title: 'Sample Video Title',
  author: 'Creator Name',
  height: 1080,
  codec: 'h264',
  index: 1,
};

const SAMPLE_KINDS = [
  { label: 'Video', kind: 'video', ext: '.mp4' },
  { label: 'Image', kind: 'image', ext: '.jpg' },
  { label: 'Audio', kind: 'audio', ext: '.mp3' },
];

function renderPreview(container, style) {
  const body = container.querySelector('.fp-body');
  body.replaceChildren();
  for (const sample of SAMPLE_KINDS) {
    const line = el('div', 'filename-preview-row');
    line.append(el('span', 'filename-preview-kind', sample.label));
    const value = el('span', 'filename-preview-value');
    value.append(document.createTextNode(buildFilename(style, { ...SAMPLE, kind: sample.kind })));
    value.append(el('span', 'ext', sample.ext));
    line.append(value);
    body.append(line);
  }
}

// Collapsed by default; the header opens the three sample file names.
export function filenamePreview(style) {
  const container = el('div', 'filename-preview');
  const head = el('button', 'fp-head');
  head.type = 'button';
  head.setAttribute('aria-expanded', 'false');
  head.append(el('span', 'filename-preview-label', 'Filename preview'));
  head.insertAdjacentHTML('beforeend', ICONS.chevronDown);
  const body = el('div', 'fp-body');
  body.hidden = true;
  head.addEventListener('click', () => {
    const open = body.hidden;
    body.hidden = !open;
    head.setAttribute('aria-expanded', String(open));
    container.classList.toggle('open', open);
  });
  container.append(head, body);
  renderPreview(container, style);
  return container;
}

export function updateFilenamePreview(style) {
  const container = document.querySelector('.filename-preview');
  if (container) renderPreview(container, style);
}
