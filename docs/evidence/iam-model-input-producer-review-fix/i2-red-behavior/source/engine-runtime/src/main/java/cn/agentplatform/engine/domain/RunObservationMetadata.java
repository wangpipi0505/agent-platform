package cn.agentplatform.engine.domain;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;

/** Data only: no retained proof, actor, grant, or conversion into a read/write capability. */
public record RunObservationMetadata(String runId,SubjectKey owner,ResourceBinding root,
        long appVersionId,long appVersionNo,String acceptedSnapshotSha256,String initialCeilingSha256) {
    public RunObservationMetadata {
        if(runId==null||!runId.matches("r_[0-9a-f]{32}")||owner==null
                ||owner.tenantId().length()>128||owner.principalId().length()>128
                ||root==null||!"app-version".equals(root.kind())||root.id()==null
                ||!root.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}@"+appVersionNo)
                ||appVersionId<1||appVersionNo<1||!hash(root.versionDigest())
                ||!hash(acceptedSnapshotSha256)||!hash(initialCeilingSha256))
            throw new PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");
    }
    private static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
}
