import { el, output } from '../core/dom.js';

const bar = className => el('span', `sk ${className}`);

function infoSkeleton() {
  const card = el('div', 'card info skel full');
  const byline = el('div', 'byline');
  const who = el('span', 'who');
  who.append(bar('sk-line sk-name'), bar('sk-line sk-sub'));
  byline.append(bar('sk-circle'), who);
  card.append(byline, bar('sk-line sk-title'), bar('sk-line sk-title short'));
  return card;
}

function mediaSkeleton() {
  const card = el('div', 'card mc skel');
  const stage = el('div', 'stage');
  stage.append(bar('sk-stage'));
  const actions = el('div', 'actions');
  actions.append(bar('sk-btn'), bar('sk-btn'));
  card.append(stage, actions);
  return card;
}

// Placeholder shown in the result area while a link is being parsed.
export function showSkeleton() {
  const wrapper = el('div', 'grid sk-wrap');
  wrapper.setAttribute('aria-hidden', 'true');
  wrapper.append(infoSkeleton(), mediaSkeleton());
  output.replaceChildren(wrapper);
}

export function clearSkeleton() {
  if (output.querySelector('.sk-wrap')) output.replaceChildren();
}

// Shimmer on a stage until its media has loaded (or failed, or took too long).
export function trackLoading(stage, media, events) {
  stage.classList.add('loading');
  let done = false;
  const finish = () => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    stage.classList.remove('loading');
  };
  const timer = setTimeout(finish, 10000);
  for (const name of events) media.addEventListener(name, finish, { once: true, capture: true });
  if (media.complete && media.naturalWidth) finish();
  return finish;
}
