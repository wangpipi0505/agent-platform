# Nested B2 PostgreSQL timeout installation fix — implementer report

Status: DONE. No commits, deployments, primary launcher restart, or whole B2/global acceptance. All source/target/owned/helper/report write rights are returned by the frozen final handoff at .local/iam-o2b-f2c2b2-query-timeout-fix/handoff.json; inventory.json and the handoff identity bind this report without a circular self-hash.

## Implemented behavior and exact scope

Only these source changes were made:
- MODIFY engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java
- NEW engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderTimeoutBudgetPostgresTest.java

Work.query now chooses max(1, remainingWholeMillis/2) for each LOCAL statement_timeout installation, including retries derived from newly measured remaining time. A concise comment explains the SET round-trip headroom and PG zero disabling timeout. Three-attempt bound, final remaining>=installed validation, monotonic entered/allowance/capture, live/interrupt/authority checks, parameterized set_config(..., true), parameter preparation order, resource closure, row/material limits, and fail-closed behavior are unchanged. The successful SELECT checks PG's actual pg_settings timeout on the same transaction/connection, not just the observed bound parameter. No authorization/private-field forging or unbounded fallback was added.

Source637→638 was frozen by hashing each existing frozen source plus the one authorized new file. Exact relation: one modified, one new, other636 unchanged. The three original B2 sources and every other frozen source remain exact. No protected-eight/design/contract writes. Frozen source snapshot: .local/iam-o2b-f2c2b2-query-timeout-fix/final/source/. Original accepted B1 source634 remains the global accepted baseline; this nested638 and historical logical2333 are not global acceptance.

Changed source identities:
[
  {
    "file": "engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java",
    "bytes": 38442,
    "sha256": "7922909e5dd00f033cdbe928497c8c7c59bb79b95f28a4e5c392b4dbe650a99e"
  },
  {
    "file": "engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEventReaderTimeoutBudgetPostgresTest.java",
    "bytes": 8353,
    "sha256": "5f2af65071eb33124825cc05cb207f03d07289fbad298ab786eb646f14fb69b1"
  }
]

## Failure-first evidence

Exact focused commands, all from G:/207/项目/算法重组工具/代码/agent-platform:
- & 'C:/Program Files/nodejs/node.exe' '.tools/iam-o2b-f2c2b2-query-timeout-fix-run.mjs' red-01
- & 'C:/Program Files/nodejs/node.exe' '.tools/iam-o2b-f2c2b2-query-timeout-fix-run.mjs' red-02
- & 'C:/Program Files/nodejs/node.exe' '.tools/iam-o2b-f2c2b2-query-timeout-fix-run.mjs' green-01

Each runner invokes the same finite Maven command (the final evidence path varies by phase):
mvn.cmd -o -B -ntp -pl engine-runtime -am test -Dtest=RunEventReaderTimeoutBudgetPostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dtimeout.fix.evidence=G:\207\项目\算法重组工具\代码\agent-platform\docs\evidence\iam-stage-4-o2b-f2c2b2-query-timeout-fix\green-01\observer

The credential is privately read from this new project's .env AGENT_DB_PASSWORD into the child environment; no credential, ticket, raw payload, or private authorization fields enter evidence/output. JDK21.0.12+8, Maven offline with own .tools/m2, PG own127.0.0.1:25432/user agent_v12, child unix-domain tmpdir V:/.local/tmp. Only RunEventReaderTimeoutBudgetPostgresTest is selected; no unrelated old green PG/GC/replay/provider suite, old B2 test06, or joined test07/08/09 was rerun. No migrations/business tables are needed for this focused real SQL query test. The legitimate RunEventReaderAuthorityFixture.open + response.currentCapture API supplies the actual capture; the historical fixed-DB Fixture is never instantiated.

- red-01: compile succeeded; three fixture errors at RunObservationSelectors.status due an invalid short r_1 test selector, before Work. This is explicitly NOT behavioral RED. Corrected only the new test to the existing required r_32hex selector. Preserved original helper/log/XML/source/target epoch. Its random database was created before failure; the actual catalog later recovered its task-prefix name as timeout_fix_ced27202d9b24c10aca31d37f3c91452. Attribution to red-01 is explicitly an inference from the one otherwise-unidentified task-exclusive prefix database, not an original creation witness.
- red-02: actual behavioral RED, production still old SHA e28220170196232b6eae4b2e705c8d0b2115175e39acb8a89299de63b5f7112c. Three tests ran: two denial tests passed, live positive test failed via production Work.query344 (1 runtime error; 0 assertion failures). Actual settings [4965,4960,4954]ms; remaining immediately after real SET plus bounded4ms delay [4960,4954,4949]ms; business SELECT count0 while 4948ms remained. This reproduces installation rejection, not fixture/compile failure or exhausted authority.
- green-01: exact same test bytes as behavioral RED; only authorized production headroom change. Tests3/failures0/errors0/skipped0, Maven exit0. Actual SELECT returned42 and actual database identity, actual PG timeout2481ms, remaining at business execution4958ms, count1. Consumed-budget case used an older entered anchor (never extended budget), installed1ms then observed negative remaining and denied with business0. SET-failure case executes real PG SET then injects a SQLException at completion and verifies Spring DataAccessException/business0, proving no unbounded business fallback.

