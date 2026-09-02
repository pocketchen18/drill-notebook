/**
 * 启动落点（START-*）：无记忆时落笔记本，有记忆时回到上次页面。
 * 页面组件全部替身，只验证路由决策本身。
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppRoutes } from './App';
import { LS_VIEW_STATE } from './lib/viewState';

vi.mock('./pages/NotebookPage', () => ({ NotebookPage: () => <div>page:notebooks</div> }));
vi.mock('./pages/BankPage', () => ({ BankPage: () => <div>page:banks</div> }));
vi.mock('./pages/WrongPage', () => ({ WrongPage: () => <div>page:wrong</div> }));
vi.mock('./pages/KnowledgePointPage', () => ({ KnowledgePointPage: () => <div>page:knowledge</div> }));
vi.mock('./pages/PracticePage', () => ({ PracticePage: () => <div>page:practice</div> }));
vi.mock('./pages/CalendarPage', () => ({ CalendarPage: () => <div>page:calendar</div> }));
vi.mock('./pages/SettingsPage', () => ({ SettingsPage: () => <div>page:settings</div> }));

function seedRoute(lastRoute: unknown): void {
  localStorage.setItem(LS_VIEW_STATE, JSON.stringify({ version: 1, lastRoute, pages: {} }));
}

function landingText(): string {
  return screen.getByText(/^page:/).textContent ?? '';
}

describe('AppRoutes 启动落点', () => {
  it('opens 笔记本 on a cold start with no memory', () => {
    render(<MemoryRouter initialEntries={['/']}><AppRoutes /></MemoryRouter>);
    expect(landingText()).toBe('page:notebooks');
  });

  it('restores the page the user last stayed on', () => {
    seedRoute('/banks');
    render(<MemoryRouter initialEntries={['/']}><AppRoutes /></MemoryRouter>);
    expect(landingText()).toBe('page:banks');
  });

  it('also restores for unknown paths', () => {
    seedRoute('/calendar');
    render(<MemoryRouter initialEntries={['/no-such-page']}><AppRoutes /></MemoryRouter>);
    expect(landingText()).toBe('page:calendar');
  });

  it('falls back to 笔记本 when the remembered route is not a nav page', () => {
    seedRoute('/quiz');
    render(<MemoryRouter initialEntries={['/']}><AppRoutes /></MemoryRouter>);
    expect(landingText()).toBe('page:notebooks');
  });

  it('ignores a corrupt cache', () => {
    localStorage.setItem(LS_VIEW_STATE, '{oops');
    render(<MemoryRouter initialEntries={['/']}><AppRoutes /></MemoryRouter>);
    expect(landingText()).toBe('page:notebooks');
  });

  it('keeps explicit deep links working', () => {
    seedRoute('/banks');
    render(<MemoryRouter initialEntries={['/knowledge?pointIds=1']}><AppRoutes /></MemoryRouter>);
    expect(landingText()).toBe('page:knowledge');
  });
});
