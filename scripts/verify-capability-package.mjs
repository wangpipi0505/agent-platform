#!/usr/bin/env node
/**
 * 能力包声明校验（T22A，PLAT-22 A 段；构建期工具，零依赖，不是平台产品代码）。
 *
 * 依据：平台侧优化方案与实施细则-20261027 §11.2（能力包结构与校验规则）、§11.9 A 段
 * （先冻结 schema 再实现）。对 contracts/capability-packages/*.json 逐个做两层校验：
 *
 * 1. schema 层：contracts/capability-package.schema.json（JSON Schema 2020-12 子集）。
 *    仓内无 JSON Schema 运行时依赖（web 侧无 ajv，后端 networknt 不跨运行时），
 *    这里内置一个覆盖本 schema 所用关键字的最小校验器：type/const/enum/required/
 *    properties/additionalProperties/propertyNames/items/min-max 类约束/pattern/allOf/
 *    anyOf/oneOf/if-then-else。schema 演进引入新关键字时必须同步扩展本表（§6.5 契约纪律）。
 * 2. 语义层（§11.2.4，schema 表达不了的跨字段规则）：
 *    - tools[].binding 必须指向本包 interfaces[].id（工具不与执行入口脱节）；
 *    - 只读与副作用互斥（v1.2 技能契约同口径）；WRITE_WITH_APPROVAL 必须带确认角色；
 *    - interfaces[].kind 必须是已注册适配器（T22D 起与 T22C 的 AdapterRegistry 五单元
 *      对齐：HTTP_SYNC/HTTP_ASYNC/MCP/ALGORITHM/PROCESS），未注册 kind 按 §11.2.4 给
 *      "需要新增适配器"的明确诊断（告警，指向归属卡）；
 *    - ontology.mode=NONE 时不得声明 contextPack（不得偷偷注入）；
 *    - verification 不允许全部关闭；关闭项必须有 exemptions 豁免留痕；
 *    - identity 禁止明文凭据（只允许 secret://、cred:// 引用）；
 *    - routeProfiles/agents 只能引用本包 interface/tool，且停用工具不得被引用；
 *    - metadata.name 在本次校验的全集内全局唯一。
 *
 * 有意边界：
 * - 本脚本只做声明文件校验，不触网、不校验本体发布 READY、不校验密钥系统——
 *   这些是运行期/平台侧能力（T22B/T22D），本卡不写平台产品代码。
 * - 未接入 verify-contracts.mjs（避免改变既有门禁行为）；门禁接入决策归 T22D。
 *   在接入前，schema sha256 以 contracts/freeze.json engineeringAddenda 登记为冻结记录。
 *
 * 用法：
 *   node scripts/verify-capability-package.mjs                 # 校验 contracts/capability-packages/ 全集
 *   node scripts/verify-capability-package.mjs --file <path>   # 只校验指定文件（可重复；负向用例/夹具）
 *
 * 退出码：0 = 全部通过（含告警）；1 = 存在错误（含 JSON 语法错误、schema 自身解析失败）。
 */
