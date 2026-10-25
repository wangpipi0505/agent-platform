package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;
import static cn.agentplatform.skill.infra.SkillAdapters.*;

import cn.agentplatform.common.*;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** K8s Job名称由持久调用键确定；恢复只查询同一Job，不按工作副本或attempt重新创建任务。 */
@Component
public class AlgorithmAdapter {
    private final JdbcTemplate db;

    public AlgorithmAdapter(JdbcTemplate db) {
        this.db = db;
    }

    public Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            SkillAdapters results,
            String runId) {
        String api = System.getenv("AGENT_K8S_API"),
                namespace = System.getenv("AGENT_ALGORITHM_NAMESPACE"),
                account = System.getenv("AGENT_ALGORITHM_SERVICE_ACCOUNT");
        if (api == null || namespace == null || account == null)
            throw new Failure(
                    "RESULT_UNKNOWN",
                    true,
                    "ALGORITHM_ENV_UNAVAILABLE: 必须显式配置专用K8s API/namespace/serviceAccount");
        var template = object(contract.get("jobTemplate"));
        String image = text(template, "image");
        var allowed =
                Arrays.asList(
                        Objects.toString(System.getenv("AGENT_ALGORITHM_ALLOWED_IMAGES"), "")
                                .split(","));
        if (!allowed.contains(image)) throw new Failure("NO_PERMISSION", false, "算法镜像未在部署允许清单中");
        var client =
                new ApiClient()
                        .setBasePath(api)
                        .setVerifyingSsl(true)
                        .setReadTimeout(((Number) contract.get("timeoutMs")).intValue());
        String auth = text(contract, "authRef");
        client.setApiKey(SecretVault.resolve(auth));
        client.setApiKeyPrefix("Bearer");
        String name = "alg-" + Digest.sha256(key).substring(0, 40);
        var jobs = new BatchV1Api(client);
        // 绑定先落PG，再尝试创建；K8s同名唯一约束是服务重启后的第二道防重保护。
        db.update(
                "insert into"
                    + " skill.external_job_binding(tenant_id,call_id,external_job_id,status,metadata)"
                    + " values(?,?,?,'SUBMITTED',?::jsonb) on conflict do nothing",
                Actor.current().tenantId(),
                callId,
                name,
                Json.write(Map.of("namespace", namespace, "image", image)));
        try {
            V1Job job;
            try {
                job = jobs.readNamespacedJob(name, namespace).execute();
            } catch (ApiException ex) {
                if (ex.getCode() != 404) throw ex;
                if (queryOnly) throw new Failure("RESULT_UNKNOWN", true, "对账未找到原Job；不自动重复执行，请人工核实");
                var container =
                        new V1Container()
                                .name("algorithm")
                                .image(image)
                                .env(
                                        List.of(
                                                new V1EnvVar()
                                                        .name("AGENT_INPUTS_JSON")
                                                        .value(Json.write(arguments)),
                                                new V1EnvVar()
                                                        .name("AGENT_IDEMPOTENCY_KEY")
                                                        .value(key)))
                                .resources(
                                        new V1ResourceRequirements()
                                                .requests(
                                                        Map.of(
                                                                "cpu",
                                                                new Quantity("100m"),
                                                                "memory",
                                                                new Quantity("128Mi")))
                                                .limits(
                                                        Map.of(
                                                                "cpu",
                                                                new Quantity("1"),
                                                                "memory",
                                                                new Quantity("512Mi"))))
                                .securityContext(
                                        new V1SecurityContext()
                                                .allowPrivilegeEscalation(false)
                                                .runAsNonRoot(true)
                                                .capabilities(
                                                        new V1Capabilities().drop(List.of("ALL"))));
                if (template.get("command") instanceof List<?> command)
                    container.command(command.stream().map(Object::toString).toList());
                var spec =
                        new V1PodSpec()
                                .containers(List.of(container))
                                .restartPolicy("Never")
                                .serviceAccountName(account)
                                .automountServiceAccountToken(false);
                var body =
                        new V1Job()
                                .apiVersion("batch/v1")
                                .kind("Job")
                                .metadata(
                                        new V1ObjectMeta()
                                                .name(name)
                                                .labels(
                                                        Map.of(
                                                                "agent-platform-call",
                                                                Long.toString(callId))))
                                .spec(
                                        new V1JobSpec()
                                                .backoffLimit(0)
                                                .activeDeadlineSeconds(
                                                        Math.max(
                                                                1,
                                                                ((Number) contract.get("timeoutMs"))
                                                                                .longValue()
                                                                        / 1000))
                                                .template(
                                                        new V1PodTemplateSpec()
                                                                .metadata(new V1ObjectMeta())
                                                                .spec(spec)));
                try {
                    job = jobs.createNamespacedJob(namespace, body).execute();
                } catch (ApiException conflict) {
                    if (conflict.getCode() != 409) throw conflict;
                    job = jobs.readNamespacedJob(name, namespace).execute();
                }
            }
            long deadline =
                    System.currentTimeMillis() + ((Number) contract.get("timeoutMs")).longValue();
            while (job.getStatus() == null
                    || job.getStatus().getSucceeded() == null
                    || job.getStatus().getSucceeded() == 0) {
                if (job.getStatus() != null
                        && job.getStatus().getFailed() != null
                        && job.getStatus().getFailed() > 0)
                    throw new Failure("RETRYABLE_FAILURE", false, "算法Job执行失败");
                if (System.currentTimeMillis() >= deadline)
                    throw new Failure("RESULT_UNKNOWN", true, "算法Job仍在运行，需使用原绑定对账");
                Thread.sleep(250);
                job = jobs.readNamespacedJob(name, namespace).execute();
            }
            var core = new CoreV1Api(client);
            var pods =
                    core.listNamespacedPod(namespace).labelSelector("job-name=" + name).execute();
            if (pods.getItems().isEmpty())
                throw new Failure("RESULT_UNKNOWN", true, "已完成Job缺少可核验输出Pod");
            String pod = pods.getItems().getFirst().getMetadata().getName();
            try (var response =
                    core.readNamespacedPodLog(pod, namespace).buildCall(null).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    throw new Failure("RESULT_UNKNOWN", true, "算法stdout结果暂不可读");
                var result = results.read(response.body().byteStream(), contract, callId, runId);
                db.update(
                        "update skill.external_job_binding set status='DONE' where tenant_id=? and"
                            + " call_id=?",
                        Actor.current().tenantId(),
                        callId);
                return result;
            }
        } catch (Failure ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure("RESULT_UNKNOWN", true, "K8s提交或查询失败，保留原Job绑定等待对账");
        }
    }
}
