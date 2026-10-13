import { el } from '../core/dom.js';
import { labelledRow } from './controls.js';

const CHANNEL_KEY = 'ytdlpChannel';

function savedChannel() {
  try { return localStorage.getItem(CHANNEL_KEY) === 'nightly' ? 'nightly' : 'stable'; } catch (e) { return 'stable'; }
}

// "yt-dlp" row: shows the installed version and updates it from the server.
export function ytdlpRows() {
  const row = labelledRow('yt-dlp', 'Checking version…');
  const hint = row.querySelector('.hint');
  const control = el('div', 'row-control');
  const button = el('button', 'btn-small', 'Update');
  button.type = 'button';
  control.append(button);
  row.append(control);

  const channelRow = labelledRow('Update channel', 'Nightly builds receive site fixes sooner but may be less stable.');
  const channelControl = el('div', 'row-control');
  const channel = el('button', 'btn-small tonal', savedChannel() === 'nightly' ? 'Nightly' : 'Stable');
  channel.type = 'button';
  channel.addEventListener('click', () => {
    const next = savedChannel() === 'nightly' ? 'stable' : 'nightly';
    try { localStorage.setItem(CHANNEL_KEY, next); } catch (e) { /* ignore */ }
    channel.textContent = next === 'nightly' ? 'Nightly' : 'Stable';
  });
  channelControl.append(channel);
  channelRow.append(channelControl);

  const showVersion = version => {
    hint.textContent = version ? `Version ${version}` : 'Not installed on the server';
  };
  fetch('/api/ytdlp').then(r => r.json()).then(data => showVersion(data.version)).catch(() => {
    hint.textContent = 'Couldn’t reach the server';
  });

  button.addEventListener('click', async () => {
    if (button.disabled) return;
    button.disabled = true;
    button.textContent = 'Updating…';
    hint.textContent = 'This can take a minute.';
    try {
      const response = await fetch('/api/ytdlp/update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ channel: savedChannel() }),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || 'Update failed');
      hint.textContent = data.updated ? `Updated to ${data.after}` : `Already up to date · ${data.after || data.before || ''}`;
    } catch (error) {
      hint.textContent = `Update failed · ${error.message}`;
    }
    button.disabled = false;
    button.textContent = 'Update';
  });

  return [row, channelRow];
}
