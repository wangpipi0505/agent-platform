param([string]$RunDirectory=(Join-Path $PSScriptRoot 'runs/full-engine-20261029-1124'))
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$run=(Resolve-Path -LiteralPath $RunDirectory).Path
$expectedRun=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'runs/full-engine-20261029-1124')).Path
if($run -cne $expectedRun){throw 'REPRESENTATION_REVIEW_IS_BOUND_TO_ONE_APPROVED_RUN'}
$baseline=Join-Path $repo 'docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842'
$reader=Join-Path $PSScriptRoot 'full-report-records.ps1'
$readerSha='96D70BE0951B75C335EA9D2A60460F794424D03D08882CB760BE5F48CABD8724'
if((Get-FileHash -LiteralPath $reader -Algorithm SHA256).Hash -cne $readerSha){throw 'FROZEN_REPORT_READER_MISMATCH'}
. $reader
$csv=Join-Path $run 'full-nonpass-reasons.csv'
$rawSummaryPath=Join-Path $run 'full-reason-comparison.json'
$moduleSummaryPath=Join-Path $run 'summary.json'
$rawSummary=[IO.File]::ReadAllText($rawSummaryPath)|ConvertFrom-Json
$moduleSummary=[IO.File]::ReadAllText($moduleSummaryPath)|ConvertFrom-Json
$all=@(Import-Csv -LiteralPath $csv)
$changed=@($all|Where-Object{$_.exactMessageSame -cne 'True' -or $_.exactDetailSame -cne 'True'})
if($all.Count -ne 115 -or $changed.Count -ne 23 -or $rawSummary.reasonComparisonPassed -or $rawSummary.statusOrTypeCauseProjectFrameChanges -ne 0 -or $rawSummary.rawMessageHashChanges -ne 23 -or $rawSummary.rawDetailHashChanges -ne 23){throw 'ORIGINAL_RAW_FALSE_GATE_OR_EXACT_23_DIFFS_MISMATCH'}
$watched=@($csv,$rawSummaryPath,$moduleSummaryPath,(Join-Path $run 'native-exit.json'),(Join-Path $run 'xml-manifest.json'),(Join-Path $baseline 'xml-manifest.json'))
$originalHashes=@{};foreach($p in $watched){$originalHashes[$p]=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
$rules=New-Object 'System.Collections.Generic.List[object]'
function Add-Rules([string]$Class,[string[]]$Methods,[string]$Category,[int[]]$Counts){
 for($i=0;$i -lt $Methods.Count;$i++){$rules.Add([pscustomobject]@{classname=$Class;method=$Methods[$i];display_name='';suite_path=$Class;category=$Category;messageTokens=$Counts[$i];detailTokens=$Counts[$i];status=if($Category -ceq 'SPRING_CONTEXT_OBJECT_IDENTITY'){'error'}else{'failure'};exceptionType=if($Category -ceq 'SPRING_CONTEXT_OBJECT_IDENTITY'){'java.lang.IllegalStateException'}else{'java.lang.AssertionError'}})}
}
Add-Rules 'cn.agentplatform.engine.infra.EngineContextPostgresTest' @('actualMvcTimeoutLeavesRoomForTheFiftyFiveSecondObservationWindow','repositoryProxyAndSchedulerUseRealInitializedJdbc') 'SPRING_CONTEXT_OBJECT_IDENTITY' @(13,13)
Add-Rules 'cn.agentplatform.engine.infra.RunAdmissionHttpTest' @('acceptedRequestIdRemainsReadableAtCapacityAndConflictStillConflicts','drainingReturns503RetryAfterButAcceptedRunStillReads','fullPoolRejectsNewDebugAndFormalBeforeAnyRunOrDependencyRead','newSessionTurnIsRejectedBeforeReservationAndNextTurnChanges','oldAcceptedEmptySessionReservationCanRecoverAtCapacity') 'SPRING_CONTEXT_OBJECT_IDENTITY' @(12,12,12,12,12)
Add-Rules 'cn.agentplatform.engine.infra.TestInputHttpTest' @('boundsInputsAndPaginationAndRevalidatesChangedSchema','callerCannotOverrideFrozenInputsAndStaleRevisionCannotPromote','chatDeclarationsValidateRequiredTypeAndChangedReuse','declaredSchemaConstraintsNullAndDefaultsMatchStructuredInputPayloads','deniesOtherOwnerTenantAndMissingEditorIncludingAdmin','pagingIsBoundedStableAndDoesNotExposeInputsInMetadata','parallelUpdatesHaveExactlyOneWinner','persistsAndCasProtectsAgainstLostUpdates','recoveryFreezesSourceAndTargetBeforeHttpAndRejectsDifferentRetry') 'SPRING_CONTEXT_OBJECT_IDENTITY' @(12,12,12,12,12,12,12,12,12)
Add-Rules 'cn.agentplatform.engine.infra.RunObservationMetadataHttpPostgresTest' @('actualHttpProjectsSevenFieldsFromReadOnlyRepeatableReadWithoutPayload','actualInvalidDigestAndRootKindInPostgresAreDeniedWithoutReadingPayload','damagedControlMetadataAndMissingOrInconsistentRootManifestsFailClosed','localExpiryOrClosedFrameAfterReadAndCommitPreventsAValidProjection','longVersionNumbersRemainPinnedWhenLaterRunsAreAccepted','notFoundAndOtherOwnerHaveTheSame404WhileOwnedLegacyHas403','selfConsistentLegalRootDigestReplacementIsTheDocumentedStorageTrustBoundary') 'TEST_CONNECTION_IDENTITY_HASHCODE' @(4,3,4,4,4,2,4)
$ruleMap=@{};foreach($rule in $rules){$key=Get-Ex04Identity $rule;if($ruleMap.ContainsKey($key)){throw 'DUPLICATE_REVIEWED_RULE'};$ruleMap[$key]=$rule}
if($ruleMap.Count -ne 23 -or @($changed|Where-Object{-not $ruleMap.ContainsKey((Get-Ex04Identity $_))}).Count -ne 0){throw 'APPROVED_RULE_IDENTITY_SET_MISMATCH'}
$allowlist=Join-Path $run 'engine-representation-allowlist.csv'
$rules.ToArray()|Sort-Object classname,method|Export-Csv -LiteralPath $allowlist -NoTypeInformation -Encoding UTF8

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
$sourceRelative='engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java'
$sourcePath=Join-Path $repo $sourceRelative
$sourceManifest=[IO.File]::ReadAllText((Join-Path $run 'source-before.json'))|ConvertFrom-Json
$sourcePin=@($sourceManifest|Where-Object{$_.path -ceq $sourceRelative})
$sourceSha=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
$sourceLines=[IO.File]::ReadAllLines($sourcePath,[Text.Encoding]::UTF8)
if($sourcePin.Count -ne 1 -or $sourceSha -cne $sourcePin[0].sha256 -or $sourceLines[135] -notmatch 'System\.identityHashCode\(connection\)'){throw 'FIXED_METADATA_TEST_IDENTITY_SOURCE_NOT_PROVEN'}
$proofs=@(foreach($row in $changed){
 $rule=$ruleMap[(Get-Ex04Identity $row)]
 if($row.structureSame -cne 'True' -or $row.beforeStatus -cne $rule.status -or $row.afterStatus -cne $rule.status -or $row.beforeType -cne $rule.exceptionType -or $row.afterType -cne $rule.exceptionType){throw 'APPROVED_CASE_STRUCTURE_CHANGED'}
 $a=Read-FieldText (Join-Path $baseline 'xml') $row.beforeXml $row
 $b=Read-FieldText (Join-Path $run 'xml') $row.afterXml $row
 if((Get-Ex04TextSha $a.message) -cne $row.beforeMessageSha256 -or (Get-Ex04TextSha $b.message) -cne $row.afterMessageSha256 -or (Get-Ex04TextSha $a.detail) -cne $row.beforeDetailSha256 -or (Get-Ex04TextSha $b.detail) -cne $row.afterDetailSha256){throw 'RAW_HASH_PAIR_NOT_BOUND_TO_ORIGINAL_COMPARISON'}
 $message=Analyze-ExactField $a.message $b.message $rule.category $rule.messageTokens 'message'
 $detail=Analyze-ExactField $a.detail $b.detail $rule.category $rule.detailTokens 'detail'
 $relations=Check-JointIdentityRelations @($message,$detail)
 [pscustomobject]@{classname=$row.classname;method=$row.method;display_name=$row.display_name;suite_path=$row.suite_path;category=$rule.category;beforeXml=$row.beforeXml;afterXml=$row.afterXml;beforeXmlSha256=$a.xmlSha256;afterXmlSha256=$b.xmlSha256;expectedMessageTokens=$rule.messageTokens;expectedDetailTokens=$rule.detailTokens;fields=@($message,$detail);bijectionAndEqualityPattern=$relations;passed=($message.passed -and $detail.passed -and $relations.passed)}
})
$unchanged=@($all|Where-Object{$_.exactMessageSame -ceq 'True' -and $_.exactDetailSame -ceq 'True'})
$passed=$proofs.Count -eq 23 -and @($proofs|Where-Object{-not $_.passed}).Count -eq 0 -and $unchanged.Count -eq 92 -and $rawSummary.missingOriginalNonpass -eq 0 -and $rawSummary.baselineStatusChanges -eq 0 -and $rawSummary.classEventsPreserved -and $rawSummary.knownUnexecutedPreserved
foreach($p in $watched){if((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -cne $originalHashes[$p]){throw 'ORIGINAL_RAW_OR_SUMMARY_CHANGED_DURING_DERIVATION'}}
$result=[ordered]@{analysis='ROOT_APPROVED_EXACT_23_IDENTITY_REPRESENTATION_PROOF_FILE_ONLY';approvedRun='full-engine-20261029-1124';baselineRun='after-module-20261028-230322-842';readerSha256=$readerSha;allowlistSha256=(Get-FileHash -LiteralPath $allowlist -Algorithm SHA256).Hash;rawComparisonSha256=$originalHashes[$rawSummaryPath];rawReasonCsvSha256=$originalHashes[$csv];actualXmlManifestSha256=$originalHashes[(Join-Path $run 'xml-manifest.json')];baselineXmlManifestSha256=$originalHashes[(Join-Path $baseline 'xml-manifest.json')];moduleSummarySha256=$originalHashes[$moduleSummaryPath];nativeExitSha256=$originalHashes[(Join-Path $run 'native-exit.json')];rawReasonGateRemainsFalse=$true;rawHashesClaimedEqual=$false;unchangedNonpass=92;reviewedDifferences=23;springIdentityCases=16;testConnectionIdentityCases=7;metadataIdentitySource=[ordered]@{path=$sourceRelative;line=136;sha256=$sourceSha;frozenModuleSourceMatches=$true;meaning='System.identityHashCode(connection), not a SQL value'};cases=$proofs;adjustedReasonComparisonPassed=$passed;adjustedMethodAndReasonEvidenceGatePassed=($passed -and $moduleSummary.methodRegressionGatePassed -and $moduleSummary.newNonpass -eq 0);gateScope='FROZEN_FULL_XML_IDENTITIES_AND_ORIGINAL_REASON_PRESERVATION_ONLY';doesNotReplacePgDynamicV222RuntimeArtifactsReview=$true;original115AssertionsStatesAndCauseStructureUnchanged=$true;originalSummaryBaselineRawXmlUntouched=$true;noSourceTestOrProductAssertionChanges=$true;noEnvironmentOrJavaMavenPgHttp=$true;noGeneralNormalizationOrFutureRunRule=$true;rootFinalIndependentReviewPending=$true;cardClosed=$false}
[IO.File]::WriteAllText((Join-Path $run 'engine-bounded-representation-proof.json'),($result|ConvertTo-Json -Depth 15),(New-Object Text.UTF8Encoding($false)))
[pscustomobject]@{reviewedCases=$proofs.Count;unchangedCases=$unchanged.Count;allExactResidualEquality=$passed;adjustedMethodAndReasonGate=$result.adjustedMethodAndReasonEvidenceGatePassed;rawReasonGateStillFalse=$true;allowlistSha256=$result.allowlistSha256;originalsUntouched=$true}|ConvertTo-Json
if(-not $passed){exit 1}