import {readFile, readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const SCHEMA_FILE = path.join(ROOT, 'contracts/capability-package.schema.json');
const PACKAGE_DIR = path.join(ROOT, 'contracts/capability-packages');

/**
 * 已注册适配器面（现状，A-01；T22D 与 T22C 后的运行层注册表对齐：
 * skill-registry AdapterRegistry 五单元 HTTP_SYNC/HTTP_ASYNC/MCP/ALGORITHM/PROCESS 全注册）。
 * 声明层此表只做"kind 是否需要新增适配器"的诊断（§11.2.4），不是运行层白名单——
 * 运行层注册表（AdapterRegistry）才是唯一分发来源；两者由 §11.2.4 口径保持一致，
 * 新增 kind 时必须同步改动此处与 AdapterRegistry（声明层留白告警指向归属卡）。
 */
const REGISTERED_ADAPTERS = new Map([
    ['HTTP_SYNC', 'HttpSyncExecutionAdapter（HTTP×SYNC）'],
    ['HTTP_ASYNC', 'HttpAsyncExecutionAdapter（HTTP×SUBMIT_QUERY/CALLBACK 对账）'],
    ['MCP', 'McpExecutionAdapter（MCP×SYNC，v1.2 既有）'],
    ['ALGORITHM', 'AlgorithmExecutionAdapter（SUBMIT_QUERY，K8s Job，v1.2 既有）'],
    ['PROCESS', 'ProcessExecutionAdapter（PROCESS×SYNC，algorithm-process/1，T21a–d + T22C）'],
]);
const PENDING_ADAPTER_OWNER = '下一张适配器卡（五类 kind 已全注册；新 kind 先扩 AdapterRegistry 运行层，再同步解本表留白）';

/** 身份区块里禁止出现明文的键名模式；命中且值不是密钥引用 → 错误。 */
const PLAINTEXT_CREDENTIAL_KEY = /pass(word)?|secret|token|api_?key|credential/i;
const SECRET_REF_PATTERN = /^(secret|cred):\/\//;

// ---------------------------------------------------------------- 最小 JSON Schema 校验器

function typeOf(value) {
    if (value === null) return 'null';
    if (Array.isArray(value)) return 'array';
    if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
    return typeof value; // string / boolean / object / undefined
}

/** instance 的类型是否满足 schema 的 type 声明（string 或数组）。 */
function typeMatches(value, expected) {
    const actual = typeOf(value);
    const list = Array.isArray(expected) ? expected : [expected];
    if (actual === 'integer' && list.includes('number')) return true;
    return list.includes(actual);
}

/**
 * 校验 value 是否满足 schema 节点；错误推入 errors（{path, message}）。
 * 返回 true = 本节点无错。布尔值是合法 schema（true = 任意通过，false = 任意失败）。
 */
function validateNode(value, schema, path, errors) {
    if (schema === true || schema === undefined) return true;
    if (schema === false) {
        errors.push({path, message: '不允许出现该值'});
        return false;
    }
    if (typeof schema !== 'object' || Array.isArray(schema)) return true;

    let ok = true;
    const fail = (message) => {
        errors.push({path, message});
        ok = false;
    };

    if ('type' in schema && !typeMatches(value, schema.type)) {
        fail(`类型应为 ${Array.isArray(schema.type) ? schema.type.join('|') : schema.type}，实际 ${typeOf(value)}`);
    }
    if ('const' in schema && JSON.stringify(value) !== JSON.stringify(schema.const)) {
        fail(`应为常量 ${JSON.stringify(schema.const)}，实际 ${JSON.stringify(value)}`);
    }
    if ('enum' in schema && !schema.enum.some((item) => JSON.stringify(item) === JSON.stringify(value))) {
        fail(`值 ${JSON.stringify(value)} 不在枚举 [${schema.enum.map((item) => JSON.stringify(item)).join(', ')}] 内`);
    }
    if (typeof value === 'string') {
        if ('minLength' in schema && value.length < schema.minLength) fail(`长度 ${value.length} 小于 minLength=${schema.minLength}`);
        if ('maxLength' in schema && value.length > schema.maxLength) fail(`长度 ${value.length} 超过 maxLength=${schema.maxLength}`);
        if ('pattern' in schema && !new RegExp(schema.pattern).test(value)) fail(`不匹配模式 ${schema.pattern}（收到 ${JSON.stringify(value.length > 80 ? value.slice(0, 77) + '…' : value)}）`);
    }
    if (typeof value === 'number') {
        if ('minimum' in schema && value < schema.minimum) fail(`值 ${value} 小于 minimum=${schema.minimum}`);
        if ('maximum' in schema && value > schema.maximum) fail(`值 ${value} 超过 maximum=${schema.maximum}`);
    }
    if (Array.isArray(value)) {
        if ('minItems' in schema && value.length < schema.minItems) fail(`元素数 ${value.length} 小于 minItems=${schema.minItems}`);
        if ('maxItems' in schema && value.length > schema.maxItems) fail(`元素数 ${value.length} 超过 maxItems=${schema.maxItems}`);
        if (schema.items) {
            value.forEach((item, index) => {
                if (!validateNode(item, schema.items, `${path}[${index}]`, errors)) ok = false;
            });
        }
    }
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        const keys = Object.keys(value);
        if ('minProperties' in schema && keys.length < schema.minProperties) fail(`键数 ${keys.length} 小于 minProperties=${schema.minProperties}`);
        for (const key of schema.required || []) {
            if (!(key in value)) fail(`缺少必需字段 ${key}`);
        }
        if (schema.propertyNames) {
            for (const key of keys) {
                const sub = [];
                validateNode(key, schema.propertyNames, `${path}.<键>${key}`, sub);
                for (const error of sub) error.message = `键名非法：${error.message}`;
                errors.push(...sub);
                if (sub.length) ok = false;
            }
        }
        for (const key of keys) {
            const propSchema = schema.properties?.[key];
            if (propSchema !== undefined) {
                if (!validateNode(value[key], propSchema, `${path}.${key}`, errors)) ok = false;
            } else if (schema.additionalProperties === false) {
                fail(`未知字段 ${key}（additionalProperties=false，schema 冻结不允许自由扩展）`);
            } else if (typeof schema.additionalProperties === 'object') {
                if (!validateNode(value[key], schema.additionalProperties, `${path}.${key}`, errors)) ok = false;
            }
        }
    }

    for (const sub of schema.allOf || []) {
        if (!validateNode(value, sub, path, errors)) ok = false;
    }
    if (schema.anyOf) {
        const probe = [];
        const matched = schema.anyOf.filter((branch) => {
            probe.length = 0;
            return validateNode(value, branch, path, probe);
        });
        if (matched.length === 0) fail('不满足 anyOf 任一分支');
    }
    if (schema.oneOf) {
        const probe = [];
        const matched = schema.oneOf.filter((branch) => {
            probe.length = 0;
            return validateNode(value, branch, path, probe);
        });
        if (matched.length !== 1) fail(`应恰好满足 oneOf 一个分支，实际 ${matched.length} 个`);
    }
    if (schema.if) {
        const probe = [];
        const matched = validateNode(value, schema.if, path, probe);
        if (matched && schema.then) validateNode(value, schema.then, path, errors);
        if (!matched && schema.else) validateNode(value, schema.else, path, errors);
    }
    return ok;
}

