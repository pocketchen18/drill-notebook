import { useRef, useState } from 'react';
import { Button, Divider, Empty, Message, Tag, Typography } from '@arco-design/web-react';
import { ChevronLeft, ChevronRight, Eye } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { KnowledgePoint, Question } from '../../lib/types';
import { MarkdownContent } from '../../components/markdown/MarkdownRenderer';
import { completeStudy } from '../../lib/study';
import { applyCurveAnswer, buildCurveQueue, readSessionCurveConfig } from '../../lib/sessionCurve';
import type { CurveEntry, CurveItemState } from '../../lib/sessionCurve';
import { finishDayQueueStep } from '../../components/DayQueueSessionBar';
import { SessionPlanRecommendModal } from '../../components/SessionPlanRecommendModal';

export interface KnowledgeMemorizeSessionProps {
  /** 全量知识点（按 id 查找会话条目） */
  points: KnowledgePoint[];
  /** 关联题目展示用 */
  questions: Question[];
  /** 背诵清单（有序） */
  ids: number[];
  /** 日队列/深链等定时任务：强制单轮，避免同一知识点会话内循环多遍 */
  singleLoop?: boolean;
  planItemId?: number;
  planDate?: string;
  planResourceId?: number;
  dayQueueMode?: boolean;
}

/**
 * 背知识点会话：短周期记忆曲线（多轮循环 + 错题重复）的播放界面。
 * 由「练习 → 背诵 → 背知识点」面板与知识点页深链（日历/今日队列）共用。
 */
