param([Parameter(Mandatory=$true)][string]$RunName)
$ErrorActionPreference='Stop'
$ApRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$RunDir=Join-Path $PSScriptRoot ('runs/'+$RunName)
$Ex00=Join-Path $PSScriptRoot '../2026-10-28-EX-00'
$Ref=Get-Content -LiteralPath (Join-Path $Ex00 'comparison-baseline.json') -Raw -Encoding UTF8|ConvertFrom-Json
$Before=@(Import-Csv -LiteralPath (Join-Path $Ex00 ($Ref.module_baselines.'platform-gateway'+'/methods.csv')) -Encoding UTF8|Where-Object module -eq 'platform-gateway')
$After=@(Import-Csv -LiteralPath (Join-Path $RunDir 'methods.csv') -Encoding UTF8)
$Run=Get-Content -LiteralPath (Join-Path $RunDir 'run.json') -Raw -Encoding UTF8|ConvertFrom-Json
$Names=@{passed='PASS';failure='FAIL';error='ERROR';skipped='SKIP'}
$B=@{};$A=@{}
foreach($Case in $Before){$Key=$Case.classname+'#'+$Case.method;if($B.ContainsKey($Key)){throw 'Duplicate baseline method'};$B[$Key]=$Case}
foreach($Case in $After){$Key=$Case.classname+'#'+$Case.method;if($A.ContainsKey($Key)){throw 'Duplicate current method'};$A[$Key]=$Case}
$Differences=@(foreach($Key in @(@($B.Keys)+@($A.Keys)|Sort-Object -Unique)){
    $Old=if($B.ContainsKey($Key)){$Names[$B[$Key].status]}else{'UNREPORTED'}
    $New=if($A.ContainsKey($Key)){$A[$Key].status}else{'MISSING'}
    if($Old -ne $New){[pscustomobject]@{key=$Key;before=$Old;after=$New}}
})
$Differences|Export-Csv -LiteralPath (Join-Path $RunDir 'method-differences.csv') -Encoding UTF8 -NoTypeInformation
$Nonpass=@($After|Where-Object status -ne 'PASS')
$Nonpass|Export-Csv -LiteralPath (Join-Path $RunDir 'nonpass.csv') -Encoding UTF8 -NoTypeInformation
function FailureFacts([string]$Path,[string]$Class,[string]$Method){
    $Doc=New-Object System.Xml.XmlDocument;$Doc.Load((Resolve-Path -LiteralPath $Path).Path)
    $Case=@($Doc.SelectNodes('//testcase')|Where-Object{$_.classname -eq $Class -and $_.name -eq $Method})
    if($Case.Count -ne 1){throw 'Nonunique failure case'}
    $Failure=if($null -ne $Case[0].failure){$Case[0].failure}else{$Case[0].error}
    $Causes=@(@(foreach($Line in ($Failure.InnerText -split '\r?\n')){
        if($Line.TrimStart() -match '^Caused by:\s*([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))(?::|$)'){$Matches[1]}
    })|Sort-Object -Unique)
    [pscustomobject]@{type=[string]$Failure.type;causes=$Causes}
}
$Causes=@(foreach($Case in $Nonpass){
    $Key=$Case.classname+'#'+$Case.method
    if($B.ContainsKey($Key) -and $Names[$B[$Key].status] -ne 'PASS'){
        $OldPath=Join-Path $Ex00 ('baseline/'+$B[$Key].xml)
        $NewPath=Join-Path $RunDir ('xml/TEST-'+$Case.classname+'.xml')
        $OldFacts=FailureFacts $OldPath $Case.classname $Case.method
        $NewFacts=FailureFacts $NewPath $Case.classname $Case.method
        [pscustomobject]@{classname=$Case.classname;method=$Case.method;baselineStatus=$Names[$B[$Key].status];afterStatus=$Case.status;
            baselineType=$OldFacts.type;afterType=$NewFacts.type;baselineCauseTypes=$OldFacts.causes;afterCauseTypes=$NewFacts.causes;
            same=($Names[$B[$Key].status] -eq $Case.status -and $OldFacts.type -eq $NewFacts.type -and ($OldFacts.causes -join '|') -eq ($NewFacts.causes -join '|'))}
    }
})
$Causes|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $RunDir 'nonpass-cause-comparison.json') -Encoding UTF8
$ProxySource='platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java'
$OriginalProxyText=(& git -C $ApRoot show ('20333f35bc9f97dc11df296c04c8028e27fd42c8:'+$ProxySource)) -join "`n"
$Pattern='@Test\s+void\s+(\w+)\('
$ProxyOriginal=@([regex]::Matches($OriginalProxyText,$Pattern)|ForEach-Object{$_.Groups[1].Value})
$ProxyExpected=@([regex]::Matches((Get-Content -LiteralPath (Join-Path $ApRoot $ProxySource) -Raw -Encoding UTF8),$Pattern)|ForEach-Object{$_.Groups[1].Value})
$Proxy=@($After|Where-Object classname -eq 'cn.agentplatform.gateway.application.GatewayPublishedRunProxyTest')
$SseScope=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'method-scope.json') -Raw -Encoding UTF8|ConvertFrom-Json
$Sse=@($After|Where-Object classname -eq 'cn.agentplatform.gateway.infra.PortalRunEventsSseLifecycleTest')
$MissingOriginalProxy=@($ProxyOriginal|Where-Object{$_ -notin $Proxy.method})
$MissingExpectedProxy=@($ProxyExpected|Where-Object{$_ -notin $Proxy.method})
$MissingSse=@($SseScope.candidateMethods|Where-Object{$_ -notin $Sse.method})
$Classes=@($After.classname|ForEach-Object{($_ -split '\.')[-1]}|Sort-Object -Unique)
$MissingClasses=@($Run.sourceTestClasses|Where-Object{$_ -notin $Classes})
$ExtraClasses=@($Classes|Where-Object{$_ -notin $Run.sourceTestClasses})
$MissingMethods=@($Differences|Where-Object after -eq 'MISSING')
$NewNonpass=@($Differences|Where-Object{$_.after -in @('FAIL','ERROR','SKIP') -and $_.before -in @('UNREPORTED','PASS')})
$UnexpectedAdded=@($Differences|Where-Object{$_.before -eq 'UNREPORTED' -and $_.key -notlike 'cn.agentplatform.gateway.application.GatewayPublishedRunProxyTest#*' -and $_.key -ne 'cn.agentplatform.gateway.infra.PortalRunEventsSseLifecycleTest#offLockCloseDuringProcessingRetainsSafetyWithoutInventingRecycle'})
$CauseChanges=@($Causes|Where-Object{-not $_.same})
$CollectionComplete=($After.Count -eq 288 -and $Run.reportCount -eq 27 -and $ProxyOriginal.Count -eq 30 -and $ProxyExpected.Count -eq 33 -and $Proxy.Count -eq 33 -and $Sse.Count -eq 38 -and $MissingClasses.Count -eq 0 -and $ExtraClasses.Count -eq 0 -and $MissingMethods.Count -eq 0 -and $MissingOriginalProxy.Count -eq 0 -and $MissingExpectedProxy.Count -eq 0 -and $MissingSse.Count -eq 0 -and $UnexpectedAdded.Count -eq 0 -and $Run.collectionErrors.Count -eq 0)
$Gate=($CollectionComplete -and $NewNonpass.Count -eq 0 -and $CauseChanges.Count -eq 0 -and @($Proxy|Where-Object status -ne 'PASS').Count -eq 0 -and @($Sse|Where-Object status -ne 'PASS').Count -eq 0 -and $Run.fingerprintChanges.Count -eq 0 -and $Run.childCount -eq 49 -and $Run.badOrMissingChildExits.Count -eq 0 -and $Run.headAtStart -eq $Run.headAtEnd)
$Summary=[pscustomobject]@{reference='EX00/comparison-baseline.json';run=$RunName;nativeMavenExit=$Run.exitCode;baselineMethods=$Before.Count;
    baselineAttempts=@($Before|Group-Object attempt|ForEach-Object{[pscustomobject]@{attempt=$_.Name;count=$_.Count}});baselineClasses=26;
    afterMethods=$After.Count;afterClasses=$Classes.Count;reports=$Run.reportCount;statuses=$Run.statuses;expectedMethods=288;
    restoredOriginalProxyMethods=$ProxyOriginal.Count;newProxyMethods=@($ProxyExpected|Where-Object{$_ -notin $ProxyOriginal});proxyReported=$Proxy.Count;
    originalSseMethods=$SseScope.originalCount;newSseMethods=$SseScope.addedMethods;sseReported=$Sse.Count;
    missingClasses=$MissingClasses;unexpectedClasses=$ExtraClasses;missingBaselineMethods=$MissingMethods;missingOriginalProxy=$MissingOriginalProxy;
    missingExpectedProxy=$MissingExpectedProxy;missingSse=$MissingSse;unexpectedAdded=$UnexpectedAdded;
    newlyNonpassing=$NewNonpass;changedReportedMethods=@($Differences|Where-Object before -ne 'UNREPORTED');
    retainedNonpassCauseChanges=$CauseChanges;retainedNonpassMethods=$Causes.Count;
    collectionComplete=$CollectionComplete;regressionGate=$Gate;allTestsPassed=($Nonpass.Count -eq 0);
    environmentDisclosure=@('Per-class fresh JVM via -DreuseForks=false differs from original EX00 fork reuse; no ServiceTokens reset',
        'AP dependency restoration is an environment fact from EX35/dependency-recovery.json, not a product fix',
        'Gateway YAML includes EX01 explicit service environment configuration; its exact SHA is frozen before/after each run');
    originalEx35ModuleFailurePreserved=$true;ex27DiagnosticFailurePreserved=$true;earlierFixtureCompileFailurePreserved=$true}
$Summary|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $RunDir 'comparison.json') -Encoding UTF8
$Summary|Select-Object nativeMavenExit,baselineMethods,afterMethods,afterClasses,reports,collectionComplete,regressionGate,allTestsPassed,
    @{n='newNonpass';e={$_.newlyNonpassing.Count}},@{n='causeChanges';e={$_.retainedNonpassCauseChanges.Count}}|ConvertTo-Json
