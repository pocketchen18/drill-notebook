export interface EmbedResult {
  platform: 'youtube' | 'bilibili' | null;
  embedUrl: string | null;
}

export function resolveEmbedUrl(url: string): EmbedResult {
  if (!url || typeof url !== 'string') return { platform: null, embedUrl: null };
  try {
    const parsed = new URL(url.trim());
    const host = parsed.hostname.replace(/^www\./, '');
    if (host === 'youtube.com' || host === 'm.youtube.com') {
      const videoId = parsed.searchParams.get('v');
      if (videoId) return { platform: 'youtube', embedUrl: `https://www.youtube.com/embed/${videoId}` };
    }
    if (host === 'youtu.be') {
      const videoId = parsed.pathname.slice(1);
      if (videoId) return { platform: 'youtube', embedUrl: `https://www.youtube.com/embed/${videoId}` };
    }
    if (host === 'bilibili.com' || host === 'm.bilibili.com') {
      const match = parsed.pathname.match(/\/video\/(BV[\w]+|av(\d+))/i);
      if (match) {
        if (match[1].toUpperCase().startsWith('BV')) {
          return { platform: 'bilibili', embedUrl: `https://player.bilibili.com/player.html?bvid=${match[1]}&page=1&autoplay=0&high_quality=1` };
        }
        const aid = match[2];
        return { platform: 'bilibili', embedUrl: `https://player.bilibili.com/player.html?aid=${aid}&page=1&autoplay=0&high_quality=1` };
      }
    }
    return { platform: null, embedUrl: null };
  } catch {
    return { platform: null, embedUrl: null };
  }
}
