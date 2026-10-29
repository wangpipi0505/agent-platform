$ErrorActionPreference='Stop'
$checks=[ordered]@{}
$parse=@(foreach($name in @('run-green-engine.ps1','check-green-engine-static.ps1')){
 $path=Join-Path $PSScriptRoot $name;$tokens=$null;$errors=$null
 [void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
 [pscustomobject]@{path=$name;errors=@($errors).Count;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
})
$s=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'run-green-engine.ps1'),[Text.Encoding]::UTF8)
$t=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'green-engine.freeze.template.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
$checks.ps51=($PSVersionTable.PSVersion.Major -eq 5 -and $PSVersionTable.PSVersion.Minor -eq 1)
$checks.parseZero=(@($parse|Where-Object{$_.errors -ne 0}).Count -eq 0)
$checks.templateAndBridgeDisabled=(-not $t.approvedForExclusiveActualWindow -and -not $t.projectionFixtureProvenance.rootVerified -and $t.phase -ceq 'GREEN_ENGINE')
$checks.fixedEngineModuleAndTwoClasses=($s.Contains("'-pl','engine-runtime'") -and [regex]::Matches($s,'-Dtest=ToolOutputPolicyTest,RunToolProfileTest').Count -eq 1)
$checks.originalRuntimeArgs=($s.Contains('-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp') -and $s.Contains('-Dsurefire.failIfNoSpecifiedTests=true'))
$checks.noIgnoreSkipAmOrderForkOrDiag=($s -notmatch '-Dmaven\.test\.failure\.ignore|-DskipTests|-Dmaven\.test\.skip|-Dex27\.|-D(?:runOrder|forkCount|reuseForks)' -and $s -notmatch "'-am'")
$checks.freezeBeforeEnv=($s.IndexOf('ROOT_FREEZE_NOT_APPROVED') -lt $s.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')"))
$checks.noOldProductOrRedPins=($s -notmatch 'redProductionPins|RED_PRODUCTION_BASELINE|expectedRedCsv|redMap|redObserved')
$checks.fullEngineRosterHashBeforeAndAfter=($s.Contains('^engine-runtime/src/') -and $s.Contains('Assert-Sources $source $before') -and $s.Contains('Assert-Sources $source $after'))
$checks.dynamicCompleteIdentitySet=($s.Contains('$rows.Count -eq $expected.Count') -and $s.Contains('$missing.Count -eq 0') -and $s.Contains('$unexpected.Count -eq 0') -and $s -notmatch '(?:Count|Reports|Records)\s*(?:-eq|-ne|=)\s*(?:47|932|907)\b')
$checks.allReportsPassNative0=($s.Contains("`$bad=@(`$rows|Where-Object{`$_.status -cne 'passed'}") -and $s.Contains('$native -eq 0 -and $null -ne $native -and $bad.Count -eq 0'))
$checks.noClassOrSkip=($s.Contains("-not `$_.method -or `$_.status -ceq 'skipped'"))
$checks.noPgOrBusinessHttpAdmission=($s -notmatch 'psql\.exe|Get-NetTCPConnection|Get-CimInstance|Invoke-WebRequest|Invoke-RestMethod|Read-TaskPg' -and $s.Contains('pgConnectedByCollector=$false;httpConnectedByCollector=$false'))
$checks.rootVerifiedFixtureRequired=($s.Contains('if(-not $bridge.rootVerified -or -not $bridge.actualPgTwoKeySource') -and $s.Contains('ROOT_FROZEN_FIXTURE_PROVENANCE_MISSING'))
$checks.fixtureProvenanceBeforeEnv=($s.IndexOf('ROOT_FROZEN_FIXTURE_PROVENANCE_MISSING') -lt $s.IndexOf("ReadAllLines((Join-Path `$taskRoot '.env')"))
$checks.ownedActualGreenSkillProducer=($s.Contains("'runs'") -and $s.Contains('StartsWith($ownedProducerRoot,[StringComparison]::OrdinalIgnoreCase)') -and $s.Contains('^green-skill-[a-z0-9-]+$'))
$checks.actualJsonXmlProofHashesPinned=($s.Contains('Load-PinnedFile $projectionFile $bridge.projectionJsonSha256') -and $s.Contains('Load-PinnedFile $producerXml $bridge.producerXmlSha256') -and $s.Contains('Load-PinnedFile $producerProofFile $bridge.producerProofSha256'))
$checks.actualProducerProofSemantics=($s.Contains("'ACTUAL_FRESH_PASSING_PROJECTION_XML_ONLY'") -and $s.Contains('$producerProof.projectionPrefixCount -ne 1') -and $s.Contains('$producerProof.batchPrefixCount -ne 4') -and $s.Contains('$producerProof.constructedBeforeActualRun'))
$checks.consumerTwoSourcePinsRequired=($s.Contains('@($bridge.consumerSourcePins).Count -ne 2') -and $s.Contains('CONSUMER_FIXTURE_SOURCE_DRIFT') -and @($t.projectionFixtureProvenance.consumerSourcePins).Count -eq 2)
$checks.fixedFixtureScopeHonest=($s.Contains('fixedFixtureRatherThanLivePgInEngine=$true') -and $s.Contains('fixture-provenance-admission.json'))
$checks.nativePersistedBeforeXml=($s.IndexOf("Write-TaskJson 'native-exit.json'") -lt $s.IndexOf("`$taskPhase='XML_COLLECTION'"))
$checks.freshUtf8XmlNoRawTail=($s.Contains('$d.XmlResolver=$null;$d.Load((Join-Path $xml $file.Name))') -and $s.Contains('$_.LastWriteTime -ge $started') -and $s -notmatch 'Get-Content[^\r\n]*-Tail')
$checks.identityDuplicatesRejected=($s.Contains('INVALID_EXPECTED_IDENTITIES') -and $s.Contains('DUPLICATE_REPORT_IDENTITY'))
$checks.safeFixedErrorAndLine=($s.Contains('reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber') -and $s.Contains('messagePrinted=$false') -and $s -notmatch '\.Exception\.Message')
$checks.finallyRestoresEnvironment=($s.Contains('foreach($n in $taskPrevious.Keys)') -and $s.Contains('Set-Location -LiteralPath $taskLocation'))
$checks.noProducerOrFixtureWrite=($s -notmatch 'WriteAllText\(\$projectionFile|WriteAllText\(\$producer|Set-Content[^\r\n]*producer|Copy-Item[^\r\n]*projectionFile')
$result=[ordered]@{phase='GREEN_ENGINE_STATIC_PREPARATION_ONLY';powershell=$PSVersionTable.PSVersion.ToString();parse=$parse;checks=$checks;allPassed=(@($checks.Keys|Where-Object{-not $checks[$_]}).Count -eq 0);expectedAuthorReports=47;envRead=$false;javaMavenPgHttpStarted=$false;actualInvocationPerformed=$false;producerArchiveOrRootInputsModified=$false}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'green-engine-static-check.json'),($result|ConvertTo-Json -Depth 7),(New-Object Text.UTF8Encoding($false)))
$result|ConvertTo-Json -Depth 6
if(-not $result.allPassed){exit 1}
