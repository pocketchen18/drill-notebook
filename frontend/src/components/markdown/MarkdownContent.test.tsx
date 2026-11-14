import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MarkdownContent } from './MarkdownRenderer';

const openExternal = vi.fn().mockResolvedValue(undefined);

beforeEach(() => {
  openExternal.mockClear();
  Object.defineProperty(window, 'api', { value: { shell: { openExternal } }, configurable: true });
});

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'api');
  vi.restoreAllMocks();
});

describe('Markdown 链接', () => {
  it.each([
    { name: '左键', type: 'click', button: 0, ctrlKey: false },
    { name: 'Ctrl 点击', type: 'click', button: 0, ctrlKey: true },
    { name: '中键', type: 'auxclick', button: 1, ctrlKey: false }
  ])('$name 来源链接在默认浏览器打开，悬停提示实际目标', ({ type, button, ctrlKey }) => {
    const url = 'https://www.nvidia.com/en-us/about-nvidia/leadership/?source=chat#team';
    const parentClick = vi.fn();
    render(<div onClick={parentClick}><MarkdownContent value={`[**NVIDIA Management Team**](${url} "管理团队")`} /></div>);
    const link = screen.getByRole('link', { name: 'NVIDIA Management Team' });
    expect(link).toHaveAttribute('title', url);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    const event = new MouseEvent(type, { button, ctrlKey, bubbles: true, cancelable: true });
    fireEvent(link.querySelector('strong')!, event);
    expect(event.defaultPrevented).toBe(true);
    expect(openExternal).toHaveBeenCalledExactlyOnceWith(url);
    expect(parentClick).not.toHaveBeenCalled();
  });

  it('自动识别的裸网址同样打开浏览器，并显示完整网址', () => {
    const url = 'http://example.com/news?q=portable';
    render(<MarkdownContent value={`来源：${url}`} />);
    const link = screen.getByRole('link', { name: url });
    expect(link).toHaveAttribute('title', url);
    fireEvent.click(link);
    expect(openExternal).toHaveBeenCalledExactlyOnceWith(url);
  });

  it('右键链接保留正常菜单，不打开浏览器', () => {
    render(<MarkdownContent value="[来源](https://example.com/news)" />);
    fireEvent(screen.getByRole('link', { name: '来源' }), new MouseEvent('auxclick', { button: 2, bubbles: true, cancelable: true }));
    expect(openExternal).not.toHaveBeenCalled();
  });

  it('浏览器调试环境通过新标签打开网页，不替换当前页面', () => {
    Reflect.deleteProperty(window, 'api');
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    render(<MarkdownContent value="[来源](https://example.com/news)" />);
    fireEvent.click(screen.getByRole('link', { name: '来源' }));
    expect(open).toHaveBeenCalledExactlyOnceWith('https://example.com/news', '_blank', 'noopener,noreferrer');
    expect(openExternal).not.toHaveBeenCalled();
  });

  it('会话和资料链接仍在软件内定位，不调用外部浏览器', () => {
    const ref = '0123456789abcdef0123456789abcdef';
    const onSessionLink = vi.fn();
    const onMaterialLink = vi.fn();
    render(<MarkdownContent value={`[**旧会话**](drill-notebook://session/${ref})\n[笔记](drill-notebook://material/note/12)`}
      onSessionLink={onSessionLink} onMaterialLink={onMaterialLink} />);
    const session = screen.getByRole('link', { name: '旧会话' });
    expect(session).not.toHaveAttribute('target');
    expect(session).not.toHaveAttribute('title');
    fireEvent.click(session.querySelector('strong')!);
    fireEvent.click(screen.getByRole('link', { name: '笔记' }));
    expect(onSessionLink).toHaveBeenCalledExactlyOnceWith(`drill-notebook://session/${ref}`);
    expect(onMaterialLink).toHaveBeenCalledExactlyOnceWith('drill-notebook://material/note/12');
    expect(openExternal).not.toHaveBeenCalled();
  });

  it('外部打开规则不应用于页内锚点、相对地址、邮件或被清洗的危险协议', () => {
    const view = render(<MarkdownContent value={'[锚点](#section) [相对](./note) [邮件](mailto:hello@example.com) [文件](file:///C:/Windows/system.ini) [脚本](javascript:alert(1))'} />);
    for (const name of ['锚点', '相对', '邮件']) {
      const link = screen.getByRole('link', { name });
      expect(link).not.toHaveAttribute('target');
      expect(link).not.toHaveAttribute('title');
    }
    for (const name of ['文件', '脚本']) {
      expect(screen.queryByRole('link', { name })).toBeNull();
    }
    fireEvent.click(view.container.querySelector('p')!);
    expect(openExternal).not.toHaveBeenCalled();
  });
});
