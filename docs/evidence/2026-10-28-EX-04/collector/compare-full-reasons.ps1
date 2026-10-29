param(
 [Parameter(Mandatory=$true)][ValidateSet('skill-registry','engine-runtime')][string]$Module,
 [Parameter(Mandatory=$true)][string]$RunDirectory
)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$reader=Join-Path $PSScriptRoot 'full-report-records.ps1'
$readerSha='96D70BE0951B75C335EA9D2A60460F794424D03D08882CB760BE5F48CABD8724'
if((Get-FileHash -LiteralPath $reader -Algorithm SHA256).Hash -cne $readerSha){throw 'FROZEN_REPORT_READER_MISMATCH'}
. $reader
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
$owned=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runs'))+[IO.Path]::DirectorySeparatorChar
if(-not $run.StartsWith($owned,[StringComparison]::OrdinalIgnoreCase)){throw 'ANALYSIS_RUN_SCOPE_MISMATCH'}
$skill=($Module -ceq 'skill-registry')
$baseline=if($skill){Join-Path $taskRoot 'docs/evidence/2026-10-28-EX-27/runs/outbox-fix-20261029-085643-361/full'}else{Join-Path $taskRoot 'docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842'}
$admission=[IO.File]::ReadAllText((Join-Path $run 'freeze-admission.json'))|ConvertFrom-Json
$native=[IO.File]::ReadAllText((Join-Path $run 'native-exit.json'))|ConvertFrom-Json
$methodSummary=[IO.File]::ReadAllText((Join-Path $run 'summary.json'))|ConvertFrom-Json
if($admission.module -cne $Module){throw 'FULL_ANALYSIS_MODULE_MISMATCH'}
$manifest=[IO.File]::ReadAllText((Join-Path $run 'xml-manifest.json'))|ConvertFrom-Json
$actualFiles=@(Get-ChildItem -LiteralPath (Join-Path $run 'xml') -File -Filter 'TEST-*.xml')
if(@($manifest).Count -ne $actualFiles.Count -or @($manifest|Where-Object{-not (Test-Path -LiteralPath (Join-Path (Join-Path $run 'xml') $_.file) -PathType Leaf) -or (Get-FileHash -LiteralPath (Join-Path (Join-Path $run 'xml') $_.file) -Algorithm SHA256).Hash -cne $_.sha256}).Count -ne 0){throw 'ARCHIVED_XML_MANIFEST_MISMATCH'}
$before=Read-Ex04FullReports (Join-Path $baseline 'xml')
$after=Read-Ex04FullReports (Join-Path $run 'xml')
$oldMap=@{};foreach($row in $before.rows){$oldMap[(Get-Ex04Identity $row)]=$row}
$newMap=@{};foreach($row in $after.rows){$newMap[(Get-Ex04Identity $row)]=$row}
$expectedBase=if($skill){674}else{907}
$expectedOldNonpass=if($skill){116}else{115}
if($oldMap.Count -ne $expectedBase -or @($before.rows|Where-Object{$_.status -cne 'passed'}).Count -ne $expectedOldNonpass){throw 'LATEST_BASELINE_SHAPE_MISMATCH'}

