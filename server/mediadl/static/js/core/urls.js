const MEDIA_VERSION = 'v6';

function endpoint(path, params) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== false) query.set(key, value === true ? '1' : value);
  }
  return `${path}?${query}`;
}

export const mediaUrl = (url, name, inline = false, audio = null) =>
  endpoint('/api/media', { u: url, name, inline, audio, _v: MEDIA_VERSION });

export const audioUrl = (url, name) => endpoint('/api/audio', { u: url, name });

export const videoDownloadUrl = (url, name, loop = false, audio = null) =>
  endpoint('/api/video', { u: url, name, loop, audio });
