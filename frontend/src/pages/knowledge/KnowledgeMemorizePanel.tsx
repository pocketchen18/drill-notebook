import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Select, Space, Tag, Tooltip, Tree, Typography } from '@arco-design/web-react';
import { BookOpenCheck, SlidersHorizontal } from 'lucide-react';
import { get } from '../../lib/api';
import type { Bank, KnowledgePoint, Question } from '../../lib/types';
import { buildKnowledgeTree } from '../../lib/knowledgeTree';
import type { KnowledgeTreeNode } from '../../lib/knowledgeTree';
import { describeSessionCurveConfig, readSessionCurveConfig } from '../../lib/sessionCurve';
import { SessionCurveSettingsModal } from '../../components/SessionCurveSettingsModal';
import { KnowledgeMemorizeSession } from './KnowledgeMemorizeSession';
import { usePersistSlice } from '../../hooks/useViewState';
import { captureKeySet, putScoped, readKeySet, readPageSlice } from '../../lib/viewState';

interface TreeDatum {
  key: string;
  title: string;
  children?: TreeDatum[];
}

function toTreeData(nodes: KnowledgeTreeNode[]): TreeDatum[] {
  return nodes.map((node) => ({
    key: String(node.id),
    title: `${node.title}（${subtreeCount(node)}）`,
    children: node.children.length ? toTreeData(node.children) : undefined
  }));
}

/** 子树内的知识点数量（不含虚拟根节点），用于节点标题展示。 */
function subtreeCount(node: KnowledgeTreeNode): number {
  return node.children.reduce((sum, child) => sum + subtreeCount(child), node.id !== 0 ? 1 : 0);
}

/**
 * 背知识点面板（练习 → 背诵）：轻量知识树勾选背诵范围 + 记忆曲线设置 + 开始背诵。
 * 与「知识点」阅读页解耦：这里只保留背诵所需的选材与会话流程。
 */