// ---------------------------------------------------------------- 语义层（§11.2.4）

function collectPlaintextCredentials(node, path, errors) {
    if (node === null || typeof node !== 'object') return;
    for (const [key, value] of Object.entries(node)) {
        const childPath = `${path}.${key}`;
        if (typeof value === 'string' && PLAINTEXT_CREDENTIAL_KEY.test(key) && !SECRET_REF_PATTERN.test(value)) {
            errors.push({path: childPath, message: `疑似明文凭据：键 ${key} 的值不是 secret:// 或 cred:// 密钥引用（§11.2.4 禁止明文）`});
        } else if (value !== null && typeof value === 'object') {
            collectPlaintextCredentials(value, childPath, errors);
        }
    }
}

function checkSemantics(pkg, label, errors, warnings) {
    const interfaceIds = new Set((pkg.interfaces || []).map((item) => item.id));
    const toolIds = new Set((pkg.tools || []).map((item) => item.id));
    const enabledToolIds = new Set((pkg.tools || []).filter((item) => item.enabled !== false).map((item) => item.id));

    for (const [index, item] of (pkg.interfaces || []).entries()) {
        if (!REGISTERED_ADAPTERS.has(item.kind)) {
            warnings.push({
                path: `interfaces[${index}].kind`,
                message: `kind=${item.kind}：适配器未注册（现状注册面：${[...REGISTERED_ADAPTERS.keys()].join('/')}）。需要新增适配器——实现归 ${PENDING_ADAPTER_OWNER}；本声明属冻结留白，不构成可执行接入`,
            });
        }
    }

    const seenToolIds = new Set();
    for (const [index, tool] of (pkg.tools || []).entries()) {
        const toolPath = `tools[${index}]`;
        if (seenToolIds.has(tool.id)) errors.push({path: `${toolPath}.id`, message: `工具 id ${tool.id} 重复（包内必须唯一）`});
        seenToolIds.add(tool.id);
        if (tool.binding !== undefined && !interfaceIds.has(tool.binding)) {
            errors.push({
                path: `${toolPath}.binding`,
                message: `绑定不可解析：${JSON.stringify(tool.binding)} 不是本包 interfaces 已声明的执行入口（可选：${[...interfaceIds].join(', ') || '无'}）——工具不与执行入口脱节（§11.2.4）`,
            });
        }
        if (tool.exposure === 'WRITE_WITH_APPROVAL') {
            if (!Array.isArray(tool.confirmRoles) || tool.confirmRoles.length === 0) {
                errors.push({path: `${toolPath}.confirmRoles`, message: 'WRITE_WITH_APPROVAL 工具必须声明非空确认角色（PLAT-15 口径）'});
            }
            if (tool.readonly) {
                errors.push({path: `${toolPath}.readonly`, message: '只读与副作用互斥：readonly=true 的工具不得声明 WRITE_WITH_APPROVAL（v1.2 技能契约同口径）'});
            }
        }
    }

    const ontology = pkg.ontology;
    if (ontology && ontology.mode === 'NONE' && ontology.contextPack !== undefined) {
        errors.push({path: 'ontology.contextPack', message: 'ontology.mode=NONE 时不得声明 contextPack（§11.2.4：NONE 时不得偷偷注入 contextPack）'});
    }

    const verification = pkg.verification || {};
    const gates = ['connection', 'contract', 'model', 'evaluation', 'safety', 'concurrency'];
    const closed = gates.filter((gate) => verification[gate] === false);
    if (closed.length === gates.length) {
        errors.push({path: 'verification', message: '六类门禁不允许全部关闭（§11.2.4）'});
    }
    const exempted = new Set((verification.exemptions || []).map((item) => item.gate));
    for (const gate of closed) {
        if (!exempted.has(gate)) {
            errors.push({path: `verification.${gate}`, message: `门禁关闭（false）但未登记豁免：须在 verification.exemptions 为 ${gate} 补充 {gate, reason} 豁免留痕（§11.2.4）`});
        }
    }
    for (const [index, exemption] of (verification.exemptions || []).entries()) {
        if (verification[exemption.gate] !== false) {
            warnings.push({path: `verification.exemptions[${index}].gate`, message: `豁免登记的门禁 ${exemption.gate} 实际为开启状态，该豁免无效果`});
        }
    }

    collectPlaintextCredentials(pkg.identity || {}, 'identity', errors);

    for (const [index, profile] of (pkg.routeProfiles || []).entries()) {
        const profilePath = `routeProfiles[${index}]`;
        if (!interfaceIds.has(profile.execution)) {
            errors.push({path: `${profilePath}.execution`, message: `执行方式引用不可解析：${JSON.stringify(profile.execution)} 不是本包 interfaces 已声明的执行入口（可选：${[...interfaceIds].join(', ') || '无'}）——路线组合只能引用本包对象（§11.2.4）`});
        }
        for (const [toolIndex, toolId] of (profile.tools || []).entries()) {
            if (!toolIds.has(toolId)) {
                errors.push({path: `${profilePath}.tools[${toolIndex}]`, message: `工具引用不可解析：${JSON.stringify(toolId)} 不在本包 tools 内（路线组合只能引用本包对象，§11.2.4）`});
            } else if (!enabledToolIds.has(toolId)) {
                errors.push({path: `${profilePath}.tools[${toolIndex}]`, message: `工具 ${toolId} 已停用（enabled=false），不得出现在路线组合`});
            }
        }
        const ontologyRef = profile.ontology;
        if (ontologyRef !== undefined && ontologyRef !== 'none') {
            if (!ontology || ontology.mode === 'NONE') {
                errors.push({path: `${profilePath}.ontology`, message: `本体引用 ${JSON.stringify(ontologyRef)} 不可解析：本包 ontology.mode=${ontology ? ontology.mode : '未声明'}，只允许 "none"`});
            } else if (ontologyRef !== `${ontology.space}@${ontology.release}`) {
                errors.push({path: `${profilePath}.ontology`, message: `本体引用 ${JSON.stringify(ontologyRef)} 与本包 ontology 声明不一致（应为 "none" 或 "${ontology.space}@${ontology.release}"，跨包/跨租户引用禁止，§11.2.4）`});
            }
        }
    }

    for (const [index, agent] of (pkg.agents || []).entries()) {
        for (const [refIndex, ref] of (agent.skillRefs || []).entries()) {
            if (!toolIds.has(ref.tool)) {
                errors.push({path: `agents[${index}].skillRefs[${refIndex}].tool`, message: `工具引用不可解析：${JSON.stringify(ref.tool)} 不在本包 tools 内`});
            } else if (!enabledToolIds.has(ref.tool)) {
                errors.push({path: `agents[${index}].skillRefs[${refIndex}].tool`, message: `工具 ${ref.tool} 已停用（enabled=false），不得被应用引用`});
            }
        }
    }
}

