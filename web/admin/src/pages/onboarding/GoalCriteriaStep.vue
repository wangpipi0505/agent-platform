<script setup lang="ts">
/**
 * SEM-08/09 接入向导·成功判据步骤。
 * 只允许从“同发布配置中的只读验证技能”里选，并用受控三算子声明判据；
 * 不写任意表达式，也不把 HTTP 200 或模型自述当作目标完成。
 */
import { computed, reactive, ref, watch } from "vue";
import { buildGoalCriterion, SemanticFormError } from "../../../../shared/semantic-runtime";

const props = defineProps<{
  approvedSkills: readonly string[];
  disabled?: boolean;
}>();
const emit = defineEmits<{ submit: [goal: ReturnType<typeof buildGoalCriterion>] }>();

const form = reactive({
  goalId: "",
  skillCode: "",
  predicatePath: "$.data.status",
  operator: "equals" as "equals" | "nonEmpty" | "exists",
  expectedValue: "",
  nextStep: "稍后按同一只读验证操作重新读回",
});
const error = ref("");
const readable = computed(() => props.approvedSkills.length > 0);

watch(
  () => props.approvedSkills,
  (skills) => {
    if (!skills.includes(form.skillCode)) form.skillCode = skills[0] ?? "";
  },
  { immediate: true },
);

function submit() {
  error.value = "";
  try {
    emit(
      "submit",
      buildGoalCriterion({
        goalId: form.goalId,
        skillCode: form.skillCode,
        predicatePath: form.predicatePath,
        operator: form.operator,
        expectedValue: form.operator === "equals" ? form.expectedValue : undefined,
        nextStep: form.nextStep,
      }),
    );
  } catch (e) {
    error.value = e instanceof SemanticFormError ? e.message : "判据配置未通过校验";
  }
}
</script>

<template>
  <div class="goal-criteria-step">
    <el-alert
      v-if="readable"
      type="info"
      :closable="false"
      show-icon
      title="目标完成只认业务回执"
      description="判据使用同一发布配置中批准的只读验证操作；运行成功或 HTTP 200 不等于目标达成。"
    />
    <el-form v-if="readable" label-position="top">
      <el-form-item label="目标标识" required>
        <el-input v-model="form.goalId" placeholder="例如 refund-done" maxlength="64" />
      </el-form-item>
      <el-form-item label="只读验证操作" required>
        <el-select v-model="form.skillCode" placeholder="选择已批准只读技能">
          <el-option v-for="skill in props.approvedSkills" :key="skill" :value="skill" :label="skill" />
        </el-select>
      </el-form-item>
      <el-form-item label="判据字段" required>
        <el-input v-model="form.predicatePath" placeholder="$.data.status" aria-label="判据字段路径" />
      </el-form-item>
      <el-form-item label="判定方式" required>
        <el-select v-model="form.operator">
          <el-option value="equals" label="等于期望值" />
          <el-option value="nonEmpty" label="存在且非空" />
          <el-option value="exists" label="字段存在" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.operator === 'equals'" label="期望值" required>
        <el-input v-model="form.expectedValue" placeholder="例如 SUCCESS" />
      </el-form-item>
      <el-form-item label="等待/未达成时的下一步">
        <el-input v-model="form.nextStep" maxlength="256" placeholder="例如稍后重新读回退款状态" />
      </el-form-item>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-button type="primary" :disabled="props.disabled" @click="submit">加入成功判据</el-button>
    </el-form>
    <el-alert
      v-else
      type="warning"
      :closable="false"
      title="当前发布没有批准的只读验证操作，无法声明成功判据"
      description="请回到接入配置为该发布批准一个只读验证技能；不要用模型自述替代。"
    />
  </div>
</template>

<style scoped>
.goal-criteria-step { display: grid; gap: 10px; max-width: 640px; }
</style>
