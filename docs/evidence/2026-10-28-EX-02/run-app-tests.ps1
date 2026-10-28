param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Phase,[string]$Selector='', [switch]$Collect)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$run=Join-Path $PSScriptRoot ('runs/'+$Phase+'-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
if(Test-Path -LiteralPath $run){throw '运行目录已存在，禁止覆盖'}
New-Item -ItemType Directory -Path $run | Out-Null
Get-Content -LiteralPath (Join-Path $repo '.env') | ForEach-Object {
 if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:ENGINE_PG_TEST='true'
$arguments=@('-B','-ntp','-pl','app-management','test',"-Dmaven.repo.local=$repo/.tools/m2",
 '-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp',
 '-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
if($Selector){$arguments+="-Dtest=$Selector"}
if($Collect){$arguments+='-Dmaven.test.failure.ignore=true'}
$started=Get-Date
$previousPreference=$ErrorActionPreference
Push-Location -LiteralPath $repo
try {
 $ErrorActionPreference='Continue'
 & mvn.cmd @arguments *> (Join-Path $run 'maven.raw.log')
 $code=$LASTEXITCODE
} finally { $ErrorActionPreference=$previousPreference; Pop-Location }
$xml=Join-Path $run 'app-management-xml'
New-Item -ItemType Directory -Path $xml | Out-Null
$reports=@(Get-ChildItem -LiteralPath (Join-Path $repo 'app-management/target/surefire-reports') -Filter 'TEST-*.xml' -File |
 Where-Object {$_.LastWriteTime -ge $started})
foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination $xml}
$sources=@{}
foreach($relative in @('app-management/src/main/java/cn/agentplatform/app/infra/ReleasePins.java',
 'app-management/src/test/java/cn/agentplatform/app/infra/ReleasePinsTest.java',
 'app-management/src/test/java/cn/agentplatform/app/application/PublishPinTest.java')){
 $sources[$relative]=(Get-FileHash -LiteralPath (Join-Path $repo $relative) -Algorithm SHA256).Hash
}
$metadata=[pscustomobject]@{name='app-management';phase=$Phase;kind='EX-02';scope=$(if($Selector){'TARGETED'}else{'MODULE'});
 executable='mvn.cmd';arguments=$arguments;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');
 exitCode=$code;status=$(if($code -eq 0){'COLLECTED'}else{'COMMAND_FAILED'});selector=$Selector;
 head=(& git -C $repo rev-parse HEAD);sourceSha256=$sources;xmlCount=$reports.Count;
 environmentSha256=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash;
 developmentFlag=($env:AGENT_DEV_AUTH -eq 'true');postgresFlag=($env:ENGINE_PG_TEST -eq 'true');
 collectionOnly=[bool]$Collect;meaning='Maven0不代表测试通过；以当次XML及逐方法差异判断'}
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'app-management-run.json') -Encoding UTF8
Write-Output ('RUN_RECORD='+$run+'; exit='+$code+'; fresh_xml='+$reports.Count)
exit $code