The test-local delegating DataSource/PreparedStatement observer executes actual PG SET/business SQL and sleeps4ms only after actual SET completion. Business timeout observation is before its real executeQuery, and row output confirms PG received the same positive setting. The short-budget test tolerates host variability: denial can happen even earlier during setup and must always precede the business SELECT. In the recorded GREEN it reached SET and expired afterward.

Exact epochs, raw logs, actual Surefire XML (each includes properties plus testcase results), native process exits, compiler input/created/status lists, resource and target copies, and statement observer JSON are under docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/{red-01,red-02,green-01}/. The full phase index is .local/iam-o2b-f2c2b2-query-timeout-fix/final/phase-index.json. Key identities and actual XML suite headers:

[
  {
    "phase": "red-01",
    "source": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/red-01/source.json",
      "bytes": 122680,
      "sha256": "b1a3513a317ec654c813d06c94a19229bb09e69238b651257789400734d0f3b0"
    },
    "raw": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/red-01/raw.log",
      "bytes": 10098,
      "sha256": "52ace2a29bfb5e295bc65f0b32e6d4fa4277296b1f6f2328cd55015234fd01fb"
    },
    "xml": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/red-01/finite-target/engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest.xml",
      "bytes": 49348,
      "sha256": "aac3b5bb59090caa17781142fe0e54060e96b1d26eb80e5801f16442cd7d9480"
    },
    "xmlSuite": "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd\" version=\"3.0.2\" name=\"cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest\" time=\"1.449\" tests=\"3\" errors=\"3\" skipped=\"0\" failures=\"0\">",
    "exit": 1,
    "pid": 56544,
    "waitForReturned": true,
    "collector": "synchronous child stdio to closed raw-file fd; no detached collectors",
    "children": []
  },
  {
    "phase": "red-02",
    "source": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/red-02/source.json",
      "bytes": 122680,
      "sha256": "9e9275143ba5402aa2fc35ac9edfd52d75e3b77363c4de28324a4787f41231de"
    },
    "raw": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/red-02/raw.log",
      "bytes": 7978,
      "sha256": "63d9dc59d16ae5f177519927cbd6d6e80b0f5806cd88650f9082bd21f0967091"
    },
    "xml": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/red-02/finite-target/engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest.xml",
      "bytes": 47529,
      "sha256": "08bf23e41483a59349374b4a8f36ed4c6af467fccd670584cc3237f3a1ded2cb"
    },
    "xmlSuite": "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd\" version=\"3.0.2\" name=\"cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest\" time=\"2.104\" tests=\"3\" errors=\"1\" skipped=\"0\" failures=\"0\">",
    "exit": 1,
    "pid": 2392,
    "waitForReturned": true,
    "collector": "synchronous child stdio to closed raw-file fd; no detached collectors",
    "children": [
      {
        "pid": 34000,
        "aliveAfterMavenExit": false,
        "exitWitness": "Surefire completed XML + Maven waited for fork exit + PID no longer live"
      }
    ]
  },
  {
    "phase": "green-01",
    "source": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/green-01/source.json",
      "bytes": 122680,
      "sha256": "2c99f0fbfa5dee8becbab881bbaadae0fbe62c4c04ec6918bc9c86a1ff093174"
    },
    "raw": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/green-01/raw.log",
      "bytes": 6531,
      "sha256": "652c855bbfca736a5bbb4b2febfe6c1fad03a5777b1eb25bdad3b680f71e1757"
    },
    "xml": {
      "file": "docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/green-01/finite-target/engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest.xml",
      "bytes": 46319,
      "sha256": "1b9640d58f8e269ec25044d9869a4328445f7244e48d55e42dd0891f65421855"
    },
    "xmlSuite": "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd\" version=\"3.0.2\" name=\"cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest\" time=\"1.987\" tests=\"3\" errors=\"0\" skipped=\"0\" failures=\"0\">",
    "exit": 0,
    "pid": 27704,
    "waitForReturned": true,
    "collector": "synchronous child stdio to closed raw-file fd; no detached collectors",
    "children": [
      {
        "pid": 51320,
        "aliveAfterMavenExit": false,
        "exitWitness": "Surefire completed XML + Maven waited for fork exit + PID no longer live"
      }
    ]
  }
]

Maven launcher processes return synchronously; raw file descriptors close after exit, with no detached collectors. Surefire completes actual XML and Maven waits for its fork. red-02 JVM34000 and green-01 JVM51320 are additionally witnessed by test JSON and verified no longer live after Maven exit. red-01 failed before test observer/PID creation; its completed three-error XML and normal Maven failure exit are retained, with no separately cached fork PID or invented exit code. The later metadata-only DatabaseIdentity Java child30172 exited0 with synchronous stdout/stderr collection; command/result/raw are .local/iam-o2b-f2c2b2-query-timeout-fix/database-identity.json and .log. Its PG catalog check reported server_version_num160015, current user agent_v12, admin database postgres, and exactly the three random task database names. No successful test or native Git operation was repeated for metadata.