export function KnowledgeMemorizePanel(): JSX.Element {
  const banksQuery = useQuery({ queryKey: ['banks'], queryFn: () => get<Bank[]>('/api/banks') });
  const cachedPractice = readPageSlice('practice');
  const [bankId, setBankId] = useState<number | undefined>(cachedPractice.knowledgeBankId);
  const resolvedBankId = bankId ?? banksQuery.data?.[0]?.id;
  useEffect(() => {
    const banks = banksQuery.data;
    if (!banks?.length || bankId === undefined) return;
    if (!banks.some((bank) => bank.id === bankId)) setBankId(undefined);
  }, [bankId, banksQuery.data]);
  const pointsQuery = useQuery({
    queryKey: ['knowledge-points', resolvedBankId],
    queryFn: () => get<KnowledgePoint[]>(`/api/knowledge-points${resolvedBankId ? `?bankId=${resolvedBankId}` : ''}`),
    enabled: resolvedBankId !== undefined
  });
  const questionsQuery = useQuery({ queryKey: ['knowledge-questions', resolvedBankId], queryFn: () => get<Question[]>(`/api/banks/${resolvedBankId}/questions`), enabled: resolvedBankId !== undefined });

  const points = pointsQuery.data ?? [];
  const currentBank = banksQuery.data?.find((bank) => bank.id === resolvedBankId);
  const tree = useMemo(() => buildKnowledgeTree(points, currentBank ? currentBank.name : '全部知识点'), [points, currentBank]);
  const treeData = useMemo(() => toTreeData(tree.roots), [tree]);
  const allKeys = useMemo(() => tree.flatList.map((node) => String(node.id)), [tree]);
  const topKeys = useMemo(() => tree.roots.map((node) => String(node.id)), [tree]);

  // 默认全选当前知识库；级联勾选（勾父带子）；勾选范围按库记忆
  const [checkedKeys, setCheckedKeys] = useState<string[] | undefined>(
    () => readKeySet(cachedPractice.knowledgeChecked, cachedPractice.knowledgeBankId) ?? undefined
  );
  // 记忆套用完成要用 state 表达：若套用结果与当前勾选相同，React 会跳过重渲染，
  // 用 ref 做门闩会让写入器永远不再运行，题库切换就记不下来。
  const [hydratedBank, setHydratedBank] = useState<number | undefined>(undefined);
  useEffect(() => {
    if (resolvedBankId === undefined || hydratedBank === resolvedBankId) return;
    setCheckedKeys(readKeySet(cachedPractice.knowledgeChecked, resolvedBankId) ?? undefined);
    setHydratedBank(resolvedBankId);
  }, [resolvedBankId, hydratedBank]); // eslint-disable-line react-hooks/exhaustive-deps -- cachedPractice 每次渲染重读
  const effectiveChecked = useMemo(() => new Set(checkedKeys ?? allKeys), [checkedKeys, allKeys]);
  // 背诵清单 = 勾选范围 ∩ 叶子卡片：父节点/章节节点只用于圈定范围，不作为背诵条目；顺序 = 文档树顺序
  const selectedIds = useMemo(() => tree.flatList.filter((node) => node.children.length === 0 && effectiveChecked.has(String(node.id))).map((node) => node.id), [tree, effectiveChecked]);

  const [curveSettingsVisible, setCurveSettingsVisible] = useState(false);
  const [curveSummary, setCurveSummary] = useState(() => readSessionCurveConfig());
  const [sessionIds, setSessionIds] = useState<number[]>();

  const handleBankChange = (id: number): void => {
    setBankId(id);
    setSessionIds(undefined);
  };

  // 勾选覆盖整棵树时等价于「全选」，按 all 哨兵存，避免写入上千个节点 key。
  // 本库勾选尚未套用前（hydratedBank 不匹配）不写，避免用上一个库的勾选覆盖记忆。
  const checkedForCache = checkedKeys && allKeys.length > 0 && checkedKeys.length >= allKeys.length ? undefined : checkedKeys;
  usePersistSlice('practice', hydratedBank !== resolvedBankId ? {} : {
    knowledgeBankId: resolvedBankId,
    knowledgeChecked: putScoped(cachedPractice.knowledgeChecked, resolvedBankId, captureKeySet(checkedForCache))
  });

  if (sessionIds?.length) {
    return <main className="page">
      <div className="page-heading">
        <div><h1>背知识点</h1><p>短周期记忆曲线：{describeSessionCurveConfig(curveSummary)}</p></div>
        <Button onClick={() => setSessionIds(undefined)}>结束背诵</Button>
      </div>
      <KnowledgeMemorizeSession points={points} questions={questionsQuery.data ?? []} ids={sessionIds} />
    </main>;
  }

  return <main className="page">
    <div className="page-heading">
      <div><h1>背知识点</h1><p>按知识树勾选背诵范围（勾父节点即整棵子树），只背叶子卡片，用短周期记忆曲线循环背诵。</p></div>
      <Space>
        <Select value={resolvedBankId} onChange={(v) => handleBankChange(Number(v))} placeholder="选择知识库" style={{ width: 200 }}>
          {banksQuery.data?.map((bank) => <Select.Option key={bank.id} value={bank.id}>{bank.name}</Select.Option>)}
        </Select>
        <Tooltip content="设置循环轮数、错题重复策略等">
          <Button icon={<SlidersHorizontal size={16} />} onClick={() => setCurveSettingsVisible(true)}>记忆曲线 · {describeSessionCurveConfig(curveSummary)}</Button>
        </Tooltip>
      </Space>
    </div>
    <section className="panel memorize-picker-panel">
      <div className="panel-header">
        <h2>选择背诵范围</h2>
        <Space>
          <Button size="small" onClick={() => setCheckedKeys(allKeys)}>全选</Button>
          <Button size="small" onClick={() => setCheckedKeys([])}>清空</Button>
        </Space>
      </div>
      <div className="panel-body">
        {points.length ? (
          <div className="memorize-picker-layout">
            <div className="memorize-tree">
              <Tree
                checkable
                blockNode
                checkedKeys={checkedKeys ?? allKeys}
                defaultExpandedKeys={topKeys}
                treeData={treeData}
                onCheck={(keys) => setCheckedKeys((keys as string[]) ?? [])}
              />
            </div>
            <aside className="memorize-picker-summary">
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>勾选节点即选中其整棵子树；父节点/章节节点只圈定范围，只背叶子卡片，按文档顺序循环。</Typography.Text>
              <div style={{ margin: '12px 0' }}>
                <Tag color="arcoblue">已选 {selectedIds.length} 个叶子卡片</Tag>
              </div>
              <div className="setup-actions">
                <Button type="primary" icon={<BookOpenCheck size={16} />} disabled={!selectedIds.length} onClick={() => setSessionIds([...selectedIds])}>开始背知识点（{selectedIds.length}）</Button>
              </div>
            </aside>
          </div>
        ) : <Empty description="该知识库暂无知识点，请先到「知识点」页导入或新建" />}
      </div>
    </section>
    <SessionCurveSettingsModal
      visible={curveSettingsVisible}
      onClose={() => setCurveSettingsVisible(false)}
      itemCount={selectedIds.length}
      onSaved={(config) => setCurveSummary(config)}
    />
  </main>;
}
