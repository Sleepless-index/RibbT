import { el } from '../core/dom.js';
import { commitSetting } from './settings-save.js';

export function section(title, rows, extraClass) {
  const block = el('div', extraClass ? `section ${extraClass}` : 'section');
  block.append(el('h2', 'section-title', title));
  const body = el('div', 'section-body');
  body.append(...rows);
  block.append(body);
  return block;
}

export function labelledRow(label, hint) {
  const row = el('div', 'row');
  const text = el('div', 'row-label');
  text.append(document.createTextNode(label));
  if (hint) text.append(el('span', 'hint', hint));
  row.append(text);
  return row;
}

export function toggleRow(label, hint, { checked, path, onChange }) {
  const row = labelledRow(label, hint);
  const control = el('div', 'row-control');
  const wrapper = el('label', 'toggle');

  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.checked = Boolean(checked);
  checkbox.setAttribute('aria-label', label);
  wrapper.append(checkbox, el('span', 'toggle-track'), el('span', 'toggle-thumb'));

  checkbox.addEventListener('change', () => {
    if (onChange) onChange(checkbox.checked);
    else commitSetting(path, checkbox.checked);
  });

  control.append(wrapper);
  row.append(control);
  return row;
}
