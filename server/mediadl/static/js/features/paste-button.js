import { input, output, pasteButton } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { bunny } from '../mascot/mascot.js';
import { clearError, showError } from './errors.js';
import { slideToCenter } from './layout.js';

const LINK_HINT = /https?:\/\/|\w\.\w{2,}\//i;

// Highlights the paste button when the clipboard holds a supported link.
export function setPasteReady(ready) {
  pasteButton.classList.toggle('ready', Boolean(ready) && !input.value.trim());
}

export function syncPasteButton() {
  const filled = Boolean(input.value.trim());
  if (filled) pasteButton.classList.remove('ready');
  pasteButton.replaceChildren();
  pasteButton.insertAdjacentHTML('beforeend', filled ? ICONS.clear : ICONS.paste);
  const label = document.createElement('span');
  label.textContent = filled ? 'Clear' : 'Paste';
  pasteButton.append(label);
}

async function pasteFromClipboard() {
  try {
    const text = (await navigator.clipboard.readText()).trim();
    if (!text) {
      showError('Clipboard is empty', 'Copy a link first, then tap paste again.');
      return;
    }
    clearError();
    input.value = text;
    syncPasteButton();
    if (LINK_HINT.test(text)) bunny.perk();
  } catch (error) {
    input.focus();
    showError('Clipboard blocked', 'Allow clipboard access, or long-press the box and tap Paste.');
  }
}

export function initPasteButton() {
  pasteButton.addEventListener('click', () => {
    if (!input.value.trim()) {
      pasteFromClipboard();
      return;
    }
    input.value = '';
    clearError();
    syncPasteButton();
    input.focus();
    if (!output.children.length) slideToCenter();
  });

  input.addEventListener('input', () => {
    clearError();
    syncPasteButton();
    if (!input.value.trim() && !output.children.length) slideToCenter();
  });

  syncPasteButton();
}
