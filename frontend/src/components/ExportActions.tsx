import { useState } from 'react';
import { Button, Message, Select, Space } from '@arco-design/web-react';
import { Download } from 'lucide-react';
import { buildStandaloneHtml, safeFileName, type ExportDocument, type ExportFormat } from '../lib/export';
import { friendlyMessage } from '../lib/errors';

interface ExportActionsProps {
  count: number;
  document: () => Promise<ExportDocument> | ExportDocument;
}

export function ExportActions({ count, document }: ExportActionsProps): JSX.Element {
  const [format, setFormat] = useState<ExportFormat>('md');
  const [exporting, setExporting] = useState(false);

  const runExport = async (): Promise<void> => {
    if (!count) { Message.warning('请至少选择一项'); return; }
    if (!window.api) { Message.error('导出功能仅在桌面应用中可用'); return; }
    setExporting(true);
    try {
      const value = await document();
      const html = await buildStandaloneHtml(value);
      const result = await window.api.exportFile.save({
        format,
        suggestedName: `${safeFileName(value.title)}.${format}`,
        content: format === 'md' ? `# ${value.title}\n\n${value.markdown}\n` : html,
        html
      });
      if (!result.canceled) Message.success(`已导出到 ${result.path ?? '所选位置'}`);
    } catch (error) {
      Message.error(friendlyMessage(error, '导出失败，请稍后重试'));
    } finally {
      setExporting(false);
    }
  };

  return <Space className="export-actions">
    <span className="export-count">已选 {count} 项</span>
    <Select size="small" value={format} onChange={(value) => setFormat(value as ExportFormat)} style={{ width: 92 }}>
      <Select.Option value="md">Markdown</Select.Option>
      <Select.Option value="html">HTML</Select.Option>
      <Select.Option value="pdf">PDF</Select.Option>
    </Select>
    <Button size="small" icon={<Download size={15} />} loading={exporting} disabled={!count} onClick={() => void runExport()}>导出</Button>
  </Space>;
}
