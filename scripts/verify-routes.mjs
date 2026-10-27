#!/usr/bin/env node
/**
 * G-9：路由目录漂移校验（构建期，零依赖）。
 *
 * 背景：`admin-common/src/main/resources/agent-service-authority.json` 是服务间调用路由目录。
 * `RouteCoverageVerifier` 在 Spring 上下文启动时要求每个 handler 都有对应目录行，否则抛
 * `ROUTE_COVERAGE_MISSING` 让整个上下文（含网关链路夹具、审计脚本）失败。目录靠手改，
 * 已发生两次漂移（Y-1 缺 `/internal/runs/{id}/wake`，Y-1b 缺引擎控制台 2 条），
 * 都是在跑别的东西时被动暴露。本脚本把同一份比对前移到 node 构建期。
 *
 * 判定口径（与运行期校验器一致的部分）：
 * - 覆盖判定是「按 target 服务 + method + template 三元组精确相等」，不是模式匹配；
 *   所以目录里的 `ANY /admin/**` 只能被同样登记为 `ANY /admin/**` 的 handler 满足。
 * - `MISSING`（有 handler 无目录行，且落在服务票据面）阻断：运行期一定会抛异常。
 * - `PORTAL_SURFACE`（`/admin/**` 上缺目录行）只告警：`SecurityConfiguration` 对 `/admin/**`
 *   要求门户会话 + portal scope，不查服务票据，目录本来就不覆盖门户面（见证据 README）。
 * - `STALE`（有目录行无 handler）只警告：目录里可能有意保留代理入口、已下线端点或
 *   非本服务实现的入口（如各服务 `/v3/api-docs` 由 springdoc 提供），不能据此判失败。
 * - `EXCLUDED`（`scripts/route-catalog-exclusions.json` 显式登记的非票据端点）不阻断，
 *   但每条必须有非空 `reason`，否则本脚本直接失败（防止用排除表掩盖真漂移）。
 *
 * 有意边界（见 docs/evidence/2026-10-27-G9-route-drift/README.md）：
 * 注解解析是文本级而非 AST，只读 `<module>/src/main/java/**\/api/*.java`；路径里的常量拼接、
 * `method = GET` 静态导入等写法无法求值，可能漏报 handler（漏报只会让本脚本偏松，
 * 不会误报 MISSING）。`api/` 之外的 controller 由 `outsideApiMappings` 单独警告提示。
 */
