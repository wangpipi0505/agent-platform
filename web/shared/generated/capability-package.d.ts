export interface paths {
    "/admin/capability-packages/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * 登记不可变能力包声明
         * @description EDITOR/ADMIN。同租户同 packageId/version/SHA 重试返回原记录；异 SHA 拒绝。重试可返回该版本现有的 RESOLVED 状态，但本收据仍固定 appCreated=false、applications=[]，不代表当前真实来源应用清单。
         */
        post: operations["registerCapabilityPackage"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/capability-packages/{id}/bindings/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * 解析选定环境和单一路线的精确技能绑定
         * @description EDITOR/ADMIN。全部远端只读校验完成后保存一份计划；同计划重试复用，异计划拒绝。RESOLVED 仅表示所选路线映射，不表示整包全部路线可用或已通过验证。
         */
        post: operations["resolveCapabilityPackageBindings"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/capability-packages/{id}/applications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * 从已解析来源创建真实可编辑应用草稿
         * @description EDITOR/ADMIN。完整配置由用户提供；不猜模型、环境或流程图。首次计划原子创建，同计划重试读取原应用当前草稿，异计划拒绝；不会覆盖后续编辑或复活已删除目标。响应有意不包含 softwareId、softwareVersion 或 bindingStatus。
         */
        post: operations["createCapabilityPackageApplications"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/capability-packages": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 读取当前租户的有界能力目录
         * @description VIEWER/EDITOR/ADMIN。按 id 倒序游标分页；仅接受这里列出的检索参数，未知参数拒绝。space/release 只匹配原声明引用，不代表本体授权或版本有效性。
         */
        get: operations["listCapabilityPackages"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/capability-packages/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * 读取包声明、选定映射与当前真实来源应用
         * @description VIEWER/EDITOR/ADMIN。跨租户与不存在统一返回 404。来源应用最多 100 条，超过时 applicationsTruncated=true；applicationCount 为真实关联总数，不从首次创建收据或声明推算。
         */
        get: operations["getCapabilityPackage"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        CapabilityPackageDeclaration: components["schemas"]["capability-package.schema"];
        CapabilityImportRequest: {
            /** @description 完整冻结声明；canonical UTF-8 最多 2 MiB，另受现有引用/凭据语义校验 */
            package: components["schemas"]["capability-package.schema"];
        };
        CapabilitySkillReference: {
            code: string;
            version: number;
            sha256?: components["schemas"]["Sha256"];
        };
        CapabilityResolveRequest: {
            /** @description 必须明确存在于原声明 software.environments */
            environment: string;
            /** @description 必须是原声明路线；没有 routeProfiles 时为 interface id */
            routeProfileId: string;
            /** @description 省略或 null 视为空数组；从选中工具原声明读取精确引用 */
            bindings?: {
                toolId: string;
                skillRef: components["schemas"]["CapabilitySkillReference"];
            }[] | null;
        };
        CapabilityApplicationsRequest: {
            applications: components["schemas"]["CapabilityApplicationPlan"][];
        };
        CapabilityApplicationPlan: {
            appCode: string;
            name: string;
            /** @enum {string} */
            type: "CHAT" | "FLOW";
            /** @description 必须等于已解析路线，不重新绑定 */
            routeProfileId: string;
            config: components["schemas"]["CapabilityDraftConfig"];
            /** @description FLOW 提供既有完整图格式；CHAT 只接受空对象 */
            definition?: Record<string, never>;
            layout?: Record<string, never>;
            description?: string;
            domain?: string;
            bizCategory?: string;
            tags?: string[];
        } & (unknown & unknown);
        /** @description 沿既有应用配置/引用/Schema 校验；完整模型和流程依赖由服务端真实读取，不由声明猜测。 */
        CapabilityDraftConfig: {
            modelBinding?: {
                /** Format: int64 */
                modelEntryId: number;
            };
        };
        Sha256: string;
        /** @enum {string} */
        AppStatus: "DRAFT" | "PUBLISHED" | "DISABLED" | "ARCHIVED";
        CapabilityImportReceipt: {
            /** Format: int64 */
            packageVersionId: number;
            packageId: string;
            version: string;
            sha256: components["schemas"]["Sha256"];
            softwareId: string;
            /** @description 原声明未提供时省略，不与包版本混淆 */
            softwareVersion?: string;
            reused: boolean;
            /** @enum {string} */
            bindingStatus: "UNRESOLVED" | "RESOLVED";
            /** @constant */
            verificationStatus: "NOT_EVALUATED";
            /** @constant */
            appCreated: false;
            applications: unknown[];
        };
        CapabilityResolvedBinding: {
            routeProfileId: string;
            toolId: string;
            interfaceId: string;
            skillCode: string;
            /** Format: int64 */
            skillVersionId: number;
            version: number;
            sha256: components["schemas"]["Sha256"];
        };
        CapabilityResolvedMapping: {
            environment: string;
            /** @description 原 software.environments 中声明值，仅保留不作为目标覆盖 */
            environmentDeclaration: string;
            routeProfileId: string;
            bindings: components["schemas"]["CapabilityResolvedBinding"][];
        };
        CapabilityResolveReceipt: {
            /** Format: int64 */
            packageVersionId: number;
            packageId: string;
            version: string;
            sha256: components["schemas"]["Sha256"];
            softwareId: string;
            softwareVersion?: string;
            environment: string;
            environmentDeclaration: string;
            routeProfileId: string;
            bindings: components["schemas"]["CapabilityResolvedBinding"][];
            /** @constant */
            bindingStatus: "RESOLVED";
            /** @constant */
            verificationStatus: "NOT_EVALUATED";
            /** @constant */
            appCreated: false;
            applications: unknown[];
            reused: boolean;
        };
        CapabilityApplicationReceipt: {
            /** Format: int64 */
            appId: number;
            appCode: string;
            /** Format: int64 */
            draftRev: number;
            status: components["schemas"]["AppStatus"];
        };
        CapabilityApplicationsReceipt: {
            /** Format: int64 */
            packageVersionId: number;
            packageId: string;
            version: string;
            sha256: components["schemas"]["Sha256"];
            applications: components["schemas"]["CapabilityApplicationReceipt"][];
            /** @constant */
            appCreated: true;
            reused: boolean;
            /** @constant */
            verificationStatus: "NOT_EVALUATED";
        };
        CapabilityPackageSummary: {
            /** Format: int64 */
            packageVersionId: number;
            packageId: string;
            version: string;
            sha256: components["schemas"]["Sha256"];
            softwareId: string;
            softwareVersion: string | null;
            /** @enum {string} */
            bindingStatus: "UNRESOLVED" | "RESOLVED";
            /** @constant */
            verificationStatus: "NOT_EVALUATED";
            /** Format: int64 */
            applicationCount: number;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            resolvedAt?: string;
        };
        CapabilityPackagePage: {
            items: components["schemas"]["CapabilityPackageSummary"][];
            /** Format: int64 */
            nextBeforeId?: number;
        };
        CapabilitySourceApplication: {
            /** Format: int64 */
            appId: number;
            appCode: string;
            name: string;
            /** @enum {string} */
            type: "CHAT" | "FLOW";
            status: components["schemas"]["AppStatus"];
            /** Format: int64 */
            draftRev: number | null;
            currentVersionNo: number | null;
        };
        CapabilityPackageDetail: components["schemas"]["CapabilityPackageSummary"] & {
            declaration: components["schemas"]["capability-package.schema"];
            resolvedMapping: Record<string, never> | components["schemas"]["CapabilityResolvedMapping"];
            applications: components["schemas"]["CapabilitySourceApplication"][];
            applicationsTruncated: boolean;
        };
        CapabilitySuccess: {
            /** @constant */
            code: "OK";
            /** @constant */
            message: "";
            data: unknown;
            traceId: string | null;
        };
        CapabilityImportResponse: components["schemas"]["CapabilitySuccess"] & {
            data: components["schemas"]["CapabilityImportReceipt"];
        };
        CapabilityResolveResponse: components["schemas"]["CapabilitySuccess"] & {
            data: components["schemas"]["CapabilityResolveReceipt"];
        };
        CapabilityApplicationsResponse: components["schemas"]["CapabilitySuccess"] & {
            data: components["schemas"]["CapabilityApplicationsReceipt"];
        };
        CapabilityListResponse: components["schemas"]["CapabilitySuccess"] & {
            data: components["schemas"]["CapabilityPackagePage"];
        };
        CapabilityDetailResponse: components["schemas"]["CapabilitySuccess"] & {
            data: components["schemas"]["CapabilityPackageDetail"];
        };
        CapabilityError: {
            /** @description 已知业务错误码见各操作 x-error-codes；此补充不代替 EX-22 全局认证/代理错误验收 */
            code: string;
            message: string;
            data: null;
            traceId: string | null;
            /** @description 安全的用户提示；旧响应可省略 */
            userMessage?: string;
            /** @enum {string} */
            category?: "AUTH" | "QUOTA" | "PARAM" | "TRANSPORT" | "SYSTEM" | "BUSINESS";
            /** @description API入口仅明确限流可重试；不代表工具副作用可重发 */
            retryable?: boolean;
        };
        /**
         * CapabilityPackage v1（能力包：软件接入的唯一声明入口）
         * @description 平台侧优化方案与实施细则-20261027 §11.2 能力包结构的 schema 冻结（T22A，PLAT-22 A 段：先冻结 schema 再实现）。本 schema 是声明文件契约，不是平台产品代码；能力包不绑定某个智能体应用，也不绑定某条路线，路线配置只是能力包的一种使用方式（routeProfiles）。冻结口径见 docs/evidence/2026-10-28-PLAT-22A-capability-schema/README.md；任何变更须走开发计划 §6.5 契约纪律（freeze.json digest 更新 + snapshot 同步 + 变更记录）。未实现的平台能力在本 schema 中以可扩展枚举/留白表达并注明实现归属卡（PROCESS 适配器归 T22C，contextPack 自动注入归 T22B，控制面/共享对象归 T22D），不得据此声称相应能力已上线。
         */
        "capability-package.schema": {
            /**
             * @description §11.2.3 示例口径 apiVersion: platform/v1。schema 演进 = 新 $id 版本（urn:agent-platform:capability-package:N.0），版本不可原地覆盖（§11.2.4）。
             * @constant
             */
            apiVersion: "platform/v1";
            /** @constant */
            kind: "CapabilityPackage";
            /** @description §11.2.2 metadata：name/domain/owner/version/description，name 全局唯一（校验脚本对 contracts/capability-packages/ 全集查重）。 */
            metadata: {
                /** @description 全局唯一名称，小写连字符。 */
                name: string;
                /** @description 业务域标识。 */
                domain: string;
                /** @description 责任团队（如 algorithm-team）。 */
                owner: string;
                /** @description 能力包自身版本（semver 三段）；发布后不可原地覆盖，演进走新版本。 */
                version: string;
                description: string;
                tags?: string[];
            };
            /** @description §11.2.2 software：软件系统、版本、环境、责任团队。 */
            software: {
                /** @description 软件系统标识。 */
                system: string;
                /** @description 软件系统版本/构建标识。 */
                version?: string;
                /** @description 软件侧责任团队（可与 metadata.owner 不同）。 */
                ownerTeam?: string;
                /** @description 环境名 → 服务基址；密钥不得出现在 URL（§11.2.4），校验脚本扫描明文凭据键。 */
                environments: {
                    [key: string]: string;
                };
                notes?: string;
            };
            /** @description §11.2.2 interfaces：至少一个执行入口。kind 枚举即适配器注册面：HTTP_SYNC/HTTP_ASYNC 由 skill-registry HttpAdapter/HttpAsyncAdapter 承载（路线一现状）；MCP/ALGORITHM 为 v1.2 既有适配器（McpAdapter/AlgoAdapter-K8s）；PROCESS 为一次性进程 stdin/stdout（协议 algorithm-process/1，PLAT-21 算法侧 worker 契约已有、平台侧适配器未注册），声明 PROCESS 属冻结留白，实现归 T22C（A-01 适配器注册表），校验脚本对 PROCESS 出告警诊断。未列枚举值 = 未来新执行方式必须先修订本 schema（§6.5 契约纪律），不允许 schema 外自由扩展。 */
            interfaces: ({
                /** @description 包内唯一；tools[].binding 与 routeProfiles[].execution 引用它。 */
                id: string;
                /**
                 * @description 执行方式枚举（§11.2.2）。PROCESS 留白归 T22C。
                 * @enum {unknown}
                 */
                kind: "HTTP_SYNC" | "HTTP_ASYNC" | "PROCESS" | "MCP" | "ALGORITHM";
                description?: string;
                /** @description HTTP_SYNC/HTTP_ASYNC 必填；完整 URL，无明文凭据。 */
                endpoint?: string;
                /**
                 * @description HTTP 入口方法；PREPARE 语义（PLAT-15 §14）只允许 POST。
                 * @enum {unknown}
                 */
                httpMethod?: "GET" | "POST";
                /** @description 单次尝试超时（不含对账）。 */
                timeoutMs?: number;
                /** @description 凭据引用（密文存取，skill 契约 authRef 同口径）；禁止明文。 */
                authRef?: string;
                /** @description 接口契约文档/机器合同引用（如 OpenAPI 路径）。 */
                contractRef?: string;
                /** @description HTTP_ASYNC 对账配置——字段与 contracts/skill-invocation.md §12（PLAT-06 冻结形态）逐字段对齐，避免声明层与技能契约两层词汇漂移。 */
                reconciliation?: {
                    /** @description 状态查询端点，须字面含 {jobId}/{idempotencyKey} 占位符或与 pathParams 一一对应（§12.1）。 */
                    endpoint: string;
                    /** @enum {unknown} */
                    method?: "GET" | "POST";
                    /** @description 任务标识读取路径（§12.2 双源优先级：reconciliation.jobIdPath 优先）。 */
                    jobIdPath?: string;
                    idempotencyKeyPath?: string;
                    donePath?: string;
                    doneValue?: string;
                    failedValue?: string;
                    failedValues?: string[];
                    failureMessagePath?: string;
                    resultEndpoint?: string;
                    resultEndpointPath?: string;
                    idempotencyKeyEcho?: boolean;
                    lookupEndpoint?: string;
                    /** @description 声明式占位符映射：键=端点占位符名，值 {from} 只允许 externalJobId/idempotencyKey（§12.1）。 */
                    pathParams?: {
                        [key: string]: {
                            /** @enum {unknown} */
                            from: "externalJobId" | "idempotencyKey";
                        };
                    };
                };
                /** @description PROCESS 必填：一次性进程命令（§11.2.3 示例形态）。留白字段：适配器实现归 T22C。 */
                command?: string[];
                /** @description PROCESS 协议名；当前冻结值 algorithm-process/1（PLAT-21c）。 */
                protocol?: string;
                /** @description PROCESS 每调用工作目录。 */
                workDir?: string;
                /** @description MCP 必填：MCP 服务地址/manifest 引用。 */
                serverRef?: string;
                /** @description ALGORITHM 必填：K8s Job 任务模板编码（v1.2 技能契约 adapterType=ALGORITHM 同口径）。 */
                jobTemplate?: string;
                notes?: string;
            } & (unknown & unknown & unknown & unknown & unknown))[];
            /** @description §11.2.2 ontology（否）：mode=NONE/REFERENCE/OWNED；引用时给 space/release/contextPack/guards。§11.2.4：mode=NONE 时不得声明 contextPack（脚本语义校验强制）。本体发布解析（READY 校验）、工具投影/consumer 注册/contextPack 自动注入归 T22B（O-02/O-03/O-05/O-07、A-03），本 schema 只冻结声明形态。 */
            ontology?: {
                /** @enum {unknown} */
                mode: "NONE" | "REFERENCE" | "OWNED";
                /** @description REFERENCE/OWNED 必填：本体空间。 */
                space?: string;
                /** @description REFERENCE/OWNED 必填：本体发布标识（须可解析到 READY 发布——发布态校验归 T22B）。 */
                release?: string;
                /** @description context pack 引用（如 auto 或摘要标识）；mode=NONE 禁止声明。 */
                contextPack?: string;
                guards?: string[];
                notes?: string;
            } & unknown;
            /** @description §11.2.2 tools：对外暴露的操作、schema、读写属性、分页、错误语义、绑定到哪个 interface。exposure 口径沿 §11.2.3 示例（READ_ONLY/WRITE_WITH_APPROVAL）；写操作确认沿 PLAT-15 §14（confirmRoles、riskLevel）。跨字段规则（§11.2.4）由校验脚本执行：binding 必须指向本包 interface；只读与副作用互斥。 */
            tools: ({
                /** @description 包内唯一工具标识；routeProfiles[].tools 与 agents[].skillRefs[].tool 引用它。 */
                id: string;
                /** @description 远端操作名（本体 operation 投影或接口操作）。 */
                operation?: string;
                description?: string;
                /** @description 必须指向本包 interfaces[].id（§11.2.4，脚本校验）。 */
                binding: string;
                /**
                 * @description §11.2.3 示例口径：只读 / 需确认写。
                 * @enum {unknown}
                 */
                exposure: "READ_ONLY" | "WRITE_WITH_APPROVAL";
                /** @description WRITE_WITH_APPROVAL 必填：确认角色（引擎 WAITING_APPROVAL 角色来源，PLAT-15 口径）。 */
                confirmRoles?: string[];
                /**
                 * @description PLAT-15 §14.1：高危写以声明为准，平台不从 URL/方法猜测。
                 * @enum {unknown}
                 */
                riskLevel?: "STANDARD" | "HIGH";
                /** @description 技能契约 readonly_flag 同口径；只读与 WRITE_WITH_APPROVAL 互斥。 */
                readonly?: boolean;
                sideEffect?: boolean;
                idempotent?: boolean;
                /**
                 * @description §11.2.2 分页语义（传输中立，O-03）。
                 * @enum {unknown}
                 */
                pagination?: "none" | "offset" | "cursor" | "page";
                /** @description 产物引用名（§11.2.3 示例 artifacts）。 */
                artifacts?: string[];
                /** @description JSON Schema Draft 2020-12 透传（v1.2 技能契约 input_schema 同口径）；本 schema 不深校验其内部。 */
                inputSchema?: Record<string, never>;
                /** @description 输出结构断言 schema 透传。 */
                outputSchema?: Record<string, never>;
                /** @description 错误语义声明，兼容两形态：v1.2 数组形态 [{match,semantic}] 或 PLAT-05 §11.2 对象形态 {codePath,messagePath,retryableCodes}。 */
                errorSemantics?: {
                    match: string;
                    semantic: string;
                }[] | {
                    codePath?: string;
                    messagePath?: string;
                    retryableCodes?: string[];
                };
                /** @description 平台侧落点引用：本工具当前由技能中心哪个已发布技能版本承载（钉版本 + 契约 sha256）。能力包声明不改变技能中心任何行为。 */
                skillRef?: {
                    code: string;
                    version: number;
                    /** @description 技能版本契约摘要（前缀必须为完整 64 位）。 */
                    sha256?: string;
                };
                /**
                 * @description 停用的工具不对外暴露（如因输出预算停用的目录查询技能）；false 时须在 notes 写明停用依据。
                 * @default true
                 */
                enabled: boolean;
                notes?: string;
            } & unknown)[];
            /** @description §11.2.2 identity：平台主体到软件/本体消费身份的映射、scope、密钥引用（不写明文）。outboundIdentity 口径沿 PLAT-12 §13（mode 只允许 PLATFORM_SUBJECT，headers 值只允许主体快照白名单）；统一主体映射（平台用户→agent principal→ontology consumer 一张映射）归 T22B/T22D（O-07、A-06），本 schema 只冻结声明形态。 */
            identity: {
                /** @description 主体映射描述（如 platform_user -> ontology_consumer）。 */
                subjectMapping: string;
                /** @description scope 声明（如 dept/attr scope，路线三口径）。 */
                scopes?: string[];
                /** @description 平台侧守卫声明快照（应用/技能守卫角色与属性）。 */
                guard?: {
                    roles?: string[];
                    attributes?: {
                        [key: string]: string[];
                    };
                };
                /** @description 出站身份注入（PLAT-12 §13 冻结形态）：mode 只允许 PLATFORM_SUBJECT；headers 值只允许主体快照字段白名单。 */
                outboundIdentity?: {
                    /** @constant */
                    mode: "PLATFORM_SUBJECT";
                    headers?: {
                        [key: string]: "principalId" | "tenantId" | "roles" | "attributes";
                    };
                };
                /** @description 密钥引用清单；只允许 secret:// 或 cred:// 引用，禁止明文（§11.2.4，脚本扫描强制）。 */
                secrets?: {
                    ref: string;
                    purpose: string;
                }[];
                notes?: string;
            };
            /** @description §11.2.2 agents（否）：推荐的应用/技能/提示词模板；能力包本身不强制绑定某个 app。提示词走引用不内联长文（§11.4 第 6 步）；应用组装自动化归 T22B/T22D（A-04）。 */
            agents?: {
                code: string;
                name?: string;
                /**
                 * @description 应用类型（图定义 trigger 口径 CHAT/TASK；FLOW 为流程编排）。
                 * @enum {unknown}
                 */
                type: "CHAT" | "TASK" | "FLOW";
                skillRefs: {
                    /** @description 必须指向本包 tools[].id（脚本校验）。 */
                    tool: string;
                    skillCode: string;
                    version: number;
                    sha256?: string;
                }[];
                modelBinding?: {
                    modelEntryId?: number;
                    /** @description 模型条目名（如 deepseek-flash）；模型可替换（§11.4 第 6 步）。 */
                    entry?: string;
                };
                guard?: {
                    roles?: string[];
                    attributes?: {
                        [key: string]: string[];
                    };
                };
                /** @description 提示词出处引用（配置快照/摘要），不内联业务长文。 */
                promptRef?: string;
                requiredLabels?: string[];
                notes?: string;
            }[];
            /** @description §11.2.2 verification：连接、契约、模型、评测、安全、并发隔离六类门禁的配置。§11.2.4：不允许全部关闭；关闭项必须走豁免并留痕（脚本强制：每个 false 门禁须有对应 exemptions 条目）。分层验证流水线归 T22D 前的 A-05，本 schema 只冻结声明形态。 */
            verification: {
                /** @description 连接门：端点真实可达（连接测试非 mock）。 */
                connection: boolean;
                /** @description 契约门：schema 校验/契约收敛通过。 */
                contract: boolean;
                /** @description 模型门：选型验收命中率门禁（≥0.8）。 */
                model: boolean;
                /** @description 评测门：评测断言通过。 */
                evaluation: boolean;
                /** @description 安全门：守卫声明/确认流程/出域检查。 */
                safety: boolean;
                /** @description 并发隔离门：并发上限/隔离用例实测。 */
                concurrency: boolean;
                /** @description 豁免留痕：关闭的门禁逐条登记理由与期限（§11.2.4）。 */
                exemptions?: {
                    /** @enum {unknown} */
                    gate: "connection" | "contract" | "model" | "evaluation" | "safety" | "concurrency";
                    reason: string;
                    /** @description 豁免期限/解除条件。 */
                    until?: string;
                }[];
                notes?: string;
            };
            /** @description §11.2.2 operations：SLO、日志/产物保留、告警接收方、成本归属。无实测数据时不得编造数值——用 notes 如实登记并指明登记归属卡；按能力包/路线/工具/本体的运行归因与成本观测归 T22D（A-07、O-08）。 */
            operations: {
                slo?: {
                    p95Ms?: number;
                    errorRate?: number;
                    notes?: string;
                };
                retention?: {
                    logsDays?: number;
                    artifactsDays?: number;
                    notes?: string;
                };
                alerting?: {
                    receivers?: string[];
                    notes?: string;
                };
                costOwner?: string;
                notes?: string;
            };
            /** @description §11.2.2 routeProfiles（否）：预置的路线组合；不填时由平台按 interface/ontology 自动生成默认组合。RouteProfile 是能力包的一次绑定（§11.3），只允许引用本包 interface/tool/ontology（§11.2.4，脚本校验）。路线一/二/三不是平台分类，是这里的配置实例。 */
            routeProfiles?: {
                /** @description 路线配置标识；§11.3 口径如 http-sync/http-async/local-process/mcp/k8s-algorithm，可加本体横切（ontology-constrained/hybrid）。 */
                id: string;
                /** @description 执行入口 = 本包 interfaces[].id（脚本校验）。 */
                execution: string;
                /** @description "none" 或 "<space>@<release>"（须与 ontology 区块一致，脚本校验）。 */
                ontology?: string;
                /** @description 本路线暴露的工具 = 本包 tools[].id（脚本校验）。 */
                tools?: string[];
                /** @description 身份组合描述（如 dept-scoped/service-account）。 */
                identity?: string;
                notes?: string;
            }[];
            /** @description 留白登记：本能力包依赖但平台尚未实现/未覆盖的能力，逐条注明归属卡。这是 §11.2 冻结时「先声明、后实现」的显式留白（T22A 口径），不得视为已上线能力。 */
            implementationGaps?: {
                area: string;
                description: string;
                /** @description 实现归属卡（如 T22B/T22C/T22D/T14.1）。 */
                ownerCard: string;
            }[];
        };
        /** Graph definition v1.0 */
        "graph-definition.schema": {
            /** @constant */
            definitionVersion: "1.0";
            /** @enum {unknown} */
            trigger: "TASK" | "CHAT";
            entry: string;
            inputs?: {
                name: string;
                /** @enum {unknown} */
                type: "STRING" | "NUMBER" | "BOOLEAN" | "OBJECT" | "ARRAY" | "NULL";
                required?: boolean;
            }[];
            outputs?: {
                name: string;
                from: string;
            }[];
            nodes: {
                id: string;
                /** @enum {unknown} */
                type: "START" | "END" | "LLM" | "SKILL" | "CONDITION" | "CODE" | "RETRIEVE" | "CONFIRM" | "AGENT" | "ONTOLOGY";
                name?: string;
                skillRef?: {
                    code: string;
                    version: number;
                    skillVersionId?: number;
                };
                args?: Record<string, never>;
                output?: Record<string, never>;
                expression?: string;
                confirmRoles?: string[];
                branches?: (unknown | unknown)[];
                errorPolicy?: {
                    /** @enum {unknown} */
                    onFail?: "TERMINATE" | "ERROR_EDGE" | "DEFAULT_VALUE";
                    retries?: number;
                    timeoutMs?: number;
                    errorEdgeTo?: string;
                    defaultValue?: unknown;
                };
            }[];
            edges: {
                from: string;
                to: string;
                when?: string;
            }[];
            joins: {
                nodeId: string;
                /** @enum {unknown} */
                semantics: "ALL" | "ANY";
                /** @enum {unknown} */
                skipCountsAs?: "PASS" | "BLOCK";
                allowSharedUpstream?: boolean;
            }[];
            executionRules: {
                parallel: boolean;
                maxParallel: number;
                /** @constant */
                cyclesForbidden: true;
            };
        };
    };
    responses: {
        /** @description 业务输入、声明或配置校验失败；具体已知错误码见 operation.x-error-codes */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["CapabilityError"];
            };
        };
        /** @description 没有有效 Actor 身份 */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["CapabilityError"];
            };
        };
        /** @description 没有当前操作所需管理角色 */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["CapabilityError"];
            };
        };
        /** @description 租户内包或精确技能版本不存在 */
        NotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["CapabilityError"];
            };
        };
        /** @description 声明、解析、来源、应用计划、发布资格或并发冲突；不覆盖现有记录 */
        Conflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["CapabilityError"];
            };
        };
        /** @description 真实技能/模型/流程校验依赖不可用或回复不完整 */
        DependencyUnavailable: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["CapabilityError"];
            };
        };
    };
    parameters: {
        PackageVersionId: number;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    registerCapabilityPackage: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CapabilityImportRequest"];
            };
        };
        responses: {
            /** @description 首次登记或同声明重试 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CapabilityImportResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
        };
    };
    resolveCapabilityPackageBindings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["PackageVersionId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CapabilityResolveRequest"];
            };
        };
        responses: {
            /** @description 精确绑定收据 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CapabilityResolveResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            502: components["responses"]["DependencyUnavailable"];
        };
    };
    createCapabilityPackageApplications: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["PackageVersionId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CapabilityApplicationsRequest"];
            };
        };
        responses: {
            /** @description 应用当前草稿收据；首次 DRAFT，重试可反映之后的应用状态 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CapabilityApplicationsResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            502: components["responses"]["DependencyUnavailable"];
        };
    };
    listCapabilityPackages: {
        parameters: {
            query?: {
                limit?: number;
                /** @description 上一页 nextBeforeId；不含该行 */
                beforeId?: number;
                /** @description packageId/softwareId 字面子串，不将 % 或 _ 当通配符 */
                q?: string;
                ontologySpace?: string;
                ontologyRelease?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 最多 limit 条；仅有下一页时提供 nextBeforeId */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CapabilityListResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    getCapabilityPackage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["PackageVersionId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 当前租户能力包详情 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CapabilityDetailResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
        };
    };
}
