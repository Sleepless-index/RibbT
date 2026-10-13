import { saveIndicator } from '../core/dom.js';
import { persistSettings } from '../state/settings.js';

const INDICATOR_MS = 1400;
let timer = null;

function pulse(text, isError) {
  clearTimeout(timer);
  saveIndicator.textContent = text;
  saveIndicator.classList.toggle('err', isError);
  saveIndicator.classList.add('on');
  timer = setTimeout(() => saveIndicator.classList.remove('on'), INDICATOR_MS);
}

export async function commitSetting(path, value) {
  const [section, key] = path.split('.');
  const saved = await persistSettings({ [section]: { [key]: value } });
  pulse(saved ? 'Saved' : 'Failed', !saved);
  return saved;
}
