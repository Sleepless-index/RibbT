import { input, output, parseButton } from '../core/dom.js';
import { bunny } from '../mascot/mascot.js';
import { hasSettings, loadSettings, readSetting, SETTINGS_CHANGED } from '../state/settings.js';
import { clearFlashTimers } from './downloads.js';
import { clearError, explain, showError } from './errors.js';
import { slideToCenter, slideToTop } from './layout.js';
import { syncPasteButton } from './paste-button.js';
import { extractLink, hostOf, isSupported, platformList } from './platforms.js';
import { render } from './render.js';
import { clearSkeleton, showSkeleton } from './skeleton.js';

const REQUEST_TIMEOUT_MS = 60000;
const qualitySetting = () => readSetting('video.quality', 'best');

let parsedLink = null;
let parsedQuality = null;
let activeController = null;

function setLoading(loading) {
  parseButton.disabled = loading;
  parseButton.classList.toggle('loading', loading);
}

function inspectInput() {
  const raw = input.value.trim();
  if (!raw) {
    return {
      title: 'No link yet',
      message: `Paste a link from ${platformList('or')}, then tap Parse.`,
      refocus: true,
    };
  }

  const link = extractLink(raw);
  if (!link) {
    return {
      title: 'No link found',
      message: `Copy the link of a post from ${platformList('or')} and paste it here.`,
      refocus: true,
    };
  }

  const host = hostOf(link);
  if (!isSupported(host)) {
    return {
      title: host ? `${host} isn’t supported` : 'Unsupported link',
      message: `Supported: ${platformList('and')}. See the Services tab for the full list.`,
      refocus: false,
    };
  }
  return { link };
}

async function requestParse(link, signal) {
  const response = await fetch('/api/parse', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url: link }),
    signal,
  });
  const data = await response.json().catch(() => null);
  return { response, data };
}

export async function parseLink() {
  clearError();
  clearFlashTimers();

  const { link, title, message, refocus } = inspectInput();
  if (!link) {
    showError(title, message);
    if (refocus) input.focus();
    return;
  }

  if (input.value !== link) {
    input.value = link;
    syncPasteButton();
  }
  if (!hasSettings()) await loadSettings();

  setLoading(true);
  bunny.set('thinking');
  output.replaceChildren();
  showSkeleton();
  parsedLink = null;

  if (activeController) activeController.abort();
  const controller = new AbortController();
  activeController = controller;
  const quality = qualitySetting();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const { response, data } = await requestParse(link, controller.signal);
    if (!response.ok) {
      const { title, hint } = explain(
        data && data.error,
        'Couldn’t read that link',
        'Check that the post is public and the link opens in your browser, then try again.',
      );
      showError(title, hint);
      return;
    }
    if (!data) {
      showError('Got an unreadable reply', 'Try again in a moment. If it keeps happening, restart the app.');
      return;
    }

    await slideToTop();
    clearSkeleton();
    render(data);
    parsedLink = link;
    parsedQuality = quality;
    bunny.set('idea');
  } catch (error) {
    if (error && error.name === 'AbortError') {
      showError('The site took too long', 'It didn’t respond in time. Check your connection and try again.');
    } else {
      showError('Can’t reach the app', 'Check that it’s still running in Termux, then try again.');
    }
  } finally {
    clearTimeout(timer);
    if (activeController === controller) {
      activeController = null;
      setLoading(false);
      clearSkeleton();
    }
  }
}

// Video quality is applied by the server while parsing, so a change needs a fresh parse.
// Re-run it for the link already on screen and keep the current cards until the new ones arrive.
async function refreshForQuality() {
  if (!parsedLink || qualitySetting() === parsedQuality) return;

  const link = parsedLink;
  const quality = qualitySetting();
  if (activeController) activeController.abort();
  const controller = new AbortController();
  activeController = controller;
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  setLoading(true);

  try {
    const { response, data } = await requestParse(link, controller.signal);
    if (activeController !== controller) return;
    if (!response.ok || !data) {
      showError('Couldn’t apply the new quality', 'Still showing the previous result. Tap the arrow to try again.');
      return;
    }
    clearError();
    clearFlashTimers();
    output.replaceChildren();
    render(data);
    parsedQuality = quality;
  } catch (error) {
    if (activeController === controller) {
      showError('Couldn’t apply the new quality', 'Still showing the previous result. Tap the arrow to try again.');
    }
  } finally {
    clearTimeout(timer);
    if (activeController === controller) {
      activeController = null;
      setLoading(false);
    }
  }
}

// Back to the empty start screen: drop the result, the link and any error, and re-centre the form.
export function resetHome() {
  if (activeController) activeController.abort();
  activeController = null;
  setLoading(false);
  clearFlashTimers();
  clearError();
  output.replaceChildren();
  parsedLink = null;
  parsedQuality = null;
  input.value = '';
  syncPasteButton();
  bunny.set('normal');
  window.scrollTo({ top: 0 });
  slideToCenter();
  document.dispatchEvent(new Event('mdl:home'));
}

document.addEventListener(SETTINGS_CHANGED, refreshForQuality);
