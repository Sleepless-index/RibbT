import { settingsBody } from '../core/dom.js';
import { bunny } from '../mascot/mascot.js';
import { loadSettings, readSetting } from '../state/settings.js';
import { section, toggleRow } from './controls.js';
import { closeOpenDropdown, dropdownRow } from './dropdown.js';
import { filenamePreview, updateFilenamePreview } from './filename-preview.js';
import { ytdlpRows } from './ytdlp-row.js';

const SETTINGS_HASH = '#/settings';

function buildView() {
  settingsBody.replaceChildren();

  settingsBody.append(section('General', [
    dropdownRow('Theme', 'Choose a light or dark appearance, or follow your device.', 'appearance.theme'),
    dropdownRow('Media layout', 'Choose how results are displayed.', 'display.layout'),
    toggleRow('Bunny follows cursor', 'The mascot’s eyes follow your pointer.', {
      checked: bunny.follow,
      onChange: value => bunny.setFollow(value),
    }),
    toggleRow('Paste and go', 'Automatically fetch a supported link from your clipboard when RibbT opens.', {
      path: 'paste.auto_go',
      checked: readSetting('paste.auto_go', true),
    }),
  ]));

  settingsBody.append(section('Video', [
    dropdownRow('Preferred quality', 'Highest resolution to download when available.', 'video.quality'),
    dropdownRow('Bitrate cap', 'Upper limit for video bitrate. Lower values give smaller files.', 'video.bitrate'),
    dropdownRow('Container', 'File format for saved videos.', 'video.container'),
  ]));

  settingsBody.append(section('Images', [
    dropdownRow('Format', 'File format for saved images.', 'images.container'),
  ]));

  settingsBody.append(section('Audio', [
    dropdownRow('Format', 'File format for extracted audio.', 'audio.format'),
    dropdownRow('Bitrate', 'Encoding quality for extracted audio.', 'audio.bitrate'),
    toggleRow('TikTok original sound', 'Download the post’s original sound track when available.', {
      path: 'audio.tiktok_original',
      checked: readSetting('audio.tiktok_original', false),
    }),
  ]));

  settingsBody.append(section('X / Twitter', [
    toggleRow('Loop videos as GIF', 'Save looping X videos as animated GIFs.', {
      path: 'x.loop_as_gif',
      checked: readSetting('x.loop_as_gif', false),
    }),
  ]));

  const saving = section('Saving', [
    dropdownRow('Save to', 'Destination for completed downloads.', 'save.location'),
    dropdownRow(
      'Filename style',
      'Naming convention applied to saved files.',
      'metadata.filename_style',
      { onChange: updateFilenamePreview },
    ),
  ], 'metadata');
  saving.querySelector('.section-body').append(
    filenamePreview(readSetting('metadata.filename_style', 'classic')),
  );
  settingsBody.append(saving);

  settingsBody.append(section('Downloader', ytdlpRows()));
}

export async function openSettings() {
  document.body.classList.remove('services');
  document.body.classList.add('settings');
  location.hash = SETTINGS_HASH;
  if (await loadSettings()) buildView();
}

export function closeSettings() {
  closeOpenDropdown();
  document.body.classList.remove('settings');
  if (location.hash === SETTINGS_HASH) history.replaceState(null, '', location.pathname);
}

export const isSettingsOpen = () => document.body.classList.contains('settings');

export function initSettingsView() {
  document.getElementById('settings-open').addEventListener('click', openSettings);

  window.addEventListener('hashchange', () => {
    if (location.hash === SETTINGS_HASH) openSettings();
    else if (isSettingsOpen()) closeSettings();
  });
  document.addEventListener('click', closeOpenDropdown);

  if (location.hash === SETTINGS_HASH) openSettings();
}