// ---------------------------------------------------------------- 主流程

function failExit(messages) {
    for (const message of messages) console.error(message);
    console.error('能力包校验失败');
    process.exit(1);
}

function parseJsonFile(file, raw) {
    try {
        return JSON.parse(raw);
    } catch (error) {
        failExit([`${path.relative(ROOT, file)}：JSON 语法错误：${error.message}`]);
        return undefined;
    }
}

async function main() {
    const args = process.argv.slice(2);
    const files = [];
    let useDefaultSet = true;
    for (let index = 0; index < args.length; index++) {
        if (args[index] === '--file') {
            const value = args[++index];
            if (!value) failExit(['--file 需要一个路径参数']);
            files.push(path.resolve(process.cwd(), value));
            useDefaultSet = false;
        } else {
            failExit([`未知参数 ${args[index]}（支持：--file <path>，可重复）`]);
        }
    }
    if (useDefaultSet) {
        const names = (await readdir(PACKAGE_DIR)).filter((name) => name.endsWith('.json')).sort();
        if (names.length === 0) failExit([`${path.relative(ROOT, PACKAGE_DIR)}：没有任何能力包声明文件`]);
        files.push(...names.map((name) => path.join(PACKAGE_DIR, name)));
    }

    const schemaRaw = await readFile(SCHEMA_FILE, 'utf8');
    const schema = parseJsonFile(SCHEMA_FILE, schemaRaw);
    const schemaSha = createHash('sha256').update(schemaRaw).digest('hex');
    console.log(`== 能力包声明校验（T22A 冻结口径，schema ${schema.$id || '（无 $id）'}）==`);
    console.log(`schema: ${path.relative(ROOT, SCHEMA_FILE)}  sha256=${schemaSha}`);

    const packages = [];
    for (const file of files) {
        const raw = await readFile(file, 'utf8');
        const pkg = parseJsonFile(file, raw);
        packages.push({file, pkg});
    }

    const nameSeen = new Map();
    const allErrors = [];
    const allWarnings = [];
    for (const [index, {file, pkg}] of packages.entries()) {
        const label = `${path.relative(ROOT, file)}${packages.length > 1 ? ` [${index + 1}/${packages.length}]` : ''}`;
        const schemaErrors = [];
        validateNode(pkg, schema, '能力包', schemaErrors);

        const errors = [];
        const warnings = [];
        checkSemantics(pkg, label, errors, warnings);

        const name = pkg?.metadata?.name;
        if (typeof name === 'string') {
            if (nameSeen.has(name)) {
                errors.push({path: 'metadata.name', message: `能力包名称全局唯一冲突：${name} 已由 ${path.relative(ROOT, nameSeen.get(name))} 声明（§11.2.4）`});
            }
            nameSeen.set(name, file);
        }

        const version = pkg?.metadata?.version ? ` v${pkg.metadata.version}` : '';
        const system = pkg?.software?.system ? `  software=${pkg.software.system}` : '';
        console.log(`[${index + 1}/${packages.length}] ${label}${version}${system}`);
        if (Array.isArray(pkg?.interfaces)) {
            const rendered = pkg.interfaces.map((item) => `${item.id}[${item.kind}]`).join(' ');
            console.log(`  interfaces(${pkg.interfaces.length}): ${rendered}`);
        }
        if (Array.isArray(pkg?.tools)) {
            const rendered = pkg.tools
                .map((tool) => `${tool.id}->${tool.binding}[${tool.exposure}${tool.enabled === false ? ',停用' : ''}]`)
                .join(' ');
            console.log(`  tools(${pkg.tools.length}): ${rendered}`);
        }
        if (pkg?.ontology) console.log(`  ontology: ${pkg.ontology.mode}`);
        if (Array.isArray(pkg?.routeProfiles)) {
            console.log(`  routeProfiles(${pkg.routeProfiles.length}): ${pkg.routeProfiles.map((item) => item.id).join(' ')}`);
        }
        if (pkg?.verification) {
            const gates = ['connection', 'contract', 'model', 'evaluation', 'safety', 'concurrency'];
            const exempted = new Set((pkg.verification.exemptions || []).map((item) => item.gate));
            const rendered = gates
                .map((gate) => `${gate}=${pkg.verification[gate] === false ? `关闭${exempted.has(gate) ? '(豁免留痕)' : '(未豁免!)'}` : '通过'}`)
                .join('  ');
            console.log(`  verification: ${rendered}`);
        }

        for (const warning of warnings) allWarnings.push({label, ...warning});
        for (const error of [...schemaErrors, ...errors]) allErrors.push({label, ...error});
        console.log(`  错误 ${schemaErrors.length + errors.length} 条，警告 ${warnings.length} 条`);
    }

    for (const warning of allWarnings) console.warn(`  警告 [${warning.label}] ${warning.path}：${warning.message}`);
    for (const error of allErrors) console.error(`  错误 [${error.label}] ${error.path}：${error.message}`);

    if (allErrors.length > 0) {
        console.error(`能力包校验失败：${packages.length} 个声明，${allErrors.length} 条错误，${allWarnings.length} 条警告`);
        process.exit(1);
    }
    console.log(`能力包校验通过：${packages.length} 个声明，0 条错误，${allWarnings.length} 条警告`);
}

main().catch((error) => failExit([`校验器自身异常：${error && error.stack ? error.stack : error}`]));
