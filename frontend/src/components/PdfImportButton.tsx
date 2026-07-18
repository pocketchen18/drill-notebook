import { useRef, useState } from 'react';
import { Button, Message } from '@arco-design/web-react';
import { FileType } from 'lucide-react';
import { post } from '../lib/api';
import { friendlyMessage } from '../lib/errors';

interface PdfImportButtonProps {
  bankId: number;
  disabled?: boolean;
  onImported?: () => void;
}

interface PdfImportResult {
  imported: number;
  skipped: number;
  failed: number;
  errors?: string[];
  strategy?: string;
}

const strategyLabel = (strategy: string | undefined): string => {
  if (strategy === 'ai-fallback') return 'AI 兜底';
  if (strategy === 'ai') return 'AI 解析';
  if (strategy === 'rules') return '规则解析';
  return '导入';
};

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const bytes = new Uint8Array(reader.result as ArrayBuffer);
      let binary = '';
      bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
      resolve(btoa(binary));
    };
    reader.onerror = () => reject(new Error('文件读取失败'));
    reader.readAsArrayBuffer(file);
  });
}

export function PdfImportButton({ bankId, disabled, onImported }: PdfImportButtonProps): JSX.Element {
  const [importing, setImporting] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const handleFileSelect = async (event: React.ChangeEvent<HTMLInputElement>): Promise<void> => {
    const file = event.target.files?.[0];
    if (!file) return;
    event.target.value = '';
    setImporting(true);
    try {
      const base64 = await fileToBase64(file);
      const result = await post<PdfImportResult>(`/api/banks/${bankId}/import/pdf`, { content: base64, forceAi: false });
      Message.success(`${strategyLabel(result.strategy)}完成：导入 ${result.imported} 题，跳过 ${result.skipped} 题`);
      if (result.errors?.length) Message.warning(result.errors.slice(0, 2).join('；'));
      onImported?.();
    } catch (error) {
      Message.error(friendlyMessage(error, 'PDF 导入失败，请稍后重试'));
    } finally {
      setImporting(false);
    }
  };

  return (
    <>
      <Button icon={<FileType size={16} />} loading={importing} disabled={disabled} onClick={() => fileInput.current?.click()}>导入 PDF</Button>
      <input ref={fileInput} type="file" accept="application/pdf,.pdf" hidden onChange={(event) => void handleFileSelect(event)} />
    </>
  );
}
