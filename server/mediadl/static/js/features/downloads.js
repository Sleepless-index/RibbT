import { ICONS } from '../core/icons.js';
import { readSetting } from '../state/settings.js';
import { explain, showError } from './errors.js';
import { reconcileFilename } from './filenames.js';
import { createToast } from './toast.js';

const FLASH_MS = 1800;
const SAVE_GAP_MS = 300;
const BLOB_URL_TTL_MS = 60000;

const flashTimers = new Set();

export function clearFlashTimers() {
  for (const timer of flashTimers) clearTimeout(timer);
  flashTimers.clear();
}

function triggerSave(blob, filename) {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), BLOB_URL_TTL_MS);
}

// Browser downloads go through an <a download>; a chosen folder is written by the app itself.
async function saveBlob(blob, filename) {
  if (readSetting('save.location', 'browser') === 'browser') {
    triggerSave(blob, filename);
    return null;
  }
  const response = await fetch(`/api/store?name=${encodeURIComponent(filename)}`, { method: 'POST', body: blob });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error((data && data.error) || 'Couldn’t save to that folder');
  return data.folder;
}

async function readWithProgress(response, onProgress) {
  const total = parseInt(response.headers.get('Content-Length') || '0', 10) || 0;
  const reader = response.body.getReader();
  const chunks = [];
  let received = 0;
  let lastPercent = -1;

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    received += value.length;
    if (total <= 0) {
      onProgress(-1, received);
      continue;
    }
    const percent = Math.min(99, Math.floor((received / total) * 100));
    if (percent !== lastPercent) {
      lastPercent = percent;
      onProgress(percent, received);
    }
  }
  onProgress(100, received);

  const merged = new Uint8Array(received);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.length;
  }
  return merged;
}

async function failureMessage(response) {
  const body = await response.text().catch(() => '');
  return (body.trim() || `Server responded ${response.status}`).slice(0, 1200);
}

async function fetchAndSave(href, filename, pendingLabel, toast) {
  if (!href) throw new Error('No URL yet.');

  toast.pending(pendingLabel);
  const response = await fetch(href, { cache: 'no-store' });
  if (!response.ok) throw new Error(await failureMessage(response));

  toast.progress(0, 0);
  const contentType = response.headers.get('Content-Type') || 'application/octet-stream';
  const data = await readWithProgress(response, (percent, received) => toast.progress(percent, received));

  const savedAs = reconcileFilename(filename, contentType);
  const folder = await saveBlob(new Blob([data], { type: contentType }), savedAs);

  const gifSkipped = /\.gif$/i.test(filename) && !/\.gif$/i.test(savedAs);
  toast.done(
    data.length,
    gifSkipped
      ? `Saved as ${savedAs.split('.').pop().toUpperCase()} · GIF conversion didn’t run`
      : (folder ? `Saved to ${folder}` : undefined),
  );
}

function flashDone(anchor, originalHtml) {
  anchor.classList.add('done');
  anchor.innerHTML = `${ICONS.check}<span>Downloaded</span>`;
  const timer = setTimeout(() => {
    anchor.classList.remove('done');
    anchor.innerHTML = originalHtml;
    flashTimers.delete(timer);
  }, FLASH_MS);
  flashTimers.add(timer);
}

function attachDownload(anchor, { errorTitle, label, task }) {
  const originalHtml = anchor.innerHTML;
  anchor.addEventListener('click', async event => {
    event.preventDefault();
    if (anchor.dataset.busy || anchor.classList.contains('done')) return;
    anchor.dataset.busy = '1';

    const toast = createToast(label());
    let failure = null;
    try {
      await task(toast);
    } catch (error) {
      failure = error;
      toast.fail(explain(error && error.message, 'Download failed').title);
    }
    delete anchor.dataset.busy;

    if (failure) {
      const network = failure instanceof TypeError;
      const { title, hint } = network
        ? { title: 'Can’t reach the app', hint: 'Check that it’s still running in Termux, then try again.' }
        : explain(failure.message, errorTitle || 'Download failed', 'Try again. If it keeps failing, paste the link again.');
      showError(title, hint);
    } else {
      flashDone(anchor, originalHtml);
    }
  });
}

export function wireDownload(anchor, { errorTitle, pendingLabel, fallbackName }) {
  const filename = () => anchor.getAttribute('download') || fallbackName;
  attachDownload(anchor, {
    errorTitle,
    label: filename,
    task: toast => fetchAndSave(anchor.getAttribute('href') || '', filename(), pendingLabel, toast),
  });
}

export function wireDownloadAll(anchor, { errorTitle, getItems }) {
  attachDownload(anchor, {
    errorTitle,
    label: () => `${getItems().length} files`,
    async task(toast) {
      const items = getItems();
      let folder = null;
      for (let index = 0; index < items.length; index++) {
        const position = index + 1;
        toast.step(position, items.length, 0);
        const response = await fetch(items[index].url);
        if (!response.ok) throw new Error(`Image ${position}: server responded ${response.status}`);
        const contentType = response.headers.get('Content-Type') || 'application/octet-stream';
        const data = await readWithProgress(response, percent => toast.step(position, items.length, percent));
        folder = await saveBlob(new Blob([data], { type: contentType }), items[index].filename);
        await new Promise(resolve => setTimeout(resolve, SAVE_GAP_MS));
      }
      toast.done(0, folder ? `Saved ${items.length} files to ${folder}` : `Saved ${items.length} files`);
    },
  });
}
