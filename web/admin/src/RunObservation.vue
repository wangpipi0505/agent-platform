<script setup lang="ts">
import { computed } from "vue";
import { record } from "../../shared/run-protocol";

const props = defineProps<{ observation?: unknown }>();
const observed = computed(() => record(props.observation));
const records = (value: unknown): Record<string, unknown>[] | undefined => Array.isArray(value) ? value.map((entry: unknown) => record(entry) || {}) : undefined;
const packages = computed(() => records(observed.value?.capabilityPackages));
const text = (value: unknown) => typeof value === "string" && value.length > 0
  ? value : typeof value === "number" && Number.isFinite(value) ? String(value) : "未记录";
const selectionLabels: Record<string, string> = {
  SELECTED: "纳入工具配置", DISABLED: "配置停用", PROFILE_NOT_MEMBER: "不在所选工具档案", GUARD_DENIED: "守卫未授予",
};
const selection = (value: unknown) => typeof value === "string" ? selectionLabels[value] || text(value) : "未记录";
</script>

<template>
  <section class="run-observation" aria-label="运行冻结来源">
    <dl class="identifiers">
      <dt>关联 Trace</dt><dd>{{ text(observed?.traceId) }}</dd>
      <dt>本体空间</dt><dd>{{ text(observed?.spaceId) }}</dd>
      <dt>本体发布</dt><dd>{{ text(observed?.releaseId) }}</dd>
    </dl>
    <p class="muted">仅展示本次接口返回的持久来源；缺值显示“未记录”。冻结的工具配置与实际工具调用分别查看。</p>
    <p v-if="!packages" class="muted">能力包来源：未记录</p>
    <p v-else-if="!packages.length" class="muted">能力包来源已记录为空。</p>
    <article v-for="(pkg, index) in packages || []" :key="index" class="source">
      <h3>{{ text(pkg.packageId) }} · {{ text(pkg.version) }}</h3>
      <p>软件 {{ text(pkg.softwareId) }} · 软件版本 {{ text(pkg.softwareVersion) }}</p>
      <p>路线 {{ text(pkg.routeProfileId) }} · 环境 {{ text(pkg.environment) }}</p>
      <p v-if="!records(pkg.bindings)" class="muted">工具来源：未记录</p>
      <p v-else-if="!records(pkg.bindings)?.length" class="muted">工具来源已记录为空。</p>
      <ul v-else class="bindings">
        <li v-for="(binding, position) in records(pkg.bindings) || []" :key="position">
          <strong>{{ text(binding.toolId) }}</strong> → 技能 {{ text(binding.skillCode) }} · 精确版本 {{ text(binding.version) }}
          <span class="muted"> · {{ selection(binding.selection) }}</span>
        </li>
      </ul>
      <details><summary>来源标识与摘要</summary>
        <p>包版本 ID {{ text(pkg.packageVersionId) }}</p>
        <p>包摘要 <code>{{ text(pkg.sha256) }}</code></p>
        <p>映射摘要 <code>{{ text(pkg.mappingSha256) }}</code></p>
        <pre>{{ JSON.stringify(pkg.bindings ?? null, null, 2) }}</pre>
      </details>
    </article>
  </section>
</template>

<style scoped>
.run-observation { min-width: 0; overflow-wrap: anywhere; }
.identifiers { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: .4rem 1rem; margin: .5rem 0; }
.identifiers dt { color: var(--muted); }
.identifiers dd { margin: 0; overflow-wrap: anywhere; }
.source { border-top: 1px solid var(--el-border-color); padding: .75rem 0; }
.source h3 { margin: .25rem 0; font-size: 1rem; }
.bindings { padding-left: 1.25rem; }
.bindings li { margin: .5rem 0; }
summary { cursor: pointer; }
code { word-break: break-all; }
</style>
