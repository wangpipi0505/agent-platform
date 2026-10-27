$ErrorActionPreference = 'Stop'
# Seed one runnable chat example through the public admin API.
# It is intentionally idempotent: an existing sample is reported, never overwritten.
$agentRoot = Split-Path -Parent $PSScriptRoot
$base = if ($env:AGENT_BASE_URL) { $env:AGENT_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:18080' }
$sampleCode = 'sample_triage'

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body)
    $headers = @{ Authorization = "Bearer $script:token" }
    $params = @{ Method = $Method; Uri = "$base$Path"; Headers = $headers; ContentType = 'application/json; charset=utf-8' }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $params.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }
    return Invoke-RestMethod @params
}

$session = Invoke-RestMethod -Method Post -Uri "$base/auth/dev-token"
$script:token = $session.data.accessToken
$apps = (Invoke-Api 'GET' '/admin/apps' $null).data
$existing = @($apps | Where-Object { $_.code -eq $sampleCode })
if ($existing.Count -gt 0) {
    Write-Output "示例已存在：$sampleCode（未覆盖）"
    exit 0
}

$entries = (Invoke-Api 'GET' '/admin/model-entries' $null).data
$entry = @($entries | Where-Object {
        $_.status -eq 'ENABLED' -and $_.provider.status -eq 'ENABLED' -and @($_.capabilities) -contains 'chat'
    } | Select-Object -First 1)
if ($entry.Count -eq 0) {
    throw '没有可用的 chat 模型条目；先在“模型条目”启用一个供应商正常的条目'
}

$created = Invoke-Api 'POST' '/admin/apps' @{
    code        = $sampleCode
    name        = '示例：告警分诊助手'
    type        = 'CHAT'
    description = '演示纯对话智能体：澄清告警、给出处置建议，不调用外部工具。'
    domain      = '运维'
    bizCategory = '故障处置'
    tags        = @('示例', '对话')
}
$rev = $created.data.draftRev
$config = @{
    systemPrompt = '你是运维告警分诊助手。先复述告警中的服务、时间和影响面；信息不足时只问一个最关键的问题；给出最多三条可执行处置建议，并明确不确定之处。不要编造监控数据。'
    modelBinding = @{ modelEntryId = $entry[0].id }
    inputs       = @(@{ name = 'message'; type = 'STRING'; required = $true })
    output       = @{ maxTokens = 512 }
    params       = @{ temperature = 0.2 }
    tools        = @()
    guard        = @{ roles = @('EDITOR'); attributes = @{} }
}
$saved = Invoke-Api 'PUT' "/admin/apps/$sampleCode/config" @{ draftRev = $rev; config = $config }
Write-Output "示例已创建：$sampleCode（draftRev=$($saved.data.draftRev)，模型条目=$($entry[0].id)）"
Write-Output "在管理台“智能体管理”打开它即可调试；发布仍需预检与评测。"
