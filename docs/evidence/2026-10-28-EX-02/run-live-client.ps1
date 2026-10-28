param([Parameter(Mandatory=$true)][ValidateSet('RED','GREEN','REPEAT')][string]$Phase,
 [Parameter(Mandatory=$true)][string]$PrivateFixture)
$ErrorActionPreference='Stop'
$ap=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$fixture=Get-Content -LiteralPath $PrivateFixture -Raw -Encoding UTF8 | ConvertFrom-Json
$base=[uri]$fixture.base
if(-not $base.IsLoopback -or $base.Port -ne 18182 -or $base.AbsolutePath -ne '/onto'){throw '只允许本卡本体本机API'}
$run=Join-Path $PSScriptRoot ('runs/live-'+$Phase.ToLowerInvariant()+'-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $run | Out-Null
$values=@{EX02_TENANT=$fixture.payload.platformTenant;EX02_BASE=$base.GetLeftPart([UriPartial]::Authority);
 EX02_SPACE=$fixture.payload.spaceId;EX02_RELEASE=$fixture.payload.releaseId;EX02_APP=$fixture.payload.appCode;
 EX02_FOREIGN_SPACE=$fixture.foreignSpaceId;AGENT_SECRET_ONTO_APP_BINDER_KEY=$fixture.binder.key;
 AGENT_SECRET_BINDINGS=('{"AGENT_SECRET_ONTO_APP_BINDER_KEY":"'+$fixture.payload.platformTenant+'"}')}
$priorConsoleEncoding=[Console]::OutputEncoding
$old=@{}
foreach($key in $values.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
 [Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
 foreach($key in $values.Keys){[Environment]::SetEnvironmentVariable($key,[string]$values[$key],'Process')}
 $classpathFile=Join-Path $ap '.local/ex02-live-classpath.txt'
 $cp=[IO.File]::ReadAllText($classpathFile)
 $raw=Join-Path $run 'client.raw.log'
 $previous=$ErrorActionPreference
 try {
  $ErrorActionPreference='Continue'
  & 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe' '-Xmx256m' `
   '-Djava.io.tmpdir=V:/.local/tmp' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' `
   '-cp' $cp (Join-Path $PSScriptRoot 'LivePinProbe.java') $Phase *> $raw
  $code=$LASTEXITCODE
 } finally {$ErrorActionPreference=$previous}
 $json=@(Get-Content -LiteralPath $raw -Encoding UTF8 | Where-Object {$_ -like '{*'}) | Select-Object -Last 1
 $result=if($json){$json | ConvertFrom-Json}else{$null}
 $record=[pscustomobject]@{phase=$Phase;exitCode=$code;runId=$fixture.runId;actualClientResult=$result;
  scope='actual ReleasePins direct client against real OR; not platform publish/J9';
  command='java -Xmx256m -Djava.io.tmpdir=V:/.local/tmp -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -cp <archived Maven XML java.class.path; ignored .local> LivePinProbe.java PHASE';
  classpathSha256=(Get-FileHash -LiteralPath $classpathFile -Algorithm SHA256).Hash;
  sourceSha256=(Get-FileHash -LiteralPath (Join-Path $ap 'app-management/src/main/java/cn/agentplatform/app/infra/ReleasePins.java') -Algorithm SHA256).Hash;
  classSha256=(Get-FileHash -LiteralPath (Join-Path $ap 'app-management/target/classes/cn/agentplatform/app/infra/ReleasePins.class') -Algorithm SHA256).Hash;
  rawSha256=(Get-FileHash -LiteralPath $raw -Algorithm SHA256).Hash;credentialsLogged=$false}
 $record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding utf8
 Write-Output ('LIVE_CLIENT_RECORD='+$run+'; phase='+$Phase+'; exit='+$code)
 if($code -ne 0 -or -not $result){throw '客户端协议探针未通过；查看本卡脱敏记录，勿打印凭据文件'}
} finally {
 [Console]::OutputEncoding=$priorConsoleEncoding
 foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
}