import {readFileSync, readdirSync, statSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const CATALOG_FILE = 'admin-common/src/main/resources/agent-service-authority.json';
const EXCLUSIONS_FILE = 'scripts/route-catalog-exclusions.json';
/** `/admin/**` 属门户鉴权面（会话 + portal scope），不是服务票据面，缺目录行不阻断。 */
const PORTAL_PREFIX = '/admin';

/** 服务目录 ↔ 目录行的 target 短名（与 verify-contracts.mjs 的命名空间映射一致）。 */
const SERVICES = [
    {module: 'app-management', target: 'app'},
    {module: 'engine-runtime', target: 'engine'},
    {module: 'skill-registry', target: 'skill'},
    {module: 'monitor', target: 'monitor'},
    {module: 'model-gateway', target: 'model'},
    {module: 'platform-gateway', target: 'gateway'},
];

/** 注解 → 声明的 HTTP 方法；`RequestMapping` 的方法来自 `method=` 属性，缺省 ANY。 */
const MAPPING_ANNOTATIONS = new Map([
    ['GetMapping', ['GET']],
    ['PostMapping', ['POST']],
    ['PutMapping', ['PUT']],
    ['DeleteMapping', ['DELETE']],
    ['PatchMapping', ['PATCH']],
    ['RequestMapping', null],
]);

/**
 * mapping 注解的扫描正则。允许全限定写法（`@org.springframework.web.bind.annotation.PostMapping`）：
 * 引擎唤醒入口就是这么写的，节点名匹配漏掉会把它误报成 STALE。
 */
function mappingMatcher() {
    return /@(?:[\w$]+\.)*(Get|Post|Put|Delete|Patch|Request)Mapping\b/g;
}

/** 类级 `@RequestMapping` 的扫描正则，同样允许全限定写法。 */
function classMappingMatcher() {
    return /@(?:[\w$]+\.)*RequestMapping\b/g;
}

/** 源码里先去注释再解析：Javadoc/行内注释里出现的注解名不能当代码。保留换行以维持行号。 */
function stripComments(source) {
    let out = '';
    for (let i = 0; i < source.length; i++) {
        const c = source[i];
        if (c === '"' || c === "'") {
            const end = skipLiteral(source, i);
            out += source.slice(i, end);
            i = end - 1;
            continue;
        }
        if (c === '/' && source[i + 1] === '/') {
            i += 2;
            while (i < source.length && source[i] !== '\n') i++;
            // i 现在指向换行（或 EOF）；补回一个换行，行号不漂移。
            if (i < source.length) out += '\n';
            continue;
        }
        if (c === '/' && source[i + 1] === '*') {
            const close = source.indexOf('*/', i + 2);
            const stop = close < 0 ? source.length : close + 2;
            for (let k = i; k < stop; k++) if (source[k] === '\n') out += '\n';
            i = stop - 1;
            continue;
        }
        out += c;
    }
    return out;
}

/** 从引号下标返回字面量结束后的下标（处理转义与 Java 21 文本块）。 */
function skipLiteral(source, start) {
    const quote = source[start];
    if (quote === '"' && source.startsWith('"""', start)) {
        const close = source.indexOf('"""', start + 3);
        return close < 0 ? source.length : close + 3;
    }
    for (let i = start + 1; i < source.length; i++) {
        const c = source[i];
        if (c === '\\') {
            i++;
            continue;
        }
        if (c === quote) return i + 1;
        if (c === '\n' && quote !== "'") return i;
    }
    return source.length;
}

function skipWhitespace(source, index) {
    let i = index;
    while (i < source.length && /\s/.test(source[i])) i++;
    return i;
}

/** 从 `(` 下标读出配平括号内的注解参数；字符串里的括号不计深度。 */
function readArguments(source, openIndex) {
    let depth = 0;
    for (let i = openIndex; i < source.length; i++) {
        const c = source[i];
        if (c === '"' || c === "'") {
            i = skipLiteral(source, i) - 1;
            continue;
        }
        if (c === '(') depth++;
        else if (c === ')') {
            depth--;
            if (depth === 0) return {text: source.slice(openIndex + 1, i), end: i + 1};
        }
    }
    return {text: source.slice(openIndex + 1), end: source.length};
}

/** 按顶层逗号切分注解参数，忽略字符串/嵌套花括号里的逗号。 */
function splitTopLevel(text) {
    const parts = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c === '"' || c === "'") {
            i = skipLiteral(text, i) - 1;
            continue;
        }
        if (c === '(' || c === '{' || c === '[') depth++;
        else if (c === ')' || c === '}' || c === ']') depth--;
        else if (c === ',' && depth === 0) {
            parts.push(text.slice(start, i));
            start = i + 1;
        }
    }
    parts.push(text.slice(start));
    return parts.map((part) => part.trim()).filter(Boolean);
}

/** 取出片段里的字符串字面量值（注解常量不会被求值，这是有意的文本级边界）。 */
function stringLiterals(text) {
    const values = [];
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c !== '"') continue;
        const end = skipLiteral(text, i);
        const raw = text.slice(i, end);
        if (raw.length >= 2 && raw.endsWith('"')) values.push(raw.slice(1, -1));
        i = end - 1;
    }
    return values;
}

/**
 * 解析一个 mapping 注解。
 * @param {string} name 注解名
 * @param {string|null} args 括号内文本；无括号为 null
 * @returns {{paths: string[], methods: string[]}}
 */
