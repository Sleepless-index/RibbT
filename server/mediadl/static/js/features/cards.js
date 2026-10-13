import { downloadButton, el } from '../core/dom.js';
import { wireDownload } from './downloads.js';
import { trackLoading } from './skeleton.js';

const MIN_RATIO = 0.5;
const MAX_RATIO = 2.4;

export function setRatio(card, width, height) {
  if (!card || !width || !height) return;
  const ratio = Math.min(MAX_RATIO, Math.max(MIN_RATIO, width / height));
  card.style.setProperty('--r', ratio.toFixed(4));
  card.dispatchEvent(new Event('ratiochange', { bubbles: true }));
}

export const hasRatio = card => card.style.getPropertyValue('--r') !== '';

export function imageCard({ src, alt, lazy = false, href, filename, label, errorTitle, fallbackName }) {
  const image = el('img', 'media');
  image.src = src;
  image.alt = alt;
  if (lazy) image.loading = 'lazy';

  const stage = el('div', 'stage');
  stage.append(image);
  trackLoading(stage, image, ['load', 'error']);

  const download = downloadButton(label);
  download.href = href;
  download.setAttribute('download', filename);
  wireDownload(download, { errorTitle, fallbackName });

  const actions = el('div', 'actions');
  actions.append(download);

  const card = el('div', 'card mc');
  card.append(stage, actions);
  image.addEventListener('load', () => setRatio(card, image.naturalWidth, image.naturalHeight));
  return card;
}
