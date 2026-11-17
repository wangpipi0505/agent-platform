package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

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
 *       激活后退出计数）。APPROVER 全部只读可见（审批动作=既有 ConfigReleaseController
 *       ADMIN 门），即"可见但不可办理的待审发布"计入；BUILDER 限提交人本人（created_by）。
 *       同库跨 schema 只读计数（app 服务持有该表，engine 侧聚合计数不做逐行核验——与
 *       ResourceManifestService "无 app schema 直查"的逐行语义核验边界不同，如实登记）。
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

  private final TraceStore store;
  private final ApprovalService approvals;
  private final Tasks tasks;

  public ConfirmationCenterService(TraceStore store, ApprovalService approvals, Tasks tasks) {
    this.store = store;
    this.approvals = approvals;
    this.tasks = tasks;
  }

  public Map<String, Object> counts(String appCode, Actor actor) {
    requireEntry(actor, appCode);
    String code = normalizedWorkspace(appCode);
    boolean admin = actor.roles().contains("ADMIN");
    int publishReview = countPublishReview(code, actor, admin);
    int highRisk = countHighRisk(code, actor);
    int planConfirmation = tasks.confirmableRevisions(code, actor).size();
    int draftMemory = countDraftMemory(code, actor, admin);
    var out = new LinkedHashMap<String, Object>();
    out.put("publishReview", publishReview);
    out.put("highRisk", highRisk);
    out.put("planConfirmation", planConfirmation);
    out.put("draftMemory", draftMemory);
    out.put("total", publishReview + highRisk + planConfirmation + draftMemory);
    return out;
  }

  /** 管理台确认中心入口门：ADMIN/APPROVER/BUILDER(EDITOR) 可读；越权 403+审计。 */
  private void requireEntry(Actor actor, String appCode) {
    if (actor.roles().contains("ADMIN")
            || actor.roles().contains("APPROVER")
            || actor.roles().contains("BUILDER")
            || actor.roles().contains("EDITOR"))
      return;
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
  private int countPublishReview(String code, Actor actor, boolean admin) {
    boolean tenantWide = admin || actor.roles().contains("APPROVER");
    var where = new StringBuilder(" from app.integration_config_release r where r.tenant_id=?"
            + " and r.status in ('VALIDATED','APPROVED')");
    var args = new ArrayList<Object>();
    args.add(actor.tenantId());
    if (!tenantWide) {
      where.append(" and r.created_by=?");
      args.add(actor.principalId());
    }
    if (code != null) {
      where.append(" and r.app_code=?");
      args.add(code);
    }
    Integer count = store.db().queryForObject("select count(*)" + where, Integer.class, args.toArray());
    return count == null ? 0 : count;
  }

  /** 高危确认计数：与 InlineApproval 四类列表共用 ApprovalService.list 同一待办条件。 */
  private int countHighRisk(String code, Actor actor) {
    var items = approvals.list(actor);
    if (code == null) return items.size();
    return (int) items.stream().filter(item -> code.equals(item.get("appCode"))).count();
  }

  /** DRAFT 记忆计数：共享层=ADMIN（+本人条目），私有层=来源用户（owner_id）。 */
  private int countDraftMemory(String code, Actor actor, boolean admin) {
    var where =
            new StringBuilder(
                    " from engine.memory_entry m where m.tenant_id=? and m.status='DRAFT'");
    var args = new ArrayList<Object>();
    args.add(actor.tenantId());
    if (admin) {
      where.append(" and (m.scope='SHARED' or m.owner_id=?)");
      args.add(actor.principalId());
    } else {
      where.append(" and m.owner_id=?");
      args.add(actor.principalId());
    }
    if (code != null) {
      where.append(" and m.workspace_code=?");
      args.add(code);
    }
    Integer count = store.db().queryForObject("select count(*)" + where, Integer.class, args.toArray());
    return count == null ? 0 : count;
  }
}
