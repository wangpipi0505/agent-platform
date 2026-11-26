package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.PublishedMetadata;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PublishedMetadataService {
    private final JdbcTemplate db;
    private final PlatformAccessService access;

    public PublishedMetadataService(JdbcTemplate db) {
        this.db = db;
        this.access = new PlatformAccessService(db);
    }
    public PublishedMetadata published(String code){
        var actor=Actor.current();
        actor=AuthorityContext.requireFor(actor,MetadataSelectors.published(actor.tenantId(),code)).actor();
        access.require(actor, PlatformAccessService.VIEW, code);
        return read(actor.tenantId(),code);
    }

    public PublishedMetadata integrationPublished(String code) {
        var expected = IntegrationTaskRoutes.metadataCall(code);
        var current = AuthorityContext.requireFor(Actor.current(), expected.resource());
        if (!expected.equals(current.call()) || !"gateway".equals(current.caller())
                || !"INTEGRATION".equals(current.operation().originKind())
                || !IntegrationTaskRoutes.submitRoles(current.actor().roles()))
            throw new PlatformException(403, "APP_BINDING_DENIED", "当前授权不允许查询接入应用版本");
        return read(current.actor().tenantId(), code);
    }

    /**
     * 组合用的一份发布元数据：读同一行、同一不可变版本，但不走 PORTAL 选择器。
     * 调用方是应用侧的公开读投影（{@code GET /v1/apps/{code}/experience}），已由路由目录与租户门禁授权，
     * 且投影只含声明字段（不含凭据、不含管理路径），因此这里不再叠加选择器权限；
     * 需要凭据或管理视角的调用必须继续走 {@link #published(String)}。
     */
    public PublishedMetadata composition(String code){
        var actor=Actor.current();
        return read(actor.tenantId(),code);
    }

    /**
     * 某个已发布版本精确引用的技能版本（code + version），按版本号确定性读取 {@code app.skill_reference}。
     * 应用版本不可变，所以这份引用等价于"该候选当时校验过的技能集合"，不需要另存副本。
     */
    public List<Map<String,Object>> skillReferences(String code,int versionNo){
        var actor=Actor.current();
        return db.query("select skill_code,skill_version from app.skill_reference "
                +"where tenant_id=? and app_code=? and app_version_no=? order by skill_code",
            (r,n)->{
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("code",r.getString("skill_code"));
                row.put("version",r.getLong("skill_version"));
                return row;
            },actor.tenantId(),code,versionNo);
    }

    private PublishedMetadata read(String tenant,String code){
        // A single statement reads status and the pointed immutable version in the same PG snapshot.
        // The selector digest versions the query semantics; the returned SHA is the stored candidate SHA.
        var rows=db.query("select a.id,a.code,a.status,a.current_version_no,v.id as version_id,v.version_no,v.candidate_sha256 "
                +"from app.application a left join app.app_version v on v.tenant_id=a.tenant_id and v.app_code=a.code "
                +"and v.app_id=a.id and v.version_no=a.current_version_no where a.tenant_id=? and a.code=?",
            (r,n)->{
                if(java.util.Set.of("DISABLED","ARCHIVED").contains(r.getString("status"))||r.getObject("version_id")==null)
                    throw new PlatformException(409,"APP_UNAVAILABLE","应用当前不可用或尚未发布");
                return new PublishedMetadata(r.getString("code"),r.getLong("version_id"),r.getInt("version_no"),r.getString("candidate_sha256"));
            },tenant,code);
        if(rows.isEmpty())throw new PlatformException(404,"APP_NOT_FOUND","应用不存在");
        return rows.getFirst();
    }
}
