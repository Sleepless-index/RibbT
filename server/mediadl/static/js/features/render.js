import { downloadButton, el, output } from '../core/dom.js';
import { ICONS } from '../core/icons.js';
import { audioUrl, mediaUrl, videoDownloadUrl } from '../core/urls.js';
import { readSetting, SETTINGS_CHANGED } from '../state/settings.js';
import { carouselCard } from './carousel.js';
import { createMasonry } from './masonry.js';
import { imageCard, setRatio } from './cards.js';
import { wireDownload } from './downloads.js';
import { trackLoading } from './skeleton.js';
import { buildFilename, imageExtension, sanitizeFilename, videoExtension, wantsGif } from './filenames.js';

const CAROUSEL_MIN_IMAGES = 2;
const MAX_STAGGER_STEPS = 8;
const STAGGER_MS = 35;

function initialOf(name) {
  const text = (name || '').trim();
  if (!text) return '?';
  const latin = text.match(/[A-Za-z]/);
  return latin ? latin[0].toUpperCase() : text[0];
}

function avatarNode(post, id) {
  const avatar = el('span', 'av');
  if (!post.avatar || !post.avatar.trim()) {
    avatar.textContent = initialOf(post.author);
    return avatar;
  }
  avatar.classList.add('has-img');
  const image = document.createElement('img');
  image.src = mediaUrl(post.avatar, `${id}_avatar`, true);
  image.alt = '';
  image.loading = 'lazy';
  image.onerror = () => {
    avatar.classList.remove('has-img');
    image.remove();
    avatar.textContent = initialOf(post.author);
  };
  avatar.append(image);
  return avatar;
}

function infoCard(post, id) {
  const hasTitle = Boolean(post.title && post.title.trim());
  const hasAuthor = Boolean(post.author && post.author.trim());
  const hasAvatar = Boolean(post.avatar && post.avatar.trim());
  if (!hasTitle && !hasAuthor && !hasAvatar) return null;

  const card = el('div', 'card info full');
  if (hasAuthor || hasAvatar) {
    const byline = el('div', 'byline');
    byline.append(avatarNode(post, id));
    const who = el('span', 'who');
    if (hasAuthor) who.append(el('span', 'name', post.author));
    const kind = post.videos.length ? 'Video' : (post.images.length ? 'Photos' : '');
    if (kind) who.append(el('span', 'sub', kind));
    byline.append(who);
    card.append(byline);
  }
  if (hasTitle) card.append(el('h2', 'title', post.title));
  return card;
}

function videoCard(video, element, context) {
  const { platform, names, index } = context;
  const useOriginalSound =
    readSetting('audio.tiktok_original', false) && platform === 'tiktok' && Boolean(video.sound_url);

  const stage = el('div', 'stage');
  stage.append(element);
  trackLoading(stage, element, ['loadeddata', 'loadedmetadata', 'error']);

  const download = downloadButton('Download');
  wireDownload(download, {
    errorTitle: 'Video download failed',
    pendingLabel: 'Encoding…',
    fallbackName: 'video.mp4',
  });

  const audio = downloadButton(useOriginalSound ? 'Original sound' : 'Audio', ICONS.audio);
  audio.title = useOriginalSound ? 'Download original sound' : 'Download audio';
  audio.setAttribute('aria-label', audio.title);
  wireDownload(audio, {
    errorTitle: 'Audio download failed',
    pendingLabel: 'Extracting…',
    fallbackName: 'audio.mp3',
  });

  const card = el('div', 'card mc');
  const actions = el('div', 'actions');
  actions.append(download, audio);
  card.append(stage, actions);

  function refreshNames() {
    const dimensions = {
      width: element.videoWidth || null,
      height: video.height || element.videoHeight || null,
      codec: video.codec || 'h264',
      index,
    };

    const videoName = names({ ...dimensions, kind: 'video' });
    download.href = videoDownloadUrl(video.url, videoName, wantsGif(video), video.audio_url);
    download.setAttribute('download', `${videoName}.${videoExtension(video)}`);

    if (useOriginalSound) {
      const soundName = sanitizeFilename(video.sound_name) || videoName;
      audio.href = mediaUrl(video.sound_url, soundName);
      audio.setAttribute('download', `${soundName}.mp3`);
    } else {
      const audioName = names({ ...dimensions, kind: 'audio' });
      audio.href = audioUrl(video.audio_url || video.url, audioName);
      audio.setAttribute('download', `${audioName}.mp3`);
    }
  }

  const onSettingsChange = () => {
    if (!card.isConnected) {
      document.removeEventListener(SETTINGS_CHANGED, onSettingsChange);
      return;
    }
    refreshNames();
  };

  refreshNames();
  element.addEventListener('loadedmetadata', refreshNames);
  document.addEventListener(SETTINGS_CHANGED, onSettingsChange);
  return card;
}