## Source and finite target freeze

{
  "at": "2026-10-21T20:57:40.132Z",
  "source": {
    "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/final/source-current.json",
    "bytes": 122680,
    "sha256": "2c99f0fbfa5dee8becbab881bbaadae0fbe62c4c04ec6918bc9c86a1ff093174"
  },
  "sourceRelation": {
    "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/final/source-relation.json",
    "bytes": 912,
    "sha256": "aaadfafb316f9f307ce5eb86e6e4080785e66999302900c5abe3c865af6abb9e"
  },
  "finite": {
    "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/final/target-current-finite.json",
    "bytes": 119807,
    "sha256": "ea3282e2f0204b83508852dad63846c280eff90373d576ef5aab17b1f9592d7a"
  },
  "finiteCount": 541,
  "delta": {
    "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/final/target-delta.json",
    "bytes": 4042,
    "sha256": "e70e59b2b6dc4f2a868a2e40bf5ebb61db46b4e7f502fe5becb174426e71a339"
  },
  "logical": {
    "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/final/target-logical-merged.json",
    "bytes": 490424,
    "sha256": "5d7b253dc6992c270821baa472ef36f96d2cc671c93b130062b32506965366ff"
  },
  "logicalCount": 2333,
  "phases": {
    "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/final/phase-index.json",
    "bytes": 4661,
    "sha256": "a878baa496756dab45db098d6ca20cf4147c7690886ccc9d328e58a48653b19d"
  }
}

Actual finite target541 is enumerated from admin-common/engine-runtime compiler input+created/status lists, their copied source resources, common test-fixture JAR, and the one actual Running class's XML/text. Snapshot: .local/iam-o2b-f2c2b2-query-timeout-fix/final/finite-target/. Compared with prior actual finite753: four new (two test classes and two Surefire reports), three modified (Work.class and two engine test compiler lists),534 exact;216 prior entries outside this task's actual finite set are not recertified. Compared with historical logical2329, the same four additions/three modifications yield logical2333;1792 prior logical entries are historical only. The merge at .local/iam-o2b-f2c2b2-query-timeout-fix/final/target-logical-merged.json is identity bookkeeping, never certification of whole current target/classpath or all tests. Phase-specific finite snapshots retain failed fixture and behavioral RED class/report bytes separately from GREEN. No platform-gateway/sibling business target or wildcard classpath was used.

## Complete review package and self-review

.local/iam-o2b-f2c2b2-query-timeout-fix/review/complete-two-path-plain.patch and complete-two-path-binary.patch contain the COMPLETE bounded two-path delta with context; the new test is absent in the before tree. Each bounded git diff --no-index ran once with expected exit1, writing the entire package to unique files rather than stdout. Plain check/apply and binary check/apply each ran once under separate owned scratch trees, all exit0. Both resulting trees exactly hash-match both tested source files. Each native command, PID, raw stdout/stderr, real wait/collector result is cached separately; full index:
{
  "file": ".local/iam-o2b-f2c2b2-query-timeout-fix/review/review-package.json",
  "bytes": 8124,
  "sha256": "03894dbd51f712272f3a98e8227e5161ebc476ed9ae06dfb883e5c24d469f7b8"
}

Self-review covered every changed line against the brief: shared Work.query is the only production change; every caller keeps its original checks/budgets; math cannot install0; residual1ms correctly clamps1; retries recompute rather than reset; expired or changed authority still fails before business SQL; SET exceptions propagate with try-resource closure; row/ledger/material limits untouched. The new test uses real PG/transaction/authority and parameter binding, avoids arbitrary packet/material/authorization fabrication, and asserts real server timeout plus real SELECT output and two denial paths. No further edits were needed after GREEN; no independent reviewer work or B2 lifecycle edit was performed.

## Limitations and separate concerns

Compiler INFO diagnostics are retained explicitly: pre-existing deprecated API notices (RunAuthorityStore and RunEventReaderPostgresTest), unchecked/unsafe notices (ContextProjection and FlowMaterialStatusPostgresTest). Therefore output is not described as pristine despite green test results. Raw byte logs are preserved; Maven was set UTF-8 while Surefire XML reports platform native.encoding/stderr.encoding GBK, so any display-decoding mismatch remains distinguishable from original bytes.

Tests cover only this bounded timeout installation change, not real provider, joined renewal, whole target or suite acceptance. The small guard-to-execute gap documented by production is unchanged; PG statement_timeout is a server-side timeout, not an exact whole-network deadline. Conservative half-budget timeout can cause an expensive SELECT to time out earlier, intentionally within the existing Work allowance. B2 test09 actual slow-reader cleanup remains unresolved and is not attributed to this SQL defect. Actor404 remains E/PG run+tenant+principal metadata filtering before material reads, not issuer registry. Root owns subsequent independent review and any justified B2 restoration.
