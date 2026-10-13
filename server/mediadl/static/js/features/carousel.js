import { downloadButton, el } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { reduceMotion } from '../core/motion.js';
import { mediaUrl } from '../core/urls.js';
import { setRatio } from './cards.js';
import { wireDownload, wireDownloadAll } from './downloads.js';
import { trackLoading } from './skeleton.js';
import { buildFilename, imageExtension } from './filenames.js';

const MAX_DOTS = 9;

function navButton(className, label, icon) {
  const button = el('button', `car-nav ${className}`);
  button.type = 'button';
  button.setAttribute('aria-label', label);
  button.innerHTML = icon;
  return button;
}

export function carouselCard(post, { id, style, platform }) {
  const total = post.images.length;
  const items = post.images.map((url, index) => ({
    url,
    base: buildFilename(style, {
      platform, id, title: post.title, author: post.author, index: index + 1, kind: 'image',
    }),
    ext: imageExtension(url),
  }));

  const card = el('div', 'card mc carousel');
  const stage = el('div', 'car-stage');
  const track = el('div', 'car-track');
  track.tabIndex = 0;
  track.setAttribute('role', 'group');
  track.setAttribute('aria-label', 'Photos');

  post.images.forEach((url, index) => {
    const slide = el('div', 'car-slide');
    const image = el('img', 'media');
    image.src = mediaUrl(url, `${id}_p${index + 1}`, true);
    image.alt = `Image ${index + 1}`;
    image.loading = index < 2 ? 'eager' : 'lazy';
    image.draggable = false;
    if (index === 0) {
      trackLoading(stage, image, ['load', 'error']);
      image.addEventListener('load', () => setRatio(card, image.naturalWidth, image.naturalHeight));
    }
    slide.append(image);
    track.append(slide);
  });

  const counter = el('span', 'car-count');
  const previous = navButton('prev', 'Previous photo', ICONS.chevronLeft);
  const next = navButton('next', 'Next photo', ICONS.chevronRight);
  stage.append(track, counter, previous, next);

  const dots = el('div', 'car-dots');
  const dotButtons = [];
  if (total <= MAX_DOTS) {
    items.forEach((_, index) => {
      const dot = el('button');
      dot.type = 'button';
      dot.setAttribute('aria-label', `Photo ${index + 1}`);
      dot.addEventListener('click', () => goTo(index));
      dots.append(dot);
      dotButtons.push(dot);
    });
  }

  const download = downloadButton('Download');
  wireDownload(download, { errorTitle: 'Image download failed', fallbackName: 'image.jpg' });

  const downloadAll = downloadButton(`Download all (${total})`);
  wireDownloadAll(downloadAll, {
    errorTitle: 'Image download failed',
    getItems: () => items.map(item => ({
      url: mediaUrl(item.url, item.base),
      filename: `${item.base}.${item.ext}`,
    })),
  });

  const actions = el('div', 'actions');
  actions.append(download, downloadAll);

  let current = 0;

  function goTo(index, smooth = true) {
    const clamped = Math.max(0, Math.min(total - 1, index));
    track.scrollTo({
      left: clamped * track.clientWidth,
      behavior: smooth && !reduceMotion ? 'smooth' : 'auto',
    });
  }

  function select(index) {
    current = index;
    counter.textContent = `${index + 1} / ${total}`;
    dotButtons.forEach((dot, position) => dot.classList.toggle('on', position === index));
    previous.disabled = index === 0;
    next.disabled = index === total - 1;
    const item = items[index];
    download.href = mediaUrl(item.url, item.base);
    download.setAttribute('download', `${item.base}.${item.ext}`);
  }
  select(0);

  track.addEventListener('scroll', () => {
    const index = Math.round(track.scrollLeft / (track.clientWidth || 1));
    if (index !== current && index >= 0 && index < total) select(index);
  }, { passive: true });
  previous.addEventListener('click', () => goTo(current - 1));
  next.addEventListener('click', () => goTo(current + 1));
  track.addEventListener('keydown', event => {
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      goTo(current - 1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      goTo(current + 1);
    }
  });
  if (window.ResizeObserver) new ResizeObserver(() => goTo(current, false)).observe(track);

  card.append(stage);
  if (dotButtons.length) card.append(dots);
  card.append(actions);
  return card;
}
