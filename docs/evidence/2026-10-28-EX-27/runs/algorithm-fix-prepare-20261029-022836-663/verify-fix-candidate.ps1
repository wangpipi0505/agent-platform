$ErrorActionPreference='Stop'
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskSource=Join-Path $taskRoot 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java'
$taskSnapshot=Join-Path $PSScriptRoot 'AlgorithmOffloadInterruptionTest.155D.java'
$taskBefore=[IO.File]::ReadAllText($taskSnapshot).Replace("`r`n","`n")
$taskAfter=[IO.File]::ReadAllText($taskSource).Replace("`r`n","`n")
function Get-Assertions([string]$Text){
 foreach($match in [regex]::Matches($Text,'(?s)\bassert(?:True|False|Equals|NotNull|Null)\s*\(.*?\);')){
  $value=[regex]::Replace($match.Value,'\s+','').Replace('quiescentAtFirstCheck','scoped.get().algorithmIoQuiescent()')
  $value=$value.Replace('peer.entered.await(1,TimeUnit.SECONDS)','peer.entered.await(remainingNanos(preparationUntil),TimeUnit.NANOSECONDS)')
  $value=$value.Replace('peer.finished.await(1,TimeUnit.SECONDS)','peer.finished.await(remainingNanos(completionUntil),TimeUnit.NANOSECONDS)')
  $value
 }
}
$taskOldAssertions=@(Get-Assertions $taskBefore)
$taskNewAssertions=@(Get-Assertions $taskAfter)
$taskAvailable=@{}
foreach($value in $taskNewAssertions){if(-not $taskAvailable.ContainsKey($value)){$taskAvailable[$value]=0};$taskAvailable[$value]++}
$taskMissing=@(foreach($value in $taskOldAssertions){if(-not $taskAvailable.ContainsKey($value) -or $taskAvailable[$value] -eq 0){$value}else{$taskAvailable[$value]--}})
$taskSourceName='    void unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit()'
$taskOldSource=$taskBefore.Substring($taskBefore.IndexOf($taskSourceName))
$taskOldSource=$taskOldSource.Substring(0,$taskOldSource.IndexOf('    /** Optional observation'))
$taskNewSource=$taskAfter.Substring($taskAfter.IndexOf($taskSourceName))
$taskNewSource=$taskNewSource.Substring(0,$taskNewSource.IndexOf('    static long remainingNanos'))
$taskSourceOldAssertions=@(Get-Assertions $taskOldSource)
$taskSourceNewAssertions=@(Get-Assertions $taskNewSource)
$taskSourceMissing=@($taskSourceOldAssertions|Where-Object{$taskSourceNewAssertions -cnotcontains $_})
$taskSourceLoop='(?s)release\.countDown\(\);\s*long until = System\.nanoTime\(\) \+ TimeUnit\.MILLISECONDS\.toNanos\(500\);\s*while \(!scoped\.get\(\)\.algorithmIoQuiescent\(\) && System\.nanoTime\(\) < until\)\s*Thread\.sleep\(5\);'
$taskLexicalPattern=@'
//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'
'@
$taskMasked=[regex]::Replace($taskAfter,$taskLexicalPattern,'')
$taskStack=New-Object 'System.Collections.Generic.Stack[char]'
$taskBalanced=$true
foreach($char in $taskMasked.ToCharArray()){
 if('{(['.Contains([string]$char)){$taskStack.Push($char)}
 elseif('})]'.Contains([string]$char)){
  if($taskStack.Count -eq 0){$taskBalanced=$false;break}
  $open=$taskStack.Pop();if(('{(['.IndexOf($open)) -ne ('})]'.IndexOf($char))){$taskBalanced=$false;break}
 }
}
$taskBalanced=$taskBalanced -and $taskStack.Count -eq 0
$taskChecks=[ordered]@{
 snapshotIsExact155D=(Get-FileHash -LiteralPath $taskSnapshot -Algorithm SHA256).Hash -ceq '155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1'
 allOriginal25AssertionObligationsRetained=$taskOldAssertions.Count -eq 25 -and $taskMissing.Count -eq 0
 allSourceOriginalAssertionsRetained=$taskSourceMissing.Count -eq 0
 threeDeclarationsFiveReports=[regex]::Matches($taskAfter,'(?m)^    @(?:Test|ParameterizedTest)\s*$').Count -eq 3
 originalParameterOrder=$taskAfter.Contains('@ValueSource(strings = {"PUT", "GET", "GET_BODY"})')
 originalNormalIdentity=$taskAfter.Contains('void interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String stage)')
 originalSourceIdentity=$taskAfter.Contains('void unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit()')
 newHandoffIdentity=$taskAfter.Contains('void originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety()')
 unchangedTwoSecondBudgets=[regex]::Matches($taskAfter,'TimeUnit\.SECONDS\.toNanos\(2\)').Count -eq 2
 unchangedFinite1500Waits=[regex]::Matches($taskAfter,'release\.await\(1500, TimeUnit\.MILLISECONDS\)').Count -eq [regex]::Matches($taskBefore,'release\.await\(1500, TimeUnit\.MILLISECONDS\)').Count
 source500LoopUnchanged=[regex]::IsMatch($taskNewSource,$taskSourceLoop)
 source800JoinsUnchanged=[regex]::Matches($taskNewSource,'worker\.join\(800\)').Count -eq 2
 sourceZeroNetworkAssertionsKept=[regex]::Matches($taskNewSource,'assertEquals\(0, peer\.routes\.size\(\)\)').Count -eq 2
 original40msKept=[regex]::Matches($taskAfter,'Thread\.sleep\(40\)').Count -eq 1
 totalPreparationBudgetShared=[regex]::IsMatch($taskAfter,'(?s)preparationUntil = System\.nanoTime\(\) \+ TimeUnit\.SECONDS\.toNanos\(1\);\s*worker\.start\(\)') -and $taskAfter.Contains('peer.entered.await(remainingNanos(preparationUntil), TimeUnit.NANOSECONDS)') -and $taskAfter.Contains('awaitBodyReadiness(worker, scoped, preparationUntil)')
 completionBudgetShared=$taskAfter.Contains('peer.finished.await(remainingNanos(completionUntil), TimeUnit.NANOSECONDS)') -and $taskAfter.Contains('awaitOriginalQuiescence(scoped.get(), completionUntil)')
 exactExceptionAndPgClassification=$taskAfter.Contains('next.getClass() == IllegalStateException.class') -and $taskAfter.Contains('"ALGORITHM_IO_STOP_NOT_CONFIRMED".equals(next.getMessage())') -and $taskAfter.Contains('if (originalHandoff && !reportedUnconfirmed)')
 handoffNoBeforeInterruptObservation=[regex]::IsMatch($taskAfter,'(?s)if \(!originalHandoff\)\s*\{\s*if \(!diagnostic\.afterReturnOnly\) diagnostic\.capture\("peer-entered", false\);.*?awaitBodyReadiness\(worker, scoped, preparationUntil\).*?\}\s*long start = System\.nanoTime\(\);')
 normalStrictFirstQuiescenceRetained=[regex]::IsMatch($taskAfter,'(?s)if \(!originalHandoff\)\s*assertTrue\(\s*quiescentAtFirstCheck,\s*"original SDK stages, calls and owned executor ended BEFORE return"\);')
 sourceNoHttpWarmupBeforeOriginalExit=-not $taskNewSource.Contains('shared.offload(') -and $taskNewSource.IndexOf('assertSharedUseThenIntentionalCleanup') -gt $taskNewSource.IndexOf('actual completion disposes retained resources')
 noSkipOrAssumption=-not [regex]::IsMatch($taskAfter,'@Disabled|Assumptions\.|assumeTrue\(|assumeFalse\(')
 oneShotBaselineBeforeEveryPeerLatch=[regex]::Matches($taskAfter,'stoppedRouteSequence\.compareAndSet\(-1, acceptedSequence\);\s*entered\.countDown\(\);').Count -eq 3
 noCounterOrFutureFaking=-not [regex]::IsMatch($taskAfter,'routes\.clear\(|receivedRoutes\.set\(|stoppedRouteSequence\.set\(|\.cancel\(')
 onlyOriginalFixtureShutdown=[regex]::Matches($taskAfter,'shutdownNow\(').Count -eq [regex]::Matches($taskBefore,'shutdownNow\(').Count
 bodyPositiveChain=$taskAfter.Contains('okhttp3.internal.connection.Exchange$ResponseBodySource') -and $taskAfter.Contains('okio.InputStreamSource') -and $taskAfter.Contains('java.net.Socket$SocketInputStream') -and $taskAfter.Contains('if (!(json && body && okio && socket)) return false;')
 ownedSdkIdleAndRealGetHeld=$taskAfter.Contains('calls.size() != 1') -and $taskAfter.Contains('!call.request().method().equals("GET")') -and $taskAfter.Contains('dispatcher.runningCallsCount() == 0') -and $taskAfter.Contains('dispatcher.queuedCallsCount() == 0') -and $taskAfter.Contains('pool.getActiveCount() == 0') -and $taskAfter.Contains('pool.getQueue().isEmpty()')
 bracesParenthesesBracketsLexicallyBalanced=$taskBalanced
 productStoreStill9DEB=(Get-FileHash -LiteralPath (Join-Path $taskRoot 'skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java') -Algorithm SHA256).Hash -ceq '9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D'
}
& git -C $taskRoot diff --check -- 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java'
$taskChecks.gitDiffCheckPassed=$LASTEXITCODE -eq 0
$taskAllPassed=@($taskChecks.Values|Where-Object{-not $_}).Count -eq 0
$taskResult=[ordered]@{phase='STATIC_ONLY_NOT_JAVA_PARSER_OR_COMPILER';sourceSha256=(Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash;snapshotSha256=(Get-FileHash -LiteralPath $taskSnapshot -Algorithm SHA256).Hash;head=((& git -C $taskRoot rev-parse HEAD)-join '');originalAssertions=$taskOldAssertions.Count;candidateAssertions=$taskNewAssertions.Count;originalSourceAssertions=$taskSourceOldAssertions.Count;missingOriginalAssertionCount=$taskMissing.Count;missingSourceAssertionCount=$taskSourceMissing.Count;expectedReports=5;expectedFullRecords=674;sourceTestFiles=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'skill-registry/src/test/java') -Recurse -File -Filter '*Test.java').Count;checks=$taskChecks;allStaticChecksPassed=$taskAllPassed;javaMavenPgHttpRun=$false;runnerPrepared=$false;compiled=$false}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'static-check.json'),(ConvertTo-Json -InputObject $taskResult -Depth 8),(New-Object Text.UTF8Encoding($false)))
$taskResult|ConvertTo-Json -Depth 8
if(-not $taskAllPassed){exit 1}
