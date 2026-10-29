$ErrorActionPreference='Stop'
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskChecks=[ordered]@{}
$taskParse=@(foreach($name in @('preflight.ps1','run-verify.ps1','compare-nonpass-reasons.ps1','check-preparation.ps1')){
    $tokens=$null;$errors=$null
    $path=Join-Path $PSScriptRoot $name
    [void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    [pscustomobject]@{path=$name;errorCount=@($errors).Count;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
})
$taskChecks.ps51=($PSVersionTable.PSVersion.Major -eq 5 -and $PSVersionTable.PSVersion.Minor -eq 1)
$taskChecks.allParseErrorsZero=(@($taskParse|Where-Object{$_.errorCount -ne 0}).Count -eq 0)
$taskRun=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'run-verify.ps1'))
$taskPreflight=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'preflight.ps1'))
$taskReasons=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'compare-nonpass-reasons.ps1'))
$taskBaseline=@(Import-Csv -LiteralPath (Join-Path $taskRoot 'docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/method-summary/methods.csv')|Where-Object{$_.module -ceq 'skill-registry'})
$taskNames=@('cn.agentplatform.skill.application.SkillExecutionTest','cn.agentplatform.skill.application.SkillTaskResultTest')
$taskTarget=@($taskBaseline|Where-Object{$taskNames -ccontains $_.classname})
$taskTargetKeys=@($taskTarget|ForEach-Object{$_.classname+'|'+$_.method+'|'+$_.display_name+'|'+$_.suite_path})
$taskChecks.exactBaseline28With25Plus3=($taskTarget.Count -eq 28 -and @($taskTarget|Where-Object{$_.classname -ceq $taskNames[0]}).Count -eq 25 -and @($taskTarget|Where-Object{$_.classname -ceq $taskNames[1]}).Count -eq 3 -and @($taskTargetKeys|Select-Object -Unique).Count -eq 28 -and @($taskTarget|Where-Object{$_.status -cne 'passed'}).Count -eq 0)
$taskChecks.baseline664Retains116Including4Skip=($taskBaseline.Count -eq 664 -and @($taskBaseline|Where-Object{$_.status -cne 'passed'}).Count -eq 116 -and @($taskBaseline|Where-Object{$_.status -ceq 'skipped'}).Count -eq 4)
$taskPrevious=[IO.File]::ReadAllText((Join-Path $taskRoot 'docs/evidence/2026-10-28-EX-27/runs/algorithm-fix-20261029-032753-387/full/module-source-sha256.json'))|ConvertFrom-Json
$taskMap=@{};foreach($row in $taskPrevious){$taskMap[$row.path]=$row.sha256}
$taskCurrent=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'skill-registry/src') -Recurse -File|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
$taskChange=@($taskCurrent|Where-Object{-not $taskMap.ContainsKey($_.path) -or $taskMap[$_.path] -cne $_.sha256})
$taskTest='skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java'
$taskSdk='skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java'
$taskChecks.source149OnlyApprovedOutboxDelta=($taskPrevious.Count -eq 149 -and $taskCurrent.Count -eq 149 -and $taskChange.Count -eq 1 -and $taskChange[0].path -ceq $taskTest -and $taskMap[$taskTest] -ceq '14AE4EEA65C05EBEEBDBDF16D3FC4733D4613FDEC20EE7644DF56DB83627194B' -and $taskChange[0].sha256 -ceq '607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8')
$taskChecks.sdk43ECAndOther147Unchanged=($taskMap[$taskSdk] -ceq '43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D' -and @($taskCurrent|Where-Object{$_.path -cne $taskTest -and $taskMap[$_.path] -cne $_.sha256}).Count -eq 0)
$taskPins=[ordered]@{
    'skill-registry/src/test/java/cn/agentplatform/skill/application/SkillTaskResultTest.java'='600F1A9DF2D5F0199A52F7F2152D339F2E6C6D78839382C30C5BA9C5E7FA164F'
    'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillOutbox.java'='30BAE61B51DB4DF75462C5D92BADC5455FBEC1FC7437856CD1D9A9DA2DA32DB7'
    'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java'='8CD3D8EDAE7C0DEE54DD562C6F2F3F3664759DE85D122F70C1E00B9BC6D25218'
    'skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java'='9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D'
}
$taskChecks.explicitFrozenProductsAndTaskResultMatch=(@($taskPins.Keys|Where-Object{(Get-FileHash -LiteralPath (Join-Path $taskRoot $_) -Algorithm SHA256).Hash -cne $taskPins[$_]}).Count -eq 0)
$taskChecks.targetAndFullSelectorAdapted=($taskRun.Contains("[ValidateSet('Target','Full')]") -and [regex]::Matches($taskRun,'-Dtest=SkillExecutionTest,SkillTaskResultTest').Count -eq 2 -and -not $taskRun.Contains('-Dtest=AlgorithmOffloadInterruptionTest') -and -not $taskRun.Contains("'-am'"))
$taskChecks.defaultOriginalArgLineOnly=([regex]::Matches($taskRun,'-DargLine=').Count -eq 1 -and $taskRun.Contains('-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp') -and $taskRun -notmatch '-Dex27\.' -and $taskRun -notmatch '-D(?:reuseForks|forkCount|runOrder|skipTests|maven\.test\.skip)')
$taskChecks.target28PrerequisiteNative0NoIgnore=($taskRun.Contains('TARGET_28_REPORTS_OR_FROZEN_CONTEXT_NOT_GREEN') -and $taskRun.Contains('$taskClassSummary.testcases -ne 28') -and $taskRun.Contains('$taskClassSummary.passed -ne 28') -and $taskRun.Contains('$taskClassSummary.skipped -ne 0') -and $taskRun.Contains('$taskClassNative.failureIgnore') -and $taskRun.Contains('target28PassedNative0NoIgnore=$true'))
$taskChecks.mainOwnerSchemaHistoryAdded=($taskPreflight.Contains("`$mainDatabase = 'agent_platform_v12'") -and $taskPreflight.Contains("EXISTING_MAIN_DATABASE_OWNER_MISMATCH") -and $taskPreflight.Contains("EXISTING_MAIN_SKILL_SCHEMA_HISTORY_MISMATCH") -and $taskPreflight.Contains("MAIN_FLYWAY_UNKNOWN_TYPE_OWNER_OR_SCRIPT") -and $taskRun.Contains('mainExactClassifiedHistory') -and $taskRun.Contains('main-precondition.json'))
$taskChecks.preflightContainsNoDdlOrQueueSql=($taskPreflight -notmatch '(?i)\b(?:create|drop|truncate|update|delete|alter|insert|grant|revoke)\s+' -and $taskPreflight.Contains('default_transaction_read_only=on') -and $taskPreflight.Contains('statement_timeout=5000') -and $taskPreflight.Contains('oldQueueReadOrWrite=$false'))
$taskChecks.strictKnownPgAndReplayRulesKept=($taskPreflight.Contains("'ProcessId=15204'") -and $taskPreflight.Contains('V:/.local/pgdata') -and $taskPreflight.Contains('agent_platform_skill_algorithm_migration_test') -and $taskPreflight.Contains('agent_platform_skill_digest_test') -and $taskPreflight.Contains("`$_.script -cne '""skill""'") -and $taskPreflight.Contains("@('SQL','SCHEMA') -cnotcontains `$_.type"))
$taskChecks.targetZeroOutcomesFullSdkFiveAndFour=($taskRun.Contains("'NOT_SELECTED_EXPECT_ZERO_OUTCOMES'") -and $taskRun.Contains("'REQUIRED_FIVE_WITH_FOUR_OUTCOMES'") -and $taskRun.Contains('$taskSdkRows.Count -eq 0 -and $taskOutcomes.Count -eq 0') -and $taskRun.Contains('$taskSdkRows.Count -eq 5') -and $taskRun.Contains('$taskOutcomes.Count -eq 4'))
$taskChecks.full674AndOriginal116Required=($taskRun.Contains("{674}else{28}") -and $taskRun.Contains("{10}else{0}") -and $taskRun.Contains('$taskRetained.Count -eq 116') -and $taskRun.Contains('$taskChanged.Count -eq 0') -and $taskRun.Contains('reasonComparisonPending=($Mode -eq ''Full'')'))
$taskChecks.nativePersistedBeforeXml=($taskRun.IndexOf("Write-TaskJson 'native-exit.json'") -lt $taskRun.IndexOf("`$taskPhase='XML_COLLECTION'") -and $taskRun.Contains('nativeExitAlreadyPersisted=$true;beforeXmlCollection=$true'))
$taskChecks.safeFixedFailureAndFinallyRestore=($taskRun.Contains('scriptLineNumber=$_.InvocationInfo.ScriptLineNumber') -and $taskRun.Contains('reason=$taskReason') -and $taskPreflight.Contains('messagePrinted=$false') -and $taskRun.Contains('foreach($taskName in $taskPrevious.Keys)') -and $taskPreflight.Contains('foreach ($name in $taskPrevious.Keys)'))
$taskChecks.reasonComparisonKeepsOriginalAndOutboxRedFull=($taskReasons.Contains('algorithm-fix-20261029-032753-387/full/xml') -and $taskReasons.Contains('full-20261028-234917-584') -and $taskReasons.Contains('previous-sdk43-full-status-delta.csv') -and $taskReasons.Contains('normalizationApplied=$false'))
$taskChecks.expectedHeadStillFrozen=(((& git -C $taskRoot rev-parse HEAD)-join '') -ceq '83726d50a6cce116251c03c5c193080d8ed5aad2')
$taskResult=[ordered]@{phase='STATIC_PREPARATION_ONLY';powershell=$PSVersionTable.PSVersion.ToString();parse=$taskParse;checks=$taskChecks;allPassed=(@($taskChecks.Keys|Where-Object{-not $taskChecks[$_]}).Count -eq 0);expectedTargetCases=28;expectedTargetClasses=2;expectedFullCases=674;expectedFullClasses=70;candidateSha256='607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8';candidateApproval='PENDING_ROOT_FINAL_SHA_AND_INDEPENDENT_REVIEW_AND_ACTUAL_WINDOW';envFileNotRead=$true;javaMavenPgHttpStarted=$false;productOrTestSourceModified=$false;indexModified=$false}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'static-check.json'),($taskResult|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
$taskResult|ConvertTo-Json -Depth 6
if(-not $taskResult.allPassed){exit 1}