function parseMappingAnnotation(name, args) {
    const declared = MAPPING_ANNOTATIONS.get(name);
    let paths = null;
    let methods = declared ? [...declared] : null;
    if (args !== null) {
        const positional = [];
        for (const segment of splitTopLevel(args)) {
            const named = /^([A-Za-z_$][\w$]*)\s*=([\s\S]*)$/.exec(segment);
            if (!named) {
                positional.push(segment);
                continue;
            }
            const attribute = named[1];
            const value = named[2];
            if (attribute === 'value' || attribute === 'path') {
                paths = [...(paths ?? []), ...stringLiterals(value)];
            } else if (attribute === 'method' && name === 'RequestMapping') {
                methods = [...value.matchAll(/RequestMethod\s*\.\s*([A-Z]+)/g)].map((m) => m[1]);
            }
        }
        if (paths === null && positional.length > 0)
            paths = positional.flatMap((segment) => stringLiterals(segment));
    }
    if (paths === null || paths.length === 0) paths = [''];
    if (methods === null || methods.length === 0) methods = ['ANY'];
    return {paths, methods};
}

/** 归一：`{任意变量}`（含 `{kind:approvals|audit}` 这类正则变量）→ `{}`，去重复与末尾 `/`。 */
function normalizeTemplate(template) {
    let normalized = template.replace(/\{[^}]*\}/g, '{}').replace(/\/{2,}/g, '/');
    if (!normalized.startsWith('/')) normalized = '/' + normalized;
    if (normalized.length > 1 && normalized.endsWith('/')) normalized = normalized.slice(0, -1);
    return normalized;
}

/** 拼接类级前缀与方法级路径；空路径表示只用前缀。 */
function joinTemplate(prefix, suffix) {
    if (!suffix) return normalizeTemplate(prefix || '/');
    if (!prefix) return normalizeTemplate(suffix);
    return normalizeTemplate(prefix.replace(/\/+$/, '') + '/' + suffix.replace(/^\/+/, ''));
}

function lineOf(source, index) {
    let line = 1;
    for (let i = 0; i < index && i < source.length; i++) if (source[i] === '\n') line++;
    return line;
}

/** 找类声明位置与类级 `@RequestMapping` 前缀（可能有多个注解，取类头里最后一个）。 */
function classHeader(source) {
    const declaration = /\b(?:class|interface|record)\s+([A-Za-z_$][\w$]*)/.exec(source);
    if (!declaration) return {name: null, prefix: '', bodyStart: 0};
    const classIndex = declaration.index;
    let prefix = '';
    const matcher = classMappingMatcher();
    let match;
    while ((match = matcher.exec(source)) !== null && match.index < classIndex) {
        const open = skipWhitespace(source, match.index + '@RequestMapping'.length);
        if (source[open] !== '(') continue;
        const args = readArguments(source, open);
        const parsed = parseMappingAnnotation('RequestMapping', args.text);
        if (parsed.paths.length > 0) prefix = parsed.paths[0];
    }
    return {name: declaration[1], prefix, bodyStart: classIndex};
}

