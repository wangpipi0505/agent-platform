<script setup lang="ts">
/**
 * SEM-02.2 / SEM-09 集成页·语义绑定目录。
 * 绑定固定“发布四元组 + V40 扩展 + 只读 profile + 技能精确版本”；
 * 修订不可原地改写：停用/修订都按当前修订号 CAS，页面不提供扩权字段。
 */
import { computed, reactive, ref } from "vue";
import PageHeader from "../../PageHeader.vue";
import PageError from "../../PageError.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";

interface BindingView {
  bindingId: string;
  appCode: string;
  currentRev: number;
  status: "ACTIVE" | "DISABLED";
  latest?: {
    releaseRef?: { spaceCode: string; releaseId: string; version: string; contentDigest: string };
    extensionId?: string;
    extensionDigest?: string;
    opCode?: string;
    skill?: { skillCode: string; skillVersion: number; skillSha256?: string };
    profiles?: string[];
  };
}

usePageTitle(() => "语义绑定");
const { api, busy, error, trace, operation, success } = usePageOperation();

const appCode = ref("");
const bindings = ref<BindingView[]>([]);
const formOpen = ref(false);
const form = reactive({
  bindingId: "",
  releaseSpace: "",
  releaseId: "",
  releaseVersion: "",
  releaseDigest: "",
  extensionId: "",
  extensionDigest: "",
  opCode: "",
  objectType: "",
  skillCode: "",
  skillVersion: 1,
  skillSha256: "",
  requestId: "",
});

const canSubmit = computed(
  () =>
    !!appCode.value.trim() &&
    !!form.bindingId.trim() &&
    !!form.releaseSpace.trim() &&
    !!form.releaseId.trim() &&
    !!form.releaseVersion.trim() &&
    /^[0-9a-f]{64}$/.test(form.releaseDigest) &&
    /^[0-9a-f]{64}$/.test(form.extensionDigest) &&
    !!form.extensionId.trim() &&
    !!form.opCode.trim() &&
    !!form.skillCode.trim() &&
    form.skillVersion >= 1,
);

function requestId(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

async function load() {
  const code = appCode.value.trim();
  if (!code) return;
  await operation(async () => {
    bindings.value = await api<BindingView[]>(
      `/admin/semantic-bindings?appCode=${encodeURIComponent(code)}`,
    );
  });
}

function openCreate() {
  form.bindingId = `sb-${Date.now().toString(36)}`;
  form.requestId = requestId("sb-create");
  formOpen.value = true;
}

async function create() {
  if (!canSubmit.value) return;
  await operation(async () => {
    const body: Record<string, unknown> = {
      bindingId: form.bindingId.trim(),
      appCode: appCode.value.trim(),
      requestId: form.requestId,
      releaseRef: {
        spaceCode: form.releaseSpace.trim(),
        releaseId: form.releaseId.trim(),
        version: form.releaseVersion.trim(),
        contentDigest: form.releaseDigest.trim(),
      },
      extensionId: form.extensionId.trim(),
      extensionDigest: form.extensionDigest.trim(),
      opCode: form.opCode.trim(),
      skill: {
        skillCode: form.skillCode.trim(),
        skillVersion: form.skillVersion,
        ...(form.skillSha256.trim() ? { skillSha256: form.skillSha256.trim() } : {}),
      },
      profiles: ["read-only@1.0"],
    };
    if (form.objectType.trim()) body.objectType = form.objectType.trim();
    await api("/admin/semantic-bindings", "POST", body);
    success("语义绑定已保存；如需变更请追加修订，不能原地改写");
    formOpen.value = false;
    await load();
  });
}

async function disable(binding: BindingView) {
  await operation(async () => {
    await api(`/admin/semantic-bindings/${encodeURIComponent(binding.bindingId)}/disable`, "POST", {
      appCode: binding.appCode,
      expectedRev: binding.currentRev,
      requestId: requestId("sb-disable"),
    });
    success("已停用；新运行不再引用该绑定，历史发布仍可反查");
    await load();
  });
}
</script>

<template>
  <PageHeader title="语义绑定" eyebrow="集成" subtitle="固定发布版本、只读扩展与技能精确版本" :busy="busy" />
  <PageError :error="error" :trace="trace" />
  <el-card class="panel">
    <div class="toolbar">
      <el-input v-model="appCode" placeholder="应用编码" maxlength="64" style="max-width: 260px" />
      <el-button :loading="busy" @click="load">查询</el-button>
      <el-button type="primary" @click="openCreate">新增绑定</el-button>
    </div>
    <el-table :data="bindings" size="small" row-key="bindingId">
      <el-table-column prop="bindingId" label="绑定" min-width="160" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="修订" width="80">
        <template #default="{ row }">v{{ row.currentRev }}</template>
      </el-table-column>
      <el-table-column label="引用" min-width="260">
        <template #default="{ row }">
          <div v-if="row.latest">
            <div>
              {{ row.latest.releaseRef?.spaceCode }} / {{ row.latest.releaseRef?.releaseId }} /
              {{ row.latest.releaseRef?.version }}
            </div>
            <div class="muted">扩展 {{ row.latest.extensionId }} · 动作 {{ row.latest.opCode }}</div>
            <div class="muted">
              技能 {{ row.latest.skill?.skillCode }} v{{ row.latest.skill?.skillVersion }} ·
              {{ (row.latest.profiles ?? []).join(",") }}
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'ACTIVE'"
            link
            type="danger"
            @click="disable(row)"
          >
            停用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formOpen" title="新增语义绑定" width="640px">
      <el-form label-position="top">
        <el-form-item label="绑定 ID" required>
          <el-input v-model="form.bindingId" maxlength="64" />
        </el-form-item>
        <el-form-item label="发布空间 / releaseId / 版本 / 摘要" required>
          <div class="grid">
            <el-input v-model="form.releaseSpace" placeholder="spaceCode" />
            <el-input v-model="form.releaseId" placeholder="releaseId（UUID）" />
            <el-input v-model="form.releaseVersion" placeholder="v1" />
            <el-input v-model="form.releaseDigest" placeholder="64 位 contentDigest" />
          </div>
        </el-form-item>
        <el-form-item label="V40 语义扩展 ID / 摘要" required>
          <div class="grid">
            <el-input v-model="form.extensionId" placeholder="extensionId（UUID）" />
            <el-input v-model="form.extensionDigest" placeholder="64 位 extensionDigest" />
          </div>
        </el-form-item>
        <el-form-item label="动作编码 / 对象类型" required>
          <div class="grid">
            <el-input v-model="form.opCode" placeholder="task_read" />
            <el-input v-model="form.objectType" placeholder="Task（可选）" />
          </div>
        </el-form-item>
        <el-form-item label="技能编码 / 版本 / 摘要" required>
          <div class="grid">
            <el-input v-model="form.skillCode" placeholder="customers.read" />
            <el-input-number v-model="form.skillVersion" :min="1" :precision="0" />
            <el-input v-model="form.skillSha256" placeholder="64 位 sha256（可选）" />
          </div>
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="profile 固定为 read-only@1.0；表单不含授权/范围字段，配置不会扩大源权限。"
        />
      </el-form>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!canSubmit" :loading="busy" @click="create">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.panel { display: grid; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; }
.grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; width: 100%; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
