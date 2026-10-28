$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$run=Get-Item -LiteralPath (Join-Path $PSScriptRoot 'runs/skill-registry-module-20261028-213410-422')
$xml=Join-Path $run.FullName 'xml'
New-Item -ItemType Directory -Path $xml -Force | Out-Null
$reports=@(Get-ChildItem -LiteralPath (Join-Path $repo 'skill-registry/target/surefire-reports') -Filter 'TEST-*.xml' -File | Where-Object {$_.LastWriteTime -ge $run.CreationTime})
if($reports.Count -ne 70){throw '本轮70份原始报告不齐；不能补造或重跑覆盖'}
foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination $xml}
$methods=@()
foreach($report in $reports){
 $doc=New-Object Xml.XmlDocument
 try{$doc.Load($report.FullName)}catch{throw "本轮XML仍不可解析：$($report.Name)"}
 foreach($case in @($doc.testsuite.testcase)){
  if($null -eq $case){continue}
  $status='PASS'
  if($null -ne $case.failure){$status='FAIL'}elseif($null -ne $case.error){$status='ERROR'}elseif($null -ne $case.skipped){$status='SKIP'}
  $methods+=[pscustomobject]@{module='skill-registry';classname=[string]$case.classname;method=[string]$case.name;status=$status;seconds=[string]$case.time}
 }
}
$methods | Export-Csv -LiteralPath (Join-Path $run.FullName 'methods.csv') -Encoding UTF8 -NoTypeInformation
$priorPath=Join-Path $PSScriptRoot 'runs/platform-gateway-targeted-20261028-213346-767/run.json'
$prior=Get-Content -Raw -LiteralPath $priorPath -Encoding UTF8 | ConvertFrom-Json
$hashMatch=$true
foreach($entry in $prior.sourceSha256.PSObject.Properties){if((Get-FileHash -LiteralPath (Join-Path $repo $entry.Name) -Algorithm SHA256).Hash -ne $entry.Value){$hashMatch=$false}}
$log=Get-Item -LiteralPath (Join-Path $run.FullName 'raw.log')
$buildSuccess=[IO.File]::ReadAllText($log.FullName).Contains('[INFO] BUILD SUCCESS')
$record=[pscustomobject]@{module='skill-registry';phase='module';scope='MODULE';selector='';
 arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$repo/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=true');
 startedAt=$run.CreationTime.ToString('o');startedAtOrigin='run-directory creation; exact captured timestamp lost with collector process';endedAt=$log.LastWriteTime.ToString('o');exitCode=$null;
 rawMavenBuildSuccess=$buildSuccess;exitCodeUnavailable='Maven completed before ANSI XML collector failed; do not invent an observed native exit code';
 headAtStart=$null;headAtRecovery=(& git -C $repo rev-parse HEAD);sourceProvenance='earlier targeted record with same candidate; hashes checked at recovery';
 earlierCandidateRecord=$priorPath;earlierCandidateSourceHashesStillMatch=$hashMatch;sourceSha256=$prior.sourceSha256;
 environmentSha256=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash;developmentFlag=$true;postgresFlag=$true;
 reportCount=$reports.Count;methodCount=$methods.Count;statuses=@($methods | Group-Object status | ForEach-Object {[pscustomobject]@{status=$_.Name;count=$_.Count}});
 sourceTestClasses=@(Get-ChildItem -LiteralPath (Join-Path $repo 'skill-registry/src/test/java') -Filter '*Test.java' -File -Recurse | ForEach-Object {$_.BaseName} | Sort-Object);
 collectionOnly=$true;recoveredWithoutTestRerun=$true;collectorFailure='PS5 default ANSI Get-Content corrupted UTF-8 XML; recovery uses XmlDocument.Load';
 meaning='实际XML/逐方法表为结果依据，原采集异常与缺失的原生退出码保持显式，不用补采冒充首次采集成功'}
$record | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $run.FullName 'run.json') -Encoding UTF8
Write-Output ('EX01_RECOVER reports='+$reports.Count+'; methods='+$methods.Count+'; rawBuildSuccess='+$buildSuccess+'; earlierCandidateHashesMatch='+$hashMatch)
$record.statuses | ConvertTo-Json
