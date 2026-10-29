# EX-10/11 能力包接口契约

新增标准 OpenAPI 3.1 源码补充，覆盖登记、解析、草稿、列表、详情五路径及实际请求/收据/错误；声明通过引用使用原冻结 schema。独立生成 capability-package.d.ts；原 live 网关生成器及生成物不变。现有契约门禁核对五路径存在实际 Controller、信封/状态与声明引用。

仅一轮，均 exit0：`node web/shared/generate-capability-api.mjs`、`node scripts/verify-contracts.mjs`、web 下 `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`。原始记录 `.local/ex10-contract/round1-20261030-01/`。未启动服务，不宣称 live OpenAPI 已重新生成。ROOT 完成一次非作者轻量检查，无阻断问题。

范围为标准契约、独立生成脚本/类型、原门禁追加和本记录；回滚对应本地提交，冻结 schema 与业务事实不变。真实服务重建后的 live 文档对照仍待，不以源码文档代替联合运行验收。
