$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$run=Join-Path $PSScriptRoot ('runs/package-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $run | Out-Null
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$modules=@('app-management','engine-runtime','skill-registry','platform-gateway')
$arguments=@('-B','-ntp','-pl',($modules -join ','),'-am','package','-Dmaven.test.skip=true',"-Dmaven.repo.local=$repo/.tools/m2")
$started=Get-Date
$head=(& git -C $repo rev-parse HEAD)
$sources=@{}
foreach($module in @('admin-common')+$modules){
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo "$module/src/main") -File -Recurse){
  $relative=$file.FullName.Substring($repo.Length+1).Replace('\','/')
  $sources[$relative]=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
 }
}
$before=$ErrorActionPreference
Push-Location -LiteralPath $repo
try{$ErrorActionPreference='Continue';& mvn.cmd @arguments *> (Join-Path $run 'raw.log');$code=$LASTEXITCODE}
finally{$ErrorActionPreference=$before;Pop-Location}
$jars=@()
if($code -eq 0){
 foreach($module in $modules){
  $path=Join-Path $repo "$module/target/$module-0.1.0-SNAPSHOT.jar"
  $jars+=[pscustomobject]@{module=$module;path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash;writtenAt=(Get-Item -LiteralPath $path).LastWriteTime.ToString('o')}
 }
}
[pscustomobject]@{kind='PACKAGE_ONLY';arguments=$arguments;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');
 headAtStart=$head;headAtEnd=(& git -C $repo rev-parse HEAD);exitCode=$code;sourceSha256=$sources;jars=$jars;testsRun=0;
 meaning='只构建四个启动服务和其admin-common依赖，不install全reactor；跳过测试编译/执行，不计测试通过'} |
 ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding UTF8
Write-Output ('EX01_PACKAGE='+$run+'; exit='+$code+'; jars='+$jars.Count)
exit $code
