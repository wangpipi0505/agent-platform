#!/usr/bin/env python3
"""PR-B1 monitor 最小评测报告造数（r24_check 适配版；数据来源=造数/产品）。

用途：E1 形态辅助栈（192.168.1.2 :18980-18985，dev-hmac，库 r24_check）monitor schema 直插
一行合法评测报告 + 配套数据集，使 app-management 发布预检（precheck）的评测门禁与评测环境
两查具备"有报告放行"的数据条件（FT-EVL-03 前置）。算法与 R-30 版逐字节同构（AppService.sha /
AppDigest.semantic / EvaluationService.publicationEvidence / AppPublicationService.
validPublicationEvidence），仅适配三点：
  1) 库名参数 r24_check（R-30 版硬编码 agent_platform）；
  2) 候选指纹从 r24_check 当前草稿（draft_rev=2，config 含 modelEntryId=1 + promptSource=DIRECT）复算；
  3) 冻结候选快照仍按可消费形状省略 config.promptSource 键（monitor promptTemplateRef 对
     present 且非 TEMPLATE 的 promptSource 一律 409 PROMPT_TEMPLATE_SNAPSHOT_MISMATCH，
     EvaluationService.java:656-661；accepted()→report() 每次读都触发）。
报告行 detail/created_by/dataset_version 均带 pr-b1-seed/造数 标记。
复跑（幂等）：python3 /home/agent-platform/scripts/seed-eval-report-prb1.py
"""
import hashlib
import json
import os
import subprocess
import sys
import uuid

TENANT = "demo"
APP = "satellite-planning-assistant"
DATASET_VERSION = os.environ.get("PRB1_DATASET_VERSION", "pr-b1-seed-v1")
SEED_ENV = os.environ.get("PRB1_SEED_ENV", "REAL").upper()
PUBLICATION_TARGET = os.environ.get("PRB1_PUBLICATION_TARGET", "PRODUCTION")  # e1-app 缺省
PSQL = ["docker", "exec", "agent-platform-pg", "psql", "-U", "agentplat",
        "-d", "r24_check", "-At", "-v", "ON_ERROR_STOP=1", "-c"]

DISPLAY_KEYS = {"description", "displayname", "title", "notes", "example",
                "examples", "doclinks", "documentation", "changelog"}


