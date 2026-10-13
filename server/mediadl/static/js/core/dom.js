import { ICONS } from './icons.js';

export const $ = selector => document.querySelector(selector);

export const input = $('#url');
export const parseButton = $('#parse');
export const pasteButton = $('#paste');
export const errorBox = $('#err');
export const output = $('#out');
export const main = $('#main');
export const content = $('#content');
export const settingsBody = $('#settings-body');
export const saveIndicator = $('#save-indicator');

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

export function downloadButton(label, icon = ICONS.download) {
  const anchor = el('a', 'dl');
  anchor.innerHTML = icon;
  anchor.append(el('span', '', label));
  return anchor;
}
