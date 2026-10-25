$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolDir = Join-Path $projectRoot '.tools/deployment'
New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
$headers = @{ 'User-Agent' = 'agent-platform-independent-deployment' }
$base = 'https://github.com/yannh/kubeconform/releases/download/v0.7.0'
$checksums = Invoke-RestMethod -Uri "$base/CHECKSUMS" -Headers $headers
$line = ($checksums -split "`n" | Where-Object { $_ -match '\s+kubeconform-windows-amd64.zip\s*$' })
if (@($line).Count -ne 1) { throw 'Official kubeconform checksum missing or ambiguous.' }
$expected = ($line.Trim() -split '\s+')[0]
if ($expected -ne '9cb75551d81c909c2241ab383ced2be68363b5bfb15fd989badcc5a63bea5d7e') { throw 'Pinned kubeconform checksum differs from official release metadata.' }
$zip = Join-Path $toolDir 'kubeconform-windows-amd64-0.7.0.zip'
Invoke-WebRequest -Uri "$base/kubeconform-windows-amd64.zip" -Headers $headers -OutFile $zip
if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'kubeconform SHA256 mismatch.' }
Expand-Archive -LiteralPath $zip -DestinationPath $toolDir -Force
$agentUrl = 'https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.20.1/opentelemetry-javaagent.jar'
$agentHash = 'd94e9fc648cab37e540571352937acc06b8014182ddbd2d93a6c08641c84e48e'
$agentFile = Join-Path $toolDir 'opentelemetry-javaagent-2.20.1.jar'
Invoke-WebRequest -Uri $agentUrl -Headers $headers -OutFile $agentFile
if ((Get-FileHash -LiteralPath $agentFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $agentHash) { throw 'Javaagent SHA256 mismatch.' }
$schemaUrl = 'https://raw.githubusercontent.com/kubernetes/kubernetes/v1.33.0/api/openapi-spec/swagger.json'
$schemaHash = '2af5abf98554af1599e516f31f7f1698085e6faa12b816401f569c097b9f7ef6'
$schemaFile = Join-Path $toolDir 'kubernetes-v1.33.0-swagger.json'
Invoke-WebRequest -Uri $schemaUrl -Headers $headers -OutFile $schemaFile
if ((Get-FileHash -LiteralPath $schemaFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $schemaHash) { throw 'Kubernetes API schema SHA256 mismatch.' }
$collectorUrl = 'https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.137.0/otelcol-contrib_0.137.0_windows_amd64.tar.gz'
$collectorHash = '44dfc0da231ad3f99b98aa7cfc77c2a9c287b8c11d8f29960a2880f5e9ed3136'
$collectorArchive = Join-Path $toolDir 'otelcol-contrib_0.137.0_windows_amd64.tar.gz'
Invoke-WebRequest -Uri $collectorUrl -Headers $headers -OutFile $collectorArchive
if ((Get-FileHash -LiteralPath $collectorArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $collectorHash) { throw 'Collector SHA256 mismatch.' }
tar -xf $collectorArchive -C $toolDir otelcol-contrib.exe
if ($LASTEXITCODE -ne 0) { throw 'Collector extraction failed.' }
$kustomizeUrl = 'https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize/v5.7.1/kustomize_v5.7.1_windows_amd64.zip'
$kustomizeHash = '4704f8b47582c1c52bf76a50b37178f7f7de17f892713794a8bf44b01b50e0ce'
$kustomizeZip = Join-Path $toolDir 'kustomize_v5.7.1_windows_amd64.zip'
Invoke-WebRequest -Uri $kustomizeUrl -Headers $headers -OutFile $kustomizeZip
if ((Get-FileHash -LiteralPath $kustomizeZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $kustomizeHash) { throw 'Kustomize SHA256 mismatch.' }
Expand-Archive -LiteralPath $kustomizeZip -DestinationPath $toolDir -Force
@{ kubeconform = @{ version='0.7.0'; url="$base/kubeconform-windows-amd64.zip"; sha256=$expected }; javaagent = @{ version='2.20.1'; url=$agentUrl; sha256=$agentHash }; kubernetesSchema = @{ version='v1.33.0'; url=$schemaUrl; sha256=$schemaHash }; collector = @{ version='0.137.0'; url=$collectorUrl; sha256=$collectorHash }; kustomize = @{ version='5.7.1'; url=$kustomizeUrl; sha256=$kustomizeHash } } |
  ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $toolDir 'downloads.json') -Encoding utf8
Write-Output 'Official deployment tools downloaded; SHA256 verified. No daemon or cluster accessed.'
