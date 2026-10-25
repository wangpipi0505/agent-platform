package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.PublishedMetadata;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PublishedMetadataService {
    private final JdbcTemplate db;
    public PublishedMetadataService(JdbcTemplate db){this.db=db;}
    public PublishedMetadata published(String code){
        var actor=Actor.current();
        actor=AuthorityContext.requireFor(actor,MetadataSelectors.published(actor.tenantId(),code)).actor();
        // A single statement reads status and the pointed immutable version in the same PG snapshot.
        // The selector digest versions the query semantics; the returned SHA is the stored candidate SHA.
        var rows=db.query("select a.id,a.code,a.status,a.current_version_no,v.id as version_id,v.version_no,v.candidate_sha256 "
                +"from app.application a left join app.app_version v on v.tenant_id=a.tenant_id and v.app_code=a.code "
                +"and v.app_id=a.id and v.version_no=a.current_version_no where a.tenant_id=? and a.code=?",
            (r,n)->{
                if(java.util.Set.of("DISABLED","ARCHIVED").contains(r.getString("status"))||r.getObject("version_id")==null)
                    throw new PlatformException(409,"APP_UNAVAILABLE","应用当前不可用或尚未发布");
                return new PublishedMetadata(r.getString("code"),r.getLong("version_id"),r.getInt("version_no"),r.getString("candidate_sha256"));
            },actor.tenantId(),code);
        if(rows.isEmpty())throw new PlatformException(404,"APP_NOT_FOUND","应用不存在");
        return rows.getFirst();
    }
}
