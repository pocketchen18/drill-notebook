import { useQuery } from '@tanstack/react-query';
import { readStorageSnapshot, storageQueryKey } from '../../lib/aiStorageApi';
import { storageAttachmentSuffix, storageMeterText, storageReadFailureText } from '../../lib/aiTurnText';

/**
 * 会话存储容量条（执行方案 §12「另设容量提示」，contracts §2.7）：
 * 常驻在输入区上方，说的是**本地落库字节对可配额度**，不是模型上下文，也不是后台维护预算。
 * 只读——不发聊天、不删任何东西，读取本身也不让服务端重建计数。
 */
export function ChatStorageMeter({ enabled = true }: { enabled?: boolean }) {
  const { data, isError, isLoading } = useQuery({
    queryKey: storageQueryKey,
    queryFn: readStorageSnapshot,
    enabled
  });

  if (isLoading) return null;
  if (isError || !data) {
    return <p className="ai-storage-meter ai-storage-meter-error">{storageReadFailureText()}</p>;
  }

  const meter = storageMeterText(data);
  const limit = data.historyLimitBytes > 0 ? data.historyLimitBytes : 1;
  const percent = Math.min(100, Math.max(0, Math.round((data.usedHistoryBytes / limit) * 100)));
  const tone = meter.code === 'HISTORY_STORAGE_FULL' ? 'full' : meter.code === 'HISTORY_STORAGE_WARNING' ? 'warning' : 'ok';

  return (
    <div className={`ai-storage-meter ai-storage-meter-${tone}`}>
      <span className="ai-storage-meter-text">
        {meter.message}
        {storageAttachmentSuffix(data)}
      </span>
      <span
        className="ai-storage-meter-bar"
        role="progressbar"
        aria-label={meter.message}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
      >
        <span className="ai-storage-meter-fill" style={{ width: `${percent}%` }} />
      </span>
    </div>
  );
}
