/**
 * 表单里的 JSON 文本统一从这里解析：浏览器原生错误是英文且带位置，直接展示给用户
 * 既难懂也不符合界面语言；这里换成可操作的中文提示，同时保留字段名以便定位。
 */
export function parseJsonInput(text: string, label = 'JSON'): unknown {
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`${label} 格式不正确：请检查括号、引号与逗号是否成对，例如 {"target": "SAT-01"}。`)
  }
}
