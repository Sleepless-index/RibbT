const svg = (size, strokeWidth, body, extra = '') =>
  `<svg${extra} width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;

export const ICONS = {
  download: svg(16, 2.2, '<path d="M12 4v11"/><path d="m7 11 5 5 5-5"/><path d="M5 20h14"/>'),
  check: svg(16, 2.6, '<path d="m5 12 5 5 9-11"/>'),
  audio: svg(16, 2.2, '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>'),
  error: svg(18, 2, '<circle cx="12" cy="12" r="9"/><path d="M12 8v5"/><path d="M12 16.5h.01"/>', ' class="ei"'),
  paste: svg(16, 2, '<rect x="8" y="3" width="8" height="4" rx="1"/><path d="M16 5h2a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2"/>'),
  clear: svg(16, 2, '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>'),
  close: svg(16, 2.4, '<path d="M6 6l12 12M18 6 6 18"/>'),
  closeSmall: svg(14, 2.4, '<path d="M6 6l12 12M18 6 6 18"/>'),
  chevronLeft: svg(18, 2.6, '<path d="m15 5-7 7 7 7"/>'),
  chevronRight: svg(18, 2.6, '<path d="m9 5 7 7-7 7"/>'),
  chevronDown: '<svg class="dd-chev" width="10" height="10" viewBox="0 0 10 6" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M1 1l4 4 4-4"/></svg>',
};
