const platforms = JSON.parse(document.getElementById('platforms').textContent);
const hosts = platforms.flatMap(platform => platform.hosts);

const escapeRegExp = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const alternation = [...hosts].sort((a, b) => b.length - a.length).map(escapeRegExp).join('|');
const LINK_END = '[^\\s<>"\'）)】\\]]*';

const ABSOLUTE_LINK = new RegExp(`https?://[^\\s<>"'）)】\\]]+`, 'i');
const BARE_LINK = new RegExp(
  `(?<![\\w.-])(?:[\\w-]+\\.)*(?:${alternation})(?![\\w-]|\\.[\\w-])/${LINK_END}`,
  'i',
);
const TRAILING_PUNCTUATION = /[.,;:!?，。；：！？、"'）)】\]]+$/;

export function extractLink(text) {
  const match = text.match(ABSOLUTE_LINK) || text.match(BARE_LINK);
  return match ? match[0].replace(TRAILING_PUNCTUATION, '') : '';
}

export function hostOf(url) {
  try {
    const absolute = /^https?:\/\//i.test(url) ? url : `https://${url}`;
    return new URL(absolute).hostname.toLowerCase().replace(/^www\./, '');
  } catch (error) {
    return '';
  }
}

export const isSupported = host =>
  Boolean(host) && hosts.some(supported => host === supported || host.endsWith(`.${supported}`));

export function platformList(conjunction) {
  const labels = platforms.map(platform => platform.label);
  if (labels.length < 2) return labels.join('');
  return `${labels.slice(0, -1).join(', ')} ${conjunction} ${labels[labels.length - 1]}`;
}
