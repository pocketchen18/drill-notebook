import { useEffect, useState } from 'react';
import { Layout, Menu, Switch, Typography } from '@arco-design/web-react';
import { BookOpenCheck, BookOpenText, BrainCircuit, Calendar, FileText, Layers3, Moon, Settings, Sun, Target, XCircle } from 'lucide-react';
import { HashRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useUiStore } from './stores/uiStore';
import { BankPage } from './pages/BankPage';
import { QuizPage } from './pages/QuizPage';
import { WrongPage } from './pages/WrongPage';
import { NotebookPage } from './pages/NotebookPage';
import { SettingsPage } from './pages/SettingsPage';
import { AiAssistant } from './components/AiAssistant';
import { QuestionStudyPage } from './pages/QuestionStudyPage';
import { KnowledgePointPage } from './pages/KnowledgePointPage';
import { CalendarPage } from './pages/CalendarPage';

const { Sider, Header, Content } = Layout;

const navItems = [
  { key: '/banks', label: '题库', icon: <BookOpenText size={17} /> },
  { key: '/quiz', label: '刷题', icon: <Target size={17} /> },
  { key: '/memorize', label: '背题', icon: <BookOpenCheck size={17} /> },
  { key: '/knowledge', label: '背知识点', icon: <Layers3 size={17} /> },
  { key: '/wrong', label: '错题', icon: <XCircle size={17} /> },
  { key: '/calendar', label: '日历', icon: <Calendar size={17} /> },
  { key: '/notebooks', label: '笔记本', icon: <FileText size={17} /> },
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
  const activeKey = navItems.some((item) => location.pathname.startsWith(item.key))
    ? navItems.find((item) => location.pathname.startsWith(item.key))!.key
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
    if (!['/quiz', '/wrong', '/notebooks', '/banks'].some((prefix) => location.pathname.startsWith(prefix))) {
      clearPageContext();
    }
  }, [clearPageContext, location.pathname]);

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" width={224} breakpoint="xl">
        <div className="brand">
          <div className="brand-mark"><BrainCircuit size={18} /></div>
          <span className="brand-name">Drill Notebook</span>
        </div>
        <Menu selectedKeys={[activeKey]} onClickMenuItem={(key) => navigate(key)} style={{ border: 0, padding: '12px 10px' }}>
          {navItems.map((item) => (
            <Menu.Item key={item.key}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>{item.icon}<span>{item.label}</span></span>
            </Menu.Item>
          ))}
        </Menu>
      </Sider>
      <Layout>
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
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/banks" replace />} />
      <Route path="/banks" element={<BankPage />} />
      <Route path="/quiz" element={<QuizPage />} />
      <Route path="/memorize" element={<QuestionStudyPage />} />
      <Route path="/knowledge" element={<KnowledgePointPage />} />
      <Route path="/wrong" element={<WrongPage />} />
      <Route path="/today" element={<Navigate to="/calendar" replace />} />
      <Route path="/calendar" element={<CalendarPage />} />
      <Route path="/notebooks" element={<NotebookPage />} />
      <Route path="/ai" element={<Navigate to="/settings" replace />} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="*" element={<Navigate to="/banks" replace />} />
    </Routes>
  );
}
