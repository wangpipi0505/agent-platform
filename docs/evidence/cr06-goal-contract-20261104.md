# CR-06 目标判据Schema与前后端联通

2026-11-04。仅本项必要功能检查，最多两轮；不访问PG、不启动外部业务链、不进行全量验收。CR-04已单独提交后开始本项。

唯一Schema为`contracts/semantic-runtime-goal-v1.schema.json`（goal@1.0），声明位于`#/$defs/declaration`，处置位于`#/$defs/outcome`。admin-common Maven资源直接映射contracts原文件；前端Ajv2020、发布与运行networknt均消费同一份定义，没有复制第二份Schema或引入依赖。契约注册与freeze摘要已登记。

- 表单、发布预检/提交、新运行受理与运行核验统一`{path,operator,value?}`。path必须`$`根，支持字段和完整零基数组下标；拒绝无根、负下标、前导零、尾部残留与自由表达式。equals要求标量value，另外两算子禁止value。
- 文本/数字/布尔/null分别保留，false与0不做真假转换；equals数值按十进制比较，exists把显式null视作存在而missing视作不存在，nonEmpty拒绝null/空白/空集合。`detail.readbackValue`保留显式null，missing不写该键。前端拒绝NaN/Infinity等非JSON值并返回独立冻结副本。
- GoalCriteriaStep增加对象、固定读回参数/对象字段、可选动作和期望值类型；仍通过submit事件交给原宿主。发布引用门要求读回引用本应用已启用只读且无副作用技能，动作也必须引用已启用技能。
- 无版本历史目标不改写，旧方言或缺绑定保守UNKNOWN，不伪装为goal@1.0摘要；新声明无法执行时发布/受理拒绝。CR-04的精确回执绑定/动作锚保持。

第1轮：

- `node node_modules/vitest/vitest.mjs run tests/semantic-runtime.test.ts`：27/27通过。
- 隔离目标组件/构造器/测试的vue-tsc：exit 0。
- 下述定向Maven：发布门2/2通过；engine新联通测试夹具`Map.of`泛型推断编译失败，engine测试尚未执行。修正为显式`Map<String,Object>`，未放宽断言。

第2轮（最终）：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
mvn -o -pl engine-runtime,app-management -am '-Dtest=GoalVerificationBindingTest,GoalCriterionRoundTripTest,AppPublicationGoalContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

exit 0，BUILD SUCCESS：AppPublicationGoalContractTest 2/2，GoalCriterionRoundTripTest 2/2，GoalVerificationBindingTest 6/6。联通测试从Node加载生产`semantic-runtime.ts`构造器，10种产物原样送入真实GoalVerificationService，覆盖三算子、数组、字符串/false/0/null、missing/空集合，并校验实际核验摘要符合完整goal Schema。发布门测试覆盖canonical接受、旧方言拒绝及读回引用必须为已启用只读技能。

```powershell
# cwd: web
node node_modules/vitest/vitest.mjs run tests/semantic-runtime.test.ts
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p D:/CodexPrep/algorithm-reorg/cr06-goals-20261104/goal-tsconfig.json
```

Vitest 1文件/30项通过；vue-tsc exit 0。隔离tsconfig继承工程web/tsconfig.json，只include共享构造器、GoalCriteriaStep.vue、semantic-runtime.test.ts与vite/client.d.ts，不改变正式tsconfig。

边界：JDBC/发布端口测试替身只提供调用事实/远端目录响应和SQL约束检验，不证明PG持久化、发布事务成功、真实动作并发或业务完成；组件页面挂载、真实API提交及视觉检查归CR-10；无全量测试或第三轮检查。
