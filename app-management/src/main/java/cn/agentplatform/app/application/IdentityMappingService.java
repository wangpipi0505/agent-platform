package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.IdentityMapping;
import cn.agentplatform.app.infra.IdentityMappingRepository;
import cn.agentplatform.common.Actor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static cn.agentplatform.app.application.AppService.error;

/** PLAT-12/X-5：平台主体身份映射。写入只取已验 Actor 的 claims，绝不采信 body 身份。 */
@Service
public class IdentityMappingService {
    private final IdentityMappingRepository repository;

    public IdentityMappingService(IdentityMappingRepository repository) {
        this.repository = repository;
    }

    /** 以验签后的 subject 快照写入/刷新该主体的当前映射（UPSERT 语义见 V107）。事务边界：本方法整体原子。 */
    @Transactional
    public IdentityMapping put(Actor actor) {
        return repository.upsert(actor.subject(), actor.principalId());
    }

    public Optional<IdentityMapping> find(String tenantId, String principalId) {
        return repository.find(tenantId, principalId);
    }

    /** 映射不存在必须显式失败：出站注入宁可拒绝调用，也不静默造一份主体身份。 */
    public IdentityMapping require(String tenantId, String principalId) {
        return repository
                .find(tenantId, principalId)
                .orElseThrow(() -> error(404, "IDENTITY_MAPPING_NOT_FOUND", "该主体尚未建立身份映射"));
    }
}
