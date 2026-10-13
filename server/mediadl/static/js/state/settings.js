import { applyLayout } from '../core/display.js';
import { applyTheme } from '../core/theme.js';

export const SETTINGS_CHANGED = 'settingschange';

const state = { settings: null, choices: null };
let pending = null;

export const hasSettings = () => state.settings !== null;
export const choicesFor = path => (state.choices && state.choices[path]) || [];

export function readSetting(path, fallback) {
  let node = state.settings;
  for (const key of path.split('.')) {
    if (node === null || node === undefined) return fallback;
    node = node[key];
  }
  return node === undefined || node === null ? fallback : node;
}

function applyAppearance(settings) {
  applyTheme(settings.appearance.theme);
  applyLayout(settings.display.layout);
}

async function fetchSettings() {
  try {
    const response = await fetch('/api/settings');
    if (!response.ok) throw new Error('load failed');
    const data = await response.json();
    state.settings = data.settings;
    state.choices = data.choices;
    applyAppearance(data.settings);
    return state;
  } catch (error) {
    return null;
  }
}

export function loadSettings(force = false) {
  if (!force && state.settings) return Promise.resolve(state);
  if (!force && pending) return pending;
  pending = fetchSettings().finally(() => { pending = null; });
  return pending;
}

function deepMerge(base, patch) {
  const merged = Array.isArray(base) ? base.slice() : { ...base };
  for (const [key, value] of Object.entries(patch || {})) {
    const isObject = value && typeof value === 'object' && !Array.isArray(value);
    merged[key] = isObject ? deepMerge((base && base[key]) || {}, value) : value;
  }
  return merged;
}

export async function persistSettings(patch) {
  state.settings = deepMerge(state.settings || {}, patch);
  try {
    const response = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state.settings),
    });
    if (!response.ok) throw new Error('save failed');
    const data = await response.json();
    state.settings = data.settings;
    applyAppearance(data.settings);
    document.dispatchEvent(new Event(SETTINGS_CHANGED));
    return true;
  } catch (error) {
    return false;
  }
}
