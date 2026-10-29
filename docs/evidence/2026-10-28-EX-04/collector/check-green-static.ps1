$ErrorActionPreference='Stop'
$checks=[ordered]@{}
$parse=@(foreach($name in @('run-green-skill.ps1','extract-projection-evidence.ps1','check-green-static.ps1')){
 $path=Join-Path $PSScriptRoot $name;$tokens=$null;$errors=$null
 [void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
 [pscustomobject]@{path=$name;errors=@($errors).Count;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
})
$s=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'run-green-skill.ps1'),[Text.Encoding]::UTF8)
$e=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'extract-projection-evidence.ps1'),[Text.Encoding]::UTF8)
$t=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'green-skill.freeze.template.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
$checks.ps51=($PSVersionTable.PSVersion.Major -eq 5 -and $PSVersionTable.PSVersion.Minor -eq 1)
$checks.parseZero=(@($parse|Where-Object{$_.errors -ne 0}).Count -eq 0)
$checks.templateDisabled=(-not $t.approvedForExclusiveActualWindow -and $t.phase -ceq 'GREEN_SKILL')
$checks.exactTwoClassSelector=([regex]::Matches($s,'-Dtest=OntologyManifestImportsTest,OntologyProjectionImportTest').Count -eq 1)
$checks.noIgnoreSkipAmOrderForkOrDiag=($s -notmatch '-Dmaven\.test\.failure\.ignore|-DskipTests|-Dmaven\.test\.skip|-Dex27\.|-D(?:runOrder|forkCount|reuseForks)' -and $s -notmatch "'-am'")
$checks.originalRuntimeArgs=($s.Contains('-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp') -and $s.Contains('-Dsurefire.failIfNoSpecifiedTests=true'))
$checks.freezeBeforeEnv=($s.IndexOf('ROOT_FREEZE_NOT_APPROVED') -lt $s.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')") -and $s.IndexOf('Load-PinnedFile $freeze.moduleSourceManifest') -lt $s.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')"))
$checks.noOldProductOrRedPins=($s -notmatch 'redProductionPins|RED_PRODUCTION_BASELINE|expectedRedCsv|redMap|redObserved')
$checks.allSourceRosterHashBeforeAndAfter=($s.Contains('FROZEN_SOURCE_DRIFT') -and $s.Contains('Assert-Sources $source $before') -and $s.Contains('Assert-Sources $source $after'))
$checks.dynamicIdentitySetNotNumericShortcut=($s.Contains('$rows.Count -eq $expected.Count') -and $s.Contains('$missing.Count -eq 0') -and $s.Contains('$unexpected.Count -eq 0') -and $s -notmatch '\b(?:33|689)\b')
$checks.allReportsPassNative0=($s.Contains("`$bad=@(`$rows|Where-Object{`$_.status -cne 'passed'}") -and $s.Contains('$native -eq 0 -and $null -ne $native -and $bad.Count -eq 0'))
$checks.noClassOrSkip=($s.Contains("-not `$_.method -or `$_.status -ceq 'skipped'"))
$checks.ownedExistingOntologyPg=($s.Contains('ProcessId=15204') -and $s.Contains('V:/.local/pgdata') -and $s.Contains("`$db.owner -ceq 'agent_v12'") -and $s.Contains('agent-platform EX-03 2026-10-28 ontology import isolated test database'))
$checks.readOnlyTimeoutNoDdl=($s.Contains('default_transaction_read_only=on') -and $s.Contains('statement_timeout=5000') -and $s -notmatch '(?i)create\s+database|comment\s+on\s+database|drop\s+(schema|database)|truncate\s+|delete\s+from|update\s+skill\.')
$checks.nativePersistedBeforeXml=($s.IndexOf("Write-TaskJson 'native-exit.json'") -lt $s.IndexOf("`$taskPhase='XML_COLLECTION'"))
$checks.xmlUtf8FreshLoad=($s.Contains('$d.XmlResolver=$null;$d.Load((Join-Path $xml $file.Name))') -and $s.Contains('$_.LastWriteTime -ge $started'))
$checks.safeFixedErrorAndLine=($s.Contains('reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber') -and $s.Contains('messagePrinted=$false') -and $s -notmatch '\.Exception\.Message')
$checks.finallyRestoresEnvironment=($s.Contains('foreach($n in $taskPrevious.Keys)') -and $s.Contains('Set-Location -LiteralPath $taskLocation'))
$checks.extractionOnlyAfterTestGreen=($s.Contains('if($green){') -and $s.IndexOf('if($green){') -lt $s.IndexOf('Export-Ex04ProjectionEvidence -RunDirectory $taskRun'))
$checks.extractorBytePinCheckedTwice=([regex]::Matches($s,"Load-PinnedFile \(Join-Path \`$PSScriptRoot 'extract-projection-evidence.ps1'\) \`$freeze.evidenceExtractorSha256").Count -eq 2 -and $t.evidenceExtractorSha256 -ceq (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'extract-projection-evidence.ps1') -Algorithm SHA256).Hash)
$checks.projectionOnlyActualXml=($e.Contains("'xml/TEST-cn.agentplatform.skill.application.OntologyProjectionImportTest.xml'") -and $e.Contains('$output.InnerText') -and $e.Contains('$projection.Count -ne 1 -or $batches.Count -ne 4'))
$checks.twoKeysAndStrictNestedShape=($e.Contains("Assert-Ex04Keys `$p @('exposure','pagination')") -and $e.Contains("Assert-Ex04Keys `$p.exposure @('kind')") -and $e.Contains("Assert-Ex04Keys `$p.pagination @('mode','request','maxPageSize','maxRows')"))
$checks.batchFourSafeFieldsAndPostcondition=($e.Contains("Assert-Ex04Keys `$v @('before','after','drafts','rejected')") -and $e.Contains('$v.before -ne $v.after -or $v.drafts -ne 0'))
$checks.noJsonBeforeAllValidation=($e.IndexOf('foreach($entry in $batches)') -lt $e.IndexOf('[IO.File]::WriteAllText($projectionFile') -and $e.Contains('$projection[0].raw'))
$checks.actualRawBytesAndProvenance=($e.Contains('projectionJsonSavedAsExactEmittedUtf8Bytes=$true') -and $e.Contains('xmlSha256=') -and $e.Contains('projectionJsonSha256=') -and $e.Contains('constructedBeforeActualRun=$false'))
$checks.helperNoServiceOrSyntheticPayload=($e -notmatch 'mvn\.cmd|psql\.exe|Invoke-WebRequest|Invoke-RestMethod|DriverManager|Get-NetTCPConnection' -and $e -notmatch 'EX04_PERSISTED_PROJECTION=\{"')
$result=[ordered]@{phase='GREEN_SKILL_STATIC_PREPARATION_ONLY';powershell=$PSVersionTable.PSVersion.ToString();parse=$parse;checks=$checks;allPassed=(@($checks.Keys|Where-Object{-not $checks[$_]}).Count -eq 0);expectedAuthorReports=33;envRead=$false;javaMavenPgHttpStarted=$false;actualInvocationPerformed=$false;projectionJsonConstructed=$false;rootInputsModified=$false}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'green-static-check.json'),($result|ConvertTo-Json -Depth 7),(New-Object Text.UTF8Encoding($false)))
$result|ConvertTo-Json -Depth 6
if(-not $result.allPassed){exit 1}