export function KnowledgeMemorizeSession({ points, questions, ids, singleLoop, planItemId, planDate, planResourceId, dayQueueMode }: KnowledgeMemorizeSessionProps): JSX.Element {
  const navigate = useNavigate();
  const [curveLoops] = useState(() => {
    const config = readSessionCurveConfig();
    return singleLoop || !config.enabled ? 1 : config.loops;
  });
  const curveConfigRef = useRef(readSessionCurveConfig());
  const [curveQueue, setCurveQueue] = useState<CurveEntry[]>(() => buildCurveQueue(ids, curveLoops));
  const [curveStates, setCurveStates] = useState<Record<number, CurveItemState>>({});
  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  // 知识点评分（SM-2 跨天曲线）：仅防重复提交 SRS
  const [kpSubmittedIds, setKpSubmittedIds] = useState<Set<number>>(new Set());
  const [recommendVisible, setRecommendVisible] = useState(false);
  const [recommendPayload, setRecommendPayload] = useState<{ pointIds?: number[] }>({});
  const recommendShownRef = useRef(false);

  const pointById = new Map(points.map((point) => [point.id, point]));
  const currentEntry = curveQueue[index];
  const current = currentEntry ? pointById.get(currentEntry.resourceId) : undefined;

  const jump = (next: number): void => { setIndex(next); setRevealed(false); };

  const openSessionRecommend = (): void => {
    if (recommendShownRef.current || !ids.length) return;
    recommendShownRef.current = true;
    setRecommendPayload({ pointIds: [...ids] });
    setRecommendVisible(true);
  };

  /** 二元评分：会 → quality 4；不会 → quality 0。SRS 只提交首次，重现仅推进会话内队列。 */
  const rateKp = (known: boolean): void => {
    if (!current) return;
    const quality = known ? 4 : 0;
    if (!kpSubmittedIds.has(current.id)) {
      setKpSubmittedIds((prev) => new Set(prev).add(current.id));
      void completeStudy({
        resourceType: 'knowledge_point',
        resourceId: current.id,
        quality,
        source: 'knowledge',
        planItemId: planItemId && planResourceId === current.id ? planItemId : undefined,
        planDate,
      }).catch(() => {
        // 后端不可用时静默失败
      });
    }
    const curve = applyCurveAnswer(curveQueue, index, known, curveStates, curveConfigRef.current);
    setCurveQueue(curve.entries);
    setCurveStates(curve.states);
    if (index < curve.entries.length - 1) {
      jump(index + 1);
    } else if (dayQueueMode && finishDayQueueStep(navigate)) {
      return;
    } else {
      Message.success(curveLoops > 1 ? `全部 ${curveLoops} 轮背诵完成` : '本轮背诵完成');
      openSessionRecommend();
    }
  };

  const nextPoint = (): void => {
    if (!curveQueue.length) return;
    if (index >= curveQueue.length - 1) {
      if (dayQueueMode && finishDayQueueStep(navigate)) {
        return;
      }
      Message.success(curveLoops > 1 ? `全部 ${curveLoops} 轮知识点背诵完成` : '本轮知识点背诵完成');
      openSessionRecommend();
      return;
    }
    jump(index + 1);
  };

  // 当前条目是否已在本条目上评过分：多轮循环下同一知识点每轮都可重新评分，不能用资源级的 done 判断
  const currentEntryRated = current ? curveStates[current.id]?.lastRatedEntryId === currentEntry?.entryId : false;

  /** 知识点跳转面板：多轮循环时按轮次分组，轮内按出场顺序编号。 */
  const renderPalette = (): JSX.Element => {
    const rounds = [...new Set(curveQueue.map((entry) => entry.round))].sort((a, b) => a - b);
    return <div>{rounds.map((round) => {
      const positions = curveQueue.map((entry, itemIndex) => ({ entry, itemIndex })).filter(({ entry }) => entry.round === round);
      return <div key={round} className="palette-round">
        {curveLoops > 1 ? <div className="palette-round-title">第 {round + 1} / {curveLoops} 轮</div> : null}
        <div className="palette-grid">{positions.map(({ entry, itemIndex }, position) => <button type="button" key={entry.entryId} title={entry.attempt > 0 ? `第 ${entry.attempt + 1} 遍重现` : `第 ${round + 1} 轮 · 第 ${position + 1} 个`} className={`palette-item ${itemIndex === index ? 'current' : ''} ${entry.attempt > 0 ? 'repeat' : ''}`} onClick={() => jump(itemIndex)}>{position + 1}</button>)}</div>
      </div>;
    })}</div>;
  };

  if (!current) {
    return <Empty description="本轮没有可背的知识点" />;
  }

  return <>
    <div className="study-session-layout"><aside className="panel question-palette"><div className="panel-header"><h2>知识点跳转</h2></div><div className="panel-body">{renderPalette()}<Typography.Text type="secondary">橙色描边：重现条目</Typography.Text></div></aside><section className="quiz-card memory-card"><div className="quiz-progress"><span>第 {index + 1} / {curveQueue.length} 个知识点</span>{curveLoops > 1 ? <Tag color="blue">第 {currentEntry.round + 1} / {curveLoops} 轮</Tag> : null}{currentEntry.attempt > 0 ? <Tag color="orange">第 {currentEntry.attempt + 1} 遍</Tag> : null}{current.category && <Tag color="arcoblue">{current.category}</Tag>}</div><h2 className="knowledge-study-title">{current.title}</h2>{revealed ? <div className="knowledge-study-content"><MarkdownContent value={current.content} />{current.questionIds.length > 0 && <div className="linked-question-list"><strong>关联题目</strong>{current.questionIds.map((id) => <div key={id}>{questions.find((question) => question.id === id)?.stem ?? `题目 #${id}`}</div>)}</div>}{currentEntryRated ? <div className="quality-rating" style={{ textAlign: 'center', padding: '16px 0' }}><Divider /><Typography.Text type="secondary">{curveStates[current.id]?.abandoned ? '本次未记住（已达额外重复上限，后续轮次仍会出场，会话结束后可加入跨天复习方案）' : '已记住'}</Typography.Text></div> : <div className="quality-rating"><Divider /><Typography.Text style={{ marginBottom: 8, display: 'block' }}>记住了吗？（不会将按记忆曲线策略重复出现，评分写入跨天记忆曲线）</Typography.Text><div className="quality-buttons"><button className="quality-btn quality-0" onClick={() => rateKp(false)}><span className="quality-label">不会</span></button><button className="quality-btn quality-4" onClick={() => rateKp(true)}><span className="quality-label">会</span></button></div></div>}</div> : <div className="knowledge-study-prompt"><Typography.Text type="secondary">先回想这个知识点的核心要点，再展开看内容。</Typography.Text><Button type="primary" icon={<Eye size={16} />} onClick={() => setRevealed(true)}>显示内容</Button></div>}</section><div className="session-nav"><Button icon={<ChevronLeft size={16} />} disabled={index === 0} onClick={() => jump(index - 1)}>上一个</Button><Button icon={<ChevronRight size={16} />} disabled={index === curveQueue.length - 1} onClick={nextPoint}>下一个</Button></div></div>
    <SessionPlanRecommendModal
      visible={recommendVisible}
      onClose={() => {
        recommendShownRef.current = false;
        setRecommendVisible(false);
      }}
      sessionType="knowledge"
      payload={recommendPayload}
    />
  </>;
}