/** 注解结束后跳过后续注解，取方法名（MISSING 报告需要能指向具体 handler）。 */
function methodNameAfter(source, index) {
    let i = skipWhitespace(source, index);
    for (;;) {
        if (source[i] !== '@') break;
        i = skipWhitespace(source, i + 1);
        while (i < source.length && /[\w$.]/.test(source[i])) i++;
        i = skipWhitespace(source, i);
        if (source[i] === '(') i = readArguments(source, i).end;
    }
    const match = /([A-Za-z_$][\w$]*)\s*\(/.exec(source.slice(i, i + 400));
    return match ? match[1] : '<unknown>';
}

/** 解析一个 controller 文件里的全部 handler。 */
function parseController(file, relativeFile) {
    const source = stripComments(readFileSync(file, 'utf8'));
    const header = classHeader(source);
    const handlers = [];
    const matcher = mappingMatcher();
    let match;
    while ((match = matcher.exec(source)) !== null) {
        if (match.index < header.bodyStart) continue;
        const name = match[1] + 'Mapping';
        const annotationEnd = match.index + match[0].length;
        const open = skipWhitespace(source, annotationEnd);
        let args = null;
        let end = annotationEnd;
        if (source[open] === '(') {
            const read = readArguments(source, open);
            args = read.text;
            end = read.end;
        }
        const parsed = parseMappingAnnotation(name, args);
        const handlerName = methodNameAfter(source, end);
        const line = lineOf(source, match.index);
        for (const method of parsed.methods)
            for (const rawPath of parsed.paths)
                handlers.push({
                    method,
                    template: joinTemplate(header.prefix, rawPath),
                    handler: handlerName,
                    file: relativeFile,
                    line,
                });
    }
    return {className: header.name, handlers};
}

function javaFilesUnder(directory) {
    const result = [];
    for (const entry of readdirSync(directory, {withFileTypes: true})) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) result.push(...javaFilesUnder(full));
        else if (entry.isFile() && entry.name.endsWith('.java')) result.push(full);
    }
    return result;
}

function relative(file) {
    return path.relative(ROOT, file).split(path.sep).join('/');
}

/** 扫描一个服务的 `**\/api/*.java`；api 目录或整个模块不存在时返回 skipped 原因。 */
function scanService(service) {
    const javaRoot = path.join(ROOT, service.module, 'src/main/java');
    const result = {
        module: service.module,
        target: service.target,
        skipped: null,
        handlers: [],
        outsideApiMappings: [],
    };
    if (!statSync(path.join(ROOT, service.module), {throwIfNoEntry: false})) {
        result.skipped = '模块目录不存在';
        return result;
    }
    if (!statSync(javaRoot, {throwIfNoEntry: false})) {
        result.skipped = 'src/main/java 不存在';
        return result;
    }
    const files = javaFilesUnder(javaRoot);
    const apiFiles = files.filter((file) => path.dirname(file).split(path.sep).pop() === 'api');
    if (apiFiles.length === 0) {
        result.skipped = '没有 **/api/*.java';
        return result;
    }
    for (const file of apiFiles)
        result.handlers.push(...parseController(file, relative(file)).handlers);
    for (const file of files) {
        if (apiFiles.includes(file)) continue;
        const source = stripComments(readFileSync(file, 'utf8'));
        const matcher = mappingMatcher();
        let match;
        while ((match = matcher.exec(source)) !== null)
            result.outsideApiMappings.push({
                service: service.target,
                file: relative(file),
                line: lineOf(source, match.index),
                annotation: match[1] + 'Mapping',
            });
    }
    return result;
}

function loadCatalog() {
    return JSON.parse(readFileSync(path.join(ROOT, CATALOG_FILE), 'utf8'));
}

/**
 * 读排除表并校验。排除项是「这个端点为什么不需要服务票据目录行」的显式证据，
 * 因此 `reason` 必须非空：空 reason 视为配置错误直接失败，避免排除表演变成挡箭牌。
 */
