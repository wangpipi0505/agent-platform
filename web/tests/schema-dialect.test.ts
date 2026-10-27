import { describe, expect, it } from 'vitest';
import { analyzeSchema } from '../shared/schema-dialect';

/**
 * 技能向导的 Schema 方言提示：只做前端只读识别，不参与提交校验。
 * 路径一律用 JSON Pointer（根为 ''，属性名按 RFC 6901 转义）。
 */
describe('schema dialect recognition', () => {
  it('recognizes a type array containing null at the root and in properties', () => {
    const root = analyzeSchema('{"type":["string","null"]}');
    expect(root.ok).toBe(true);
    expect(root.nullable).toEqual(['']);
    expect(root.problems).toEqual([]);

    const nested = analyzeSchema('{"type":"object","properties":{"a":{"type":["object","null"]}}}');
    expect(nested.nullable).toEqual(['/properties/a']);
    expect(nested.problems).toEqual([]);
  });

  it('recognizes nullable:true and reports the property path', () => {
    const result = analyzeSchema('{"type":"object","properties":{"a":{"nullable":true}}}');
    expect(result.ok).toBe(true);
    expect(result.nullable).toEqual(['/properties/a']);
    expect(result.problems).toEqual([]);
  });

  it('flags a multi concrete type union and path', () => {
    const result = analyzeSchema('{"type":["string","object"]}');
    expect(result.ok).toBe(true);
    expect(result.nullable).toEqual([]);
    expect(result.problems).toHaveLength(1);
    expect(result.problems[0].path).toBe('/type');
    expect(result.problems[0].message).toContain('多具体类型');
  });

  it('flags remote $ref but accepts a document local reference', () => {
    const remote = analyzeSchema('{"$ref":"https://x/y.json"}');
    expect(remote.ok).toBe(true);
    expect(remote.problems).toHaveLength(1);
    expect(remote.problems[0].path).toBe('/$ref');
    expect(remote.problems[0].message).toContain('远程引用');

    const local = analyzeSchema('{"$ref":"#/$defs/Thing","$defs":{"Thing":{"type":["string","null"]}}}');
    expect(local.problems).toEqual([]);
    expect(local.nullable).toEqual(['/$defs/Thing']);
  });

  it('reports bad JSON as an unparsable document without guessing a schema', () => {
    const result = analyzeSchema('{');
    expect(result.ok).toBe(false);
    expect(result.nullable).toEqual([]);
    expect(result.problems).toHaveLength(1);
    expect(result.problems[0].message).toContain('JSON 解析失败');

    const blank = analyzeSchema('   ');
    expect(blank.ok).toBe(false);
    expect(blank.problems[0].message).toContain('JSON 解析失败');
  });

  it('keeps a plain object schema clean', () => {
    expect(analyzeSchema('{"type":"object"}')).toEqual({ ok: true, nullable: [], problems: [] });
  });
});

describe('schema dialect structural hints', () => {
  it('flags a non string/array type at the exact property path', () => {
    const result = analyzeSchema('{"properties":{"x":{"type":1}}}');
    expect(result.problems).toHaveLength(1);
    expect(result.problems[0].path).toBe('/properties/x/type');
    expect(result.problems[0].message).toContain('type 必须是字符串或字符串数组');
  });

  it('flags a type array holding a non string member once', () => {
    const result = analyzeSchema('{"type":["string",1,"null"]}');
    expect(result.problems).toHaveLength(1);
    expect(result.problems[0].path).toBe('/type');
    expect(result.problems[0].message).toContain('type 数组只能包含字符串');
    expect(result.nullable).toEqual(['']);
  });

  it('flags a non boolean nullable keyword', () => {
    const result = analyzeSchema('{"properties":{"a":{"nullable":"yes"}}}');
    expect(result.problems).toHaveLength(1);
    expect(result.problems[0].path).toBe('/properties/a/nullable');
    expect(result.problems[0].message).toContain('nullable 必须是 true/false');
  });

  it('flags a non object root and empty type arrays', () => {
    const arrayRoot = analyzeSchema('[]');
    expect(arrayRoot.ok).toBe(true);
    expect(arrayRoot.problems[0].message).toContain('根节点必须是 JSON 对象');

    const emptyType = analyzeSchema('{"type":[]}');
    expect(emptyType.problems[0].path).toBe('/type');
    expect(emptyType.problems[0].message).toContain('type 数组不能为空');
  });

  it('escapes JSON Pointer tokens and walks composition keywords', () => {
    const escaped = analyzeSchema('{"properties":{"a/b":{"type":["string","null"]}}}');
    expect(escaped.nullable).toEqual(['/properties/a~1b']);

    const composed = analyzeSchema('{"oneOf":[{"type":"string"},{"items":{"type":["integer","null"]}}]}');
    expect(composed.nullable).toEqual(['/oneOf/1/items']);
    expect(composed.problems).toEqual([]);
  });

  it('deduplicates a field that declares null in both dialects', () => {
    expect(analyzeSchema('{"type":["string","null"],"nullable":true}').nullable).toEqual(['']);
  });
});
