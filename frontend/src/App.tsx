import { useEffect, useState } from 'react';
import { Layout, Menu, Switch, Typography } from '@arco-design/web-react';
import { BookOpenText, BrainCircuit, FileText, Moon, Settings, Sparkles, Sun, Target, XCircle } from 'lucide-react';
import { HashRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useUiStore } from './stores/uiStore';
import { BankPage } from './pages/BankPage';
import { QuizPage } from './pages/QuizPage';
import { WrongPage } from './pages/WrongPage';
import { NotebookPage } from './pages/NotebookPage';
import { AiPage } from './pages/AiPage';
import { SettingsPage } from './pages/SettingsPage';

const { Sider, Header, Content } = Layout;

const navItems = [
  { key: '/banks', label: '题库', icon: <BookOpenText size={17} /> },
  { key: '/quiz', label: '刷题', icon: <Target size={17} /> },
  { key: '/wrong', label: '错题', icon: <XCircle size={17} /> },
  { key: '/notebooks', label: '笔记本', icon: <FileText size={17} /> },
  { key: '/ai', label: 'AI', icon: <Sparkles size={17} /> },
  { key: '/settings', label: '设置', icon: <Settings size={17} /> }
];

function Shell(): JSX.Element {
  const location = useLocation();
  const navigate = useNavigate();
  const { theme, toggleTheme } = useUiStore();
  const setTheme = useUiStore((state) => state.setTheme);
  const [configLoaded, setConfigLoaded] = useState(false);
  const activeKey = navItems.some((item) => location.pathname.startsWith(item.key)) ? navItems.find((item) => location.pathname.startsWith(item.key))!.key : '/banks';
  const title = navItems.find((item) => item.key === activeKey)?.label ?? 'Drill Notebook';

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  useEffect(() => {
    void window.api?.config.get().then((config) => {
      if (config.theme) setTheme(config.theme);
      setConfigLoaded(true);
    }).catch(() => setConfigLoaded(true));
  }, [setTheme]);
  useEffect(() => {
    if (configLoaded) void window.api?.config.set({ theme });
  }, [configLoaded, theme]);

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" width={224} breakpoint="xl">
        <div className="brand">
          <div className="brand-mark"><BrainCircuit size={18} /></div>
          <span className="brand-name">Drill Notebook</span>
        </div>
        <Menu selectedKeys={[activeKey]} onClickMenuItem={(key) => navigate(key)} style={{ border: 0, padding: '12px 10px' }}>
          {navItems.map((item) => <Menu.Item key={item.key}><span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>{item.icon}<span>{item.label}</span></span></Menu.Item>)}
        </Menu>
      </Sider>
      <Layout>
        <Header className="topbar">
          <Typography.Title heading={5} className="topbar-title">{title}</Typography.Title>
          <Switch
            checked={theme === 'dark'}
            onChange={toggleTheme}
            checkedText={<Moon size={14} />}
            uncheckedText={<Sun size={14} />}
            aria-label="切换主题"
          />
        </Header>
        <Content><AppRoutes /></Content>
      </Layout>
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
      <Route path="/wrong" element={<WrongPage />} />
      <Route path="/notebooks" element={<NotebookPage />} />
      <Route path="/ai" element={<AiPage />} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="*" element={<Navigate to="/banks" replace />} />
    </Routes>
  );
}
