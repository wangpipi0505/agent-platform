$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$evidenceRoot=$PSScriptRoot
foreach($entry in @(
 @{Path=(Join-Path $repo 'scripts/start-backend.ps1');Name='Start-AgentServiceProcess'},
 @{Path=(Join-Path $PSScriptRoot 'run-live.ps1');Name='Invoke-PrivateProbe'})){
 $tokens=$null;$errors=$null
 $ast=[Management.Automation.Language.Parser]::ParseFile($entry.Path,[ref]$tokens,[ref]$errors)
 if($errors.Count){throw '实际采集函数源码存在语法错误'}
 $name=$entry.Name
 $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
 . ([scriptblock]::Create($definition.Extent.Text))
}
$private=Join-Path $repo ('.local/ex01-reporter-probe-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $private | Out-Null
$java=Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
$facts=New-Object 'System.Collections.Generic.List[object]'
$plan=@{Overrides=@{'skill-registry'=@{EX01_REPORTER_SYNTHETIC='synthetic-marker'}}}
$failure=$null
try {
 Invoke-PrivateProbe 'ordinary-powershell-child' 'skill-registry' $plan @('-NoProfile','-NonInteractive','-Command','Write-Output EX01_SYNTHETIC_PASS; exit 0') 'EX01_SYNTHETIC_PASS'
} catch {$failure=[pscustomobject]@{type=$_.Exception.GetType().FullName;stack=$_.ScriptStackTrace}}
try {
 $record=[pscustomobject]@{status=$(if($failure){'FAIL'}else{'PASS'});powershell=$PSVersionTable.PSVersion.ToString();
  scope='实际生产环境包装函数与实际采集函数，只将可执行文件替换为普通powershell子进程；无Java/PG/HTTP、无真实.env或凭据';
  facts=$facts.ToArray();failure=$failure}
 $record | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'live-reporter-harmless-check.json') -Encoding UTF8
 Write-Output ('EX01_REPORTER_CHECK='+$record.status+'; ordinaryChildOnly=true; facts='+$facts.Count)
 if($failure){throw '实际采集函数的无业务子进程检查失败'}
} finally {
 $full=[IO.Path]::GetFullPath($private)
 $prefix=[IO.Path]::GetFullPath((Join-Path $repo '.local'))+[IO.Path]::DirectorySeparatorChar
 if(-not $full.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $full) -notlike 'ex01-reporter-probe-*'){throw '临时清理目录越界'}
 Remove-Item -LiteralPath $full -Recurse -Force
}
