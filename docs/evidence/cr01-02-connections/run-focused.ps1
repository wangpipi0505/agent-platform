param([string]$Phase = 'cr01-red', [string[]]$Tests = @('cn.agentplatform.skill.application.ApprovedConnectionGateTest'), [string[]]$Sources = @())
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = Join-Path 'D:/CodexPrep/algorithm-reorg/upgrade-cr01-02' $Phase
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
[xml]$report = Get-Content -LiteralPath (Join-Path $workspace 'skill-registry/target/surefire-reports/TEST-cn.agentplatform.skill.application.ApprovedConnectionGateTest.xml') -Raw
$jdk = ($report.testsuite.properties.property | Where-Object name -eq 'java.home').value
$testCp = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$engineJar = $testCp.Split(';') | Where-Object { $_ -match 'junit-platform-engine-[0-9].*\.jar$' } | Select-Object -First 1
$platformVersion = ([IO.Path]::GetFileNameWithoutExtension($engineJar)) -replace '^junit-platform-engine-', ''
$launcher = "C:/Users/Administrator/.m2/repository/org/junit/platform/junit-platform-launcher/$platformVersion/junit-platform-launcher-$platformVersion.jar"
$cp = "$evidence;$testCp;$launcher"
$runner = @'
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
public class FocusedJUnit {
 public static void main(String[] args) {
  var request = LauncherDiscoveryRequestBuilder.request();
  for (var test : args) request.selectors(DiscoverySelectors.selectClass(test));
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
 $Sources = @('skill-registry/src/main/java/cn/agentplatform/skill/application/ApprovedConnectionResolver.java', 'skill-registry/src/test/java/cn/agentplatform/skill/application/ApprovedConnectionGateTest.java')
}
$paths = @($Sources | ForEach-Object { Join-Path $workspace $_ })
& (Join-Path $jdk 'bin/javac.exe') -encoding UTF-8 -cp $cp -d $evidence @paths (Join-Path $evidence 'FocusedJUnit.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $jdk 'bin/java.exe') -cp $cp FocusedJUnit @Tests 2>&1 | Tee-Object -FilePath (Join-Path $PSScriptRoot "$Phase.log")
exit $LASTEXITCODE
