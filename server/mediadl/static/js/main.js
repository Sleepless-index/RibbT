import { $ } from './core/dom.js';
import { initClipboardWatch } from './features/clipboard-watch.js';
import { initNavigation } from './features/navigation.js';
import { parseLink } from './features/parse.js';
import { initPasteButton } from './features/paste-button.js';
import { initSettingsView } from './features/settings-view.js';
import { loadSettings } from './state/settings.js';

initPasteButton();
$('#form').addEventListener('submit', event => {
  event.preventDefault();
  parseLink();
});
initSettingsView();
initNavigation();
loadSettings();
initClipboardWatch();
