import { describe, expect, it } from 'vitest';
import { resolveEmbedUrl } from './videoEmbed';

describe('resolveEmbedUrl', () => {
  it('resolves youtube watch URL', () => {
    const result = resolveEmbedUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    expect(result.platform).toBe('youtube');
    expect(result.embedUrl).toBe('https://www.youtube.com/embed/dQw4w9WgXcQ');
  });

  it('resolves youtu.be short URL', () => {
    const result = resolveEmbedUrl('https://youtu.be/dQw4w9WgXcQ');
    expect(result.platform).toBe('youtube');
    expect(result.embedUrl).toBe('https://www.youtube.com/embed/dQw4w9WgXcQ');
  });

  it('resolves bilibili BV URL', () => {
    const result = resolveEmbedUrl('https://www.bilibili.com/video/BV1xx411c7mD');
    expect(result.platform).toBe('bilibili');
    expect(result.embedUrl).toBe('//player.bilibili.com/player.html?bvid=BV1xx411c7mD&page=1');
  });

  it('resolves bilibili AV URL', () => {
    const result = resolveEmbedUrl('https://www.bilibili.com/video/av170001');
    expect(result.platform).toBe('bilibili');
    expect(result.embedUrl).toBe('//player.bilibili.com/player.html?aid=170001&page=1');
  });

  it('returns null platform for unknown URL', () => {
    const result = resolveEmbedUrl('https://example.com/video');
    expect(result.platform).toBeNull();
    expect(result.embedUrl).toBeNull();
  });
});
