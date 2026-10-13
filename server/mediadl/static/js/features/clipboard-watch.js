import { input } from '../core/dom.js';
import { loadSettings, readSetting } from '../state/settings.js';
import { parseLink } from './parse.js';
import { setPasteReady, syncPasteButton } from './paste-button.js';
import { extractLink, hostOf, isSupported } from './platforms.js';

const HANDLED_KEY = 'lastPastedLink';
let checking = false;

const rememberLink = link => { try { localStorage.setItem(HANDLED_KEY, link); } catch (error) {} };
const lastLink = () => { try { return localStorage.getItem(HANDLED_KEY) || ''; } catch (error) { return ''; } };

// Needs clipboard permission. If it's missing or denied this quietly does nothing.
async function clipboardLink() {
  if (!navigator.clipboard || !navigator.clipboard.readText) return '';
  try {
    const link = extractLink(await navigator.clipboard.readText());
    return link && isSupported(hostOf(link)) ? link : '';
  } catch (error) {
    return '';
  }
}

async function check() {
  const onHome = !document.body.classList.contains('settings') && !document.body.classList.contains('services');
  if (checking || document.visibilityState !== 'visible' || !onHome) return;
  if (input.value.trim()) { setPasteReady(false); return; }

  checking = true;
  try {
    await loadSettings();
    const link = await clipboardLink();
    if (!link || input.value.trim()) { setPasteReady(false); return; }

    if (readSetting('paste.auto_go', true) && link !== lastLink()) {
      rememberLink(link);
      input.value = link;
      syncPasteButton();
      parseLink();
      return;
    }
    setPasteReady(true);
  } finally {
    checking = false;
  }
}

export function initClipboardWatch() {
  window.addEventListener('focus', check);
  document.addEventListener('visibilitychange', check);
  document.addEventListener('mdl:home', check);
  check();
}
