import { Modal, Button, Popconfirm, Tooltip } from '@arco-design/web-react';
import { BookOpenCheck, FileDown, RotateCcw } from 'lucide-react';
import {
  summarizeBank,
  resummarizeBank,
  summarizeImport,
  type SummaryResult,
  type SummarizeImportResult,
} from '../lib/knowledgeApi';

// 当前正在跑的总结任务类型；null = 没在跑
export type SummaryTask = 'import' | 'summarize' | 'resummarize';

export interface AiSummaryModalProps {
  visible: boolean;
  bankId: number | undefined;
  bankHasSummary: boolean;
  bankHasContent: boolean;
  onPickFile: () => Promise<string | undefined>;  // 「总结并导入」时调用：开文件对话框，返回原文内容（undefined 表示用户取消）
  onClose: () => void;
  // 父组件持有的转换任务态：null=空闲，否则表示正在跑某类总结。Modal 内不自持 loading，全部由父组件传入以支持「关 Modal 后继续跑」。
  activeTask: SummaryTask | null;
  // 触发总结任务；父组件设 activeTask 并启动 fetch，完成后弹 Toast + 刷新。返回 boolean 表示是否成功启动。
  onRunImport: (rawContent: string) => void;
  onRunSummarize: () => void;
  onRunResummarize: () => void;
}

export function AiSummaryModal({ visible, bankId, bankHasSummary, bankHasContent, onPickFile, onClose, activeTask, onRunImport, onRunSummarize, onRunResummarize }: AiSummaryModalProps) {
  const busy = activeTask !== null;
  // 被点中的那个按钮文案态
  const importLoading = activeTask === 'import';
  const summarizeLoading = activeTask === 'summarize';
  const resummarizeLoading = activeTask === 'resummarize';

  const handleSummarizeImport = async (): Promise<void> => {
    if (!bankId || busy) return;
    const rawContent = await onPickFile();
    if (rawContent === undefined) return;  // 用户取消文件选择
    onRunImport(rawContent);
  };

  const handleSummaryLibrary = (): void => {
    if (!bankId || busy) return;
    if (bankHasSummary) {
      onRunResummarize();
    } else {
      onRunSummarize();
    }
  };

  const handleResummarizeBank = (): void => {
    if (!bankId || busy) return;
    onRunResummarize();
  };

  // 按钮文案态：被点中时变「正在…」，其他按钮禁用
  const importTitle = importLoading ? '正在导入…' : '总结并导入';
  const importDesc = importLoading ? 'AI 正在总结原文件并导入，请稍候（可关闭此窗口，完成后会提示）' : 'AI 读取原文件并总结后导入当前知识库';
  const summarizeTitle = summarizeLoading ? '正在总结…' : '总结当前知识库';
  const summarizeDesc = summarizeLoading ? 'AI 正在浓缩当前知识库知识点，请稍候（可关闭此窗口，完成后会提示）' : '对当前知识库全部知识点做 AI 浓缩';
  const resummarizeTitle = resummarizeLoading ? '正在重新总结…' : '重新总结';
  const resummarizeDesc = resummarizeLoading ? 'AI 正在从原文重新总结，请稍候（可关闭此窗口，完成后会提示）' : '从已保存原文重新跑 AI 总结';

  return (
    <Modal title="AI 总结" visible={visible} onCancel={onClose} footer={null} style={{ width: 480 }} maskClosable={!busy}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {bankHasContent ? (
          <Popconfirm title="当前知识库不为空，是否覆盖导入？" onOk={handleSummarizeImport} disabled={busy}>
            <div><ActionCard icon={<FileDown size={20} />} title={importTitle} desc={importDesc} loading={importLoading} disabled={busy && !importLoading} /></div>
          </Popconfirm>
        ) : (
          <ActionCard icon={<FileDown size={20} />} title={importTitle} desc={importDesc} onClick={handleSummarizeImport} loading={importLoading} disabled={busy && !importLoading} />
        )}

        {bankHasSummary ? (
          <Popconfirm title="当前知识库已总结，是否重新总结？" onOk={handleResummarizeBank} disabled={busy}>
            <div><ActionCard icon={<BookOpenCheck size={20} />} title={summarizeTitle} desc={summarizeDesc} loading={summarizeLoading} disabled={busy && !summarizeLoading} /></div>
          </Popconfirm>
        ) : (
          <ActionCard icon={<BookOpenCheck size={20} />} title={summarizeTitle} desc={summarizeDesc} onClick={handleSummaryLibrary} loading={summarizeLoading} disabled={busy && !summarizeLoading} />
        )}

        <Tooltip content={bankHasSummary ? '' : '当前知识库还未总结，请先点击"总结当前知识库"'}>
          <ActionCard
            icon={<RotateCcw size={20} />}
            title={resummarizeTitle}
            desc={resummarizeDesc}
            onClick={handleResummarizeBank}
            loading={resummarizeLoading}
            disabled={!bankHasSummary || (busy && !resummarizeLoading)}
          />
        </Tooltip>

        <div style={{ textAlign: 'right', marginTop: 8 }}>
          <Button onClick={onClose} disabled={busy}>{busy ? '后台继续运行' : '取消'}</Button>
        </div>
      </div>
    </Modal>
  );
}

interface ActionCardProps {
  icon: React.ReactNode;
  title: string;
  desc: string;
  onClick?: () => void;
  loading?: boolean;
  disabled?: boolean;
}

function ActionCard({ icon, title, desc, onClick, loading, disabled }: ActionCardProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '14px 16px',
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        background: 'white',
        cursor: disabled ? 'not-allowed' : 'pointer',
        textAlign: 'left',
        width: '100%',
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <span style={{ color: '#4f46e5' }}>{icon}</span>
      <span style={{ flex: 1 }}>
        <span style={{ display: 'block', fontWeight: 600, marginBottom: 4 }}>{title}</span>
        <span style={{ display: 'block', color: '#6b7280', fontSize: 12 }}>{desc}</span>
      </span>
    </button>
  );
}
