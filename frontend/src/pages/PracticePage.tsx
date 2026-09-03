import { useEffect, useState } from 'react';
import { Radio, Tabs } from '@arco-design/web-react';
import { QuizPage } from './QuizPage';
import { QuestionStudyPage } from './QuestionStudyPage';
import { KnowledgeMemorizePanel } from './knowledge/KnowledgeMemorizePanel';
import { usePersistSlice } from '../hooks/useViewState';
import { readPageSlice } from '../lib/viewState';
import type { MemorizeTarget, PracticeTab } from '../lib/viewState';

/** 「练习」页：刷题 / 背诵两种模式的合并入口；背诵内含「背题（题库）」与「背知识点」两个子模式。 */
export function PracticePage({ initialTab }: { initialTab?: PracticeTab }): JSX.Element {
  // 深链（/quiz、/memorize）显式指定的 Tab 优先；否则回到上次停留的 Tab 与背诵子模式。
  const [tab, setTab] = useState<PracticeTab>(() => initialTab ?? readPageSlice('practice').tab ?? 'quiz');
  const [memorizeTarget, setMemorizeTarget] = useState<MemorizeTarget>(
    () => readPageSlice('practice').memorizeTarget ?? 'questions'
  );

  useEffect(() => { if (initialTab) setTab(initialTab); }, [initialTab]);
  usePersistSlice('practice', { tab, memorizeTarget });

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