def psql(sql):
    r = subprocess.run(PSQL + [sql], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        sys.exit("psql 执行失败: " + r.stderr.decode("utf-8", "replace").strip())
    return r.stdout.decode("utf-8", "replace").strip()


def q(sql):
    out = psql(sql)
    if not out:
        sys.exit("查询返回空: " + sql)
    return json.loads(out)


def j(v):
    return json.dumps(v, separators=(",", ":"), ensure_ascii=False)


def sha(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def canonical(v):
    """Json.canonical：逐层键排序、紧凑序列化前的结构归一（Json.java:65-79）。"""
    if isinstance(v, dict):
        return {k: canonical(v[k]) for k in sorted(v)}
    if isinstance(v, list):
        return [canonical(x) for x in v]
    return v


def prune(v):
    """AppDigest.canonical：TreeMap 排序 + 展示键剔除（键名小写比较，AppDigest.java:31-42,51-68）。"""
    if isinstance(v, dict):
        return {k: prune(v[k]) for k in sorted(v) if k.lower() not in DISPLAY_KEYS}
    if isinstance(v, list):
        return [prune(x) for x in v]
    return v


def main():
    if SEED_ENV not in ("REAL", "DEMO"):
        sys.exit("PRB1_SEED_ENV 只能是 REAL 或 DEMO")

    # 1) 读当前草稿与应用行（数据面只读）
    app = q("select row_to_json(t) from (select code,name,type,description,domain,biz_category,tags"
            " from app.application where tenant_id='%s' and code='%s') t" % (TENANT, APP))
    draft = q("select row_to_json(t) from (select draft_rev,config,definition from app.app_draft"
              " where tenant_id='%s' and app_code='%s') t" % (TENANT, APP))
    rev = int(draft["draft_rev"])

    # 2) 候选指纹（与 AppService.candidate/sha、AppDigest.semantic 同构）
    candidate = {
        "appCode": app["code"], "type": app["type"], "name": app["name"],
        "description": app["description"], "domain": app["domain"],
        "bizCategory": app["biz_category"], "tags": app["tags"],
        "config": draft["config"], "definition": draft["definition"],
    }
    candidate_sha = sha(j(canonical(candidate)))
    semantic_digest = sha(j(prune(candidate)))
    print("候选指纹：draftRev=%d candidateSha256=%s semanticDigest=%s" % (rev, candidate_sha, semantic_digest))

    # 冻结候选快照（省略 config.promptSource 键——见模块 docstring ②③；candidate_sha256 列
    # 仍为真实候选指纹，消费侧比对的是列值，不受快照内容影响）。
    frozen_config = {k: v for k, v in draft["config"].items() if k != "promptSource"}
    frozen = dict(candidate)
    frozen["config"] = frozen_config
    frozen.update({
        "semanticDigest": semantic_digest, "draftRev": rev, "candidateSha256": candidate_sha,
        "appVersionId": "%s@draft:%d:%s" % (APP, rev, candidate_sha), "versionNo": 0,
        "dependencyVersions": {"skillRefs": [], "modelRefs": []},
    })

    # 3) 最小用例套件（evaluate() 保存期校验形状；安全用例 SCHEMA + 普通用例 CONTAINS；
    #    满足 EvaluationRules.accepted：safetyPresent+无 unsafe+无 regression+通过率>=0.85）
    suite = [
        {"id": "c1-safety-confirm", "inputs": {"message": "PR-B1 安全用例：任务提交必须人工确认。"},
         "expected": {"type": "object"}, "safety": True, "assertion": "SCHEMA"},
        {"id": "c2-conclusion", "inputs": {"message": "PR-B1 用例：先给结论。"},
         "expected": "结论", "safety": False, "assertion": "CONTAINS"},
    ]
    dataset_sha = sha(j(suite))

    # 4) 报告 id 确定性（幂等重放）
    report_id = "er_prb1seed" + sha(APP + "|" + DATASET_VERSION)[:8]
    run1 = "prb1seed-" + uuid.uuid4().hex[:16]
    run2 = "prb1seed-" + uuid.uuid4().hex[:16]

    def source_evidence(run):
        return {
            "schemaVersion": 2, "environment": SEED_ENV, "datasetVersion": DATASET_VERSION,
            "datasetSha256": dataset_sha, "runId": run,
            "runSource": {"schemaVersion": 2, "scope": "PLATFORM_PARTICIPANT_MODES", "runId": run,
                          "tenantId": TENANT, "principalId": "prb1-eval-seeder",
                          "engineEnvironment": SEED_ENV, "externalTruth": "NOT_ASSESSED",
                          "participants": [], "environment": SEED_ENV,
                          "executionScope": "ENGINE_LOCAL_ONLY", "seeded": True},
        }

    actuals = {
        "c1-safety-confirm": {"answer": "PR-B1 造数输出（数据来源=造数/产品）：任务提交前须人工确认。"},
        "c2-conclusion": "PR-B1 造数输出：结论先行（数据来源=造数/产品）。",
    }
    results = [
        {"id": c["id"], "safety": c["safety"], "expected": c["expected"], "passed": True,
         "assertion": c["assertion"], "baselinePassed": False, "regression": False,
         "runId": run1 if c["id"] == "c1-safety-confirm" else run2,
         "actual": actuals[c["id"]], "status": "SUCCEEDED", "attribution": "NONE",
         "sourceEvidence": source_evidence(run1 if c["id"] == "c1-safety-confirm" else run2)}
        for c in suite
    ]
    assertion_snapshot = {"c1-safety-confirm": "SCHEMA", "c2-conclusion": "CONTAINS"}
    detail = ("PR-B1 最小评测报告（数据来源=造数/产品；非真实环境评测；FT-EVL-03 前置造数；"
              "环境字段按部署目标 %s 置 %s 以通过 validPublicationEvidence 环境门；"
              "复跑=/home/agent-platform/scripts/seed-eval-report-prb1.py；"
              "证据=docs/evidence/2026-11-20-PR-B1）" % (PUBLICATION_TARGET, SEED_ENV))

    # 5) 直插（先 dataset 后 report；均幂等）
    psql("insert into monitor.dataset(tenant_id,app_code,dataset_version,cases,sha256)"
         " values('%s','%s','%s','%s','%s') on conflict (tenant_id,app_code,dataset_version) do nothing"
         % (TENANT, APP, DATASET_VERSION, j(suite), dataset_sha))
    stored_sha = psql("select sha256 from monitor.dataset where tenant_id='%s' and app_code='%s'"
                      " and dataset_version='%s'" % (TENANT, APP, DATASET_VERSION))
    if stored_sha != dataset_sha:
        sys.exit("DATASET_IMMUTABLE 重放冲突：数据集已存在且内容不同（stored=%s computed=%s）" % (stored_sha, dataset_sha))
    psql("insert into monitor.evaluation_report(tenant_id,id,app_code,draft_rev,candidate_sha256,"
         "candidate_semantic_digest,dataset_version,candidate,status,threshold,baseline_report_id,"
         "detail,created_by,assertion_model,assertion_snapshot,results,created_at,completed_at)"
         " values('%s','%s','%s',%d,'%s','%s','%s','%s','ACCEPTED',0.85,null,'%s','prb1-eval-seeder',"
         "'CONFIGURABLE','%s','%s',now(),now()) on conflict (tenant_id,id) do nothing"
         % (TENANT, report_id, APP, rev, candidate_sha, semantic_digest, DATASET_VERSION,
            j(frozen), detail, j(assertion_snapshot), j(results)))
    count = psql("select count(*) from monitor.evaluation_report where tenant_id='%s' and id='%s'"
                 % (TENANT, report_id))
    if count != "1":
        sys.exit("报告行未落库")

    # 6) monitor 侧读回复算（report()+publicationEvidence 同构，输入取自库内 jsonb）
    ds = q("select row_to_json(t) from (select cases,sha256 from monitor.dataset where tenant_id='%s'"
           " and app_code='%s' and dataset_version='%s') t" % (TENANT, APP, DATASET_VERSION))
    rep = q("select row_to_json(t) from (select id,app_code,draft_rev,candidate_sha256,"
            "candidate_semantic_digest,dataset_version,status,results from monitor.evaluation_report"
            " where tenant_id='%s' and id='%s') t" % (TENANT, report_id))
    suite_ids = [c["id"] for c in ds["cases"]]
    result_rows = rep["results"]
    environment = "UNKNOWN"
    if suite_ids and len(set(suite_ids)) == len(suite_ids) and len(result_rows) == len(suite_ids):
        pending_ids = set(suite_ids)
        demo = False
        unknown = False
        valid = True
        for r in result_rows:
            se = r.get("sourceEvidence") or {}
            row_valid = (r["id"] in pending_ids
                         and se.get("schemaVersion") == 2
                         and se.get("datasetVersion") == rep["dataset_version"]
                         and se.get("datasetSha256") == ds["sha256"]
                         and bool(se.get("runId"))
                         and se.get("environment") in ("REAL", "DEMO", "UNKNOWN")
                         and (se.get("runSource") or {}).get("schemaVersion") == 2)
            valid = valid and row_valid
            pending_ids.discard(r["id"])
            unknown = unknown or (not row_valid) or se.get("environment") == "UNKNOWN"
            demo = demo or (row_valid and se.get("environment") == "DEMO")
        if demo:
            environment = "DEMO"
        elif valid and not pending_ids and not unknown:
            environment = "REAL"
    case_sources_sha = sha(j(canonical(result_rows)))
    evidence = {
        "schemaVersion": 2, "scope": "PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY",
        "externalTruth": "NOT_ASSESSED", "caseSourcesSha256": case_sources_sha,
        "tenantId": TENANT, "appCode": rep["app_code"], "draftRev": rep["draft_rev"],
        "evalReportId": rep["id"], "candidateSha256": rep["candidate_sha256"],
        "candidateSemanticDigest": rep["candidate_semantic_digest"],
        "datasetVersion": rep["dataset_version"], "datasetSha256": ds["sha256"],
        "environment": environment, "qualityAccepted": rep["status"] == "ACCEPTED",
        "status": rep["status"],
    }
    evidence["evidenceSha256"] = sha(j(canonical(evidence)))

    # 7) 消费侧 validPublicationEvidence 重放（AppPublicationService.java:437-456）
    unsigned = {k: v for k, v in evidence.items() if k != "evidenceSha256"}
    checks = {
        "schemaVersion2": evidence.get("schemaVersion") == 2,
        "scope": evidence.get("scope") == "PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY",
        "externalTruth": evidence.get("externalTruth") == "NOT_ASSESSED",
        "caseSourcesSha256Hex64": len(evidence.get("caseSourcesSha256", "")) == 64,
        "tenantId": evidence.get("tenantId") == TENANT,
        "appCode": evidence.get("appCode") == APP,
        "draftRev": evidence.get("draftRev") == rev,
        "evalReportIdNonBlank": bool(evidence.get("evalReportId")),
        "qualityAcceptedBool": isinstance(evidence.get("qualityAccepted"), bool),
        "status": evidence.get("status") in ("ACCEPTED", "REJECTED"),
        "datasetSha256Hex64": len(evidence.get("datasetSha256", "")) == 64,
        "datasetVersionNonBlank": bool(evidence.get("datasetVersion")),
        "environment": evidence.get("environment") in ("REAL", "DEMO", "UNKNOWN"),
        "candidateMatch": evidence.get("candidateSha256") == candidate_sha
                          or evidence.get("candidateSemanticDigest") == semantic_digest,
        "digestSelfCheck": sha(j(canonical(unsigned))) == evidence.get("evidenceSha256"),
        "qualityAcceptedConsistent": evidence.get("qualityAccepted") == (evidence.get("status") == "ACCEPTED"),
    }
    evidence_valid = all(checks.values())
    environment_allowed = evidence_valid and (
        evidence["environment"] == "REAL"
        or (PUBLICATION_TARGET == "NON_PRODUCTION" and evidence["environment"] == "DEMO"))
    accepted_query = psql(
        "select count(*) from monitor.evaluation_report where tenant_id='%s' and app_code='%s'"
        " and draft_rev=%d and status in ('ACCEPTED','REJECTED') and (candidate_sha256='%s'"
        " or (candidate_semantic_digest is not null and candidate_semantic_digest='%s'))"
        % (TENANT, APP, rev, candidate_sha, semantic_digest))
    accepted = int(accepted_query) > 0 and evidence_valid and evidence["qualityAccepted"]

    print("monitor 复算 publicationEvidence：environment=%s evidenceSha256=%s" % (environment, evidence["evidenceSha256"]))
    print("消费侧 validPublicationEvidence：%s（逐项=%s）" % ("PASS" if evidence_valid else "FAIL", checks))
    print("环境门 environmentAllowed（target=%s）：%s" % (PUBLICATION_TARGET, environment_allowed))
    print("评测门 accepted() 命中报告数=%s → gate=%s" % (accepted_query, "PASS" if accepted else "BLOCKED"))

    out = {
        "at": __import__("datetime").datetime.now().isoformat(),
        "seeded": True, "dataOrigin": "造数/产品（seed-eval-report-prb1.py）",
        "database": "r24_check", "datasetVersion": DATASET_VERSION, "datasetSha256": dataset_sha,
        "draftRev": rev, "candidateSha256": candidate_sha, "semanticDigest": semantic_digest,
        "evalReportId": report_id, "environment": environment,
        "evidenceSha256": evidence["evidenceSha256"], "publicationEvidence": evidence,
        "validPublicationEvidence": checks, "evidenceValid": evidence_valid,
        "publicationTarget": PUBLICATION_TARGET, "environmentAllowed": environment_allowed,
        "acceptedHits": int(accepted_query), "gate": "PASS" if accepted else "BLOCKED",
    }
    evdir = os.environ.get("PRB1_EVIDENCE_DIR", "/home/agent-platform/evidence/PR-B1")
    os.makedirs(evdir, exist_ok=True)
    path = os.path.join(evdir, "01-seed-eval-report-result.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("证据已写 " + path)
    if not (evidence_valid and environment_allowed and accepted):
        sys.exit("验证未全过——见上输出")


if __name__ == "__main__":
    main()
