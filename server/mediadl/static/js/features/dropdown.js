import { el } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { choicesFor, readSetting } from '../state/settings.js';
import { labelledRow } from './controls.js';
import { commitSetting } from './settings-save.js';

const VIEWPORT_PAD = 8;
const MENU_GAP = 6;

let openDropdown = null;

export function closeOpenDropdown() {
  if (openDropdown) openDropdown.close();
  openDropdown = null;
}

export function dropdownRow(label, hint, path, { onChange } = {}) {
  const choices = choicesFor(path);
  const labelOf = value => (choices.find(choice => choice.value === value) || {}).label || value;

  const row = labelledRow(label, hint);
  const control = el('div', 'row-control');
  const dropdown = el('div', 'dd');
  dropdown.setAttribute('data-open', 'false');

  const button = el('button', 'dd-btn');
  button.type = 'button';
  button.setAttribute('aria-haspopup', 'listbox');
  button.setAttribute('aria-expanded', 'false');
  button.setAttribute('aria-label', label);

  let selected = String(readSetting(path, ''));
  const valueLabel = el('span', 'dd-value', labelOf(selected));
  button.append(valueLabel);
  button.insertAdjacentHTML('beforeend', ICONS.chevronDown);

  const menu = el('ul', 'dd-menu');
  menu.setAttribute('role', 'listbox');
  menu.setAttribute('aria-label', label);
  menu.tabIndex = -1;

  const options = choices.map(choice => {
    const item = el('li');
    item.setAttribute('role', 'option');
    item.setAttribute('data-value', choice.value);
    item.textContent = choice.label;
    if (choice.value === selected) item.setAttribute('aria-selected', 'true');
    menu.append(item);
    return item;
  });

  let activeIndex = -1;

  const isOpen = () => dropdown.getAttribute('data-open') === 'true';
  const valueAt = index => options[index].getAttribute('data-value');

  function highlight(index) {
    activeIndex = index;
    options.forEach((option, position) => option.classList.toggle('active', position === index));
  }

  function positionMenu() {
    if (!isOpen()) return;
    const anchor = button.getBoundingClientRect();
    const width = menu.offsetWidth || 180;
    const height = menu.offsetHeight || 200;

    let left = anchor.right - width;
    if (left < VIEWPORT_PAD) left = VIEWPORT_PAD;
    if (left + width > innerWidth - VIEWPORT_PAD) {
      left = Math.max(VIEWPORT_PAD, innerWidth - VIEWPORT_PAD - width);
    }

    let top = anchor.bottom + MENU_GAP;
    if (top + height > innerHeight - VIEWPORT_PAD) {
      const above = anchor.top - MENU_GAP - height;
      top = above >= VIEWPORT_PAD ? above : Math.max(VIEWPORT_PAD, innerHeight - VIEWPORT_PAD - height);
    }

    menu.style.minWidth = `${Math.max(anchor.width, 160)}px`;
    menu.style.maxWidth = `${Math.min(280, innerWidth - VIEWPORT_PAD * 2)}px`;
    menu.style.top = `${Math.round(top)}px`;
    menu.style.left = `${Math.round(left)}px`;
  }

  function scrollActiveIntoView() {
    const active = options[activeIndex];
    if (!active) return;
    const top = active.offsetTop;
    const bottom = top + active.offsetHeight;
    if (top < menu.scrollTop) menu.scrollTop = top - 4;
    else if (bottom > menu.scrollTop + menu.clientHeight) menu.scrollTop = bottom - menu.clientHeight + 4;
  }

  function onPointerDown(event) {
    if (!dropdown.contains(event.target) && !menu.contains(event.target)) closeMenu();
  }

  function onScroll(event) {
    if (!menu.contains(event.target)) closeMenu();
  }

  function openMenu() {
    if (isOpen()) return;
    closeOpenDropdown();
    openDropdown = { close: closeMenu };
    dropdown.setAttribute('data-open', 'true');
    button.setAttribute('aria-expanded', 'true');
    requestAnimationFrame(() => {
      positionMenu();
      highlight(Math.max(0, options.findIndex(option => option.getAttribute('data-value') === selected)));
      scrollActiveIntoView();
    });
    document.addEventListener('pointerdown', onPointerDown, true);
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', positionMenu);
  }

  function closeMenu() {
    if (!isOpen()) return;
    dropdown.setAttribute('data-open', 'false');
    button.setAttribute('aria-expanded', 'false');
    highlight(-1);
    document.removeEventListener('pointerdown', onPointerDown, true);
    window.removeEventListener('scroll', onScroll, true);
    window.removeEventListener('resize', positionMenu);
    if (openDropdown && openDropdown.close === closeMenu) openDropdown = null;
  }

  function choose(value) {
    closeMenu();
    if (value === selected) return;
    selected = value;
    valueLabel.textContent = labelOf(value);
    options.forEach(option => {
      if (option.getAttribute('data-value') === value) option.setAttribute('aria-selected', 'true');
      else option.removeAttribute('aria-selected');
    });
    commitSetting(path, value);
    if (onChange) onChange(value);
  }

  const step = delta => highlight((activeIndex + delta + options.length) % options.length);

  button.addEventListener('click', event => {
    event.stopPropagation();
    if (isOpen()) closeMenu();
    else openMenu();
  });

  button.addEventListener('keydown', event => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!isOpen()) openMenu();
      else step(event.key === 'ArrowDown' ? 1 : -1);
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (!isOpen()) openMenu();
      else if (activeIndex >= 0) choose(valueAt(activeIndex));
    } else if (event.key === 'Escape' && isOpen()) {
      event.preventDefault();
      closeMenu();
    }
  });

  options.forEach((option, index) => {
    option.addEventListener('click', event => {
      event.stopPropagation();
      choose(valueAt(index));
    });
    option.addEventListener('mouseenter', () => highlight(index));
  });

  menu.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeMenu();
      button.focus();
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      highlight(Math.min(activeIndex + 1, options.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      highlight(Math.max(activeIndex - 1, 0));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (activeIndex >= 0) choose(valueAt(activeIndex));
    }
  });

  dropdown.append(button, menu);
  control.append(dropdown);
  row.append(control);
  return row;
}
