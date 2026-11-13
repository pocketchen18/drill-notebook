import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useRegisterPageContext } from '../hooks/useRegisterPageContext';
import type { AiPageContext } from '../stores/uiStore';

type SectionContent = Pick<AiPageContext, 'markdown' | 'isDraft' | 'truncated'>;
type Section = SectionContent & { owner: symbol; scope: string };
interface SectionRegistry {
  scope: string;
  register: (scope: string, id: string, owner: symbol, content: SectionContent) => () => void;
}
const PageSections = createContext<SectionRegistry | null>(null);

/** 子面板只登记明确允许读取的展示数据；不扫描 DOM、输入框或浏览器存储。 */
export function PageContextProvider({ context, children }: { context: AiPageContext; children: ReactNode }): JSX.Element {
  const [sections, setSections] = useState<Record<string, Section>>({});
  const scope = `${context.kind}:${context.route ?? context.title}`;
  const register = useCallback<SectionRegistry['register']>((sectionScope, id, owner, content) => {
    setSections(current => ({ ...current, [id]: { ...content, owner, scope: sectionScope } }));
    return () => setSections(current => {
      if (current[id]?.owner !== owner) return current;
      const next = { ...current };
      delete next[id];
      return next;
    });
  }, []);
  const registry = useMemo(() => ({ scope, register }), [scope, register]);
  const active = Object.entries(sections).sort(([a], [b]) => a.localeCompare(b))
    .map(([, section]) => section).filter(section => section.scope === scope);
  useRegisterPageContext({
    ...context,
    markdown: [context.markdown, ...active.map(section => section.markdown)].filter(Boolean).join('\n\n'),
    isDraft: context.isDraft === true || active.some(section => section.isDraft),
    truncated: context.truncated === true || active.some(section => section.truncated)
  });
  return <PageSections.Provider value={registry}>{children}</PageSections.Provider>;
}

/** 在其他页面复用面板时不登记；切换分区后不混入上一分区的内容。 */
export function usePageContextSection(id: string, markdown: string, isDraft = false, truncated = false): void {
  const registry = useContext(PageSections);
  useEffect(() => {
    if (!registry) return;
    return registry.register(registry.scope, id, Symbol(id), { markdown, isDraft, truncated });
  }, [registry, id, markdown, isDraft, truncated]);
}

/** 只接收调用方逐项挑选的标量，禁止整份配置/请求对象被顺手序列化进快照。 */
export function pageContextRows(title: string, rows: ReadonlyArray<readonly [string, string | number | boolean | null | undefined]>): string {
  return [`## ${title}`, ...rows.filter(([, value]) => value !== undefined && value !== null)
    .map(([label, value]) => `- ${label}：${typeof value === 'boolean' ? value ? '开启' : '关闭' : value}`)].join('\n');
}
