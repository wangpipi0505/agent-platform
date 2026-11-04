# 提示词配置模板

体验修订中的 `promptRefs` 只登记**引用与版本**（`{ "ref": "...", "version": N }`），提示词正文与变量声明由平台提示词模板服务维护。本页给出命名与变量约定，便于宿主与平台对齐；不要在本文件或体验配置里写密钥、个人信息或任意脚本。

## 引用命名

| 用途 | 建议 ref | 说明 |
| --- | --- | --- |
| 主回答 | `prompt:<appCode>.answer` | 对话主提示词，变量最少化 |
| 跟进/写操作 | `prompt:<appCode>.<action>` | 写操作必须配合审批，不直接提交 |
| 摘要/归类 | `prompt:<appCode>.classify` | 只读、输出结构固定 |

`version` 必须是平台已发布的正整数版本；体验修订与提示词版本同版本号绑定，升级用新修订，不原地覆盖。

## 变量声明

在平台提示词模板中声明变量（名称、是否必填、说明）。推荐变量：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `pageContext` | 否 | 页面显式声明的业务上下文（标量，≤16 键） |
| `question` | 是 | 用户本轮问题 |
| `recordRefs` | 否 | 已由服务端授权的记录引用摘要（非原始数据） |
| `locale` | 否 | `zh-CN` 等，仅影响文案 |

约束：

- 变量值来自服务端已验证的输入或 pageContext，**不得**接受浏览器自报的 tenantId/userId/roles/permissions。
- 变量内容按纯文本处理；不拼接 HTML/脚本/URL 回调。
- 变量超长或超预算时拒绝生成，不放宽预算。

## 体验引用模板

```json
{
  "promptRefs": [
    { "ref": "prompt:example_app.answer", "version": 3 },
    { "ref": "prompt:example_app.followup", "version": 2 }
  ]
}
```

## 变更流程

1. 平台侧新建提示词版本（变量声明随版本冻结）。
2. 配置管理台复制体验为草稿，更新 `promptRefs` 的 `version`，走差异/权限检查、审批、CAS 激活。
3. 激活前复核引用仍有效；失效时返回 `CONFIG_VERSION_UNKNOWN` 或 `CONFIG_REF_NOT_FOUND`，不静默回退到旧版本。
4. 回退体验修订即可回退提示词版本；已撤销的权限不因回退恢复。
