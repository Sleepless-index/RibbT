import { el } from '../core/dom.js';
import { hasRatio } from './cards.js';

const WIDE_QUERY = window.matchMedia('(min-width:700px)');
const PHONE_COLUMNS = 2;
const WIDE_COLUMNS = 3;
const REVEAL_TIMEOUT_MS = 1500;
const WIDE_RATIO = 1.2; // landscape cards at least this wide-for-their-height get a full-width row

// Packs cards into columns, always adding the next card to the shorter column (the RedNote feed look).
// Landscape cards break the columns and take a full-width row, so videos aren't squeezed into half a screen;
// the portrait cards before and after them keep packing into columns, and order is preserved.
// Card height comes from each card's --r ratio, so the wall stays hidden until the ratios are known,
// lays out once, and then leaves cards where they are.
export function createMasonry(items) {
  const root = el('div', 'masonry full pending');
  let settled = false;
  let queued = false;
  let timer = null;
  let laidOutWide = '';

  const ratioOf = card => parseFloat(card.style.getPropertyValue('--r')) || 0;
  const isWide = card => ratioOf(card) >= WIDE_RATIO;

  const wideKey = () => items.map(card => (isWide(card) ? 1 : 0)).join('');

  const shortest = columns => columns.reduce((best, column) => (
    column.offsetHeight < best.offsetHeight ? column : best
  ));

  function layout() {
    const count = Math.max(1, Math.min(WIDE_QUERY.matches ? WIDE_COLUMNS : PHONE_COLUMNS, items.length));
    root.style.setProperty('--cols', String(count));
    root.replaceChildren();
    laidOutWide = wideKey();

    let columns = null;
    for (const card of items) {
      if (count > 1 && isWide(card)) {
        columns = null;
        root.append(card);
        continue;
      }
      if (!columns) {
        const segment = el('div', 'mseg');
        columns = Array.from({ length: count }, () => el('div', 'mcol'));
        segment.append(...columns);
        root.append(segment);
      }
      shortest(columns).append(card);
    }
  }

  function settle() {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    layout();
    root.classList.remove('pending');
  }

  function maybeSettle() {
    queued = false;
    if (items.every(hasRatio)) settle();
  }

  // A slow image can report its size after the wall was shown; if that makes it a landscape card, re-flow once.
  function maybeReflow() {
    queued = false;
    if (wideKey() !== laidOutWide) layout();
  }

  root.addEventListener('ratiochange', () => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(settled ? maybeReflow : maybeSettle);
  });

  const onResize = () => {
    if (!root.isConnected) {
      WIDE_QUERY.removeEventListener('change', onResize);
      return;
    }
    if (settled) layout();
  };
  WIDE_QUERY.addEventListener('change', onResize);

  return {
    root,
    start() {
      layout();
      if (items.every(hasRatio)) settle();
      else timer = setTimeout(settle, REVEAL_TIMEOUT_MS);
    },
  };
}
