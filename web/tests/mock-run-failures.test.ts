import { describe, expect, it } from 'vitest';
import { withRunFailureSummaries, withRunNodeInputs } from '../shared/mock-run-failures';

// F2-3：运行域演示补形契约——失败行 error/errorSemantic、详情节点 input/失败错误结构。
// 锁定 RunList 错误摘要列与 RunDetail 诊断锚点/检查器输入展示消费的形状（demo:true 标记）。

describe('mock-run-failures（F2-3 演示形状）', () => {
  it('仅 FAILED 列表行补 errorSemantic + error{reasonCode,message}，成功行原样透传', () => {
    const rows = [
      { runId: 'run-ok', status: 'SUCCEEDED', errorMessage: null },
      { runId: 'run-bad', status: 'FAILED', errorMessage: 'SKILL_TIMEOUT: telemetry_fetch 超过 5000ms' },
      { runId: 'run-bad-bare', status: 'FAILED' },
    ];
    const [ok, bad, bare] = withRunFailureSummaries(rows) as Array<Record<string, any>>;
    expect(ok.errorSemantic).toBeUndefined();
    expect(ok.error).toBeUndefined();
    expect(bad.demo).toBe(true);
    expect(bad.errorSemantic).toBe('SKILL_TIMEOUT');
    expect(bad.error).toEqual({ reasonCode: 'SKILL_TIMEOUT', message: 'telemetry_fetch 超过 5000ms' });
    // 无任何既有错误文本时如实给占位，不臆造原因。
    expect(bare.errorSemantic).toBe('RUN_FAILED');
    expect(bare.error.message).toBe('运行失败');
  });

  it('节点行按 appCode:nodeDefId 补 input；FAILED 节点补 errorSemantic/error，其余原样', () => {
    const nodes = [
      { nodeDefId: 'visibility_plan', status: 'FAILED', errorMessage: 'TLE_CHECKSUM: 观测星两行根数校验和不匹配' },
      { nodeDefId: 'respond', status: 'SUCCEEDED', duration: 180 },
    ];
    const [failed, plain] = withRunNodeInputs('space-visibility-agent', nodes) as Array<Record<string, any>>;
    expect(failed.demo).toBe(true);
    expect(failed.input).toEqual({ targets: ['SAT-01', 'SAT-A'], window: '2026-07-01 08:00-12:00 UTC', minElevationDeg: 10 });
    expect(failed.errorSemantic).toBe('TLE_CHECKSUM');
    expect(failed.error.message).toBe('观测星两行根数校验和不匹配');
    expect(plain.demo).toBeUndefined();
    expect(plain.input).toBeUndefined();
    // 非演示域节点不臆造输入。
    expect(withRunNodeInputs('other-app', [nodes[0]])[0].input).toBeUndefined();
  });

  it('非数组输入原样返回空数组，不抛错', () => {
    expect(withRunFailureSummaries(null)).toEqual([]);
    expect(withRunNodeInputs('app', undefined)).toEqual([]);
  });
});
