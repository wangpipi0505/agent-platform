param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$run=Join-Path $PSScriptRoot $RunId
$private=Join-Path $root ('.local/acceptance/'+$RunId)
if((Test-Path $run) -or (Test-Path $private)){throw 'Run already exists'}
$paths=@('scripts/acceptance/Joint.Common.ps1','scripts/acceptance/Prepare-Joint.ps1','scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs','docs/evidence/2026-10-29-EX-06/Test-JointProtocol.ps1','docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1')
function Sources {@($paths|ForEach-Object{[pscustomobject]@{path=$_;sha256=(Get-FileHash (Join-Path $root $_)).Hash}})}
function Fact([string]$name,[object]$value){[IO.File]::WriteAllText((Join-Path $run $name),(ConvertTo-Json -InputObject $value -Depth 12),(New-Object Text.UTF8Encoding($false)))}
$before=Sources
if($before[2].sha256 -ne 'BCFA907779DD98CB69A26A8DE9D20D4E048ACEBC6CBDF2E38255F2ECCA5FF401' -or $before[3].sha256 -ne 'FBEB113D01506B6A7429B51C3F02EF9F253B14B6F90348544658C31FB5213298'){throw 'Reviewed model candidate drift'}
[IO.Directory]::CreateDirectory($run)|Out-Null
Fact 'source-before.json' $before
$head=((& git -C $root rev-parse HEAD)-join '')
$envHash=(Get-FileHash (Join-Path $root '.env')).Hash
Fact 'command.json' ([ordered]@{head=$head;envSha256=$envHash;script=$paths[3];evidenceDirectory=$private;powershellVersion=$PSVersionTable.PSVersion.ToString();collectorSha256=(Get-FileHash $PSCommandPath).Hash;businessValidationPerformed=$false;environmentImported=$false;startedAtUtc=[DateTime]::UtcNow.ToString('o')})
$ErrorActionPreference='Continue'
& "$PSHOME/powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root $paths[3]) -EvidenceDirectory $private > (Join-Path $run 'driver.log') 2>&1
$native=$LASTEXITCODE
$ErrorActionPreference='Stop'
$checksPath=Join-Path $private 'protocol-checks.json'
if(-not(Test-Path $checksPath)){Fact 'collector-error.json' @{nativeExit=$native;error='Missing protocol facts'};exit 2}
Copy-Item -LiteralPath $checksPath -Destination (Join-Path $run 'protocol-checks.json')
$checks=[IO.File]::ReadAllText($checksPath)|ConvertFrom-Json
$children=@(foreach($id in @($checks.fixturePid,$checks.modelFixturePid)){[pscustomobject]@{pid=$id;absentAfterDriver=($null -eq (Get-Process -Id $id -ErrorAction SilentlyContinue))}})
$ports=@(foreach($file in @('fixture-ready.json','model-ready.json')){$registration=[IO.File]::ReadAllText((Join-Path $private $file))|ConvertFrom-Json;[pscustomobject]@{registration=$file;pid=$registration.pid;port=$registration.port;listenerAbsent=(@(Get-NetTCPConnection -State Listen -LocalPort $registration.port -ErrorAction SilentlyContinue).Count -eq 0)}})
$after=Sources;Fact 'source-after.json' $after
$stable=(ConvertTo-Json -InputObject $before -Compress) -ceq (ConvertTo-Json -InputObject $after -Compress)
$context=$head -ceq ((& git -C $root rev-parse HEAD)-join '') -and $envHash -eq (Get-FileHash (Join-Path $root '.env')).Hash
$passed=$native -eq 0 -and $checks.passed -and @($checks.checks).Count -eq 63 -and $checks.modelStopped -and @($children|Where-Object {-not $_.absentAfterDriver}).Count -eq 0 -and @($ports|Where-Object {-not $_.listenerAbsent}).Count -eq 0 -and $stable -and $context
Fact 'summary.json' ([ordered]@{nativeExit=$native;checks=@($checks.checks).Count;passed=$passed;children=$children;ports=$ports;sourceStable=$stable;contextStable=$context;businessValidationPerformed=$false;protocolChecksSha256=(Get-FileHash $checksPath).Hash;endedAtUtc=[DateTime]::UtcNow.ToString('o')})
Get-Content (Join-Path $run 'summary.json') -Encoding UTF8
if(-not $passed){exit 1}
exit 0