function loadExclusions() {
    const file = path.join(ROOT, EXCLUSIONS_FILE);
    if (!statSync(file, {throwIfNoEntry: false})) return {file: EXCLUSIONS_FILE, list: []};
    const document = JSON.parse(readFileSync(file, 'utf8'));
    const list = Array.isArray(document?.exclusions) ? document.exclusions : null;
    if (!list) throw new Error(`${EXCLUSIONS_FILE} 结构应为 {"exclusions": [{service, method, template, reason}]}`);
    const seen = new Set();
    for (const [index, item] of list.entries()) {
        const where = `${EXCLUSIONS_FILE} 第 ${index + 1} 条`;
        if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error(`${where} 不是对象`);
        for (const field of ['service', 'method', 'template'])
            if (typeof item[field] !== 'string' || item[field].trim() === '')
                throw new Error(`${where} 缺 ${field}`);
        if (typeof item.reason !== 'string' || item.reason.trim() === '')
            throw new Error(
                `${where}（${item.method.toUpperCase()} ${item.template}）reason 为空：` +
                    '排除项必须写明为什么该端点不需要目录行',
            );
        const key = exclusionKey(item);
        if (seen.has(key)) throw new Error(`${EXCLUSIONS_FILE} 重复排除：${key}`);
        seen.add(key);
    }
    return {file: EXCLUSIONS_FILE, list};
}

function exclusionKey(item) {
    return `${item.service} ${item.method.toUpperCase()} ${normalizeTemplate(item.template)}`;
}

function catalogKey(method, template) {
    return `${method} ${normalizeTemplate(template)}`;
}

/** `/admin/**` 是门户鉴权面（会话 + portal scope），不走服务票据目录。 */
function isPortalTemplate(template) {
    return template === PORTAL_PREFIX || template.startsWith(`${PORTAL_PREFIX}/`);
}

/**
 * 全量比对。
 * @param {{service?: string|null, json?: boolean}} [options] `service` 接受模块名或 target 短名
 * @returns {{ok: boolean, blocking: boolean, services: object[], missing: object[],
 *            portalSurface: object[], excluded: object[], stale: object[],
 *            unusedExclusions: object[], warnings: object[],
 *            totals: {handlers: number, catalog: number, missing: number, portalSurface: number,
 *                     excluded: number, stale: number}}}
 */
export function verifyRoutes(options = {}) {
    const catalog = loadCatalog();
    const exclusions = loadExclusions();
    const requested = options.service ?? null;
    let selected = SERVICES;
    if (requested) {
        selected = SERVICES.filter(
            (service) => service.module === requested || service.target === requested,
        );
        if (selected.length === 0) {
            const failure = new Error(`未知服务：${requested}（可选：${SERVICES.map((s) => `${s.target}/${s.module}`).join('、')}）`);
            failure.usage = true;
            throw failure;
        }
    }
    const catalogByTarget = new Map();
    for (const service of SERVICES) catalogByTarget.set(service.target, []);
    for (const row of catalog) {
        if (catalogByTarget.has(row.target)) catalogByTarget.get(row.target).push(row);
    }
    const exclusionByKey = new Map(exclusions.list.map((item) => [exclusionKey(item), item]));
    const usedExclusions = new Set();
    const services = [];
    const missing = [];
    const portalSurface = [];
    const excluded = [];
    const stale = [];
    const warnings = [];
    for (const service of selected) {
        const scan = scanService(service);
        const rows = catalogByTarget.get(service.target) ?? [];
        const catalogKeys = new Set(rows.map((row) => catalogKey(row.method, row.template)));
        const handlerKeys = new Set(scan.handlers.map((item) => catalogKey(item.method, item.template)));
        const serviceMissing = [];
        const servicePortal = [];
        const serviceExcluded = [];
        for (const handler of scan.handlers) {
            if (catalogKeys.has(catalogKey(handler.method, handler.template))) continue;
            const entry = {service: service.target, module: service.module, ...handler};
            const key = `${service.target} ${handler.method} ${handler.template}`;
            const exclusion = exclusionByKey.get(key);
            if (exclusion) {
                usedExclusions.add(key);
                excluded.push({...entry, reason: exclusion.reason});
                serviceExcluded.push(entry);
            } else if (isPortalTemplate(handler.template)) {
                portalSurface.push(entry);
                servicePortal.push(entry);
            } else {
                missing.push(entry);
                serviceMissing.push(entry);
            }
        }
        for (const row of rows) {
            if (!handlerKeys.has(catalogKey(row.method, row.template)))
                stale.push({
                    service: service.target,
                    method: row.method,
                    template: normalizeTemplate(row.template),
                    mode: row.mode,
                    source: row.source ?? null,
                });
        }
        for (const item of scan.outsideApiMappings) warnings.push(item);
        services.push({
            module: service.module,
            target: service.target,
            skipped: scan.skipped,
            handlers: scan.handlers.length,
            catalog: rows.length,
            /** 目录里落在门户面上的行数：历史为通过运行期 verifier 而加，与判定口径不一致。 */
            portalCatalog: rows.filter((row) => isPortalTemplate(normalizeTemplate(row.template))).length,
            missing: serviceMissing.length,
            portalSurface: servicePortal.length,
            excluded: serviceExcluded.length,
            stale: rows.filter((row) => !handlerKeys.has(catalogKey(row.method, row.template))).length,
            outsideApiMappings: scan.outsideApiMappings.length,
        });
    }
    const unusedExclusions = exclusions.list.filter(
        (item) => selected.some((service) => service.target === item.service) && !usedExclusions.has(exclusionKey(item)),
    );
    const totals = {
        handlers: services.reduce((sum, item) => sum + item.handlers, 0),
        catalog: services.reduce((sum, item) => sum + item.catalog, 0),
        missing: missing.length,
        portalSurface: portalSurface.length,
        excluded: excluded.length,
        stale: stale.length,
    };
    // 默认只把服务票据面的 MISSING 当失败；--strict-portal 可把门户面缺行也当失败，
    // 供将来网关改成「服务票据代理门户」或全量上下文夹具启用时收紧。
    const strictPortal = options.strictPortal === true;
    const blocking = missing.length > 0 || (strictPortal && portalSurface.length > 0);
    return {
        ok: !blocking,
        blocking,
        strictPortal,
        services,
        missing,
        portalSurface,
        excluded,
        stale,
        unusedExclusions,
        warnings,
        totals,
    };
}

