import { el } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { bunny } from '../mascot/mascot.js';

const CLOSE_ANIMATION_MS = 260;

export function formatBytes(bytes) {
  if (!bytes || bytes < 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

function toastHost() {
  let host = document.getElementById('toasts');
  if (!host) {
    host = el('div');
    host.id = 'toasts';
    host.setAttribute('aria-live', 'polite');
    document.body.append(host);
  }
  return host;
}

export function createToast(filename) {
  const toast = el('div', 'toast indet');
  toast.setAttribute('role', 'status');

  const icon = el('span', 't-ic');
  icon.innerHTML = ICONS.download;
  const name = el('div', 't-name', filename || 'Download');
  const status = el('div', 't-sub', 'Preparing…');
  const text = el('div', 't-txt');
  text.append(name, status);

  const dismiss = el('button', 't-x');
  dismiss.type = 'button';
  dismiss.setAttribute('aria-label', 'Dismiss');
  dismiss.innerHTML = ICONS.closeSmall;

  const row = el('div', 't-row');
  row.append(icon, text, dismiss);

  const fill = document.createElement('i');
  const bar = el('div', 't-bar');
  bar.append(fill);
  toast.append(row, bar);
  toastHost().append(toast);

  bunny.track(fill);
  const release = ok => bunny.untrack(fill, ok);

  let timer = null;
  const close = () => {
    clearTimeout(timer);
    release(false);
    if (toast.classList.contains('out')) return;
    toast.classList.add('out');
    setTimeout(() => toast.remove(), CLOSE_ANIMATION_MS);
  };
  dismiss.addEventListener('click', close);

  const finish = (className, iconHtml, message, visibleMs) => {
    release(className === 'ok');
    toast.classList.remove('indet');
    toast.classList.add(className);
    icon.innerHTML = iconHtml;
    status.textContent = message;
    timer = setTimeout(close, visibleMs);
  };

  return {
    pending(label) {
      toast.classList.add('indet');
      status.textContent = label || 'Preparing…';
    },
    progress(percent, received) {
      toast.classList.remove('indet');
      if (percent >= 0) {
        toast.style.setProperty('--p', (percent / 100).toFixed(3));
        status.textContent = `Downloading · ${percent}%${received ? ` · ${formatBytes(received)}` : ''}`;
      } else {
        toast.classList.add('indet');
        status.textContent = `Downloading · ${formatBytes(received)}`;
      }
    },
    step(index, total, percent) {
      toast.classList.remove('indet');
      const done = (index - 1) + Math.max(0, percent) / 100;
      toast.style.setProperty('--p', Math.min(1, done / total).toFixed(3));
      status.textContent = `Downloading ${index} of ${total}${percent >= 0 ? ` · ${percent}%` : ''}`;
    },
    done(size, message) {
      finish('ok', ICONS.check, message || `Saved${size ? ` · ${formatBytes(size)}` : ''}`, 3500);
    },
    fail(message) {
      const reason = message ? ` · ${String(message).split('\n')[0].slice(0, 80)}` : '';
      finish('bad', ICONS.close, `Failed${reason}`, 6000);
    },
  };
}
