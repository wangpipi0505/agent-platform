# File-only proof for one newly sealed SSE Full pair; an unsealed preparation cannot execute.
param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,[switch]$ExecuteFileOnly)
$ErrorActionPreference='Stop'
if(-not $ExecuteFileOnly){throw 'FILE_ONLY_DERIVATION_NOT_APPROVED'}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$manifestPath=Join-Path $PSScriptRoot 'sse-full-reasons-manifest.json'
if((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
$manifest=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
if($manifest.sealed -ne $true -or [string]::IsNullOrWhiteSpace($manifest.actualAfter) -or @($manifest.pins).Count-eq 0){throw 'SSE_ACTUAL_AFTER_NOT_SEALED'}
if($manifest.actualAfter-cne $manifest.plannedAfterRun -or $manifest.actualAfter-cne '.local/ex27-v222-recovery/sse-owners-full-933-20261029-01'){throw 'SSE_EXACT_AFTER_RUN_MISMATCH'}
if(@($manifest.rules|Where-Object{[string]::IsNullOrWhiteSpace($_.afterXml) -or $_.afterMessageSha256-cnotmatch '^[A-F0-9]{64}$' -or $_.afterDetailSha256-cnotmatch '^[A-F0-9]{64}$'}).Count-ne 0){throw 'SSE_EXACT_AFTER_CASE_PINS_NOT_SEALED'}
$run=Join-Path $repo $manifest.actualAfter
$baseline=Join-Path $repo $manifest.baselineRun
$output=Join-Path $repo $manifest.outputDirectory
if(Test-Path -LiteralPath $output){throw 'DERIVED_OUTPUT_ALREADY_EXISTS'}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash}
function Verify-FrozenInputs {
 foreach($pin in $manifest.pins){if((Hash (Join-Path $repo $pin.path)) -cne $pin.sha256){throw 'FROZEN_FILE_PAIR_INPUT_CHANGED'}}
 foreach($side in @($baseline,$run)){
  $list=[IO.File]::ReadAllText((Join-Path $side 'xml-manifest.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
  $files=@(Get-ChildItem -LiteralPath (Join-Path $side 'xml') -File -Filter 'TEST-*.xml')
  if($list.Count-ne 93 -or $files.Count-ne 93){throw 'FROZEN_XML_SET_NOT_93'}
  $seen=@{};foreach($pin in $list){if($pin.file -cnotmatch '^TEST-[A-Za-z0-9_.$-]+\.xml$' -or $seen.ContainsKey($pin.file)){throw 'INVALID_OR_DUPLICATE_XML_PIN'};$seen[$pin.file]=$true;$path=Join-Path $side ('xml/'+$pin.file);if((Hash $path)-cne $pin.sha256 -or (Get-Item -LiteralPath $path).Length-ne $pin.bytes){throw 'FROZEN_XML_BYTES_CHANGED'}}
 }
}
function Identity-Map($Rows){$map=@{};foreach($row in $Rows){$key=Get-Ex04Identity $row;if($map.ContainsKey($key)){throw 'DUPLICATE_REPORT_IDENTITY'};$map[$key]=$row};$map}
function Read-VerifiedRows([string]$Directory,[int]$Count){
 $csv=@(Import-Csv -LiteralPath (Join-Path $Directory 'methods.csv'));$csvMap=Identity-Map $csv
 $report=Read-Ex04FullReports (Join-Path $Directory 'xml');$xmlMap=Identity-Map @($report.rows)
 if($csvMap.Count-ne $Count -or $xmlMap.Count-ne $Count -or @($report.issues).Count-ne 0){throw 'CSV_XML_IDENTITY_OR_PARSER_ISSUE'}
 foreach($key in $csvMap.Keys){if(-not $xmlMap.ContainsKey($key)){throw 'CSV_XML_IDENTITY_MISSING'};foreach($field in @('record_kind','status','exception_type','cause_types','first_project_frame','message_sha256','detail_sha256','xml')){if($csvMap[$key].$field-cne $xmlMap[$key].$field){throw 'CSV_XML_RAW_RECORD_DIFFERS'}}}
 $csv
}
Verify-FrozenInputs
. (Join-Path $repo $manifest.reportReader)
# These three functions are copied verbatim from the reviewed EX04 deriver; no pattern is widened.

function Read-FieldText([string]$XmlDirectory,[string]$RelativeXml,$Record){
 if($RelativeXml -notmatch '^xml/(TEST-[A-Za-z0-9_.$-]+\.xml)$'){throw 'CASE_XML_SCOPE_MISMATCH'}
 $file=Join-Path $XmlDirectory $Matches[1];$d=New-Object Xml.XmlDocument;$d.XmlResolver=$null;$d.Load($file)
 $found=@(foreach($case in $d.SelectNodes("//*[local-name()='testcase']")){
  $suites=New-Object 'System.Collections.Generic.List[string]';$parent=$case.ParentNode
  while($parent -and $parent -is [Xml.XmlElement]){if($parent.LocalName -eq 'testsuite'){$suites.Insert(0,$parent.GetAttribute('name'))};$parent=$parent.ParentNode}
  $row=[pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');display_name=$case.GetAttribute('displayName');suite_path=($suites -join ' > ')}
  if((Get-Ex04Identity $row) -ceq (Get-Ex04Identity $Record)){$case}
 })
 if($found.Count -ne 1){throw 'EXACT_CASE_NOT_UNIQUE'}
 $event=$found[0].SelectSingleNode('./'+$Record.beforeStatus);if(-not $event){throw 'ORIGINAL_EVENT_TYPE_MISSING'}
 [pscustomobject]@{message=$event.GetAttribute('message');detail=$event.InnerText.Replace("`r`n","`n");xmlSha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}
}

function Analyze-ExactField([string]$Before,[string]$After,[string]$Category,[int]$ExpectedCount,[string]$Field){
 $pattern=if($Category -ceq 'SPRING_CONTEXT_OBJECT_IDENTITY'){'(?<prefix>[A-Za-z_$][A-Za-z0-9_.$]*@)(?<value>[0-9a-fA-F]+)(?![A-Za-z0-9_])'}else{'(?<prefix>\bconnection=)(?<value>-?[0-9]+)(?![A-Za-z0-9_])'}
 $a=@([regex]::Matches($Before,$pattern));$b=@([regex]::Matches($After,$pattern))
 $countOkay=$a.Count -eq $ExpectedCount -and $b.Count -eq $ExpectedCount
 $locations=New-Object 'System.Collections.Generic.List[object]'
 $left=New-Object Text.StringBuilder;$right=New-Object Text.StringBuilder;$leftEnd=0;$rightEnd=0;$prefixesEqual=$true
 if($countOkay){
  for($i=0;$i -lt $ExpectedCount;$i++){
   $av=$a[$i].Groups['value'];$bv=$b[$i].Groups['value'];$ap=$a[$i].Groups['prefix'].Value;$bp=$b[$i].Groups['prefix'].Value
   if($ap -cne $bp){$prefixesEqual=$false}
   [void]$left.Append($Before.Substring($leftEnd,$av.Index-$leftEnd));[void]$left.Append('<EX04_REVIEWED_IDENTITY_'+$i+'>');$leftEnd=$av.Index+$av.Length
   [void]$right.Append($After.Substring($rightEnd,$bv.Index-$rightEnd));[void]$right.Append('<EX04_REVIEWED_IDENTITY_'+$i+'>');$rightEnd=$bv.Index+$bv.Length
   $locations.Add([pscustomobject]@{ordinal=$i;beforeOffsetUtf16=$av.Index;afterOffsetUtf16=$bv.Index;beforeLength=$av.Length;afterLength=$bv.Length;adjacentPrefixBefore=$ap;adjacentPrefixAfter=$bp;beforeIdentityValue=$av.Value;afterIdentityValue=$bv.Value;beforeIdentitySha256=(Get-Ex04TextSha $av.Value);afterIdentitySha256=(Get-Ex04TextSha $bv.Value);changed=($av.Value -cne $bv.Value);onlyCapturedValueReplaced=$true})
  }
  [void]$left.Append($Before.Substring($leftEnd));[void]$right.Append($After.Substring($rightEnd))
 }
 $equal=$countOkay -and $prefixesEqual -and $left.ToString() -ceq $right.ToString()
 [pscustomobject]@{field=$Field;coordinateSpace='UTF16_OFFSETS_OF_DECODED_XML_ATTRIBUTE_OR_READER_CRLF_NORMALIZED_DETAIL';expectedTokens=$ExpectedCount;beforeTokens=$a.Count;afterTokens=$b.Count;beforeRawSha256=(Get-Ex04TextSha $Before);afterRawSha256=(Get-Ex04TextSha $After);rawHashSame=((Get-Ex04TextSha $Before) -ceq (Get-Ex04TextSha $After));adjacentPrefixesAndClassOrderEqual=$prefixesEqual;allRemainingCharactersExactlyEqual=$equal;noStackLineSqlValueOrOtherFieldReplacement=$true;locations=$locations.ToArray();passed=$equal}
}

function Check-JointIdentityRelations($Fields){
 $entries=@(foreach($field in $Fields){foreach($location in $field.locations){[pscustomobject]@{field=$field.field;ordinal=$location.ordinal;prefix=$location.adjacentPrefixBefore;before=$location.beforeIdentityValue;after=$location.afterIdentityValue;beforeSha=$location.beforeIdentitySha256;afterSha=$location.afterIdentitySha256;changed=$location.changed;beforeOffset=$location.beforeOffsetUtf16;afterOffset=$location.afterOffsetUtf16}}})
 $labels=New-Object 'System.Collections.Generic.List[string]';foreach($entry in $entries){if($labels.ToArray() -cnotcontains $entry.prefix){$labels.Add($entry.prefix)}}
 $domains=@(foreach($label in $labels){
  $tokens=@($entries|Where-Object{$_.prefix -ceq $label});$forward=@{};$reverse=@{};$forwardUnique=$true;$reverseUnique=$true
  foreach($token in $tokens|Where-Object{$_.changed}){
   if($token.beforeSha -ceq $token.afterSha){throw 'CHANGED_TOKEN_RAW_HASH_NOT_DISTINCT'}
   if($forward.ContainsKey($token.before) -and $forward[$token.before] -cne $token.after){$forwardUnique=$false}
   if($reverse.ContainsKey($token.after) -and $reverse[$token.after] -cne $token.before){$reverseUnique=$false}
   $forward[$token.before]=$token.after;$reverse[$token.after]=$token.before
  }
  $beforePattern=@();$afterPattern=@()
  for($i=0;$i -lt $tokens.Count;$i++){
   $bp='';$ap='';for($j=0;$j -lt $tokens.Count;$j++){$bp+=$(if($tokens[$i].before -ceq $tokens[$j].before){'1'}else{'0'});$ap+=$(if($tokens[$i].after -ceq $tokens[$j].after){'1'}else{'0'})}
   $beforePattern+=$bp;$afterPattern+=$ap
  }
  $samePattern=($beforePattern -join '|') -ceq ($afterPattern -join '|')
  $mappings=@(foreach($from in $forward.Keys|Sort-Object){$to=$forward[$from];[pscustomobject]@{oldIdentity=$from;newIdentity=$to;oldTokenSha256=(Get-Ex04TextSha $from);newTokenSha256=(Get-Ex04TextSha $to);onlyActualChangedCapturedToken=$true;occurrences=@($tokens|Where-Object{$_.changed -and $_.before -ceq $from -and $_.after -ceq $to}|Select-Object field,ordinal,beforeOffset,afterOffset)}})
  [pscustomobject]@{classQualifiedOrFixedFieldPrefix=$label;messageAndDetailJointlyChecked=$true;occurrences=$tokens.Count;uniqueBeforeIdentities=@($tokens.before|Sort-Object -Unique).Count;uniqueAfterIdentities=@($tokens.after|Sort-Object -Unique).Count;changedTokenOccurrences=@($tokens|Where-Object{$_.changed}).Count;changedTokenMappings=$mappings;forwardUnique=$forwardUnique;reverseUnique=$reverseUnique;beforeEqualityPattern=$beforePattern;afterEqualityPattern=$afterPattern;equalityPatternPreserved=$samePattern;unchangedTokensParticipateInEqualityPatternButNotMapping=$true;passed=($forwardUnique -and $reverseUnique -and $samePattern)}
 })
 [pscustomobject]@{scope='THIS_EXACT_CASE_MESSAGE_PLUS_DETAIL_CLASS_QUALIFIED';classQualifiedBindingDomains=$domains.Count;actualChangedTokensOnlyMapped=$true;domains=$domains;forwardAndReverseUnique=(@($domains|Where-Object{-not $_.forwardUnique -or -not $_.reverseUnique}).Count -eq 0);allEqualityPatternsPreserved=(@($domains|Where-Object{-not $_.equalityPatternPreserved}).Count -eq 0);passed=(@($domains|Where-Object{-not $_.passed}).Count -eq 0)}
}

$actualSummary=[IO.File]::ReadAllText((Join-Path $run 'summary.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
if($actualSummary.records-ne 933 -or $actualSummary.passed-ne 818 -or $actualSummary.failures-ne 35 -or $actualSummary.errors-ne 80 -or $actualSummary.skipped-ne 0 -or $actualSummary.nativeExit-ne 0 -or -not $actualSummary.methodIdentityAndStatusGate -or $actualSummary.strictOriginalReasonGate -or $actualSummary.collectorGatePassed -or $actualSummary.allTestsGreen -or -not $actualSummary.sourceStable -or -not $actualSummary.contextStable -or -not $actualSummary.greenDependenciesStable -or -not $actualSummary.storageGate -or -not $actualSummary.knownUnexecutedScopeGate){throw 'SSE_ACTUAL_COUNTS_METHOD_CONTEXT_OR_RAW_GATES_DIFFER_FROM_PLAN'}
$sourceBefore=Get-Content -LiteralPath (Join-Path $run 'source-before.json') -Raw|ConvertFrom-Json
$sourceAfter=Get-Content -LiteralPath (Join-Path $run 'source-after.json') -Raw|ConvertFrom-Json
$sourceMap=@{};foreach($source in $sourceBefore){if($sourceMap.ContainsKey($source.path)){throw 'DUPLICATE_AFTER_SOURCE_PATH'};$sourceMap[$source.path]=$source.sha256}
if($sourceMap.Count-ne 306 -or $sourceAfter.Count-ne 306 -or @($sourceAfter.path|Sort-Object -Unique).Count-ne 306 -or @($sourceAfter|Where-Object{-not $sourceMap.ContainsKey($_.path) -or $sourceMap[$_.path]-cne $_.sha256}).Count-ne 0 -or $sourceMap[$manifest.metadataIdentitySource.path]-cne $manifest.metadataIdentitySource.sha256){throw 'ACTUAL_306_SOURCE_BEFORE_AFTER_OR_METADATA_PIN_DIFFERS'}
$actualNative=Get-Content -LiteralPath (Join-Path $run 'native-exit.json') -Raw|ConvertFrom-Json
if($actualNative.nativeExit-ne 0){throw 'ACTUAL_NATIVE_EXIT_NOT_ZERO'}
$beforeRows=@(Read-VerifiedRows $baseline 932);$afterRows=@(Read-VerifiedRows $run 933)
$beforeMap=Identity-Map $beforeRows;$afterMap=Identity-Map $afterRows
$missing=@($beforeMap.Keys|Where-Object{-not $afterMap.ContainsKey($_)});$extra=@($afterMap.Keys|Where-Object{-not $beforeMap.ContainsKey($_)})
$addedKey=Get-Ex04Identity $manifest.addedMethod
if($missing.Count-ne 0 -or $extra.Count-ne 1 -or $extra[0]-cne $addedKey -or $afterMap[$addedKey].status-cne 'passed'){throw 'EXACT_ADDED_V222_METHOD_OR_BASELINE_SET_DIFFERS'}
$eventsBefore=@($beforeRows|Where-Object record_kind -ceq 'CLASS_EVENT');$eventsAfter=@($afterRows|Where-Object record_kind -ceq 'CLASS_EVENT')
if($eventsBefore.Count-ne 2 -or $eventsAfter.Count-ne 2){throw 'ORIGINAL_TWO_CLASS_EVENTS_NOT_PRESERVED'}
$knownBefore=Get-Content -LiteralPath (Join-Path $baseline 'known-unexecuted-source.json') -Raw|ConvertFrom-Json;$knownAfter=Get-Content -LiteralPath (Join-Path $run 'known-unexecuted-source.json') -Raw|ConvertFrom-Json
$knownA=@($knownBefore|ForEach-Object{$_.classname+'|'+$_.method+'|'+$_.baseline}|Sort-Object);$knownB=@($knownAfter|ForEach-Object{$_.classname+'|'+$_.method+'|'+$_.baseline}|Sort-Object)
if($knownA.Count-ne 14 -or $knownB.Count-ne 14 -or ($knownA-join "`n")-cne ($knownB-join "`n")){throw 'ORIGINAL_FOURTEEN_UNEXECUTED_SOURCE_SCOPE_CHANGED'}
$statusChanges=@(foreach($key in $beforeMap.Keys){$a=$beforeMap[$key];$b=$afterMap[$key];if($a.status-cne $b.status -or $a.record_kind-cne $b.record_kind){[pscustomobject]@{identity=$key;kind='STATUS_OR_RECORD_KIND';before=$a.status;after=$b.status}}})
if($statusChanges.Count-ne 0){throw 'NEW_FULL_BASELINE_STATUS_OR_RECORD_KIND_REGRESSION'}
$sseKey=Get-Ex04Identity $manifest.expectedSseMethod
if(-not $beforeMap.ContainsKey($sseKey) -or -not $afterMap.ContainsKey($sseKey) -or $beforeMap[$sseKey].status-cne 'passed' -or $afterMap[$sseKey].status-cne 'passed'){throw 'SSE_ORIGINAL_PASS_NOT_RESTORED_IN_THIS_ACTUAL_RUN'}
$rawStatus=Get-Content -LiteralPath (Join-Path $run 'method-status-delta.json') -Raw|ConvertFrom-Json
if($rawStatus.Count-ne 0){throw 'ORIGINAL_STATUS_DELTA_MUST_BE_EMPTY_FOR_THIS_NEW_FULL'}
$ruleMap=@{};foreach($rule in $manifest.rules){$key=Get-Ex04Identity $rule;if($ruleMap.ContainsKey($key)){throw 'DUPLICATE_APPROVED_RULE'};$ruleMap[$key]=$rule}
if($ruleMap.Count-ne 23 -or $ruleMap.ContainsKey($sseKey)){throw 'EXACT_23_RULE_SET_OR_SSE_EXCLUSION_CHANGED'}
$nonpass=@($beforeRows|Where-Object{$_.status-cne 'passed'});if($nonpass.Count-ne 115){throw 'ORIGINAL_NONPASS_NOT_115'}
$comparisons=@(foreach($a in $nonpass){$key=Get-Ex04Identity $a;$b=$afterMap[$key];$same=$true;foreach($field in @('record_kind','status','exception_type','cause_types','first_project_frame')){if($a.$field-cne $b.$field){$same=$false}};[pscustomobject]@{classname=$a.classname;method=$a.method;display_name=$a.display_name;suite_path=$a.suite_path;identity=$key;beforeStatus=$a.status;afterStatus=$b.status;beforeType=$a.exception_type;afterType=$b.exception_type;beforeCauseTypes=$a.cause_types;afterCauseTypes=$b.cause_types;beforeProjectFrame=$a.first_project_frame;afterProjectFrame=$b.first_project_frame;structureSame=$same;beforeMessageSha256=$a.message_sha256;afterMessageSha256=$b.message_sha256;beforeDetailSha256=$a.detail_sha256;afterDetailSha256=$b.detail_sha256;exactMessageSame=($a.message_sha256-ceq $b.message_sha256);exactDetailSame=($a.detail_sha256-ceq $b.detail_sha256);beforeXml=$a.xml;afterXml=$b.xml}})
$changed=@($comparisons|Where-Object{-not $_.exactMessageSame -or -not $_.exactDetailSame});$unchanged=@($comparisons|Where-Object{$_.exactMessageSame -and $_.exactDetailSame})
if(@($comparisons|Where-Object{-not $_.structureSame}).Count-ne 0 -or $changed.Count-ne 23 -or $unchanged.Count-ne 92 -or @($changed|Where-Object{$_.exactMessageSame -or $_.exactDetailSame -or -not $ruleMap.ContainsKey($_.identity)}).Count-ne 0){throw 'EXACT_23_CHANGED_AND_92_IDENTICAL_NONPASS_NOT_REPRODUCED'}
$rawDelta=Get-Content -LiteralPath (Join-Path $run 'strict-reason-delta.json') -Raw|ConvertFrom-Json
$expectedDelta=@(foreach($row in $changed){[pscustomobject]@{identity=$row.identity;field='message_sha256';before=$row.beforeMessageSha256;after=$row.afterMessageSha256};[pscustomobject]@{identity=$row.identity;field='detail_sha256';before=$row.beforeDetailSha256;after=$row.afterDetailSha256}})
if($rawDelta.Count-ne 46){throw 'ORIGINAL_46_RAW_FIELD_DELTAS_CHANGED'}
foreach($item in $expectedDelta){if(@($rawDelta|Where-Object{$_.identity-ceq $item.identity -and $_.field-ceq $item.field -and $_.before-ceq $item.before -and $_.after-ceq $item.after}).Count-ne 1){throw 'RAW_FIELD_DELTA_PAIR_NOT_REPRODUCED'}}
$sourcePath=Join-Path $repo $manifest.metadataIdentitySource.path
$sourceLines=[IO.File]::ReadAllLines($sourcePath,[Text.Encoding]::UTF8)
if((Hash $sourcePath)-cne $manifest.metadataIdentitySource.sha256 -or $sourceLines[135]-notmatch 'System\.identityHashCode\(connection\)'){throw 'METADATA_IDENTITY_HASH_SOURCE_NOT_PROVEN'}
$proofs=@(foreach($row in $changed){
 $rule=$ruleMap[$row.identity]
 if($row.beforeStatus-cne $rule.status -or $row.afterStatus-cne $rule.status -or $row.beforeType-cne $rule.exceptionType -or $row.afterType-cne $rule.exceptionType){throw 'APPROVED_EXACT_CASE_STRUCTURE_CHANGED'}
 foreach($field in @('beforeMessageSha256','afterMessageSha256','beforeDetailSha256','afterDetailSha256','beforeXml','afterXml')){if($row.$field-cne $rule.$field){throw 'APPROVED_CASE_RAW_HASH_PAIR_CHANGED'}}
 $a=Read-FieldText (Join-Path $baseline 'xml') $row.beforeXml $row;$b=Read-FieldText (Join-Path $run 'xml') $row.afterXml $row
 if((Get-Ex04TextSha $a.message)-cne $row.beforeMessageSha256 -or (Get-Ex04TextSha $b.message)-cne $row.afterMessageSha256 -or (Get-Ex04TextSha $a.detail)-cne $row.beforeDetailSha256 -or (Get-Ex04TextSha $b.detail)-cne $row.afterDetailSha256){throw 'CASE_XML_DECODED_HASH_PAIR_MISMATCH'}
 $message=Analyze-ExactField $a.message $b.message $rule.category $rule.messageTokens 'message';$detail=Analyze-ExactField $a.detail $b.detail $rule.category $rule.detailTokens 'detail'
 foreach($field in @($message,$detail)){$expectedPrefixes=if($field.field-ceq 'message'){@($rule.messagePrefixes)}else{@($rule.detailPrefixes)};$actualPrefixes=@($field.locations|ForEach-Object{$_.adjacentPrefixBefore});if(($expectedPrefixes-join "`n")-cne ($actualPrefixes-join "`n")){throw 'REVIEWED_CLASS_QUALIFIED_PREFIX_SEQUENCE_CHANGED'}}
 $relations=Check-JointIdentityRelations @($message,$detail)
 [pscustomobject]@{classname=$row.classname;method=$row.method;display_name=$row.display_name;suite_path=$row.suite_path;category=$rule.category;beforeXml=$row.beforeXml;afterXml=$row.afterXml;beforeXmlSha256=$a.xmlSha256;afterXmlSha256=$b.xmlSha256;expectedMessageTokens=$rule.messageTokens;expectedDetailTokens=$rule.detailTokens;fields=@($message,$detail);bijectionAndEqualityPattern=$relations;passed=($message.passed -and $detail.passed -and $relations.passed)}
})
$reasonPassed=$proofs.Count-eq 23 -and @($proofs|Where-Object{-not $_.passed}).Count-eq 0 -and $unchanged.Count-eq 92
# Recheck every pinned input and all 186 XML bytes before writing only to a new sibling directory.
Verify-FrozenInputs
$manifestSha=Hash $manifestPath
if($manifestSha-cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED_DURING_DERIVATION'}
[void](New-Item -ItemType Directory -Path $output)
Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $output 'approved-manifest.json')
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $output 'deriver.ps1')
$manifest.rules|Export-Csv -LiteralPath (Join-Path $output 'exact-23-allowlist.csv') -NoTypeInformation -Encoding UTF8
$comparisons|Sort-Object classname,method|Export-Csv -LiteralPath (Join-Path $output 'original-115-comparison.csv') -NoTypeInformation -Encoding UTF8
$adjustedGate=$reasonPassed -and $actualSummary.methodIdentityAndStatusGate -and $actualSummary.sourceStable -and $actualSummary.contextStable -and $actualSummary.greenDependenciesStable -and $actualSummary.storageGate -and $actualSummary.knownUnexecutedScopeGate -and $actualNative.nativeExit-eq 0 -and $statusChanges.Count-eq 0
$result=[ordered]@{analysis='EXACT_SSE_FULL_VS_EX04_23_CASE_FILE_ONLY_PROOF';actualAfter=$manifest.actualAfter;baselineRun=$manifest.baselineRun;approvedManifestSha256=$manifestSha;deriverSha256=(Hash $PSCommandPath);inputs=$manifest.pins;unchangedOriginalNonpass=92;reviewedDifferences=23;rawChangedFields=46;springIdentityCases=16;testConnectionIdentityCases=7;metadataIdentitySource=$manifest.metadataIdentitySource;cases=$proofs;adjustedOriginalReasonComparisonPassed=$reasonPassed;rawReasonGateRemainsFalse=$true;rawHashesClaimedEqual=$false;methodIdentityAndStatusGate=$actualSummary.methodIdentityAndStatusGate;sourceStable=$actualSummary.sourceStable;contextStable=$actualSummary.contextStable;greenDependenciesStable=$actualSummary.greenDependenciesStable;storageGate=$actualSummary.storageGate;knownUnexecutedScopeGate=$actualSummary.knownUnexecutedScopeGate;nativeExit=$actualNative.nativeExit;baselineStatusChanges=$statusChanges.Count;sseOriginalPassRestoredInThisRun=$true;adjustedMethodAndReasonEvidenceGatePassed=$adjustedGate;adjustedRelativeRegressionGatePassed=$adjustedGate;originalCollectorGatePassed=$actualSummary.collectorGatePassed;allTestsGreen=$actualSummary.allTestsGreen;originalTwoClassEventsPreserved=$true;originalFourteenSourceTestsStillUnexecuted=$true;originalRawCsvXmlSummaryUntouched=$true;noGeneralNormalizationOrFutureRunRule=$true;futureRunRequiresNewExactFreezeAndReview=$true;doesNotReplaceDependencyStorageV222OrSseReview=$true;exitZeroRequiresReasonAndActualRelativeGates=$true;cardClosed=$false}
[IO.File]::WriteAllText((Join-Path $output 'bounded-representation-proof.json'),($result|ConvertTo-Json -Depth 18),(New-Object Text.UTF8Encoding($false)))
[pscustomobject]@{reviewedCases=$proofs.Count;unchangedCases=$unchanged.Count;adjustedOriginalReasonComparisonPassed=$reasonPassed;adjustedMethodAndReasonGate=$adjustedGate;rawReasonGateStillFalse=$true;sseOriginalPassRestoredInThisRun=$true;allTestsGreen=$actualSummary.allTestsGreen;originalsUntouched=$true;cardClosed=$false}|ConvertTo-Json
if(-not $adjustedGate){exit 1}
exit 0
