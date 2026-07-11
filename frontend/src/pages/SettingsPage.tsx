import { useEffect, useState } from 'react';
import { Button, Descriptions, Message, Spin, Switch, Typography } from '@arco-design/web-react';
import { ExternalLink, FolderOpen, RefreshCw } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { get } from '../lib/api';
import { useUiStore } from '../stores/uiStore';

interface Health { status: string; appRoot: string; dbPath: string; }

export function SettingsPage(): JSX.Element {
  const navigate = useNavigate();
  const { theme, toggleTheme } = useUiStore();
  const [portable, setPortable] = useState<{ root: string; database: string; portable: boolean }>();
  const [health, setHealth] = useState<Health>();
  const [loading, setLoading] = useState(true);

  const load = async (): Promise<void> => {
    setLoading(true);
    try {
      const [info, status] = await Promise.all([
        window.api?.backend.getPortableInfo() ?? Promise.resolve({ root: '浏览器开发模式', database: '由后端决定', portable: true }),
        get<Health>('/api/health')
      ]);
      setPortable(info);
      setHealth(status);
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '后端不可用');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  return <main className="page">
    <div className="page-heading"><div><h1>设置</h1><p>查看便携运行位置和应用状态。</p></div><Button type="text" icon={<RefreshCw size={16} />} onClick={() => void load()} aria-label="刷新状态" /></div>
    {loading ? <Spin /> : <div className="content-grid">
      <section className="panel"><div className="panel-header"><h2>便携运行</h2></div><div className="panel-body">
        <Descriptions column={1} border data={[
          { label: '模式', value: portable?.portable ? '绿色便携' : '标准模式' },
          { label: '应用根目录', value: <code className="path-code">{portable?.root}</code> },
          { label: '数据库', value: <code className="path-code">{portable?.database}</code> },
          { label: '后端状态', value: health?.status === 'UP' ? '运行中' : health?.status || '未知' }
        ]} />
      </div></section>
      <section className="panel"><div className="panel-header"><h2>界面</h2></div><div className="panel-body form-stack">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}><div><Typography.Text bold>深色主题</Typography.Text><br /><Typography.Text type="secondary">适合夜间整理笔记。</Typography.Text></div><Switch checked={theme === 'dark'} onChange={toggleTheme} /></div>
        <Button icon={<ExternalLink size={16} />} onClick={() => navigate('/ai')}>配置 AI 连接</Button>
        <div className="muted"><FolderOpen size={16} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />所有应用数据均保存在应用根目录下。</div>
      </div></section>
    </div>}
  </main>;
}
