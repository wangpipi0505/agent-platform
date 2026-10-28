param([ValidateSet('targeted','class','module')][string]$Stage='targeted')
$ErrorActionPreference='Stop'
$ApRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
Get-Content -LiteralPath (Join-Path $ApRoot '.env') -Encoding UTF8 | ForEach-Object {
    if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:ENGINE_PG_TEST='true'
$Source='platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java'
$FingerprintPaths=@($Source,'platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java',
    'platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java',
    'platform-gateway/src/main/resources/application.yml',
    'platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java',
    'platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java',
    'platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudget.java','.env')
function Fingerprints { @($FingerprintPaths|ForEach-Object{[pscustomobject]@{path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $ApRoot $_) -Algorithm SHA256).Hash}}) }
$Before=Fingerprints
if($Before[0].sha256 -ne '6B144C0033CE22556996897824B2F0D864A862E39F6E12EF2C21B5212B5199C1'){throw 'Unreviewed SSE test source'}
if($Before[1].sha256 -ne 'B4AFF02FFC69CBDC5697A5B7D1B9BC6151B6D75D180791AD5B05E5926C92E129'){throw 'Unexpected proxy product source'}
if($Before[2].sha256 -ne '03533FDD869AC48D3F1036871FCA76F0EE4CDB05B98A03FA8D62C78F00E376D6'){throw 'Unexpected proxy test source'}
$RunDir=Join-Path $PSScriptRoot ('runs/safety-'+$Stage+'-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $RunDir|Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $RunDir 'run-safety.ps1')
$CollectorHash=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
$Before|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $RunDir 'fingerprints-before.json') -Encoding UTF8
$SourceMethods=@([regex]::Matches((Get-Content -LiteralPath (Join-Path $ApRoot $Source) -Raw -Encoding UTF8),'@Test\s+void\s+(\w+)\(')|ForEach-Object{$_.Groups[1].Value})
$SourceMethods|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $RunDir 'source-methods.json') -Encoding UTF8
$SourceClasses=@(Get-ChildItem -LiteralPath (Join-Path $ApRoot 'platform-gateway/src/test/java') -Filter '*Test.java' -Recurse|ForEach-Object{$_.BaseName}|Sort-Object)
$StartedAt=Get-Date
$HeadBefore=(& git -C $ApRoot rev-parse HEAD)
$Targets=@('forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion','offLockCloseDuringProcessingRetainsSafetyWithoutInventingRecycle')
$Selector=if($Stage -eq 'targeted'){'PortalRunEventsSseLifecycleTest#'+($Targets -join '+')}elseif($Stage -eq 'class'){'PortalRunEventsSseLifecycleTest'}else{''}
$MavenArgs=@('-B','-ntp','-pl','platform-gateway','test',"-Dmaven.repo.local=$ApRoot/.tools/m2",
    '-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-DreuseForks=false',
    '-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
if($Selector){$MavenArgs+="-Dtest=$Selector"}
Push-Location -LiteralPath $ApRoot
try {
    $ErrorActionPreference='Continue'
    & mvn.cmd @MavenArgs *> (Join-Path $RunDir 'raw.log')
    $NativeExit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    $Execution=[pscustomobject]@{stage=$Stage;startedAt=$StartedAt.ToString('o');processEndedAt=(Get-Date).ToString('o');
        exitCode=$NativeExit;command=('mvn.cmd '+($MavenArgs -join ' '));collectorSha256=$CollectorHash;diagnosticFlag=$false}
    $Execution|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $RunDir 'execution.json') -Encoding UTF8
    $Errors=@();$Children=@();$Facts=@()
    $PeerRoot=Join-Path $ApRoot '.local/iam-o2b-f2c2a-http-exit-fixes/peers'
    $PeerDirs=@(Get-ChildItem -LiteralPath $PeerRoot -Directory|Where-Object{$_.CreationTime -ge $StartedAt})
    foreach($Peer in $PeerDirs){
        try {
            $Dest=Join-Path $RunDir ('peers/'+$Peer.Name);New-Item -ItemType Directory -Path $Dest -Force|Out-Null
            $ExitFile=Join-Path $Peer.FullName 'exit.properties';$Props=@{}
            if(Test-Path -LiteralPath $ExitFile){
                Copy-Item -LiteralPath $ExitFile -Destination (Join-Path $Dest 'exit.properties')
                Get-Content -LiteralPath $ExitFile -Encoding UTF8|ForEach-Object{if($_ -match '^([A-Za-z]+)=(.*)$'){$Props[$Matches[1]]=$Matches[2]}}
            }
            $Children+=[pscustomobject]@{directory=$Peer.Name;exit=$Props['exit'];alive=$Props['alive'];collectorAlive=$Props['collectorAlive'];pid=$Props['pid']}
            foreach($Name in @('associated-close-facts.json','ex27-release-diagnostic.json','forced-release.properties','off-lock-release-safety.properties','protocol.properties')){
                $File=Join-Path $Peer.FullName $Name
                if(Test-Path -LiteralPath $File){Copy-Item -LiteralPath $File -Destination (Join-Path $Dest $Name)}
            }
            $TraceFacts=@(foreach($File in @(Get-ChildItem -LiteralPath $Peer.FullName -Filter 'forced-trace-*.txt')){
                $Phases=@(Get-Content -LiteralPath $File.FullName -Encoding UTF8|ForEach-Object{if($_ -match '^([a-z][a-z0-9-]{0,64})=-?[0-9]+$'){$Matches[1]}})
                [pscustomobject]@{file=$File.Name;phases=$Phases;counts=@($Phases|Group-Object|ForEach-Object{[pscustomobject]@{phase=$_.Name;count=$_.Count}})}
            })
            if($TraceFacts.Count){$TraceFacts|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $Dest 'phase-facts.json') -Encoding UTF8}
            if($Peer.Name -match '^(forced-release-|off-lock-release-safety-)'){$Facts+=[pscustomobject]@{directory=$Peer.Name;traceFiles=$TraceFacts.Count}}
        }catch{$Errors+=[pscustomobject]@{stage='child';file=$Peer.Name;type=$_.Exception.GetType().FullName}}
    }
    $Children|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $RunDir 'native-children.json') -Encoding UTF8
    $XmlDir=Join-Path $RunDir 'xml';New-Item -ItemType Directory -Path $XmlDir|Out-Null
    $Reports=@(Get-ChildItem -LiteralPath 'platform-gateway/target/surefire-reports' -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTime -ge $StartedAt})
    $Methods=@();$Failures=@()
    foreach($Report in $Reports){
        try {
            Copy-Item -LiteralPath $Report.FullName -Destination $XmlDir
            $Doc=New-Object System.Xml.XmlDocument;$Doc.Load($Report.FullName)
            foreach($Case in @($Doc.testsuite.testcase)){
                if($null -eq $Case){continue}
                $Status='PASS';$Failure=$null
                if($null -ne $Case.failure){$Status='FAIL';$Failure=$Case.failure}elseif($null -ne $Case.error){$Status='ERROR';$Failure=$Case.error}elseif($null -ne $Case.skipped){$Status='SKIP'}
                $Methods+=[pscustomobject]@{module='platform-gateway';classname=[string]$Case.classname;method=[string]$Case.name;status=$Status;seconds=[string]$Case.time}
                if($null -ne $Failure){
                    $Types=@();$Frames=@()
                    foreach($Line in ($Failure.InnerText -split '\r?\n')){
                        if($Line -match '^(?:Caused by: )?([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))(?::|$)'){$Types+=$Matches[1]}
                        if($Line -match '^\s*at cn\.agentplatform\.[A-Za-z0-9_.$]+\([^\r\n]*\.java:\d+\)\s*$'){$Frames+=$Line.Trim()}
                    }
                    $Failures+=[pscustomobject]@{classname=[string]$Case.classname;method=[string]$Case.name;status=$Status;type=[string]$Failure.type;causeTypes=@($Types|Select-Object -Unique);frames=$Frames}
                }
            }
        }catch{$Errors+=[pscustomobject]@{stage='xml';file=$Report.Name;type=$_.Exception.GetType().FullName}}
    }
    $Methods|Export-Csv -LiteralPath (Join-Path $RunDir 'methods.csv') -Encoding UTF8 -NoTypeInformation
    $Failures|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $RunDir 'failure-safe-facts.json') -Encoding UTF8
    $After=Fingerprints
    $After|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $RunDir 'fingerprints-after.json') -Encoding UTF8
    $Changes=@(for($i=0;$i -lt $Before.Count;$i++){if($Before[$i].sha256 -ne $After[$i].sha256){$Before[$i].path}})
    $Run=[pscustomobject]@{stage=$Stage;startedAt=$StartedAt.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$NativeExit;
        headAtStart=$HeadBefore;headAtEnd=(& git rev-parse HEAD);command=$Execution.command;freshForkPerClass=$true;diagnosticFlag=$false;
        collectorSha256=$CollectorHash;sourceSha256=$Before[0].sha256;fingerprintChanges=$Changes;sourceTestClasses=$SourceClasses;
        configurationSha256=$Before[-1].sha256;developmentFlagPresent=($env:AGENT_DEV_AUTH -eq 'true');postgresTestsEnabled=($env:ENGINE_PG_TEST -eq 'true');
        reportCount=$Reports.Count;methodCount=$Methods.Count;statuses=@($Methods|Group-Object status|ForEach-Object{[pscustomobject]@{status=$_.Name;count=$_.Count}});
        childCount=$Children.Count;badOrMissingChildExits=@($Children|Where-Object{$_.exit -ne '0' -or $_.alive -ne 'false' -or $_.collectorAlive -ne 'false'});
        collectionErrors=$Errors;independentPowerShellProcess=$true;serviceTokensReset=$false}
    $Run|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $RunDir 'run.json') -Encoding UTF8
    $Safe=@(Get-Content -LiteralPath (Join-Path $RunDir 'raw.log') -Encoding UTF8|Where-Object{$_ -match '^\[INFO\] (Tests run:|BUILD |Total time:)|^\[ERROR\] (Tests run:|COMPILATION ERROR|.*\.java:\[)'})
    $Safe|Set-Content -LiteralPath (Join-Path $RunDir 'safe-facts.txt') -Encoding UTF8
    $Safe
    [pscustomobject]@{runDirectory=$RunDir;stage=$Stage;nativeExit=$NativeExit;reportCount=$Run.reportCount;methodCount=$Run.methodCount;statuses=$Run.statuses;childCount=$Run.childCount;badChildExits=$Run.badOrMissingChildExits.Count;collectionErrors=$Errors.Count;fingerprintChanges=$Changes.Count;headUnchanged=($Run.headAtStart -eq $Run.headAtEnd)}|ConvertTo-Json -Depth 5
}finally{Pop-Location}
exit $NativeExit

