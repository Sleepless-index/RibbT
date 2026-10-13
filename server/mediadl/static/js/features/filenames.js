import { readSetting } from '../state/settings.js';

const EXT_BY_TYPE = {
  'video/mp4': 'mp4',
  'video/webm': 'webm',
  'video/quicktime': 'mov',
  'video/x-matroska': 'mkv',
  'image/gif': 'gif',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/avif': 'avif',
};

const IMAGE_EXT_BY_SUFFIX = [
  ['.png', 'png'],
  ['.webp', 'webp'],
  ['.avif', 'avif'],
  ['.gif', 'gif'],
];

export function sanitizeFilename(text) {
  return String(text || '')
    .replace(/[\\/:*?"<>|\x00-\x1f]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 100);
}

function kindSuffix(context, quality, codec) {
  switch (context.kind) {
    case 'thumbnail': return 'thumbnail';
    case 'image': return `image ${context.index || 1}`;
    case 'audio': return quality ? `${quality} ${codec} audio` : `${codec} audio`;
    default: return quality ? `${quality} ${codec}` : codec;
  }
}

function classicName(context, dimensions, codec) {
  const parts = [context.platform || 'media', context.id || 'media'];
  if (context.kind === 'thumbnail') {
    parts.push('thumbnail');
  } else if (context.kind === 'image') {
    parts.push(`p${context.index || 1}`);
  } else {
    if (dimensions) parts.push(dimensions);
    parts.push(codec);
    if (context.kind === 'audio') parts.push('audio');
  }
  return parts.join('_');
}

export function buildFilename(style, context) {
  const title = sanitizeFilename(context.title || 'Untitled');
  const author = sanitizeFilename(context.author || '');
  const codec = context.codec || 'h264';
  const quality = context.height ? `${context.height}p` : null;
  const dimensions = context.width && context.height
    ? `${context.width}x${context.height}`
    : quality;
  const suffix = kindSuffix(context, quality, codec);

  switch (style) {
    case 'title':
      return `${title} (${suffix})`;
    case 'author_title':
      return author ? `${author} - ${title} (${suffix})` : `${title} (${suffix})`;
    default:
      return classicName(context, dimensions, codec);
  }
}

export function reconcileFilename(filename, contentType) {
  const real = EXT_BY_TYPE[String(contentType || '').split(';')[0].trim().toLowerCase()];
  if (!real) return filename;
  const match = filename.match(/\.([A-Za-z0-9]{2,4})$/);
  const current = match ? match[1].toLowerCase() : '';
  if (current === real || (real === 'jpg' && current === 'jpeg')) return filename;
  return (match ? filename.slice(0, -match[0].length) : filename) + '.' + real;
}

export function imageExtension(url) {
  const container = readSetting('images.container', 'auto');
  if (container !== 'auto') return container;
  const path = (String(url || '').split('?')[0] || '').toLowerCase();
  const match = IMAGE_EXT_BY_SUFFIX.find(([suffix]) => path.endsWith(suffix));
  return match ? match[1] : 'jpg';
}

function isTwitterLoop(url) {
  try {
    const { hostname, pathname } = new URL(url);
    return /(^|\.)twimg\.com$/.test(hostname) && pathname.includes('/tweet_video/');
  } catch (error) {
    return false;
  }
}

export function wantsGif(video) {
  if (!video || !readSetting('x.loop_as_gif', false)) return false;
  return Boolean(video.loop) || isTwitterLoop(video.url);
}

export function videoExtension(video) {
  if (wantsGif(video)) return 'gif';
  const processed = Boolean(video.audio_url) || readSetting('video.bitrate', 'auto') !== 'auto';
  return processed && readSetting('video.container', 'auto') === 'mkv' ? 'mkv' : 'mp4';
}