const FIX_GUIDE =
    '补 `admin-service-authority.json` 对应行：有真实调用方的按 delegated+caller 登记，' +
    '无调用方的登记为 disabled+caller null+reason；不要为了过检查凭空给 caller。';

function printHuman(result, options) {
    const scope = options.service ? `（--service=${options.service}）` : '';
    console.log(`G-9 路由目录比对${scope}：${CATALOG_FILE}`);
    console.log('服务                   handler  目录行  MISSING  PORTAL  排除  STALE');
    for (const service of result.services) {
        const label = `${service.module}(${service.target})`;
        if (service.skipped) {
            console.log(`${label.padEnd(22)}  跳过：${service.skipped}`);
            continue;
        }
        console.log(
            `${label.padEnd(22)}  ${String(service.handlers).padStart(7)}  ` +
                `${String(service.catalog).padStart(6)}  ${String(service.missing).padStart(7)}  ` +
                `${String(service.portalSurface).padStart(6)}  ${String(service.excluded).padStart(4)}  ` +
                `${String(service.stale).padStart(5)}`,
        );
    }
    if (result.missing.length > 0) {
        console.log('\nMISSING（服务票据面上有 handler 无目录行，阻断：运行期会抛 ROUTE_COVERAGE_MISSING）：');
        for (const item of result.missing)
            console.log(`  ${item.service} ${item.method} ${item.template}  <= ${item.file}:${item.line} ${item.handler}()`);
    }
    if (result.portalSurface.length > 0) {
        console.log(
            '\nPORTAL_SURFACE（/admin/** 门户面上有 handler 无目录行，仅告警：门户面查会话+portal scope，' +
                '不查服务票据，目录不覆盖它不是缺陷）：',
        );
        for (const item of result.portalSurface)
            console.log(`  ${item.service} ${item.method} ${item.template}  <= ${item.file}:${item.line} ${item.handler}()`);
    }
    if (result.excluded.length > 0) {
        console.log(`\nEXCLUDED（${EXCLUSIONS_FILE} 显式登记的非票据端点，不阻断）：`);
        for (const item of result.excluded)
            console.log(`  ${item.service} ${item.method} ${item.template}  reason=${item.reason}`);
    }
    if (result.stale.length > 0) {
        console.log('\nSTALE（有目录行无 handler，仅警告：可能是代理/下线/外部入口）：');
        for (const item of result.stale)
            console.log(`  ${item.service} ${item.method} ${item.template}  mode=${item.mode}  source=${item.source ?? '-'}`);
    }
    if (result.unusedExclusions.length > 0) {
        console.log('\n注意（排除表里没有匹配到缺失 handler 的条目，可能已失效）：');
        for (const item of result.unusedExclusions)
            console.log(`  ${item.service} ${item.method.toUpperCase()} ${normalizeTemplate(item.template)}`);
    }
    if (result.warnings.length > 0) {
        console.log('\n注意（api/ 之外的 mapping 注解，不在本次比对范围）：');
        for (const item of result.warnings)
            console.log(`  ${item.service} ${item.file}:${item.line} @${item.annotation}`);
    }
    console.log(
        `\n合计：handler ${result.totals.handlers} / 目录行 ${result.totals.catalog} / ` +
            `MISSING ${result.totals.missing} / PORTAL_SURFACE ${result.totals.portalSurface} / ` +
            `EXCLUDED ${result.totals.excluded} / STALE ${result.totals.stale}`,
    );
    if (result.blocking) {
        const reasons = [];
        if (result.missing.length > 0) reasons.push(`${result.missing.length} 个服务票据面 handler 没有目录行`);
        if (result.strictPortal && result.portalSurface.length > 0)
            reasons.push(`${result.portalSurface.length} 个门户面 handler 没有目录行（--strict-portal）`);
        console.log(`\n失败：${reasons.join('；')}。${FIX_GUIDE}`);
    } else {
        console.log(
            `\n通过：服务票据面没有 MISSING（blocking=false）。` +
                (result.portalSurface.length > 0
                    ? `另有 ${result.portalSurface.length} 条门户面缺失仅告警，见 docs/evidence/2026-10-27-G9-route-drift/README.md。`
                    : ''),
        );
    }
}

