import { useEffect, useState } from 'react';
import { Layout } from '@arco-design/web-react';
import { BookOpenText, Calendar, ChevronsLeft, ChevronsRight, FileText, Layers3, Moon, Settings, Sparkles, Sun, Target, XCircle } from 'lucide-react';
import { HashRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { resolveSystemTheme, useUiStore } from './stores/uiStore';
import { useGlobalShortcuts } from './hooks/useGlobalShortcuts';
import { describeAccelerator } from './lib/shortcuts';
import { readLastRoute, recordRoute } from './lib/viewState';
import { BrandMark } from './components/BrandMark';
import { BankPage } from './pages/BankPage';
import { WrongPage } from './pages/WrongPage';
import { NotebookPage } from './pages/NotebookPage';
import { SettingsPage } from './pages/SettingsPage';
import { AiAssistant } from './components/AiAssistant';
import { PracticePage } from './pages/PracticePage';
import { KnowledgePointPage } from './pages/KnowledgePointPage';
import { CalendarPage } from './pages/CalendarPage';

const { Sider, Header, Content } = Layout;

interface NavItem {
  key: string;
  label: string;
  icon: JSX.Element;
}

/** 主导航按用途分组：资料 → 学习 → 规划；「设置」固定在侧栏底部。 */
const navGroups: Array<{ caption: string; items: NavItem[] }> = [
  {
    caption: '资料',
    items: [
      { key: '/notebooks', label: '笔记本', icon: <FileText size={18} /> },
      { key: '/banks', label: '题库', icon: <BookOpenText size={18} /> },
      { key: '/knowledge', label: '知识点', icon: <Layers3 size={18} /> }
    ]
  },
  {
    caption: '学习',
    items: [
      { key: '/practice', label: '练习', icon: <Target size={18} /> },
      { key: '/wrong', label: '错题', icon: <XCircle size={18} /> }
    ]
  },
  {
    caption: '规划',
    items: [{ key: '/calendar', label: '日历', icon: <Calendar size={18} /> }]
  }
];
const settingsItem: NavItem = { key: '/settings', label: '设置', icon: <Settings size={18} /> };
const navItems: NavItem[] = [...navGroups.flatMap((group) => group.items), settingsItem];

function Shell(): JSX.Element {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((state) => state.theme);
  const toggleTheme = useUiStore((state) => state.toggleTheme);
  const setThemeMode = useUiStore((state) => state.setThemeMode);
  const clearPageContext = useUiStore((state) => state.clearPageContext);
  const setAiOpen = useUiStore((state) => state.setAiOpen);
  const notebookFocusMode = useUiStore((state) => state.notebookFocusMode);
  // AI 助手当前的第一个绑定（设置页改绑后提示同步更新）
  const aiAccelerator = useUiStore((state) => state.shortcutConfig.toggleAi[0]);
  // 全局快捷键（设置 → 常规 → 快捷键）
  useGlobalShortcuts();
  const [configLoaded, setConfigLoaded] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  // 折叠后自动隐藏：折叠态停留 3 秒自动收起侧栏；鼠标靠近左边缘唤回（保持折叠态）。
  const [siderHidden, setSiderHidden] = useState(false);
  const [siderHover, setSiderHover] = useState(false);
  // 折叠态悬停提示：在每个图标上直接绑定 hover/focus 事件，使用 getBoundingClientRect 定位。
  const [hoverTip, setHoverTip] = useState<{ label: string; left: number; top: number } | null>(null);
  // 深链 /quiz、/memorize 仍在使用，导航高亮统一归到「练习」。
  const normalizedPath = location.pathname.startsWith('/quiz') || location.pathname.startsWith('/memorize') ? '/practice' : location.pathname;
  const activeKey = navItems.some((item) => normalizedPath.startsWith(item.key))
    ? navItems.find((item) => normalizedPath.startsWith(item.key))!.key
    : '/banks';
  const title = navItems.find((item) => item.key === activeKey)?.label ?? 'Drill Notebook';

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    // Arco Design global dark mode (components + portaled Drawer/Modal/Select)
    if (theme === 'dark') document.body.setAttribute('arco-theme', 'dark');
    else document.body.removeAttribute('arco-theme');
    try {
      localStorage.setItem('drill-notebook-theme', theme);
    } catch {
      /* ignore */
    }
  }, [theme]);
  useEffect(() => {
    recordRoute(normalizedPath);
  }, [normalizedPath]);
  useEffect(() => {
    void window.api?.config.get().then((config) => {
      // 「跟随系统」时以当前系统深浅色为准，不用上次会话的明暗快照覆盖；
      // 显式模式下按 Electron 配置对齐，并同步模式本身，保证设置页单选与实际明暗一致
      if (useUiStore.getState().themeMode !== 'system' && (config.theme === 'dark' || config.theme === 'light')) setThemeMode(config.theme);
      setConfigLoaded(true);
    }).catch(() => setConfigLoaded(true));
  }, [setThemeMode]);
  // 主题跟随系统：系统深浅色变化时同步（仅 system 模式生效）
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!media || typeof media.addEventListener !== 'function') return;
    const onChange = (): void => {
      const { themeMode, setTheme: applyTheme } = useUiStore.getState();
      if (themeMode === 'system') applyTheme(resolveSystemTheme());
    };
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);
  useEffect(() => {
    if (configLoaded) void window.api?.config.set({ theme });
  }, [configLoaded, theme]);
  useEffect(() => {
    // Leaving a domain page clears stale context unless the page re-registers.
    if (!['/practice', '/quiz', '/wrong', '/notebooks', '/banks'].some((prefix) => location.pathname.startsWith(prefix))) {
      clearPageContext();
    }
  }, [clearPageContext, location.pathname]);

  // 折叠态下 3 秒未悬停则自动隐藏导航栏；展开态不会触发。
  useEffect(() => {
    if (!collapsed || siderHover) { setSiderHidden(false); return; }
    const timer = window.setTimeout(() => setSiderHidden(true), 3000);
    return () => window.clearTimeout(timer);
  }, [collapsed, siderHidden, siderHover]);

  // 展开或隐藏时清除悬停提示
  useEffect(() => {
    if (!collapsed || siderHidden) setHoverTip(null);
  }, [collapsed, siderHidden]);

  // Arco Sider 不接收鼠标事件属性，用窗口级指针坐标判断是否悬停在侧栏区域；
  // 隐藏后鼠标靠近左边缘（≤ 12px）即唤回，仍保持折叠态。
  useEffect(() => {
    const onMove = (event: MouseEvent) => {
      const overSider = event.clientX <= (collapsed ? 56 : 224);
      setSiderHover((prev) => (prev === overSider ? prev : overSider));
      if (siderHidden && event.clientX <= 12) setSiderHidden(false);
    };
    window.addEventListener('mousemove', onMove);
    return () => window.removeEventListener('mousemove', onMove);
  }, [collapsed, siderHidden]);

  /** 折叠态提示：贴着触发元素右侧垂直居中（固定定位，不受侧栏 overflow 裁剪）。 */
  const showTip = (label: string, target: HTMLElement): void => {
    const rect = target.getBoundingClientRect();
    setHoverTip({ label, left: rect.right + 10, top: rect.top + rect.height / 2 });
  };

  const renderNavItem = (item: NavItem): JSX.Element => {
    const active = item.key === activeKey;
    const isSettings = item.key === settingsItem.key;
    return (
      <button
        type="button"
        key={item.key}
        role="menuitem"
        className={`sider-nav-item${active ? ' is-active' : ''}${isSettings ? ` sider-settings${active ? ' active' : ''}` : ''}`}
        data-menukey={item.key}
        aria-label={item.label}
        aria-current={active ? 'page' : undefined}
        title={isSettings && !collapsed ? item.label : undefined}
        onClick={() => { setHoverTip(null); navigate(item.key); }}
        onMouseEnter={(event) => { if (collapsed) showTip(item.label, event.currentTarget); }}
        onMouseLeave={() => setHoverTip(null)}
        onFocus={(event) => { if (collapsed) showTip(item.label, event.currentTarget); }}
        onBlur={() => setHoverTip(null)}
      >
        <span className="sider-nav-icon">{item.icon}</span>
        {!collapsed && <span className="sider-nav-label">{item.label}</span>}
      </button>
    );
  };

  const collapseLabel = collapsed ? '展开导航栏' : '折叠导航栏';

  return (
    <Layout className="app-shell">
      <Sider
        className={`app-sider${siderHidden ? ' app-sider-hidden' : ''}`}
        width={224}
        collapsedWidth={56}
        collapsed={collapsed}
        collapsible={false}
        breakpoint="xl"
        onCollapse={setCollapsed}
      >
        <div className="brand">
          <div className="brand-mark"><BrandMark size={32} /></div>
          {!collapsed && <span className="brand-name">Drill Notebook</span>}
        </div>
        <div className="sider-body">
          <nav className="sider-nav" role="menu" aria-label="主导航">
            {navGroups.map((group) => (
              <div className="sider-nav-group" key={group.caption} role="group" aria-label={group.caption}>
                {!collapsed && <div className="sider-nav-caption">{group.caption}</div>}
                {group.items.map(renderNavItem)}
              </div>
            ))}
          </nav>
          <div className="sider-footer">
            {renderNavItem(settingsItem)}
            <button
              type="button"
              className="sider-collapse-btn"
              onClick={() => {
                setHoverTip(null);
                setCollapsed((value) => !value);
              }}
              aria-label={collapseLabel}
              onMouseEnter={(event) => { if (collapsed) showTip(collapseLabel, event.currentTarget); }}
              onMouseLeave={() => setHoverTip(null)}
              onFocus={(event) => { if (collapsed) showTip(collapseLabel, event.currentTarget); }}
              onBlur={() => setHoverTip(null)}
            >
              {collapsed ? <ChevronsRight size={18} /> : <ChevronsLeft size={18} />}
              {!collapsed && <span className="sider-collapse-label">收起侧栏</span>}
            </button>
          </div>
        </div>
      </Sider>
      {hoverTip && (
        <div className="sider-hover-tip" style={{ left: hoverTip.left, top: hoverTip.top }}>
          {hoverTip.label}
        </div>
      )}
      <Layout>
        {notebookFocusMode ? null : (
          <Header className="topbar">
            <span className="topbar-title">{title}</span>
            <div className="topbar-actions">
              <button type="button" className="topbar-ai-pill" onClick={() => setAiOpen(true)} title="打开 AI 助手">
                <Sparkles size={15} />
                <span>AI 助手</span>
                {aiAccelerator ? <kbd>{describeAccelerator(aiAccelerator)}</kbd> : null}
              </button>
              <button
                type="button"
                role="switch"
                className="theme-toggle"
                onClick={toggleTheme}
                aria-checked={theme === 'dark'}
                aria-label="切换主题"
                title={theme === 'dark' ? '切换到浅色' : '切换到深色'}
              >
                {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
              </button>
            </div>
          </Header>
        )}
        <Content><AppRoutes /></Content>
      </Layout>
      <AiAssistant />
    </Layout>
  );
}

export function App(): JSX.Element {
  return (
    <HashRouter>
      <Shell />
    </HashRouter>
  );
}

export function AppRoutes(): JSX.Element {
  // 只记 pathname：/quiz?autoStart=1… 这类深链不会在下次启动自动续考。
  const landing = readLastRoute() ?? '/notebooks';
  return (
    <Routes>
      <Route path="/" element={<Navigate to={landing} replace />} />
      <Route path="/banks" element={<BankPage />} />
      <Route path="/practice" element={<PracticePage />} />
      <Route path="/quiz" element={<PracticePage initialTab="quiz" />} />
      <Route path="/memorize" element={<PracticePage initialTab="memorize" />} />
      <Route path="/knowledge" element={<KnowledgePointPage />} />
      <Route path="/wrong" element={<WrongPage />} />
      <Route path="/today" element={<Navigate to="/calendar" replace />} />
      <Route path="/calendar" element={<CalendarPage />} />
      <Route path="/notebooks" element={<NotebookPage />} />
      <Route path="/ai" element={<Navigate to="/settings" replace />} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="*" element={<Navigate to={landing} replace />} />
    </Routes>
  );
}
