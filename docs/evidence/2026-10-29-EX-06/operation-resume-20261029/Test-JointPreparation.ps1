param(
    [ValidateSet('All','ExistingManifestCollision','CreatePlan','ExplicitReuse','ReuseIdentity','ManifestSecrets','RealModelRequired','RootUrl','PrivateDirectory','OperationIdentity')]
    [string]$Case = 'All',
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$commonPath = Join-Path $repositoryRoot 'scripts/acceptance/Joint.Common.ps1'
$phase = 'PREPARATION_FAILED'
$checks = New-Object 'System.Collections.Generic.List[string]'
$checksPassed = $false
$evidence = $null
$safeError = $null
function Assert-JointCheck([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw ('ASSERTION_FAILED:' + $Name) }
    $checks.Add($Name)
}
function Assert-JointReject([scriptblock]$Action, [string]$Code, [string]$Name) {
    $caught = $null
    try { & $Action | Out-Null } catch { $caught = $_.Exception.Message }
    Assert-JointCheck ($caught -eq $Code) $Name
    # 拒绝路径也不能回显值；这里只使用无秘密的合成标记。
    Assert-JointCheck (-not $caught -or $caught -notmatch 'synthetic-raw-value') ($Name + ' keeps rejected values private')
}
function New-JointTestManifest {
    $manifest = [ordered]@{
        sessionId='joint-unit-01'; platformTenant='local-v12'; environment='production'
        spaceId='11111111-1111-4111-8111-111111111111'; spaceCode='joint_unit_a'
        releaseId='22222222-2222-4222-8222-222222222222'; replacementReleaseId='33333333-3333-4333-8333-333333333333'
        appCode='joint-unit-binding'; pinAppCode='joint-unit-pin'; explicitAppCode='joint-unit-explicit'; opCode='joint_unit_op'
        readCredentialId='44444444-4444-4444-8444-444444444444'; binderCredentialId='55555555-5555-4555-8555-555555555555'; consumerCredentialId='66666666-6666-4666-8666-666666666666'
        readKeyRef='env:AGENT_SECRET_JOINT_UNIT_READ'; binderKeyRef='env:AGENT_SECRET_JOINT_UNIT_BINDER'; consumerKeyRef='env:AGENT_SECRET_JOINT_UNIT_CONSUMER'
        platformBaseUrl='http://127.0.0.1:18080'; ontologyBaseUrl='http://127.0.0.1:18182'
        revocationFixtures=[ordered]@{
            readCredentialId='77777777-7777-4777-8777-777777777777'; binderCredentialId='88888888-8888-4888-8888-888888888888'; consumerCredentialId='99999999-9999-4999-8999-999999999999'
            readKeyRef='env:AGENT_SECRET_JOINT_UNIT_TEMP_READ'; binderKeyRef='env:AGENT_SECRET_JOINT_UNIT_TEMP_BINDER'; consumerKeyRef='env:AGENT_SECRET_JOINT_UNIT_TEMP_CONSUMER'
        }
        isolationFixtures=[ordered]@{
            spaceId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'; spaceCode='joint_unit_b'; releaseId='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
            endpointId='cccccccc-cccc-4ccc-8ccc-cccccccccccc'; opCode='joint_unit_op'
        }
    }
    return ($manifest | ConvertTo-Json -Depth 8 | ConvertFrom-Json)
}
try {
    if (-not (Test-Path -LiteralPath $commonPath -PathType Leaf)) { throw 'JOINT_HELPER_NOT_DELIVERED' }
    $tokens = $null; $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($commonPath, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw 'JOINT_HELPER_PARSE_FAILED' }
    # 只用AST提取真实公共函数，不执行验收入口或读取真实.env。
    foreach ($name in @('Get-JointValue','Get-JointNames','Assert-JointRootUrl','Assert-JointAcceptancePath','Assert-JointManifestShape','Get-JointPreparationPlan','New-JointPrivateDirectory')) {
        $definitions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
        if ($definitions.Count -ne 1) { throw 'JOINT_HELPER_FUNCTION_NOT_DELIVERED' }
        . ([scriptblock]::Create($definitions[0].Extent.Text))
    }
    $evidence = Assert-JointAcceptancePath $repositoryRoot $EvidenceDirectory
    if (Test-Path -LiteralPath $evidence) { throw 'JOINT_TEST_OUTPUT_EXISTS' }
    New-Item -ItemType Directory -Path $evidence | Out-Null
    $fixtureRoot = Join-Path $evidence 'fixtures'
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    $phase = 'PREPARATION_ASSERTION_FAILED'
    $cases = if ($Case -eq 'All') { @('ExistingManifestCollision','CreatePlan','ExplicitReuse','ReuseIdentity','ManifestSecrets','RealModelRequired','RootUrl','PrivateDirectory','OperationIdentity') } else { @($Case) }
    foreach ($selected in $cases) {
        $output = Join-Path $fixtureRoot $selected
        New-Item -ItemType Directory -Path $output | Out-Null
        $arguments = @{RepositoryRoot=$repositoryRoot;SessionId='joint-unit-01';PlatformTenant='local-v12';Environment='production';OutputDirectory=$output;PlatformBaseUrl='http://127.0.0.1:18080';OntologyBaseUrl='http://127.0.0.1:18182';ModelMode='TestFixture'}
        $manifest = New-JointTestManifest
        $manifestPath = Join-Path $output 'manifest.json'
        if ($selected -in @('ExistingManifestCollision','ExplicitReuse','ReuseIdentity','ManifestSecrets')) {
            [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
        }
        switch ($selected) {
            'ExistingManifestCollision' {
                $before = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
                Assert-JointReject { Get-JointPreparationPlan @arguments } 'JOINT_MANIFEST_EXISTS' 'existing manifest requires explicit reuse'
                Assert-JointCheck ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -eq $before) 'collision preserves exact manifest bytes'
                Assert-JointCheck (@(Get-ChildItem -LiteralPath $output -Force).Count -eq 1) 'collision creates no private directory or extra file'
            }
            'CreatePlan' {
                $plan = Get-JointPreparationPlan @arguments
                Assert-JointCheck ($plan.Mode -eq 'Create' -and $plan.RequiresLiveValidation -and -not $plan.ResourcesVerified) 'create plan cannot claim resources ready'
                Assert-JointCheck (-not (Test-Path -LiteralPath $manifestPath) -and -not (Test-Path -LiteralPath $plan.PrivateDirectory)) 'planning has no write or secret-file side effect'
            }
            'ExplicitReuse' {
                $arguments.ReuseManifestPath = $manifestPath
                $plan = Get-JointPreparationPlan @arguments
                Assert-JointCheck ($plan.Mode -eq 'Reuse' -and $plan.Manifest.releaseId -eq $manifest.releaseId) 'explicit reuse preserves resource mapping'
                Assert-JointCheck ($plan.RequiresLiveValidation -and -not $plan.ResourcesVerified) 'static reuse never claims READY ACTIVE or private refs verified'
            }
            'ReuseIdentity' {
                $arguments.ReuseManifestPath = $manifestPath
                foreach ($field in @('SessionId','PlatformTenant','Environment')) {
                    $saved = $arguments[$field]; $arguments[$field] = 'different-unit-context'
                    Assert-JointReject { Get-JointPreparationPlan @arguments } 'JOINT_MANIFEST_CONTEXT_MISMATCH' ('reuse rejects different ' + $field)
                    $arguments[$field] = $saved
                }
            }
            'ManifestSecrets' {
                $manifest.readKeyRef = 'ontosa_synthetic-raw-value'
                Assert-JointReject { Assert-JointManifestShape $manifest 'joint-unit-01' 'local-v12' 'production' } 'JOINT_MANIFEST_SECRET_REF_INVALID' 'raw key cannot become a public ref'
                $manifest = New-JointTestManifest
                $manifest | Add-Member -NotePropertyName token -NotePropertyValue 'synthetic-raw-value'
                Assert-JointReject { Assert-JointManifestShape $manifest 'joint-unit-01' 'local-v12' 'production' } 'JOINT_MANIFEST_FIELDS_INVALID' 'unlisted secret field cannot enter manifest'
            }
            'RealModelRequired' {
                $arguments.ModelMode = 'Real'
                Assert-JointReject { Get-JointPreparationPlan @arguments } 'JOINT_REAL_MODEL_REQUIRED' 'Real mode cannot choose the first model implicitly'
                $arguments.ModelEntryId = 'explicit-unit-model'
                $plan = Get-JointPreparationPlan @arguments
                Assert-JointCheck ($plan.ModelMode -eq 'Real' -and $plan.ModelEntryId -eq 'explicit-unit-model') 'Real plan retains explicit model entry'
            }
            'RootUrl' {
                foreach ($invalid in @('https://example.invalid','http://127.0.0.1:18182/onto','http://user:synthetic-raw-value@127.0.0.1:18182','http://127.0.0.1:18182/?token=synthetic-raw-value')) {
                    $arguments.OntologyBaseUrl = $invalid
                    Assert-JointReject { Get-JointPreparationPlan @arguments } 'JOINT_ROOT_URL_INVALID' 'root URL excludes remote path and credentials'
                }
            }
            'OperationIdentity' {
                $plan = Get-JointPreparationPlan @arguments
                Assert-JointCheck ($plan.OpCode -cmatch '^joint_[a-f0-9]{24}$') 'new session plan exposes bounded unique operation code'
                Assert-JointCheck ($plan.OpCode -ceq 'joint_7cd41b76f38d0ca927b26060') 'operation code matches fixed UTF8 session digest vector'
                Assert-JointCheck ((Get-JointPreparationPlan @arguments).OpCode -ceq $plan.OpCode) 'same session plan retains operation code'
                $arguments.SessionId = 'joint-unit-02'
                Assert-JointCheck ((Get-JointPreparationPlan @arguments).OpCode -cne $plan.OpCode) 'different session plan separates operation code'
                Assert-JointCheck (-not (Test-Path -LiteralPath $manifestPath) -and -not (Test-Path -LiteralPath $plan.PrivateDirectory)) 'operation planning creates no resource or private file'
                $arguments.SessionId = '非法-session'
                Assert-JointReject { Get-JointPreparationPlan @arguments } 'JOINT_CONTEXT_INVALID' 'operation planning retains invalid session rejection'
                $arguments.SessionId = 'joint-unit-01'
                [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
                $arguments.ReuseManifestPath = $manifestPath
                Assert-JointCheck ((Get-JointPreparationPlan @arguments).OpCode -ceq 'joint_unit_op') 'explicit manifest retains confirmed legacy operation code'
                Assert-JointCheck (@(Get-JointNames ([pscustomobject]@{})).Count -eq 0) 'empty journal operation identity has no property names'
            }
            'PrivateDirectory' {
                $plan = Get-JointPreparationPlan @arguments
                $private = New-JointPrivateDirectory $repositoryRoot $plan.PrivateDirectory
                $acl = Get-Acl -LiteralPath $private
                $current = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
                $allowed = @($current,'S-1-5-18','S-1-5-32-544')
                $rules = @($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
                Assert-JointCheck ($acl.AreAccessRulesProtected -and $rules.Count -gt 0) 'private session directory disables inherited access'
                Assert-JointCheck (@($rules | Where-Object { $_.IsInherited -or $_.IdentityReference.Value -notin $allowed -or $_.AccessControlType -ne 'Allow' }).Count -eq 0) 'private session grants only current user SYSTEM and Administrators'
                Assert-JointCheck ((New-JointPrivateDirectory $repositoryRoot $private) -eq $private) 'private directory can be reused without widening access'
            }
        }
    }
    $checksPassed = $true; $phase = 'PREPARATION_CHECKS_PASSED'
} catch {
    # 只记录阶段和断言名，不能把任意异常对象/原响应序列化到证据。
    $safeError = 'UNEXPECTED_PREPARATION_FAILURE'
    if ($_.Exception.Message -match '^(JOINT_[A-Z_]+|ASSERTION_FAILED:[A-Za-z0-9 /_-]+)$') { $safeError = $_.Exception.Message }
} finally {
    if ($evidence -and (Test-Path -LiteralPath $evidence -PathType Container)) {
        $result = [ordered]@{case=$Case;phase=$phase;checksPassed=$checksPassed;passedChecks=@($checks);safeError=$safeError;businessValidationPerformed=$false;httpCalls=0;businessResourcesCreated=0}
        [IO.File]::WriteAllText((Join-Path $evidence 'preparation-checks.json'), ($result | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    }
}
if (-not $checksPassed) { Write-Error ($phase + ':' + $safeError) -ErrorAction Continue; if ($phase -eq 'PREPARATION_FAILED') { exit 2 }; exit 1 }
Write-Output ('PREPARATION_CHECKS_PASSED count=' + $checks.Count + '; businessValidationPerformed=false')
exit 0
