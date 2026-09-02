import { useEffect, useState } from 'react';
import { Radio, Tabs } from '@arco-design/web-react';
import { QuizPage } from './QuizPage';
import { QuestionStudyPage } from './QuestionStudyPage';
import { KnowledgeMemorizePanel } from './knowledge/KnowledgeMemorizePanel';

type PracticeTab = 'quiz' | 'memorize';
/** 背诵子模式：背题库题目 / 背知识点 */
type MemorizeTarget = 'questions' | 'knowledge';

/** 「练习」页：刷题 / 背诵两种模式的合并入口；背诵内含「背题（题库）」与「背知识点」两个子模式。 */
export function PracticePage({ initialTab = 'quiz' }: { initialTab?: PracticeTab }): JSX.Element {
  const [tab, setTab] = useState<PracticeTab>(initialTab);
  const [memorizeTarget, setMemorizeTarget] = useState<MemorizeTarget>('questions');

  // 深链（/quiz?... 或 /memorize?...）变化时同步切到对应 Tab。
  useEffect(() => { setTab(initialTab); }, [initialTab]);

  return (
    <div className="practice-page">
      <Tabs
        className="practice-tabs"
        type="line"
        size="large"
        activeTab={tab}
        onChange={(key) => setTab(key as PracticeTab)}
      >
        <Tabs.TabPane key="quiz" title="刷题">
          <QuizPage />
        </Tabs.TabPane>
        <Tabs.TabPane key="memorize" title="背诵">
          <div className="memorize-mode-bar">
            <Radio.Group type="button" value={memorizeTarget} onChange={(value) => setMemorizeTarget(value as MemorizeTarget)}>
              <Radio value="questions">背题（题库）</Radio>
              <Radio value="knowledge">背知识点</Radio>
            </Radio.Group>
          </div>
          {memorizeTarget === 'questions' ? <QuestionStudyPage /> : <KnowledgeMemorizePanel />}
        </Tabs.TabPane>
      </Tabs>
    </div>
  );
}