function Read-ExactReasonText([string]$Directory,$Record){
 $name=$Record.xml.Substring(4)
 if($name -notmatch '^TEST-[A-Za-z0-9_.$-]+\.xml$'){throw 'REASON_XML_SCOPE_MISMATCH'}
 $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load((Join-Path $Directory $name))
 $found=@(foreach($case in $doc.SelectNodes("//*[local-name()='testcase']")){
  $suites=New-Object 'System.Collections.Generic.List[string]';$parent=$case.ParentNode
  while($parent -and $parent -is [Xml.XmlElement]){if($parent.LocalName -eq 'testsuite'){$suites.Insert(0,$parent.GetAttribute('name'))};$parent=$parent.ParentNode}
  $candidate=[pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');display_name=$case.GetAttribute('displayName');suite_path=($suites -join ' > ')}
  if((Get-Ex04Identity $candidate) -ceq (Get-Ex04Identity $Record)){$case}
 })
 if($found.Count -ne 1){throw 'EXACT_REASON_CASE_NOT_UNIQUE'}
 $event=$found[0].SelectSingleNode('./'+$Record.status)
 if(-not $event){throw 'EXACT_REASON_EVENT_MISSING'}
 [pscustomobject]@{message=$event.GetAttribute('message');detail=$event.InnerText.Replace("`r`n","`n")}
}

# This single known migration assertion has two generated UUIDs and two timestamps.
# Only that exact identity/type/unchanged structural cause can get this bounded proof.
$volatileClass='cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest'
$volatileMethod='v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity'
$uuid='[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
$stamp='\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}(?::?\d{2})?)?'
$volatileProof=New-Object 'System.Collections.Generic.List[object]'
$reasons=@(foreach($key in $oldMap.Keys|Sort-Object){
 $old=$oldMap[$key];if($old.status -ceq 'passed'){continue}
 $current=$newMap[$key]
 $structural=$null -ne $current -and $old.status -ceq $current.status -and $old.exception_type -ceq $current.exception_type -and $old.cause_types -ceq $current.cause_types -and $old.first_project_frame -ceq $current.first_project_frame
 $messageSame=$null -ne $current -and $old.message_sha256 -ceq $current.message_sha256
 $detailSame=$null -ne $current -and $old.detail_sha256 -ceq $current.detail_sha256
 $bounded=$false
 if($skill -and $structural -and (-not $messageSame -or -not $detailSame) -and $old.classname -ceq $volatileClass -and $old.method -ceq $volatileMethod -and $old.status -ceq 'failure' -and $old.exception_type -ceq 'org.opentest4j.AssertionFailedError'){
  $a=Read-ExactReasonText (Join-Path $baseline 'xml') $old
  $b=Read-ExactReasonText (Join-Path $run 'xml') $current
  $counts=@([regex]::Matches($a.message,$uuid).Count,[regex]::Matches($b.message,$uuid).Count,[regex]::Matches($a.message,$stamp).Count,[regex]::Matches($b.message,$stamp).Count,[regex]::Matches($a.detail,$uuid).Count,[regex]::Matches($b.detail,$uuid).Count,[regex]::Matches($a.detail,$stamp).Count,[regex]::Matches($b.detail,$stamp).Count)
  $normalizedMessageSame=[regex]::Replace([regex]::Replace($a.message,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>') -ceq [regex]::Replace([regex]::Replace($b.message,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>')
  $normalizedDetailSame=[regex]::Replace([regex]::Replace($a.detail,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>') -ceq [regex]::Replace([regex]::Replace($b.detail,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>')
  $bounded=@($counts|Where-Object{$_ -ne 2}).Count -eq 0 -and $normalizedMessageSame -and $normalizedDetailSame
  $volatileProof.Add([pscustomobject]@{classname=$old.classname;method=$old.method;scope='ONLY_THIS_EXACT_MIGRATION_CASE_TWO_UUID_TWO_TIMESTAMPS';counts=$counts;beforeMessageLength=$a.message.Length;afterMessageLength=$b.message.Length;normalizedMessageEqual=$normalizedMessageSame;normalizedDetailEqual=$normalizedDetailSame;passed=$bounded;rawMessagesPrinted=$false;rawHashesPreserved=$true;storedXmlNotNormalized=$true})
 }
 [pscustomobject]@{classname=$old.classname;method=$old.method;display_name=$old.display_name;suite_path=$old.suite_path;record_kind=$old.record_kind;beforeStatus=$old.status;afterStatus=if($current){$current.status}else{'MISSING'};beforeType=$old.exception_type;afterType=if($current){$current.exception_type}else{''};beforeCauseTypes=$old.cause_types;afterCauseTypes=if($current){$current.cause_types}else{''};beforeProjectFrame=$old.first_project_frame;afterProjectFrame=if($current){$current.first_project_frame}else{''};structureSame=$structural;exactMessageSame=$messageSame;exactDetailSame=$detailSame;beforeMessageSha256=$old.message_sha256;afterMessageSha256=if($current){$current.message_sha256}else{''};beforeDetailSha256=$old.detail_sha256;afterDetailSha256=if($current){$current.detail_sha256}else{''};boundedVolatileExplanationPassed=$bounded;reasonPreserved=($structural -and (($messageSame -and $detailSame) -or $bounded));beforeXml=$old.xml;afterXml=if($current){$current.xml}else{''}}
})
$delta=@(foreach($key in $oldMap.Keys|Sort-Object){$old=$oldMap[$key];$current=$newMap[$key];if(-not $current -or $old.status -cne $current.status){[pscustomobject]@{identity=$key;beforeStatus=$old.status;afterStatus=if($current){$current.status}else{'MISSING'}}}})
$oldEvents=@($before.rows|Where-Object{-not $_.method});$newEvents=@($after.rows|Where-Object{-not $_.method})
$knownUnexecuted=@();if(-not $skill){
 $known=@(Import-Csv -LiteralPath (Join-Path $PSScriptRoot 'engine-baseline-unexecuted-source.csv'))
 $knownUnexecuted=@($known|Where-Object{$k=$_;@($after.rows|Where-Object{$_.classname -ceq $k.classname -and ($_.method -ceq $k.method -or $_.method.StartsWith($k.method+'('))}).Count -eq 0})
}
$skips=@($reasons|Where-Object{$_.beforeStatus -ceq 'skipped'})
$eventsPreserved=$oldEvents.Count -eq $newEvents.Count -and @($oldEvents|Where-Object{$key=Get-Ex04Identity $_;-not $newMap.ContainsKey($key) -or $newMap[$key].status -cne $_.status}).Count -eq 0
$unexecutedPreserved=$skill -or ($known.Count -eq 14 -and $knownUnexecuted.Count -eq 14)
$reasonGate=$reasons.Count -eq $expectedOldNonpass -and @($reasons|Where-Object{-not $_.reasonPreserved}).Count -eq 0 -and $eventsPreserved -and $unexecutedPreserved
$reasons|Export-Csv -LiteralPath (Join-Path $run 'full-nonpass-reasons.csv') -NoTypeInformation -Encoding UTF8
$delta|Export-Csv -LiteralPath (Join-Path $run 'full-baseline-status-delta.csv') -NoTypeInformation -Encoding UTF8
[IO.File]::WriteAllText((Join-Path $run 'full-bounded-volatile-reason-proof.json'),(ConvertTo-Json -InputObject ($volatileProof.ToArray()) -Depth 7),(New-Object Text.UTF8Encoding($false)))
$summary=[ordered]@{analysis='PURE_FILE_XML_REASON_COMPARISON_NO_PRODUCT_COMMAND';module=$Module;readerSha256=$readerSha;baseline=$baseline;baselineRecords=$oldMap.Count;currentRecords=$newMap.Count;baselineNonpass=$reasons.Count;missingOriginalNonpass=@($reasons|Where-Object{$_.afterStatus -ceq 'MISSING'}).Count;statusOrTypeCauseProjectFrameChanges=@($reasons|Where-Object{-not $_.structureSame}).Count;rawMessageHashChanges=@($reasons|Where-Object{-not $_.exactMessageSame}).Count;rawDetailHashChanges=@($reasons|Where-Object{-not $_.exactDetailSame}).Count;boundedVolatileExplanations=@($reasons|Where-Object{$_.boundedVolatileExplanationPassed}).Count;unexplainedReasons=@($reasons|Where-Object{-not $_.reasonPreserved}).Count;baselineStatusChanges=$delta.Count;originalClassEvents=$oldEvents.Count;actualClassEvents=$newEvents.Count;classEventsPreserved=$eventsPreserved;knownUnexecutedSourceTests=$knownUnexecuted.Count;knownUnexecutedPreserved=$unexecutedPreserved;oldSkips=$skips.Count;oldSkipExact=@($skips|Where-Object{-not $_.structureSame -or -not $_.exactMessageSame -or -not $_.exactDetailSame}).Count -eq 0;reasonComparisonPassed=$reasonGate;methodRegressionGatePassed=$methodSummary.methodRegressionGatePassed;currentNewNonpass=$methodSummary.newNonpass;overallRegressionEvidenceSatisfied=($reasonGate -and $methodSummary.methodRegressionGatePassed -and $delta.Count -eq 0);fileAnalysisExit0OnlyMeansOldReasonComparisonPassed=$true;nativeExitNotRewritten=$native.nativeExit;nativeIgnoreNotAllGreen=$true;xmlManifestSha256=(Get-FileHash -LiteralPath (Join-Path $run 'xml-manifest.json') -Algorithm SHA256).Hash;noSourceIndexOrXmlWrites=$true;noEnvironmentRead=$true;noJavaMavenPgHttp=$true;generalNormalizationApplied=$false;engineDynamicDatabasePostProofReviewedHere=$false;cardClosed=$false}
[IO.File]::WriteAllText((Join-Path $run 'full-reason-comparison.json'),($summary|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
$summary|ConvertTo-Json -Depth 6
if(-not $reasonGate){exit 1}
