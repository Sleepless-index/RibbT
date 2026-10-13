import { content, main } from '../core/dom.js';
import { reduceMotion } from '../core/motion.js';

const SLIDE_FALLBACK_MS = 800;
let atTop = false;

function animateLayout(applyLayout) {
  return new Promise(resolve => {
    if (reduceMotion) {
      applyLayout();
      resolve();
      return;
    }
    const first = content.getBoundingClientRect().top;
    applyLayout();
    const offset = first - content.getBoundingClientRect().top;
    if (Math.abs(offset) < 1) {
      resolve();
      return;
    }

    content.style.willChange = 'transform';
    content.style.transition = 'none';
    content.style.transform = `translateY(${offset}px)`;
    void content.offsetHeight;
    content.style.transition = 'transform .55s var(--ease)';
    content.style.transform = 'translateY(0)';

    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(fallback);
      content.removeEventListener('transitionend', onEnd);
      content.style.transition = content.style.transform = content.style.willChange = '';
      resolve();
    };
    const onEnd = event => {
      if (event.target === content && event.propertyName === 'transform') finish();
    };
    const fallback = setTimeout(finish, SLIDE_FALLBACK_MS);
    content.addEventListener('transitionend', onEnd);
  });
}

export function slideToTop() {
  if (atTop) return Promise.resolve();
  atTop = true;
  return animateLayout(() => main.classList.add('ready'));
}

export function slideToCenter() {
  if (!atTop) return Promise.resolve();
  atTop = false;
  return animateLayout(() => main.classList.remove('ready'));
}
