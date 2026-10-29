param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskRun=Join-Path $PSScriptRoot $RunId
if(Test-Path -LiteralPath $taskRun){throw 'Run directory already exists'}
New-Item -ItemType Directory -Path $taskRun | Out-Null
function Write-Fact($Name,$Value){
 [IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 8),(New-Object Text.UTF8Encoding($false)))
}
function Source-Facts {
 @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'skill-registry/src') -Recurse -File | Sort-Object FullName | ForEach-Object {
  [pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
 })
}
$before=Source-Facts
Write-Fact 'source-before.json' $before
$prior=@{};$location=Get-Location
$native=2
try {
 Set-Location -LiteralPath $taskRoot
 $values=@{}
 foreach($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'),[Text.Encoding]::UTF8)){
  if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}
 }
 foreach($key in (@($values.Keys)+@('JAVA_HOME','PATH','ENGINE_PG_TEST') | Select-Object -Unique)){
  $prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
 }
 foreach($key in $values.Keys){[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
 $env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
 $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $env:ENGINE_PG_TEST='true'
 $mavenArgs=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dtest=MultipartStreamingTest')
 $started=[DateTime]::UtcNow
 Write-Fact 'context.json' ([ordered]@{head=((& git rev-parse HEAD)-join '');envSha256=(Get-FileHash -LiteralPath '.env').Hash;adminJarSha256=(Get-FileHash -LiteralPath '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar').Hash;arguments=$mavenArgs;startedAt=$started.ToString('o');failureIgnore=$false;scope='selected class; controlled loopback HTTP and persistence stubs, no real PG persistence claim'})
 $ErrorActionPreference='Continue'
 & mvn.cmd @mavenArgs > (Join-Path $taskRun 'maven.log') 2>&1
 $native=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Write-Fact 'native-exit.json' ([ordered]@{nativeExit=$native;endedAt=[DateTime]::UtcNow.ToString('o')})
 $report=Join-Path $taskRoot 'skill-registry/target/surefire-reports/TEST-cn.agentplatform.skill.infra.MultipartStreamingTest.xml'
 if(-not(Test-Path -LiteralPath $report) -or (Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $started){throw 'Fresh selected XML missing'}
 Copy-Item -LiteralPath $report -Destination (Join-Path $taskRun 'methods.xml')
 $xml=New-Object Xml.XmlDocument
 $xml.Load($report)
 $methods=@($xml.SelectNodes('//testcase') | ForEach-Object {
  $failure=$_.SelectSingleNode('failure');$errorNode=$_.SelectSingleNode('error');$skipped=$_.SelectSingleNode('skipped')
  [pscustomobject]@{class=$_.classname;method=$_.name;status=$(if($failure){'failure'}elseif($errorNode){'error'}elseif($skipped){'skipped'}else{'passed'});message=$(if($failure){$failure.message}elseif($errorNode){$errorNode.message}else{''})}
 })
 Write-Fact 'methods.json' $methods
 $after=Source-Facts
 Write-Fact 'source-after.json' $after
 $stable=(ConvertTo-Json -InputObject $before -Compress) -ceq (ConvertTo-Json -InputObject $after -Compress)
 Write-Fact 'summary.json' ([ordered]@{nativeExit=$native;tests=$methods.Count;passed=@($methods|Where-Object status -eq 'passed').Count;failures=@($methods|Where-Object status -eq 'failure').Count;errors=@($methods|Where-Object status -eq 'error').Count;skipped=@($methods|Where-Object status -eq 'skipped').Count;sourceStable=$stable})
 if(-not $stable){throw 'Source changed during execution'}
 Get-Content -LiteralPath (Join-Path $taskRun 'summary.json') -Encoding UTF8
} finally {
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
 Set-Location -LiteralPath $location
}
exit $native
