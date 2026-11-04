package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.OnboardingImportReceipts;
import org.springframework.stereotype.Component;
import java.util.*;

/** 导入回执只绑定该主体的精确技能字节；客户端不能用自报bindingRefs替换受控测试目的地。 */
@Component
public class OnboardingExecutionBindings {
    private final OnboardingImportReceipts receipts;
    public OnboardingExecutionBindings(OnboardingImportReceipts receipts){this.receipts=receipts;}
    public Map<String,Object> attach(Actor actor,Map<String,Object> current) {
        long id=((Number)current.get("skillVersionId")).longValue();var found=receipts.findBySkillVersion(actor,id);
        if(found.isEmpty()){
            if(receipts.hasSkillVersion(actor,id))throw new PlatformException(403,"ONBOARDING_IMPORT_OWNER_DENIED","导入草稿测试只接受原会话当前主体");
            return current;
        }
        var receipt=found.get();String sha=Objects.toString(current.get("sha256"),"");
        if(!actor.principalId().equals(receipt.principalId())||receipt.skillVersionId()!=id
                ||!receipt.code().equals(current.get("code"))||receipt.version()!=((Number)current.get("versionNo")).intValue()
                ||!receipt.sha256().equals(sha)||!sha.equals(Digest.sha256(Json.canonical(current.get("definition")))))
            throw denied("接入导入回执与当前精确技能版本不一致");
        if(receipt.bindingRefs().size()!=1)throw denied("接入受控测试必须固定一个连接");
        var ref=receipt.bindingRefs().getFirst();
        if(!Set.of("profileId","profileRev","bindingId","bindingRev","operation","environment","resolutionDigest").containsAll(ref.keySet())
                ||!receipt.operationId().equals(ref.get("operation"))
                ||!validId(ref.get("profileId"))||!validId(ref.get("bindingId"))
                ||!positive(ref.get("profileRev"))||!positive(ref.get("bindingRev"))
                ||!(ref.get("environment") instanceof String env)||!env.matches("[a-z][a-z0-9-]{0,31}")
                ||ref.containsKey("resolutionDigest")&&(!(ref.get("resolutionDigest") instanceof String digest)||!digest.matches("[a-f0-9]{64}")))
            throw denied("接入回执的固定连接引用无效");
        var source=receipt.source();
        if(!receipt.sourceSha256().equals(Digest.sha256(Json.canonical(source)))
                ||!receipt.requestSha256().matches("[a-f0-9]{64}"))throw denied("导入来源摘要不完整或已变化");
        if(!(source.get("sessionId") instanceof String sessionId)||!sessionId.matches("os_[a-f0-9]{32}")
                ||!(source.get("planSha256") instanceof String planSha)||!planSha.matches("[a-f0-9]{64}"))
            throw denied("接入回执缺少冻结会话来源");
        var metadata=new LinkedHashMap<String,Object>();metadata.put("receiptId",receipt.receiptId());metadata.put("sessionId",sessionId);
        metadata.put("planSha256",planSha);metadata.put("sourceSha256",receipt.sourceSha256());metadata.put("requestSha256",receipt.requestSha256());
        metadata.put("operationId",receipt.operationId());metadata.put("skillVersionId",id);metadata.put("sha256",sha);
        metadata.put("bindingRefsDigest",Digest.sha256(Json.canonical(receipt.bindingRefs())));
        var out=new LinkedHashMap<>(current);out.put("onboardingReceiptRef",metadata);out.put("executionBindingRefs",receipt.bindingRefs());return out;
    }
    private static boolean validId(Object value){return value instanceof String id&&id.matches("[a-z][a-z0-9-]{2,47}");}
    private static boolean positive(Object value){return value instanceof Number n&&n.intValue()>0&&n.doubleValue()==n.intValue();}
    private static PlatformException denied(String message){return new PlatformException(409,"ONBOARDING_IMPORT_BINDING_MISMATCH",message);}
}
