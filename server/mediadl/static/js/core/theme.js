const DARK = '#0e0f11';
const LIGHT = '#f4f4f4';

function remember(theme) {
  try { localStorage.setItem('theme', theme); } catch (error) {}
}

export function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === 'light' || theme === 'dark') {
    root.setAttribute('data-theme', theme);
    remember(theme);
  } else {
    root.removeAttribute('data-theme');
    remember('auto');
  }
  const prefersDark = matchMedia('(prefers-color-scheme: dark)').matches;
  const color = theme === 'dark' || (theme !== 'light' && prefersDark) ? DARK : LIGHT;
  const meta = document.querySelector('meta[name=theme-color]');
  if (meta) meta.setAttribute('content', color);
}
