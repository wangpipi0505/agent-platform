package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Roles;
import cn.agentplatform.engine.infra.TraceStore;
import cn.agentplatform.engine.infra.PortalRuntimeAccess.Grant;
import cn.agentplatform.engine.infra.PortalRuntimeAccess.Scope;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * B4-1（台账 §7.5）：确认中心待办角标——GET /admin/confirmation-center/counts（接口稿 §5.7）。
 *
 * <p>形状 {@code {publishReview,highRisk,planConfirmation,draftMemory,total}}，total=四项之和；
 * 计数=按可见待办的<b>未分页总数</b>（与四类列表一致性契约），授权过滤先于计数。可选 appCode：
 * 省略=当前主体全部授权范围（引擎侧无工作区成员数据源，授权范围=ADMIN/APPROVER 本租户、
 * 其余角色提交人本人，不放大——四类列表与角标共用同一范围口径）。
 *
 * <p>读取角色：ADMIN、APPROVER 与具有管理台确认中心入口的 BUILDER（EDITOR 为 BUILDER 兼容
 * 别名，照 F1-2 page-access 映射）；其余角色 403 {@code CONFIRMATION_CENTER_FORBIDDEN} 并记
 * 拒绝审计（引擎无独立审计表，归口 monitor，照 Memories/ChatSessions 结构化日志先例）。
 *
 * <p>四类口径（前端稿 §5.9 四类待办矩阵）：
 *
 * <ul>
 *   <li><b>publishReview</b>：app.integration_config_release 中未完结发布（status=VALIDATED
 *       待评审 + APPROVED 已批准未激活——卡面"待审 APPROVED 未激活"口径，批准与激活分离，
 *       激活后退出计数）。<b>计数按业务号 release_id 去重</b>（R-17：同号重复提交不重复计数；
 *       主键 (tenant_id, release_id) 一行业务号一行，validate/approve 为同行 CAS 迁移，
 *       count(distinct) 为口径显式化与防御性收口）。APPROVER 全部只读可见（审批动作=既有
 *       ConfigReleaseController ADMIN 门），即"可见但不可办理的待审发布"计入；BUILDER 限
 *       提交人本人（created_by）。同库跨 schema 只读计数（app 服务持有该表，engine 侧聚合
 *       计数不做逐行核验——与 ResourceManifestService "无 app schema 直查"的逐行语义核验
 *       边界不同，如实登记）。
 *   <li><b>highRisk</b>：InlineApproval 待审（engine.node_instance WAITING_APPROVAL），与
 *       {@link ApprovalService#list} 四类列表<b>共用同一待办条件</b>（节点/调用级确认角色
 *       过滤=被确认人+APPROVER，§5.9）。
 *   <li><b>planConfirmation</b>：{@link Tasks#confirmableRevisions} 投影计数——PLAN_READY
 *       任务每计划头修订 VALID 且无有效凭据（未确认或最大 confirm_seq 已过期），范围=
 *       ADMIN/APPROVER 本租户、其余角色提交人本人（§5.9 提交人本人/APPROVER/ADMIN）。
 *   <li><b>draftMemory</b>：memory_entry status=DRAFT 且可见（§5.9：共享层=ADMIN、私有层=
 *       来源用户）——ADMIN 计共享层+本人条目，其余角色计本人条目（owner_id）。
 * </ul>
 */
@Service
public class ConfirmationCenterService {
  private static final Set<String> ENTRY_ROLES = Set.of("ADMIN", "APPROVER", "BUILDER");
  private static final Set<String> TENANT_ROLES = Set.of("ADMIN", "APPROVER");

  private final TraceStore store;
  private final ApprovalService approvals;
  private final Tasks tasks;

  public ConfirmationCenterService(TraceStore store, ApprovalService approvals, Tasks tasks) {
    this.store = store;
    this.approvals = approvals;
    this.tasks = tasks;
  }

  public Map<String, Object> counts(String appCode, Actor actor) {
    return counts(appCode, actor, null);
  }

  public Map<String, Object> counts(String appCode, Actor actor, Grant grant) {
    String code = normalizedWorkspace(appCode);
    Scope eligible = grant == null ? Scope.ALL : grant.scope(ENTRY_ROLES);
    if (grant == null) {
      if (!Roles.hasAny(actor, Roles.ADMIN, Roles.APPROVER, Roles.BUILDER, Roles.EDITOR))
        reject(actor, code);
    } else if (eligible.empty() || code != null && !eligible.allows(code)) {
      reject(actor, code);
    }
    Scope wide = grant == null
            ? (Roles.hasAny(actor, Roles.ADMIN, Roles.APPROVER) ? Scope.ALL : Scope.NONE)
            : grant.scope(TENANT_ROLES);
    Scope admin = grant == null
            ? (Roles.has(actor, Roles.ADMIN) ? Scope.ALL : Scope.NONE) : grant.scope(Set.of("ADMIN"));
    int publishReview = countPublishReview(code, actor, eligible, wide);
    int highRisk = countHighRisk(code, actor, grant, eligible);
    int planConfirmation = grant == null ? tasks.confirmableRevisions(code, actor).size()
            : tasks.confirmableRevisions(code, actor, eligible, wide).size();
    int draftMemory = countDraftMemory(code, actor, eligible, admin);
    var out = new LinkedHashMap<String, Object>();
    out.put("publishReview", publishReview);
    out.put("highRisk", highRisk);
    out.put("planConfirmation", planConfirmation);
    out.put("draftMemory", draftMemory);
    out.put("total", publishReview + highRisk + planConfirmation + draftMemory);
    return out;
  }

  /** 管理台确认中心入口门：ADMIN/APPROVER/BUILDER(EDITOR) 可读（R-17：判定引 §3.1 授权目录常量）；越权 403+审计。 */
  private void reject(Actor actor, String appCode) {
    org.slf4j.LoggerFactory.getLogger(ConfirmationCenterService.class)
            .warn(
                    "AUDIT CONFIRMATION_CENTER_FORBIDDEN tenant={} principal={} roles={} appCode={}",
                    actor.tenantId(),
                    actor.principalId(),
                    actor.roles(),
                    appCode == null ? "" : appCode);
    throw new PlatformException(403, "CONFIRMATION_CENTER_FORBIDDEN", "无确认中心读取权限");
  }

  private static String normalizedWorkspace(String appCode) {
    if (appCode == null || appCode.isBlank()) return null;
    if (!appCode.matches("[A-Za-z0-9_-]{1,64}"))
      throw new PlatformException(400, "INVALID_INPUT", "appCode 形状非法");
    return appCode;
  }

  /**
   * 发布待审计数：跨 schema 只读（同库同实例，PG 唯一业务权威源）。口径=未完结发布
   * VALIDATED/APPROVED（激活后退出；SUPERSEDED/DISABLED/DRAFT 不计）。范围：ADMIN 与
   * APPROVER 覆盖本租户（APPROVER 只读可见=可见但不可办理，审批动作 ADMIN 门）；其余
   * 角色（BUILDER）限提交人本人（created_by 收窄）。
   */
  private int countPublishReview(String code, Actor actor, Scope eligible, Scope wide) {
    // R-17 计数口径：按业务号 release_id 去重（count(distinct)）——同号重复提交不重复计数。
    // 现状核实：app.integration_config_release 主键 (tenant_id, release_id) 一行业务号一行，
    // validate/approve 为同行 CAS 状态迁移不新增行，此去重为口径显式化与防御性收口。
    var where = new StringBuilder(" from app.integration_config_release r where r.tenant_id=?"
            + " and r.status in ('VALIDATED','APPROVED')");
    var args = new ArrayList<Object>();
    args.add(actor.tenantId());
    if (!eligible.all()) where.append(" and ").append(eligible.predicate("r.app_code", args));
    if (!wide.all()) {
      args.add(actor.principalId());
      where.append(" and (r.created_by=? or ").append(wide.predicate("r.app_code", args)).append(")");
    }
    if (code != null) {
      where.append(" and r.app_code=?");
      args.add(code);
    }
    Integer count =
            store.db()
                    .queryForObject(
                            "select count(distinct r.release_id)" + where,
                            Integer.class,
                            args.toArray());
    return count == null ? 0 : count;
  }

  /** 高危确认计数：与 InlineApproval 四类列表共用 ApprovalService.list 同一待办条件。 */
  private int countHighRisk(String code, Actor actor, Grant grant, Scope eligible) {
    var items = grant == null ? approvals.list(actor)
            : approvals.list(actor, app -> eligible.allows(app) ? grant.rolesFor(app) : Set.of());
    if (code == null) return items.size();
    return (int) items.stream().filter(item -> code.equals(item.get("appCode"))).count();
  }

  /** DRAFT 记忆计数：共享层=ADMIN（+本人条目），私有层=来源用户（owner_id）。 */
  private int countDraftMemory(String code, Actor actor, Scope eligible, Scope admin) {
    var where =
            new StringBuilder(
                    " from engine.memory_entry m where m.tenant_id=? and m.status='DRAFT'");
    var args = new ArrayList<Object>();
    args.add(actor.tenantId());
    if (!eligible.all()) where.append(" and ").append(eligible.predicate("m.workspace_code", args));
    args.add(actor.principalId());
    where.append(" and (m.owner_id=? or (m.scope='SHARED' and ")
            .append(admin.predicate("m.workspace_code", args)).append("))");
    if (code != null) {
      where.append(" and m.workspace_code=?");
      args.add(code);
    }
    Integer count = store.db().queryForObject("select count(*)" + where, Integer.class, args.toArray());
    return count == null ? 0 : count;
  }
}
