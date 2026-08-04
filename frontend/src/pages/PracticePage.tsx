import { useEffect, useState } from 'react';
import { Tabs } from '@arco-design/web-react';
import { QuizPage } from './QuizPage';
import { QuestionStudyPage } from './QuestionStudyPage';

type PracticeTab = 'quiz' | 'memorize';

/** 「练习」页：刷题 / 背题两种模式的合并入口，用 Tab 切换。 */
export function PracticePage({ initialTab = 'quiz' }: { initialTab?: PracticeTab }): JSX.Element {
  const [tab, setTab] = useState<PracticeTab>(initialTab);

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
        <Tabs.TabPane key="memorize" title="背题">
          <QuestionStudyPage />
        </Tabs.TabPane>
      </Tabs>
    </div>
  );
}
