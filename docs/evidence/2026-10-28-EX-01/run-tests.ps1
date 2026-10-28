param([Parameter(Mandatory=$true)][ValidateSet('app-management','engine-runtime','skill-registry','platform-gateway')][string]$Module,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Phase,[string]$Selector='',[switch]$Collect)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$run=Join-Path $PSScriptRoot ('runs/'+$Module+'-'+$Phase+'-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $run | Out-Null
Get-Content -LiteralPath (Join-Path $repo '.env') | ForEach-Object {
 if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:ENGINE_PG_TEST='true'
$arguments=@('-B','-ntp','-pl',$Module,'test',"-Dmaven.repo.local=$repo/.tools/m2",
 '-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp',
 '-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
if($Module -eq 'platform-gateway'){$arguments+='-DreuseForks=false'}
if($Selector){$arguments+="-Dtest=$Selector"}
if($Collect){$arguments+='-Dmaven.test.failure.ignore=true'}
$hashes=@{}
foreach($path in @('scripts/start-backend.ps1','.env.example','scripts/Test-ServiceEnvironment.ps1',
 'engine-runtime/src/main/resources/application.yml','platform-gateway/src/main/resources/application.yml',
 'app-management/src/main/java/cn/agentplatform/app/infra/ReleasePins.java',
 'platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java',
 'platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java')){
 $hashes[$path]=(Get-FileHash -LiteralPath (Join-Path $repo $path) -Algorithm SHA256).Hash
}
$started=Get-Date
$startHead=(& git -C $repo rev-parse HEAD)
$priorPreference=$ErrorActionPreference
Push-Location -LiteralPath $repo
try {
 $ErrorActionPreference='Continue'
 & mvn.cmd @arguments *> (Join-Path $run 'raw.log')
 $code=$LASTEXITCODE
} finally {$ErrorActionPreference=$priorPreference;Pop-Location}
[pscustomobject]@{startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$code;arguments=$arguments;headAtStart=$startHead;sourceSha256=$hashes} |
 ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'execution.json') -Encoding UTF8
$xml=Join-Path $run 'xml'
New-Item -ItemType Directory -Path $xml | Out-Null
$reports=@(Get-ChildItem -LiteralPath (Join-Path $repo "$Module/target/surefire-reports") -Filter 'TEST-*.xml' -File |
 Where-Object {$_.LastWriteTime -ge $started})
$methods=@()
foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination $xml}
foreach($report in $reports){
 # 按XML自身UTF-8声明读取，禁止5.1默认ANSI破坏中文属性；解析错误也不能回显整份XML。
 $doc=New-Object Xml.XmlDocument
 try {$doc.Load($report.FullName)}catch{throw "本轮XML解析失败：$($report.Name)；异常类型=$($_.Exception.GetType().Name)"}
 foreach($case in @($doc.testsuite.testcase)){
  if($null -eq $case){continue}
  $status='PASS'
  if($null -ne $case.failure){$status='FAIL'}elseif($null -ne $case.error){$status='ERROR'}elseif($null -ne $case.skipped){$status='SKIP'}
  $methods+=[pscustomobject]@{module=$Module;classname=[string]$case.classname;method=[string]$case.name;status=$status;seconds=[string]$case.time}
 }
}
$methods | Export-Csv -LiteralPath (Join-Path $run 'methods.csv') -Encoding UTF8 -NoTypeInformation
$record=[pscustomobject]@{module=$Module;phase=$Phase;scope=$(if($Selector){'TARGETED'}else{'MODULE'});selector=$Selector;
 arguments=$arguments;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$code;
 headAtStart=$startHead;headAtEnd=(& git -C $repo rev-parse HEAD);sourceSha256=$hashes;
 environmentSha256=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash;
 developmentFlag=($env:AGENT_DEV_AUTH -eq 'true');postgresFlag=($env:ENGINE_PG_TEST -eq 'true');
 reportCount=$reports.Count;methodCount=$methods.Count;statuses=@($methods | Group-Object status | ForEach-Object {[pscustomobject]@{status=$_.Name;count=$_.Count}});
 sourceTestClasses=@(Get-ChildItem -LiteralPath (Join-Path $repo "$Module/src/test/java") -Filter '*Test.java' -File -Recurse | ForEach-Object {$_.BaseName} | Sort-Object);
 collectionOnly=[bool]$Collect;meaning='测试结论以本轮方法表/基线对照为准，不以Maven退出0替代通过'}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding UTF8
Write-Output ('EX01_RUN='+$run+'; exit='+$code+'; fresh_xml='+$reports.Count+'; methods='+$methods.Count)
$record.statuses | ConvertTo-Json
exit $code
