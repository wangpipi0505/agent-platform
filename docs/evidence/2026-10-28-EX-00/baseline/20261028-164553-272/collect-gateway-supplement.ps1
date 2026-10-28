$ErrorActionPreference='Stop'
$root='G:/207/项目/算法重组工具/代码/agent-platform'
$primary=Join-Path $root 'docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272'
$expectedHead='ebbfd62ca1d19efc4d172804efb5ac79453ceb64'
$head=(& git -C $root rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0 -or $head -ne $expectedHead){throw 'Source HEAD differs from the primary baseline'}
if(-not(Test-Path -LiteralPath "$primary/monitor-run.json")){throw 'Primary AP module collection must finish before supplement'}
$alreadyReported=@('GatewayPublishedChainPostgresTest','GatewayRunStatusChainPostgresTest','PortalSessionHttpTest')
$knownBad='GatewayPublishedRunProxyTest'
$files=@(Get-ChildItem -LiteralPath "$root/platform-gateway/src/test/java" -Filter '*Test.java' -Recurse -File)
$selectedFiles=@($files|Where-Object {$_.BaseName -notin $alreadyReported -and $_.BaseName -ne $knownBad}|Sort-Object FullName)
if($selectedFiles.Count -ne 23){throw 'Supplement source selection differs from the approved 23-class set'}
$selected=@($selectedFiles|ForEach-Object{
 $package=Select-String -LiteralPath $_.FullName -Pattern '^package\s+([^;]+);'|Select-Object -First 1
 if(-not $package){throw 'Missing package declaration in selected source'}
 ($package.Matches[0].Groups[1].Value)+'.'+$_.BaseName
})
$runId=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$evidence=Join-Path $root "docs/evidence/2026-10-28-EX-00/baseline/$runId"
if(Test-Path -LiteralPath $evidence){throw 'Evidence directory already exists'}
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$head|Set-Content -LiteralPath "$evidence/HEAD.txt" -Encoding utf8
git -C $root branch --show-current|Set-Content -LiteralPath "$evidence/branch.txt" -Encoding utf8
$selected|Set-Content -LiteralPath "$evidence/selected-classes.txt" -Encoding utf8
Get-Content -LiteralPath "$root/.env"|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:ENGINE_PG_TEST='true'
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:MAVEN_OPTS='-Xmx512m'
$arguments=@('-B','-ntp','-pl','platform-gateway','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=true',"-Dtest=$($selected -join ',')")
$started=Get-Date
$code=$null;$status='COLLECTED'
Push-Location -LiteralPath $root
$previous=$ErrorActionPreference
try{
 $ErrorActionPreference='Continue'
 & mvn.cmd @arguments *> "$evidence/platform-gateway.raw.txt"
 $code=$LASTEXITCODE
 if($code -ne 0){$status='COMMAND_FAILED'}
}catch{
 $status='COMMAND_FAILED'
 $_|Out-String|Add-Content -LiteralPath "$evidence/platform-gateway.raw.txt"
}finally{
 $ErrorActionPreference=$previous
 Pop-Location
 $xml=Join-Path $evidence 'platform-gateway-xml'
 New-Item -ItemType Directory -Path $xml -Force|Out-Null
 Get-ChildItem -LiteralPath "$root/platform-gateway/target/surefire-reports" -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue|Where-Object {$_.LastWriteTime -ge $started}|Copy-Item -Destination $xml
 [pscustomobject]@{name='platform-gateway';root=$root;executable='mvn.cmd';arguments=$arguments;selectedClasses=$selected;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$code;status=$status;kind='supplement';primaryRunId='20261028-164553-272';excludedReportedClasses=$alreadyReported;excludedBadClass=$knownBad}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$evidence/platform-gateway-run.json" -Encoding utf8
 [pscustomobject]@{runId=$runId;path=$evidence;primaryRunId='20261028-164553-272';sourceHead=$head}|ConvertTo-Json|Set-Content -LiteralPath "$primary/gateway-supplement-link.json" -Encoding utf8
}
Write-Host "Gateway supplement path=$evidence exit=$code status=$status"
