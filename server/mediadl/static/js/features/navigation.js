import { resetHome } from './parse.js';
import { closeSettings, isSettingsOpen, openSettings } from './settings-view.js';

const SERVICES_HASH = '#/services';
const body = document.body;

export const isServicesOpen = () => body.classList.contains('services');

export function openServices() {
  if (isSettingsOpen()) closeSettings();
  body.classList.add('services');
  location.hash = SERVICES_HASH;
  window.scrollTo({ top: 0 });
}

export function closeServices() {
  body.classList.remove('services');
  if (location.hash === SERVICES_HASH) history.replaceState(null, '', location.pathname);
}

function showHome() {
  const onHome = !isSettingsOpen() && !isServicesOpen();
  if (isSettingsOpen()) closeSettings();
  if (isServicesOpen()) closeServices();
  // Tapping Home while already on it returns to the empty start screen.
  if (onHome) resetHome();
  else {
    window.scrollTo({ top: 0, behavior: 'smooth' });
    document.dispatchEvent(new Event('mdl:home'));
  }
}

export function initNavigation() {
  document.getElementById('home-tab').addEventListener('click', showHome);
  document.getElementById('services-open').addEventListener('click', openServices);

  window.addEventListener('hashchange', () => {
    if (location.hash === SERVICES_HASH) openServices();
    else if (isServicesOpen()) closeServices();
  });
  if (location.hash === SERVICES_HASH) openServices();
}

export { openSettings };
