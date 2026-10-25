$ErrorActionPreference = 'Stop'
$workspace = 'G:\207\项目\算法重组工具\代码\agent-platform'
$review = Join-Path $workspace 'docs\evidence\review-iam-stage-3'
$frozen = Join-Path $workspace 'docs\evidence\iam-stage-3\frozen-target'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin'

function Check-FrozenInputs {
    $bad = @()
    $sourceMap = Get-Content -LiteralPath (Join-Path $workspace 'docs\evidence\iam-stage-3\source-after.json') -Raw | ConvertFrom-Json
    foreach ($p in $sourceMap.PSObject.Properties) {
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $workspace $p.Name)).Hash.ToLowerInvariant()
        if ($actual -ne $p.Value) { $bad += "source:$($p.Name)" }
    }
    $targetMap = Get-Content -LiteralPath (Join-Path $workspace 'docs\evidence\iam-stage-3\target-after.json') -Raw | ConvertFrom-Json
    foreach ($p in $targetMap.PSObject.Properties) {
        foreach ($base in @((Join-Path $workspace 'admin-common\target'), $frozen)) {
            $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $base $p.Name)).Hash.ToLowerInvariant()
            if ($actual -ne $p.Value) { $bad += "target:$base/$($p.Name)" }
        }
    }
    if ($bad.Count -ne 0) { throw ($bad -join "`n") }
    return @{
        source_files = @($sourceMap.PSObject.Properties).Count
        target_files_each = @($targetMap.PSObject.Properties).Count
        live_and_frozen_targets_unchanged = $true
    }
}

$before = Check-FrozenInputs
[xml]$xml = Get-Content -LiteralPath (Join-Path $workspace 'docs\evidence\iam-stage-3\frozen-full-xml\TEST-cn.agentplatform.common.authority.ResponseAuthorityTest.xml') -Raw
$classpath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$classpath = $classpath.Replace((Join-Path $workspace 'admin-common\target'), $frozen)
$classpath = ($classpath -split ';' | Where-Object { $_.Length -gt 0 }) -join ';'
foreach ($entry in ($classpath -split ';' | Where-Object { $_.Length -gt 0 })) {
    $absolute = [System.IO.Path]::GetFullPath($entry)
    if (-not $absolute.StartsWith($workspace + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Classpath escaped the authorized workspace"
    }
    if (-not (Test-Path -LiteralPath $absolute)) { throw "Missing frozen dependency" }
}
$classes = Join-Path $review 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath | Set-Content -LiteralPath (Join-Path $review 'frozen-classpath.txt') -Encoding utf8
& (Join-Path $java 'javac.exe') -encoding UTF-8 -cp $classpath -d $classes (Join-Path $review 'Task3ReviewProbe.java') 2>&1 |
    Tee-Object -FilePath (Join-Path $review 'compile.log')
$compileExit = $LASTEXITCODE
if ($compileExit -ne 0) { throw "Probe compile failed: $compileExit" }
& (Join-Path $java 'java.exe') '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' -cp ($classes + ';' + $classpath) cn.agentplatform.common.authority.Task3ReviewProbe 2>&1 |
    Tee-Object -FilePath (Join-Path $review 'probe.log')
$runExit = $LASTEXITCODE
$after = Check-FrozenInputs
@{before=$before;after=$after;compile_exit=$compileExit;run_exit=$runExit;run_count=1} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $review 'probe-audit.json') -Encoding utf8
if ($runExit -ne 0) { throw "Probe failed: $runExit" }
