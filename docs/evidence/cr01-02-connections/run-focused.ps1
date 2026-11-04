param([string]$Phase = 'cr01-red', [string[]]$Tests = @('cn.agentplatform.skill.application.ApprovedConnectionGateTest'), [string[]]$Sources = @(), [switch]$CompileOnly, [switch]$FreshDependencies, [switch]$ConnectionFixtures)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = Join-Path 'D:/CodexPrep/algorithm-reorg/upgrade-cr01-02' $Phase
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
[xml]$report = Get-Content -LiteralPath (Join-Path $workspace 'skill-registry/target/surefire-reports/TEST-cn.agentplatform.skill.application.ApprovedConnectionGateTest.xml') -Raw
$jdk = ($report.testsuite.properties.property | Where-Object name -eq 'java.home').value
$testCp = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if ($Phase.StartsWith('cr02')) {
 [xml]$engineReport = Get-Content -LiteralPath (Join-Path $workspace 'engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.application.RunConfigReferenceTest.xml') -Raw
 $testCp += ';' + ($engineReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
}
$dependencySnapshot = 'D:/CodexPrep/algorithm-reorg/upgrade-cr01-02/dependencies'
if ($FreshDependencies) { $dependencySnapshot = Join-Path $evidence 'dependencies' }
foreach ($module in @('admin-common', 'skill-registry', 'app-management', 'engine-runtime')) {
 $classes = Join-Path $workspace "$module/target/classes"
 $isolatedClasses = Join-Path $dependencySnapshot $module
 if (!(Test-Path -LiteralPath $isolatedClasses)) {
  New-Item -ItemType Directory -Path $isolatedClasses -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $classes 'cn') -Destination $isolatedClasses -Recurse
 }
 $testCp = $testCp.Replace($classes, $isolatedClasses)
 if ($module -in @('app-management', 'engine-runtime')) { $testCp += ";$isolatedClasses" }
}
$engineJar = $testCp.Split(';') | Where-Object { $_ -match 'junit-platform-engine-[0-9].*\.jar$' } | Select-Object -First 1
$platformVersion = ([IO.Path]::GetFileNameWithoutExtension($engineJar)) -replace '^junit-platform-engine-', ''
$launcher = "C:/Users/Administrator/.m2/repository/org/junit/platform/junit-platform-launcher/$platformVersion/junit-platform-launcher-$platformVersion.jar"
$testCp = ($testCp.Split(';') | Select-Object -Unique) -join ';'
$cp = "$evidence;$testCp;$launcher"
$runner = @'
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
public class FocusedJUnit {
 public static void main(String[] args) {
  var request = LauncherDiscoveryRequestBuilder.request();
  for (var test : args) request.selectors(test.contains("#")
          ? DiscoverySelectors.selectMethod(test) : DiscoverySelectors.selectClass(test));
  var listener = new SummaryGeneratingListener();
  LauncherFactory.create().execute(request.build(), listener);
  var out = new java.io.PrintWriter(System.out, true);
  listener.getSummary().printTo(out);
  listener.getSummary().printFailuresTo(out);
  if (listener.getSummary().getTestsFoundCount() == 0 || listener.getSummary().getTotalFailureCount() > 0) System.exit(1);
 }
}
'@
[IO.File]::WriteAllText((Join-Path $evidence 'FocusedJUnit.java'), $runner)
if (!$Sources.Count) {
 if ($Phase.StartsWith('cr02')) {
  $Sources = @(
   'skill-registry/src/main/java/cn/agentplatform/skill/application/ApprovedConnectionResolver.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/infra/ApprovedConnectionExecution.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/infra/McpAdapter.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/infra/BoundedMcpSseTransport.java',
   'skill-registry/src/main/java/cn/agentplatform/skill/infra/ConnectionResolutionReceipts.java',
   'app-management/src/main/java/cn/agentplatform/app/application/ApprovedConnections.java',
   'app-management/src/main/java/cn/agentplatform/app/application/ConnectionGrants.java',
   'app-management/src/main/java/cn/agentplatform/app/application/IntegrationConfigReleases.java',
   'app-management/src/main/java/cn/agentplatform/app/api/InternalConnectionProfilesController.java',
   'engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java',
   'engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java',
   'skill-registry/src/test/java/cn/agentplatform/skill/application/ApprovedConnectionGateTest.java',
   'skill-registry/src/test/java/cn/agentplatform/skill/application/ApprovedConnectionDispatchTest.java',
   'docs/evidence/cr01-02-connections/checks/ApprovedConnectionConfigurationTest.java')
 } else {
  $Sources = @('skill-registry/src/main/java/cn/agentplatform/skill/application/ApprovedConnectionResolver.java', 'skill-registry/src/test/java/cn/agentplatform/skill/application/ApprovedConnectionGateTest.java')
 }
}
$paths = @($Sources | ForEach-Object { Join-Path $workspace $_ })
$sourcePath = @('admin-common', 'app-management', 'engine-runtime', 'skill-registry') | ForEach-Object { Join-Path $workspace "$_/src/main/java" }
$compilerArguments = @('-encoding', 'UTF-8', '-cp', $cp, '-sourcepath', ($sourcePath -join ';'), '-d', $evidence) + $paths + (Join-Path $evidence 'FocusedJUnit.java')
$compilerArgumentFile = Join-Path $evidence 'compile.args'
[IO.File]::WriteAllLines($compilerArgumentFile, @($compilerArguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' }))
& (Join-Path $jdk 'bin/javac.exe') "@$compilerArgumentFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($CompileOnly) { exit 0 }
$fixtureNames = @('AGENT_SECRET_AP_CR02_BOUND','AGENT_SECRET_AP_CR02_LEGACY','AGENT_SECRET_AP_CR02_MISSING','AGENT_SECRET_BINDINGS')
$originalEnvironment = @{}
foreach ($name in $fixtureNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
 if ($ConnectionFixtures) {
  # 仅当前定向进程使用合成凭据；缺失引用也绑定测试租户，确保真测“不可解析”而非碰巧越权。
  [Environment]::SetEnvironmentVariable('AGENT_SECRET_AP_CR02_BOUND','bound-secret','Process')
  [Environment]::SetEnvironmentVariable('AGENT_SECRET_AP_CR02_LEGACY','legacy-secret','Process')
  [Environment]::SetEnvironmentVariable('AGENT_SECRET_AP_CR02_MISSING',$null,'Process')
  [Environment]::SetEnvironmentVariable('AGENT_SECRET_BINDINGS','{"AGENT_SECRET_AP_CR02_BOUND":"dispatch-test","AGENT_SECRET_AP_CR02_LEGACY":"dispatch-test","AGENT_SECRET_AP_CR02_MISSING":"dispatch-test"}','Process')
 }
 & (Join-Path $jdk 'bin/java.exe') '-Djdk.net.unixdomain.tmpdir=D:/CodexPrep/algorithm-reorg/upgrade-cr01-02/no-unix-sockets' -cp $cp FocusedJUnit @Tests 2>&1 | Tee-Object -FilePath (Join-Path $PSScriptRoot "$Phase.log")
 $result = $LASTEXITCODE
} finally {
 foreach ($name in $fixtureNames) { [Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process') }
}
exit $result
