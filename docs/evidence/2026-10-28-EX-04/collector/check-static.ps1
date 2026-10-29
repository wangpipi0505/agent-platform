$ErrorActionPreference='Stop'
$checks=[ordered]@{}
$parse=@(foreach($n in @('run-red-skill.ps1','run-red-engine.ps1','check-static.ps1')){
 $path=Join-Path $PSScriptRoot $n;$tokens=$null;$errors=$null
 [void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
 [pscustomobject]@{path=$n;errors=@($errors).Count;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
})
$s=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'run-red-skill.ps1'),[Text.Encoding]::UTF8)
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'red-skill.freeze.template.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
$engine=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'run-red-engine.ps1'),[Text.Encoding]::UTF8)
$engineTemplate=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'red-engine.freeze.template.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
$checks.ps51=($PSVersionTable.PSVersion.Major -eq 5 -and $PSVersionTable.PSVersion.Minor -eq 1)
$checks.parseZero=(@($parse|Where-Object{$_.errors -ne 0}).Count -eq 0)
$checks.templateDisabled=(-not $template.approvedForExclusiveActualWindow)
$checks.frozenInputsBeforeEnv=($s.IndexOf('ROOT_FREEZE_NOT_APPROVED') -lt $s.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')") -and $s.IndexOf('Load-PinnedFile $freeze.moduleSourceManifest') -lt $s.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')"))
$checks.fixedTwoClassSelector=([regex]::Matches($s,'-Dtest=OntologyManifestImportsTest,OntologyProjectionImportTest').Count -eq 1)
$checks.noIgnoreAmSkipDiagnosticOverride=($s -notmatch '-Dmaven\.test\.failure\.ignore|-DskipTests|-Dmaven\.test\.skip|-Dex27\.|-D(?:runOrder|forkCount|reuseForks)' -and $s -notmatch "'-am'")
$checks.originalRuntimeArgLine=($s.Contains('-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp') -and $s.Contains('-Dsurefire.failIfNoSpecifiedTests=true'))
$checks.noDatabaseCreateResetDdl=($s -notmatch '(?i)(create\s+database|comment\s+on\s+database|drop\s+(?:schema|database)|truncate\s+|delete\s+from|update\s+skill\.)')
$checks.readOnlyTimeoutAndExistingOwnerMarker=($s.Contains('default_transaction_read_only=on') -and $s.Contains('statement_timeout=5000') -and $s.Contains('agent-platform EX-03 2026-10-28 ontology import isolated test database') -and $s.Contains("`$db.owner -ceq 'agent_v12'"))
$checks.strictKnownPgInstance=($s.Contains('ProcessId=15204') -and $s.Contains("'127.0.0.1'") -and $s.Contains('V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe') -and $s.Contains('V:/.local/pgdata'))
$checks.productionThreePinsRequired=($s.Contains('RED_PRODUCTION_BASELINE_MISMATCH') -and $s.Contains('$prod.Count -ne 3') -and @($template.redProductionPins).Count -eq 3)
$checks.allModuleSourceSetAndHashesRequired=($s.Contains('INVALID_SOURCE_MANIFEST') -and $s.Contains('FROZEN_SOURCE_DRIFT') -and $s.Contains('$map.Count -ne @($Actual).Count'))
$checks.nativePersistedBeforeXml=($s.IndexOf("Write-TaskJson 'native-exit.json'") -lt $s.IndexOf("`$taskPhase='XML_COLLECTION'"))
$checks.xmlUtf8FileLoadAndNoRawTail=($s.Contains('$d.Load((Join-Path $xml $file.Name))') -and $s.Contains('$d.XmlResolver=$null') -and $s -notmatch 'Get-Content[^\r\n]*-Tail')
$checks.identityAndExactRedTypeGuard=($s.Contains('DUPLICATE_REPORT_IDENTITY') -and $s.Contains('INVALID_EXPECTED_RED_VECTOR') -and $s.Contains('$row.exception_type -cne $redMap[$key].exception_type') -and $s.Contains('$missing.Count -eq 0') -and $s.Contains('$unexpected.Count -eq 0'))
$checks.noClassOrSkipAsTargetRed=($s.Contains("-not `$_.method -or `$_.status -ceq 'skipped'") -and $s.Contains('compileOrFixtureFailureNotTargetRed=(-not $complete)'))
$checks.redNativeMustFailAndAllOtherPass=($s.Contains('$native -ne 0 -and $null -ne $native') -and $s.Contains("elseif(`$row.status -cne 'passed')"))
$checks.fixedErrorStageLineNoMessage=($s.Contains('reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber') -and $s.Contains('messagePrinted=$false') -and $s -notmatch '\.Exception\.Message')
$checks.finallyRestoresProcess=($s.Contains('foreach($n in $taskPrevious.Keys)') -and $s.Contains("SetEnvironmentVariable(`$n,`$taskPrevious[`$n],'Process')") -and $s.Contains('Set-Location -LiteralPath $taskLocation'))
$checks.engineTemplateDisabled=(-not $engineTemplate.approvedForExclusiveActualWindow -and $engineTemplate.pureTwoClassNoPg)
$checks.engineFixedTwoClasses=([regex]::Matches($engine,'-Dtest=ToolOutputPolicyTest,RunToolProfileTest').Count -eq 1 -and $engine.Contains("'-pl','engine-runtime'"))
$checks.engineNoPgOrBusinessClientInvocation=($engine -notmatch 'psql\.exe|Get-NetTCPConnection|Get-CimInstance|Invoke-WebRequest|Invoke-RestMethod|Read-TaskPg' -and $engine.Contains('pgConnected=$false'))
$checks.engineOriginalPolicyPin=(@($engineTemplate.redProductionPins).Count -eq 1 -and $engineTemplate.redProductionPins[0].sha256 -ceq '94CF53C998668FE6D4A7C60D41A063D5C05FCED6E7800A96FC3FD1A289FE4C77' -and $engine.Contains('$prod.Count -ne 1') -and $engine.Contains('engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java'))
$checks.engineNoIgnoreOrDiagnostic=($engine -notmatch '-Dmaven\.test\.failure\.ignore|-DskipTests|-Dmaven\.test\.skip|-Dex27\.|-D(?:runOrder|forkCount|reuseForks)' -and $engine -notmatch "'-am'")
$checks.engineFreezeBeforeEnv=($engine.IndexOf('ROOT_FREEZE_NOT_APPROVED') -lt $engine.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')") -and $engine.Contains('^engine-runtime/src/'))
$checks.engineNativeAndExactRedIdentityGuard=($engine.IndexOf("Write-TaskJson 'native-exit.json'") -lt $engine.IndexOf("`$taskPhase='XML_COLLECTION'") -and $engine.Contains('$row.exception_type -cne $redMap[$key].exception_type') -and $engine.Contains('$missing.Count -eq 0') -and $engine.Contains('$unexpected.Count -eq 0'))
$result=[ordered]@{phase='STATIC_PREPARATION_ONLY';powershell=$PSVersionTable.PSVersion.ToString();parse=$parse;checks=$checks;allPassed=(@($checks.Keys|Where-Object{-not $checks[$_]}).Count -eq 0);targetCountNotInvented=$true;envRead=$false;javaMavenPgHttpStarted=$false;sourceOrIndexModified=$false;actualInvocationPerformed=$false}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'static-check.json'),($result|ConvertTo-Json -Depth 7),(New-Object Text.UTF8Encoding($false)))
$result|ConvertTo-Json -Depth 6
if(-not $result.allPassed){exit 1}
