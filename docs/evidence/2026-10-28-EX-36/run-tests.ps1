param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Phase,
      [string]$Selector='OntologyContextPacksTest', [switch]$Collect, [switch]$All)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$run=Join-Path $PSScriptRoot ('runs/'+$Phase+'-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $run | Out-Null
Get-Content -LiteralPath (Join-Path $repo '.env') | ForEach-Object {
 if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:ENGINE_PG_TEST='true'
if($All){$Selector=''}
$arguments=@('-B','-ntp','-pl','engine-runtime','test',"-Dmaven.repo.local=$repo/.tools/m2",
 '-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp',
 '-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
if($Selector){$arguments+="-Dtest=$Selector"}
if($Collect){$arguments+='-Dmaven.test.failure.ignore=true'}
$sources=@{}
foreach($path in @('engine-runtime/src/main/java/cn/agentplatform/engine/infra/OntologyContextPacks.java',
 'engine-runtime/src/test/java/cn/agentplatform/engine/infra/OntologyContextPacksTest.java')){
 $sources[$path]=(Get-FileHash -LiteralPath (Join-Path $repo $path) -Algorithm SHA256).Hash
}
$head=(& git -C $repo rev-parse HEAD)
$started=Get-Date
Push-Location -LiteralPath $repo
try {
 $ErrorActionPreference='Continue'
 & mvn.cmd @arguments *> (Join-Path $run 'raw.log')
 $code=$LASTEXITCODE
} finally {$ErrorActionPreference='Stop';Pop-Location}
$record=[ordered]@{phase=$Phase;module='engine-runtime';arguments=$arguments;
 startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$code;
 headAtStart=$head;headAtEnd=(& git -C $repo rev-parse HEAD);sourceSha256=$sources;
 collectorSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash;
 environmentSha256=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash;
 developmentFlag=($env:AGENT_DEV_AUTH -eq 'true');postgresFlag=($env:ENGINE_PG_TEST -eq 'true');
 securityModePresent=($null -ne [Environment]::GetEnvironmentVariable('AGENT_SECURITY_MODE','Process'));
 securityMode=[Environment]::GetEnvironmentVariable('AGENT_SECURITY_MODE','Process');
 selector=$Selector;collectionOnly=[bool]$Collect;independentPowerShellProcessRequired=$true}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'execution.json') -Encoding UTF8
$xml=New-Item -ItemType Directory -Path (Join-Path $run 'xml')
$reports=@(Get-ChildItem -LiteralPath (Join-Path $repo 'engine-runtime/target/surefire-reports') -File -Filter 'TEST-*.xml' |
 Where-Object {$_.LastWriteTime -ge $started})
$methods=@(foreach($report in $reports){
 Copy-Item -LiteralPath $report.FullName -Destination $xml.FullName
 $doc=New-Object Xml.XmlDocument
 try {$doc.Load($report.FullName)}catch{throw "XML解析失败：$($report.Name)，原生退出已归档"}
 foreach($case in @($doc.testsuite.testcase)){
  $status='PASS'
  if($null -ne $case.failure){$status='FAIL'}elseif($null -ne $case.error){$status='ERROR'}elseif($null -ne $case.skipped){$status='SKIP'}
  [pscustomobject]@{module='engine-runtime';classname=[string]$case.classname;method=[string]$case.name;status=$status;seconds=[string]$case.time}
 }
})
$methods | Export-Csv -LiteralPath (Join-Path $run 'methods.csv') -Encoding UTF8 -NoTypeInformation
$record.reportCount=$reports.Count
$record.methodCount=$methods.Count
$record.statuses=@($methods | Group-Object status | ForEach-Object {[pscustomobject]@{status=$_.Name;count=$_.Count}})
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding UTF8
Write-Output ('EX36_RUN='+$run+' exit='+$code+' fresh_xml='+$reports.Count+' methods='+$methods.Count)
$record.statuses | ConvertTo-Json
exit $code
