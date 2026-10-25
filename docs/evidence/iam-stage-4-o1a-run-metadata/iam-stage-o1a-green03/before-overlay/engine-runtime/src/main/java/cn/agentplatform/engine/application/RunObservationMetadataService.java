package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.domain.RunObservationMetadata;
import cn.agentplatform.engine.infra.RunAuthorityStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public final class RunObservationMetadataService {
    private final RunAuthorityStore store;
    // No IAM-only bean: legacy assembly remains possible, while the endpoint requires a live frame.
    public RunObservationMetadataService(JdbcTemplate db){store=new RunAuthorityStore(Objects.requireNonNull(db.getDataSource()),Clock.systemUTC());}
    public RunObservationMetadata metadata(String publicRunId){
        // Reject before requireInitialFor: the online refresh may never inherit a caller transaction.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("OBSERVATION_REQUIRES_NO_CALLER_TRANSACTION");
        var current=AuthorityContext.current();
        var owner=new SubjectKey(current.actor().tenantId(),current.actor().principalId());
        var selector=RunObservationSelectors.metadata(owner,publicRunId);
        requireQuery(current,selector);
        var proof=AuthorityContext.requireInitialFor(current.actor(),selector);
        requireQuery(proof.current(),selector);
        var result=store.observationMetadata(owner,publicRunId,proof);
        if(Json.write(result).getBytes(StandardCharsets.UTF_8).length>4096)
            throw new PlatformException(403,"RUN_METADATA_TOO_LARGE","运行观察元数据超出范围");
        // Local proof check immediately before the service returns, not a claim about later wire I/O.
        AuthorityContext.requireBound(proof);requireQuery(proof.current(),selector);
        return result;
    }
    private static void requireQuery(VerifiedAuthority current,ResourceBinding selector){
        RunObservationSelectors.requirePurpose(current.call(),current.caller(),"delegated",current.operation().sourceKind(),current.operation().originKind());
        if(!RunObservationSelectors.ACTION.equals(current.call().action())||!selector.equals(current.call().resource())
                ||!selector.equals(current.operation().resource()))throw RunAuthorityStore.denied();
    }
}
