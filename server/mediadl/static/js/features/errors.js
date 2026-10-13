import { errorBox } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { bunny } from '../mascot/mascot.js';

export function showError(title, message) {
  errorBox.innerHTML = `${ICONS.error}<div><strong></strong><span class="em"></span></div>`;
  errorBox.querySelector('strong').textContent = title;
  const detail = errorBox.querySelector('.em');
  if (message) detail.textContent = message;
  else detail.remove();
  errorBox.hidden = false;
  bunny.set('error');
}

export function clearError() {
  if (errorBox.hidden) return;
  errorBox.hidden = true;
  errorBox.replaceChildren();
  bunny.set('normal');
}

// Turns raw server text into a short title plus one concrete next step.
const RULES = [
  [/yt-dlp/i, 'yt-dlp is missing', 'Install it with: pip install -U yt-dlp'],
  [/ffmpeg is not installed|could not start ffmpeg/i, 'ffmpeg is missing', 'Install it with: pkg install ffmpeg'],
  [/sign in|log in|login|\bbot\b|cookies|confirm you/i, 'The site wants a sign-in',
    'For YouTube, start the app with YTDLP_COOKIES pointing to your cookies.txt, then try again.'],
  [/private|removed|deleted|not available|unavailable|blocked the request|age.restricted/i, 'Can’t access that post',
    'It may be private, deleted or age-restricted. Open the link in your browser to check.'],
  [/no downloadable|no media|nothing to download/i, 'No photo or video found',
    'That post doesn’t seem to contain downloadable media.'],
  [/doesn.t look like|not a .*link|isn.t a/i, 'That isn’t a post link',
    'Open the post itself and copy its link. Profile and home page links don’t work.'],
  [/timed out|timeout/i, 'The site took too long', 'It was slow to respond. Try again in a moment.'],
  [/resolver|could not reach|unreadable|unexpected response|redirect/i, 'The site didn’t answer properly',
    'Try again in a moment. If it keeps happening, the site may have changed.'],
  [/server responded \d{3}/i, 'The app hit a problem', 'Try again. If it keeps happening, restart the app in Termux.'],
  [/busy/i, 'Server is busy', 'Wait a few seconds and try again.'],
  [/transcode|convert|encode/i, 'Couldn’t convert the file',
    'Set the format or bitrate to Auto in Settings and try again.'],
  [/blocked or unreachable media|expired|forbidden|\b(?:403|410)\b/i, 'That download link expired',
    'Paste the post link again to get a fresh one.'],
  [/storage access|termux-setup-storage|couldn.t write|only works from the device|turned off/i, 'Couldn’t save to that folder',
    null],
];

export function explain(message, fallbackTitle, fallbackHint) {
  const text = String(message || '');
  for (const [pattern, title, hint] of RULES) {
    if (pattern.test(text)) return { title, hint: hint === null ? text.split('\n')[0] : hint };
  }
  const first = text.split('\n')[0].trim();
  return {
    title: first && first.length <= 90 ? first : fallbackTitle,
    hint: first && first.length <= 90 ? fallbackHint : fallbackHint || first.slice(0, 200),
  };
}
