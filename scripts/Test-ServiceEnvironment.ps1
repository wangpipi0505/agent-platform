param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$agentRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'start-backend.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'start-backend.ps1 has syntax errors' }
# 只提取生产函数；测试不能执行启动器或读取真实.env。
foreach ($name in @('Read-AgentEnvironmentFile', 'Get-AgentServiceEnvironmentPlan', 'Start-AgentServiceProcess')) {
    $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if (-not $definition) { throw "Missing production function: $name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}
$checks = New-Object 'System.Collections.Generic.List[string]'
function Assert-AgentCheck([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAILED: $Name" }
    $checks.Add($Name)
}
function Assert-AgentRejected([scriptblock]$Action, [string]$Name) {
    $rejected = $false
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -match 'synthetic-(app-marker|engine-marker|sensitive-value)') { throw 'Rejected configuration exposed a synthetic secret value' }
        $rejected = $true
    }
    Assert-AgentCheck $rejected $Name
}
$fixtureRoot = Join-Path $agentRoot ('.local/ex01-service-environment-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$fixtureNames = @('AGENT_SECRET_ONTO_APP_BINDER_KEY', 'AGENT_SECRET_ONTO_ENGINE_READ_KEY', 'AGENT_SECRET_BINDINGS', 'AGENT_ONTOLOGY_API_KEY_REF', 'EX01_SHARED', 'EX01_ONLY_APP', 'AGENT_SECURITY_SERVICEID')
$before = @{}
foreach ($name in $fixtureNames) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$serviceIds = @{'app-management'='app'; 'engine-runtime'='engine'; 'skill-registry'='skill'}
$utf8 = New-Object Text.UTF8Encoding($true)
function Write-AgentFixture([string]$Name, [string[]]$Lines) {
    $path = Join-Path $fixtureRoot $Name
    [IO.File]::WriteAllLines($path, $Lines, $utf8)
    return $path
}
try {
    $appFile = Write-AgentFixture 'app.env' @(
        'AGENT_ONTOLOGY_API_KEY_REF=env:AGENT_SECRET_ONTO_APP_BINDER_KEY',
        'AGENT_SECRET_BINDINGS={"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12"}',
        'EX01_SHARED=app', 'EX01_ONLY_APP=app-only')
    $engineFile = Write-AgentFixture 'engine.env' @(
        'AGENT_ONTOLOGY_API_KEY_REF=env:AGENT_SECRET_ONTO_ENGINE_READ_KEY',
        'AGENT_SECRET_BINDINGS={"AGENT_SECRET_ONTO_ENGINE_READ_KEY":"local-v12"}',
        'EX01_SHARED=engine')
    # 仅用无秘密标记，验证父进程原本已有两把私有密钥时仍必须隔离。
    [Environment]::SetEnvironmentVariable('AGENT_SECRET_ONTO_APP_BINDER_KEY', 'synthetic-app-marker', 'Process')
    [Environment]::SetEnvironmentVariable('AGENT_SECRET_ONTO_ENGINE_READ_KEY', 'synthetic-engine-marker', 'Process')
    [Environment]::SetEnvironmentVariable('AGENT_SECRET_BINDINGS', '{"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12","AGENT_SECRET_ONTO_ENGINE_READ_KEY":"local-v12"}', 'Process')
    [Environment]::SetEnvironmentVariable('AGENT_ONTOLOGY_API_KEY_REF', 'env:AGENT_SECRET_ONTO_ENGINE_READ_KEY', 'Process')
    [Environment]::SetEnvironmentVariable('EX01_SHARED', 'parent', 'Process')
    [Environment]::SetEnvironmentVariable('EX01_ONLY_APP', $null, 'Process')
    $files = @{'app-management'=$appFile; 'engine-runtime'=$engineFile}
    $plan = Get-AgentServiceEnvironmentPlan $agentRoot @('app-management','engine-runtime','skill-registry') $files $serviceIds @{'EX01_SHARED'='global';'AGENT_ONTOLOGY_API_KEY_REF'='env:AGENT_SECRET_ONTO_ENGINE_READ_KEY'}
    $probe = Write-AgentFixture 'probe.ps1' @(
        'param([string]$OutputPath)',
        '$names=@("AGENT_SECRET_ONTO_APP_BINDER_KEY","AGENT_SECRET_ONTO_ENGINE_READ_KEY","AGENT_SECRET_BINDINGS","AGENT_ONTOLOGY_API_KEY_REF","EX01_SHARED","EX01_ONLY_APP","AGENT_SECURITY_SERVICEID")',
        '$result=@{}; foreach($name in $names){$result[$name]=[Environment]::GetEnvironmentVariable($name,"Process")}',
        '$result | ConvertTo-Json -Compress | Set-Content -LiteralPath $OutputPath -Encoding UTF8')
    $powershell = Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
    foreach ($service in @('app-management','engine-runtime','skill-registry')) {
        $output = Join-Path $fixtureRoot "$service.json"
        $parameters = @{FilePath=$powershell; ArgumentList=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$probe+'"'),'-OutputPath',('"'+$output+'"')); WorkingDirectory=$fixtureRoot; WindowStyle='Hidden'; Wait=$true; PassThru=$true}
        $process = Start-AgentServiceProcess $plan.Overrides[$service] $parameters
        Assert-AgentCheck ($process.ExitCode -eq 0) "$service harmless child exits zero"
        $child = Get-Content -LiteralPath $output -Raw | ConvertFrom-Json
        Assert-AgentCheck ($child.AGENT_SECURITY_SERVICEID -eq $serviceIds[$service]) "$service identity is scoped"
        if ($service -eq 'app-management') {
            Assert-AgentCheck ($child.AGENT_ONTOLOGY_API_KEY_REF -eq 'env:AGENT_SECRET_ONTO_APP_BINDER_KEY' -and $child.AGENT_SECRET_ONTO_APP_BINDER_KEY -eq 'synthetic-app-marker' -and -not $child.AGENT_SECRET_ONTO_ENGINE_READ_KEY) 'app receives binder key and never read key'
            Assert-AgentCheck ($child.EX01_SHARED -eq 'app' -and $child.EX01_ONLY_APP -eq 'app-only') 'app overrides global configuration'
            Assert-AgentCheck ($child.AGENT_SECRET_BINDINGS -notmatch 'ENGINE_READ') 'app bindings exclude engine key'
        } elseif ($service -eq 'engine-runtime') {
            Assert-AgentCheck ($child.AGENT_ONTOLOGY_API_KEY_REF -eq 'env:AGENT_SECRET_ONTO_ENGINE_READ_KEY' -and $child.AGENT_SECRET_ONTO_ENGINE_READ_KEY -eq 'synthetic-engine-marker' -and -not $child.AGENT_SECRET_ONTO_APP_BINDER_KEY) 'engine receives read key and never binder key'
            Assert-AgentCheck ($child.EX01_SHARED -eq 'engine' -and -not $child.EX01_ONLY_APP) 'engine never inherits app-only override'
            Assert-AgentCheck ($child.AGENT_SECRET_BINDINGS -notmatch 'APP_BINDER') 'engine bindings exclude app key'
        } else {
            Assert-AgentCheck (-not $child.AGENT_SECRET_ONTO_APP_BINDER_KEY -and -not $child.AGENT_SECRET_ONTO_ENGINE_READ_KEY) 'unassigned service receives neither private key'
            Assert-AgentCheck (-not $child.AGENT_ONTOLOGY_API_KEY_REF) 'unassigned service receives no inherited private reference'
            Assert-AgentCheck (-not $child.EX01_ONLY_APP) 'unassigned service never inherits app-only override'
        }
        Assert-AgentCheck ([Environment]::GetEnvironmentVariable('EX01_SHARED','Process') -eq 'parent' -and $null -eq [Environment]::GetEnvironmentVariable('EX01_ONLY_APP','Process')) "$service restores existing and absent parent variables"
        Assert-AgentCheck ([Environment]::GetEnvironmentVariable('AGENT_SECRET_ONTO_APP_BINDER_KEY','Process') -eq 'synthetic-app-marker' -and [Environment]::GetEnvironmentVariable('AGENT_SECRET_ONTO_ENGINE_READ_KEY','Process') -eq 'synthetic-engine-marker') "$service restores both original parent keys"
    }
    Assert-AgentRejected { Start-AgentServiceProcess $plan.Overrides['app-management'] @{FilePath=(Join-Path $fixtureRoot 'absent.exe'); PassThru=$true} } 'child start failure is reported'
    Assert-AgentCheck ([Environment]::GetEnvironmentVariable('EX01_SHARED','Process') -eq 'parent' -and $null -eq [Environment]::GetEnvironmentVariable('EX01_ONLY_APP','Process') -and [Environment]::GetEnvironmentVariable('AGENT_SECRET_ONTO_ENGINE_READ_KEY','Process') -eq 'synthetic-engine-marker') 'finally restores parent after child start failure'
    $legacy = Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{} $serviceIds @{}
    Assert-AgentCheck ($legacy.Overrides['app-management'].Count -eq 1 -and $legacy.Overrides['app-management']['AGENT_SECURITY_SERVICEID'] -eq 'app') 'no service files preserves legacy inheritance'
    $boundOnlyFile = Write-AgentFixture 'bound-only.env' @('AGENT_SECRET_BINDINGS={"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12"}')
    $boundOnly = Get-AgentServiceEnvironmentPlan $agentRoot @('app-management','skill-registry') @{'app-management'=$boundOnlyFile} $serviceIds @{}
    Assert-AgentCheck ($boundOnly.Overrides['app-management']['AGENT_SECRET_ONTO_APP_BINDER_KEY'] -eq 'synthetic-app-marker' -and $null -eq $boundOnly.Overrides['skill-registry']['AGENT_SECRET_ONTO_APP_BINDER_KEY']) 'binding-only ownership also isolates parent private keys'
    $definedOnlyFile = Write-AgentFixture 'defined-only.env' @('AGENT_SECRET_ONTO_APP_BINDER_KEY=synthetic-sensitive-value')
    $definedOnly = Get-AgentServiceEnvironmentPlan $agentRoot @('app-management','skill-registry') @{'app-management'=$definedOnlyFile} $serviceIds @{}
    Assert-AgentCheck ($definedOnly.Overrides['app-management']['AGENT_SECRET_ONTO_APP_BINDER_KEY'] -eq 'synthetic-sensitive-value' -and $null -eq $definedOnly.Overrides['skill-registry']['AGENT_SECRET_ONTO_APP_BINDER_KEY']) 'defined-only ownership also isolates private keys'
    $globalFile = Write-AgentFixture 'global.env' @('EX01_SHARED=global','ignored legacy line','# comment')
    $global = Read-AgentEnvironmentFile $globalFile
    Assert-AgentCheck ($global.EX01_SHARED -eq 'global' -and $global.Count -eq 1) 'legacy dotenv remains permissive'
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('unknown') @{} $serviceIds @{} } 'unknown requested service rejected'
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{'unknown'=$appFile} $serviceIds @{} } 'unknown service-file key rejected'
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{'app-management'='.local/app.env'} $serviceIds @{} } 'relative service path rejected'
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{'app-management'=$source} $serviceIds @{} } 'tracked directory service path rejected'
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $fixtureRoot @('app-management') @{'app-management'=$source} $serviceIds @{} } 'outside-root service path rejected'
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{'app-management'=(Join-Path $fixtureRoot 'missing.env')} $serviceIds @{} } 'missing service path rejected'
    foreach ($case in @(
        @{Name='malformed dotenv'; Lines=@('synthetic-sensitive-value')},
        @{Name='invalid dotenv key'; Lines=@('BAD KEY=value')},
        @{Name='duplicate dotenv key'; Lines=@('EX01_SHARED=one','EX01_SHARED=two')},
        @{Name='invalid secret reference'; Lines=@('AGENT_ONTOLOGY_API_KEY_REF=env:ONTO_APP_BINDER_KEY')},
        @{Name='malformed tenant bindings'; Lines=@('AGENT_SECRET_BINDINGS=not-json')},
        @{Name='invalid tenant binding'; Lines=@('AGENT_SECRET_BINDINGS={"AGENT_SECRET_ONTO_APP_BINDER_KEY":""}')},
        @{Name='unbound secret reference'; Lines=@('AGENT_ONTOLOGY_API_KEY_REF=env:AGENT_SECRET_UNBOUND')}
    )) {
        $bad = Write-AgentFixture 'bad.env' $case.Lines
        Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{'app-management'=$bad} $serviceIds @{} } "$($case.Name) rejected"
    }
    Assert-AgentRejected { Get-AgentServiceEnvironmentPlan $agentRoot @('app-management') @{'engine-runtime'=$bad} $serviceIds @{} } 'invalid file for unselected service rejected'
    $calls = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Start-AgentServiceProcess'}, $true))
    Assert-AgentCheck ($calls.Count -eq 1 -and $calls[0].Extent.Text -match '\$agentEnvironmentPlan\.Overrides\[\$agentService\]') 'production launch invokes the tested isolation function'
    $preflight = $ast.Extent.Text.IndexOf('$agentEnvironmentPlan=Get-AgentServiceEnvironmentPlan')
    $database = $ast.Extent.Text.IndexOf("& (Join-Path `$PSScriptRoot 'start-database.ps1')")
    Assert-AgentCheck ($preflight -ge 0 -and $database -gt $preflight) 'all service input is validated before database/build/start'
    $keyLog = $ast.Find({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Write-Output' -and $node.Extent.Text -match '配置覆盖键'}, $true)
    Assert-AgentCheck ($keyLog -and $keyLog.Extent.Text -match '\.Keys' -and $keyLog.Extent.Text -notmatch '\.Values') 'configuration log enumerates keys only'
    $agentEnvironmentPlan = $plan
    $agentService = 'app-management'
    $log = & ([scriptblock]::Create($keyLog.Extent.Text))
    Assert-AgentCheck ($log -match 'AGENT_ONTOLOGY_API_KEY_REF' -and $log -notmatch 'synthetic-|local-v12|env:AGENT_SECRET_') 'actual production configuration log contains no values'
    # 用生产finally语句验证全局覆盖恢复；不执行其try中的数据库或真实.env读取。
    $outerTry = $ast.Find({param($node) $node -is [Management.Automation.Language.TryStatementAst] -and $node.Finally -and $node.Finally.Extent.Text -match '\$agentOriginalEnvironment'}, $true)
    $agentOriginalEnvironment = @{}
    foreach ($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()) { $agentOriginalEnvironment[$entry.Key] = $entry.Value }
    [Environment]::SetEnvironmentVariable('EX01_SHARED', 'synthetic-global-change', 'Process')
    [Environment]::SetEnvironmentVariable('EX01_ONLY_APP', 'synthetic-new-global', 'Process')
    . ([scriptblock]::Create(($outerTry.Finally.Statements | ForEach-Object { $_.Extent.Text }) -join [Environment]::NewLine))
    Assert-AgentCheck ([Environment]::GetEnvironmentVariable('EX01_SHARED','Process') -eq 'parent' -and $null -eq [Environment]::GetEnvironmentVariable('EX01_ONLY_APP','Process')) 'production outer finally restores global changes and removes new variables'
    $result = [pscustomobject]@{status='PASS'; powershell=$PSVersionTable.PSVersion.ToString(); checks=$checks.Count; names=@($checks); fixture='ignored .local directory; synthetic markers only'; actualServicesStarted=$false; realDotEnvRead=$false}
    if ($EvidenceDirectory) {
        if (-not (Test-Path -LiteralPath $EvidenceDirectory -PathType Container)) { throw 'Evidence directory must already exist' }
        $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'powershell-isolation-results.json') -Encoding UTF8
    }
    $result | ConvertTo-Json -Depth 5
} finally {
    foreach ($name in $fixtureNames) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
    # 递归清理前校验绝对路径；只能删除本次创建的忽略夹具目录。
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $allowedPrefix = [IO.Path]::GetFullPath((Join-Path $agentRoot '.local')) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedFixture.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolvedFixture) -like 'ex01-service-environment-*') {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    } else { throw 'Fixture cleanup path escaped the allowed .local directory' }
}