function videoElement(video, post, id, base) {
  const element = el('video', 'media');
  element.controls = true;
  element.playsInline = true;
  element.preload = 'metadata';
  element.dataset.mediaUrl = video.url;
  if (post.cover) element.poster = mediaUrl(post.cover, `${id}_poster`, true);

  const sources = [video.url];
  if (video.preview && video.preview !== video.url) sources.push(video.preview);
  for (const url of sources) {
    const source = document.createElement('source');
    source.src = mediaUrl(url, base, true, url === video.url ? video.audio_url : null);
    element.append(source);
  }

  element.addEventListener('loadedmetadata', () => {
    setRatio(element.closest('.card'), element.videoWidth, element.videoHeight);
  });
  return element;
}

let lastPost = null;
let renderedLayout = null;
let renderedSound = null;

export function render(post) {
  lastPost = post;
  renderedLayout = readSetting('display.layout', 'carousel');
  renderedSound = readSetting('audio.tiktok_original', false);
  const id = post.id || 'media';
  const style = readSetting('metadata.filename_style', 'classic');
  const platform = post.platform || 'rednote';
  const names = extra => buildFilename(style, {
    platform, id, title: post.title, author: post.author, ...extra,
  });
  const cards = document.createDocumentFragment();

  const info = infoCard(post, id);
  if (info) cards.append(info);

  post.videos.forEach((video, index) => {
    const suffix = post.videos.length > 1 ? `_${index + 1}` : '';
    const element = videoElement(video, post, id, `${id}_video${suffix}`);
    cards.append(videoCard(video, element, { platform, names, index: index + 1 }));
    element.load();
  });

  if (post.kind === 'video' && post.cover) {
    cards.append(imageCard({
      src: mediaUrl(post.cover, `${id}_thumb`, true),
      alt: 'Thumbnail',
      href: mediaUrl(post.cover, names({ kind: 'thumbnail' })),
      filename: `${names({ kind: 'thumbnail' })}.${imageExtension(post.cover)}`,
      label: 'Thumbnail',
      errorTitle: 'Thumbnail download failed',
      fallbackName: 'thumbnail.jpg',
    }));
  }

  const useCarousel = renderedLayout === 'carousel' && post.images.length >= CAROUSEL_MIN_IMAGES;
  if (useCarousel) {
    cards.append(carouselCard(post, { id, style, platform }));
  } else {
    post.images.forEach((url, index) => {
      const name = names({ index: index + 1, kind: 'image' });
      cards.append(imageCard({
        src: mediaUrl(url, `${id}_p${index + 1}`, true),
        alt: `Image ${index + 1}`,
        lazy: renderedLayout !== 'masonry',
        href: mediaUrl(url, name),
        filename: `${name}.${imageExtension(url)}`,
        label: 'Download',
        errorTitle: 'Image download failed',
        fallbackName: 'image.jpg',
      }));
    });
  }

  if (renderedLayout === 'masonry') {
    const items = [...cards.children].filter(card => !card.classList.contains('full'));
    const wide = [...cards.children].filter(card => card.classList.contains('full'));
    output.append(...wide);
    if (items.length) {
      const wall = createMasonry(items);
      output.append(wall.root);
      wall.start();
    }
  } else {
    output.append(cards);
  }
  [...output.children].forEach((card, index) => {
    card.style.animationDelay = `${Math.min(index, MAX_STAGGER_STEPS) * STAGGER_MS}ms`;
  });
}

document.addEventListener(SETTINGS_CHANGED, () => {
  if (!lastPost || !output.children.length) return;
  const sameLayout = readSetting('display.layout', 'carousel') === renderedLayout;
  const sameSound = readSetting('audio.tiktok_original', false) === renderedSound;
  if (sameLayout && sameSound) return;
  output.replaceChildren();
  render(lastPost);
});