function parseArguments(argv) {
    const options = {service: null, json: false, strictPortal: false};
    for (const argument of argv) {
        if (argument === '--json') options.json = true;
        else if (argument === '--strict-portal') options.strictPortal = true;
        else if (argument.startsWith('--service=')) options.service = argument.slice('--service='.length);
        else if (argument === '--help' || argument === '-h') options.help = true;
        else throw new Error(`未知参数：${argument}（支持 --service=<name>、--json、--strict-portal）`);
    }
    return options;
}

function main() {
    let options;
    try {
        options = parseArguments(process.argv.slice(2));
    } catch (error) {
        console.error(error.message);
        process.exitCode = 2;
        return;
    }
    if (options.help) {
        console.log('用法：node scripts/verify-routes.mjs [--service=<app|engine|skill|monitor|model|gateway>] [--json] [--strict-portal]');
        console.log('退出码：有 MISSING（服务票据面缺目录行）为 1；只有 PORTAL_SURFACE/STALE/EXCLUDED 为 0；');
        console.log('        --strict-portal 时门户面缺行也算失败。');
        return;
    }
    let result;
    try {
        result = verifyRoutes(options);
    } catch (error) {
        console.error(`G-9 路由校验无法执行：${error.message}`);
        process.exitCode = error.usage ? 2 : 1;
        return;
    }
    if (options.json) console.log(JSON.stringify(result, null, 2));
    else printHuman(result, options);
    process.exitCode = result.blocking ? 1 : 0;
}

if (process.argv[1] && path.basename(process.argv[1]) === 'verify-routes.mjs') main();
