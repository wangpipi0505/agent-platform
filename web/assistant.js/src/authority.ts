import { goalOutcomeRows, authoritativeView } from '../../shared/semantic-runtime';
import type { RunAuthority } from '../../shared/server-authority';
/** SDK复用Vue的受控结果解码；只用textContent，服务端字段/模型文本均不成为HTML。 */
export function renderAuthority(host: HTMLElement, view: RunAuthority) {
  host.querySelector('[data-run-authority]')?.remove();
  const section = document.createElement('section');
  section.dataset.runAuthority = 'true';
  section.setAttribute('aria-label', '服务端业务依据');
  const line = (text: string) => {
    const p = document.createElement('p');
    p.textContent = text;
    section.append(p);
  };
  for (const row of goalOutcomeRows(view.goals))
    line(`目标 ${row.goalId}：${row.label}${row.nextStep ? '；' + row.nextStep : ''}${row.issues.length ? '；' + row.issues.join('；') : ''}`);
  for (const receipt of view.receipts) {
    const result = authoritativeView(receipt.server);
    line(`语义读回 ${receipt.callId}：${result.state}；${result.note}；版本 ${receipt.release}；原始时效 ${receipt.observedAt}`);
  }
  if (view.error)
    line(view.error);
  if (!view.goals.length && !view.receipts.length)
    line('尚无可核验的业务回执；模型文本不作为业务完成依据。');
  host.append(section);
}
