<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { useApi } from "../../../shared/api";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";

type DirectoryUser = { subject: string; displayName: string; active: boolean };
type Grant = { subject: string; role: string; appScope: string[]; status: "ACTIVE" | "REVOKED"; expiresAt?: string };
type Application = { code: string; name: string };

const roles = [
  { value: "ADMIN", label: "管理员" },
  { value: "BUILDER", label: "构建者" },
  { value: "OPERATOR", label: "运维人员" },
  { value: "USER", label: "普通用户" },
];
const api = useApi();
const keyword = ref("");
const users = ref<DirectoryUser[]>([]);
const applications = ref<Application[]>([]);
const selected = ref<DirectoryUser>();
const grants = ref<Grant[]>([]);
const role = ref("USER");
const scope = ref<string[]>([]);
const expiry = ref<Date | null>(null);
const busy = ref(false);
const searched = ref(false);
const error = ref("");
const status = ref("");
const base = "/admin/platform-authorization";

const scopeLabel = computed(() => scope.value.length ? `已选择 ${scope.value.length} 个应用` : "空范围授予本租户内全部应用");

async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  status.value = "";
  try {
    await action();
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : "操作失败，请稍后重试";
  } finally {
    busy.value = false;
  }
}

async function search() {
  const query = keyword.value.trim();
  if (!query) return;
  await run(async () => {
    users.value = [];
    searched.value = false;
    users.value = await api<DirectoryUser[]>(`${base}/users?q=${encodeURIComponent(query)}`);
    searched.value = true;
  });
}

async function loadGrants(subject: string) {
  grants.value = await api<Grant[]>(`${base}/grants?subject=${encodeURIComponent(subject)}`);
}

async function selectUser(user: DirectoryUser) {
  await run(async () => {
    selected.value = user;
    grants.value = [];
    role.value = "USER";
    scope.value = [];
    expiry.value = null;
    await loadGrants(user.subject);
    selectRole(role.value);
  });
}

function selectRole(value: string) {
  const grant = grants.value.find((item) => item.role === value);
  scope.value = [...(grant?.appScope ?? [])];
  expiry.value = grant?.expiresAt ? new Date(grant.expiresAt) : null;
}

async function save() {
  const user = selected.value;
  if (!user?.active) return;
  await run(async () => {
    await api(`${base}/grants`, "PUT", {
      subject: user.subject, role: role.value, appScope: scope.value,
      expiresAt: expiry.value?.toISOString(),
    });
    status.value = "授权已保存";
    await loadGrants(user.subject);
  });
}

async function revoke(grant: Grant) {
  await run(async () => {
    try {
      await ElMessageBox.confirm(`回收 ${grant.subject} 的 ${grant.role} 授权？`, "回收授权", {
        confirmButtonText: "回收", cancelButtonText: "取消", type: "warning",
      });
    } catch {
      return;
    }
    await api(`${base}/grants`, "DELETE", {
      subject: grant.subject, role: grant.role,
    });
    status.value = "授权已回收";
    await loadGrants(grant.subject);
  });
}

onMounted(() => run(async () => {
  applications.value = await api<Application[]>("/admin/apps");
}));
</script>

<template>
  <div class="platform-authorizations">
    <PageHeader title="用户与授权" />
    <PageError :error="error" />
    <p v-if="status" role="status">{{ status }}</p>

    <section class="panel authorization-section" aria-labelledby="directory-heading">
      <h2 id="directory-heading">检索用户</h2>
      <el-form inline @submit.prevent="search">
        <el-form-item label="用户名" for="directory-keyword">
          <el-input id="directory-keyword" v-model="keyword" :maxlength="64" :disabled="busy" />
        </el-form-item>
        <el-button native-type="submit" :disabled="busy || !keyword.trim()">检索</el-button>
      </el-form>
      <p v-if="searched && !users.length">没有匹配的用户。</p>
      <el-table v-if="users.length" :data="users" row-key="subject">
        <el-table-column prop="subject" label="用户名" />
        <el-table-column prop="displayName" label="显示名" />
        <el-table-column label="账号状态">
          <template #default="{ row }">{{ row.active ? "启用" : "停用" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="busy" @click="selectUser(row)">管理授权</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-if="selected" class="panel authorization-section" aria-labelledby="grant-heading">
      <h2 id="grant-heading">{{ selected.displayName }} 的平台授权</h2>
      <el-form label-position="top" :disabled="busy" @submit.prevent="save">
        <el-form-item label="平台角色" for="grant-role">
          <el-select id="grant-role" v-model="role" @change="selectRole">
            <el-option v-for="item in roles" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="应用范围" for="grant-applications">
          <el-select id="grant-applications" v-model="scope" multiple filterable clearable
            aria-describedby="scope-description">
            <el-option v-for="app in applications" :key="app.code" :value="app.code" :label="app.name" />
          </el-select>
          <p id="scope-description" class="muted">{{ scopeLabel }}</p>
        </el-form-item>
        <el-form-item label="到期时间（选填）" for="grant-expiry">
          <el-date-picker id="grant-expiry" v-model="expiry" type="datetime" placeholder="长期有效" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :disabled="!selected.active">保存授权</el-button>
      </el-form>

      <el-table v-if="grants.length" :data="grants" row-key="role">
        <el-table-column prop="role" label="角色" />
        <el-table-column label="应用范围">
          <template #default="{ row }">{{ row.appScope.length ? row.appScope.join("、") : "本租户内全部应用" }}</template>
        </el-table-column>
        <el-table-column label="授权状态">
          <template #default="{ row }">{{ row.status === "ACTIVE" ? "有效" : "已回收" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="busy || row.status !== 'ACTIVE'" @click="revoke(row)">回收</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.platform-authorizations {
  display: grid;
  gap: var(--space-4, 16px);
}

.authorization-section {
  min-width: 0;
  padding: var(--space-4, 16px);
}

.authorization-section h2 {
  margin: 0 0 var(--space-4, 16px);
}

.authorization-section .el-form:not(.el-form--inline) {
  max-width: 32rem;
  margin-bottom: var(--space-4, 16px);
}
</style>
