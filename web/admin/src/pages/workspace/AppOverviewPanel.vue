<script setup lang="ts">
import ApplicationHistory from "../../ApplicationHistory.vue";
import { displayTime, statusLabel } from "../resources/resource-format";

defineProps<{
  app: any;
  code: string;
  versions: any[];
  busy: boolean;
  refreshKey: number;
}>();
const emit = defineEmits<{
  (e: "lifecycle", action: string): void;
  (e: "save-metadata"): void;
}>();
</script>

<template>
  <div>
    <div class="row">
      <span class="muted">应用信息与生命周期</span>
      <div class="actions">
        <el-button
          v-if="app.currentVersionNo && !['DISABLED', 'ARCHIVED'].includes(app.status)"
          v-permission="'edit'"
          @click="emit('lifecycle', 'disable')"
          >停用应用</el-button
        >
        <el-button
          v-if="app.status === 'DISABLED'"
          v-permission="'edit'"
          @click="emit('lifecycle', 'enable')"
          >启用应用</el-button
        >
        <el-button
          v-if="app.status === 'DISABLED'"
          type="danger"
          plain
          v-permission="'edit'"
          @click="emit('lifecycle', 'archive')"
          >归档应用</el-button
        >
      </div>
    </div>
    <div class="grid">
      <div class="panel">
        <div class="muted">生命周期</div>
        <h2>
          <span :class="['status', app.status]">{{
            app.statusLabel || statusLabel(app.status)
          }}</span>
          <small v-if="app.currentVersionNo && app.status === 'DRAFT'"
            >已发布 v{{ app.currentVersionNo }}，新草稿待发布</small
          >
        </h2>
      </div>
      <div class="panel">
        <div class="muted">草稿修订</div>
        <h2>{{ app.draftRev }}</h2>
      </div>
      <div class="panel">
        <div class="muted">当前发布版本</div>
        <h2>{{ app.currentVersionNo || "尚未发布" }}</h2>
      </div>
    </div>
    <div class="panel">
      <el-collapse>
        <el-collapse-item title="编辑应用名称与分类">
          <el-form label-position="left" label-width="6rem">
            <el-form-item label="名称"><el-input v-model="app.name" /></el-form-item>
            <el-form-item label="说明">
              <el-input v-model="app.description" type="textarea" />
            </el-form-item>
            <el-form-item label="专业领域">
              <el-input v-model="app.domain" />
            </el-form-item>
            <el-form-item label="业务分类">
              <el-input v-model="app.bizCategory" />
            </el-form-item>
            <el-form-item label="标签">
              <el-select v-model="app.tags" multiple filterable allow-create>
                <el-option v-for="tag in app.tags || []" :key="tag" :value="tag" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-button
            type="primary"
            :loading="busy"
            v-permission="'edit'"
            @click="emit('save-metadata')"
            >保存应用信息</el-button
          >
        </el-collapse-item>
      </el-collapse>
      <h2>版本记录</h2>
      <el-table :data="versions">
        <el-table-column prop="versionNo" label="版本" />
        <el-table-column prop="candidateSha256" label="候选摘要" />
        <el-table-column label="发布时间">
          <template #default="{ row }">{{ displayTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>尚无发布版本，请先完成配置、调试与评测。</template>
      </el-table>
    </div>
    <ApplicationHistory
      :app-code="code"
      :versions="versions"
      :revision="refreshKey"
    />
  </div>
</template>
