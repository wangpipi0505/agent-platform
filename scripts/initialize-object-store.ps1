$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $agentRoot
Get-Content -LiteralPath (Join-Path $agentRoot '.env')|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:JAVA_HOME=if($env:AGENT_JAVA_HOME){$env:AGENT_JAVA_HOME}else{'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'}
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
# 仅安装公共jar以供Maven解析依赖，不重打包/停止正在运行的任何业务服务。
& mvn.cmd -B -ntp -pl admin-common -am install -DskipTests "-Dmaven.repo.local=$agentRoot/.tools/m2"
if($LASTEXITCODE -ne 0){throw '公共SDK构建失败'}
& mvn.cmd -B -ntp -pl skill-registry dependency:build-classpath "-Dmaven.repo.local=$agentRoot/.tools/m2" "-Dmdep.outputFile=$agentRoot/.local/object-store-classpath.txt"
if($LASTEXITCODE -ne 0){throw '对象存储SDK依赖解析失败'}
$agentClasspath=(Get-Content -LiteralPath '.local/object-store-classpath.txt' -Raw).Trim().Replace($agentRoot,'V:').Replace('\','/')
$agentArguments='-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'+[Environment]::NewLine+'-cp'+[Environment]::NewLine+'"'+$agentClasspath+'"'+[Environment]::NewLine+'scripts/InitializeObjectStore.java'
[IO.File]::WriteAllText((Join-Path $agentRoot '.local/initialize-object-store.args'),$agentArguments,[Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME/bin/java.exe" '@.local/initialize-object-store.args'
if($LASTEXITCODE -ne 0){throw '对象存储桶初始化失败'}
