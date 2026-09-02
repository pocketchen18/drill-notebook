import { useEffect, useState } from 'react';
import { Layout, Menu, Switch, Typography } from '@arco-design/web-react';
import { BookOpenText, BrainCircuit, Calendar, ChevronsLeft, ChevronsRight, FileText, Layers3, Moon, Settings, Sun, Target, XCircle } from 'lucide-react';
import { HashRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useUiStore } from './stores/uiStore';
import { readLastRoute, recordRoute } from './lib/viewState';
import { BankPage } from './pages/BankPage';
import { WrongPage } from './pages/WrongPage';
import { NotebookPage } from './pages/NotebookPage';
import { SettingsPage } from './pages/SettingsPage';
import { AiAssistant } from './components/AiAssistant';
import { PracticePage } from './pages/PracticePage';
import { KnowledgePointPage } from './pages/KnowledgePointPage';
import { CalendarPage } from './pages/CalendarPage';

const { Sider, Header, Content } = Layout;

const navItems = [
  { key: '/notebooks', label: '笔记本', icon: <FileText size={17} /> },
  { key: '/banks', label: '题库', icon: <BookOpenText size={17} /> },
  { key: '/wrong', label: '错题', icon: <XCircle size={17} /> },
  { key: '/knowledge', label: '知识点', icon: <Layers3 size={17} /> },
  { key: '/practice', label: '练习', icon: <Target size={17} /> },
  { key: '/calendar', label: '日历', icon: <Calendar size={17} /> },
  { key: '/settings', label: '设置', icon: <Settings size={17} /> }
];

function Shell(): JSX.Element {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((state) => state.theme);
  const toggleTheme = useUiStore((state) => state.toggleTheme);
  const setTheme = useUiStore((state) => state.setTheme);
  const clearPageContext = useUiStore((state) => state.clearPageContext);
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
      if (config.theme === 'dark' || config.theme === 'light') setTheme(config.theme);
      setConfigLoaded(true);
    }).catch(() => setConfigLoaded(true));
  }, [setTheme]);
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
          <div className="brand-mark"><BrainCircuit size={18} /></div>
          {!collapsed && <span className="brand-name">Drill Notebook</span>}
        </div>
        <div className="sider-body">
          <div className="sider-menu-wrap">
            <Menu
              selectedKeys={[activeKey]}
              onClickMenuItem={(key) => navigate(key)}
              collapse={collapsed}
              style={{ border: 0, padding: collapsed ? '12px 0' : '12px 10px' }}
            >
              {navItems.filter((item) => item.key !== '/settings').map((item) => (
                <Menu.Item key={item.key}>
                  {collapsed ? (
                    <button
                      type="button"
                      className="menu-icon-only"
                      data-menukey={item.key}
                      aria-label={item.label}
                      onMouseEnter={(event) => {
                        const rect = event.currentTarget.getBoundingClientRect();
                        setHoverTip({ label: item.label, left: rect.right + 10, top: rect.top + rect.height / 2 });
                      }}
                      onMouseLeave={() => setHoverTip(null)}
                      onFocus={(event) => {
                        const rect = event.currentTarget.getBoundingClientRect();
                        setHoverTip({ label: item.label, left: rect.right + 10, top: rect.top + rect.height / 2 });
                      }}
                      onBlur={() => setHoverTip(null)}
                      onClick={() => setHoverTip(null)}
                    >
                      {item.icon}
                    </button>
                  ) : (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>{item.icon}<span>{item.label}</span></span>
                  )}
                </Menu.Item>
              ))}
            </Menu>
          </div>
          <button
            type="button"
            className="sider-collapse-btn"
            onClick={() => {
              setHoverTip(null);
              setCollapsed((value) => !value);
            }}
            aria-label={collapsed ? '展开导航栏' : '折叠导航栏'}
            onMouseEnter={(event) => {
              const rect = event.currentTarget.getBoundingClientRect();
              setHoverTip({
                label: collapsed ? '展开导航栏' : '折叠导航栏',
                left: rect.right + 10,
                top: rect.top + rect.height / 2,
              });
            }}
            onMouseLeave={() => setHoverTip(null)}
            onFocus={(event) => {
              const rect = event.currentTarget.getBoundingClientRect();
              setHoverTip({
                label: collapsed ? '展开导航栏' : '折叠导航栏',
                left: rect.right + 10,
                top: rect.top + rect.height / 2,
              });
            }}
            onBlur={() => setHoverTip(null)}
          >
            {collapsed ? <ChevronsRight size={18} /> : <ChevronsLeft size={18} />}
          </button>
          <button
            type="button"
            className={`sider-settings ${activeKey === '/settings' ? 'active' : ''}`}
            onClick={() => { setHoverTip(null); navigate('/settings'); }}
            title="设置"
            aria-label="设置"
            onMouseEnter={(event) => {
              if (collapsed) {
                const rect = event.currentTarget.getBoundingClientRect();
                setHoverTip({ label: '设置', left: rect.right + 10, top: rect.top + rect.height / 2 });
              }
            }}
            onMouseLeave={() => setHoverTip(null)}
            onFocus={(event) => {
              if (collapsed) {
                const rect = event.currentTarget.getBoundingClientRect();
                setHoverTip({ label: '设置', left: rect.right + 10, top: rect.top + rect.height / 2 });
              }
            }}
            onBlur={() => setHoverTip(null)}
          >
            <Settings size={18} />
            {!collapsed && <span className="sider-settings-text">设置</span>}
          </button>
        </div>
      </Sider>
      {hoverTip && (
        <div className="sider-hover-tip" style={{ left: hoverTip.left, top: hoverTip.top }}>
          {hoverTip.label}
        </div>
      )}
      <Layout>
        {useUiStore((state) => state.notebookFocusMode) ? null : (
          <Header className="topbar">
            <Typography.Title heading={5} className="topbar-title">{title}</Typography.Title>
            <div className="topbar-actions">
              <Typography.Text type="secondary" className="topbar-hint">AI · Ctrl+J</Typography.Text>
              <Switch
                checked={theme === 'dark'}
                onChange={toggleTheme}
                checkedText={<Moon size={14} />}
                uncheckedText={<Sun size={14} />}
                aria-label="切换主题"
              />
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
