param(
    [Parameter(Mandatory)][ValidatePattern('^.+@sha256:[a-f0-9]{64}$')][string]$JavaBuildImage,
    [Parameter(Mandatory)][ValidatePattern('^.+@sha256:[a-f0-9]{64}$')][string]$JavaRuntimeImage,
    [Parameter(Mandatory)][ValidatePattern('^.+@sha256:[a-f0-9]{64}$')][string]$NodeBuildImage,
    [Parameter(Mandatory)][ValidatePattern('^.+@sha256:[a-f0-9]{64}$')][string]$NginxRuntimeImage
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# Caller supplies packaged Spring Boot jars and an explicitly enabled dedicated build environment.
foreach ($service in @('platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "$service/target/$service-0.1.0-SNAPSHOT.jar"))) { throw "Package $service first." }
    & docker build -f (Join-Path $PSScriptRoot 'Dockerfile.backend') --build-arg "SERVICE=$service" --build-arg "JAVA_BUILD_IMAGE=$JavaBuildImage" --build-arg "JAVA_RUNTIME_IMAGE=$JavaRuntimeImage" -t "agent-platform-v12/$($service):0.1.0" $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $service" }
}
& docker build -f (Join-Path $PSScriptRoot 'Dockerfile.frontend') --build-arg "NODE_BUILD_IMAGE=$NodeBuildImage" --build-arg "NGINX_RUNTIME_IMAGE=$NginxRuntimeImage" -t 'agent-platform-v12/frontend:0.1.0' $projectRoot
if ($LASTEXITCODE -ne 0) { throw 'Frontend image build failed.' }
Write-Output 'Local images built. Nothing pushed; release config still requires actual immutable digests.'
