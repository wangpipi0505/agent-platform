param([Parameter(Mandatory=$true)][string]$RunDirectory,[string]$BaselineCsv,
 [ValidatePattern('^[a-z0-9-]+$')][string]$Name='comparison')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$record=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $RunDirectory 'run.json') | ConvertFrom-Json
if(-not $BaselineCsv){
 $selection=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repo 'docs/evidence/2026-10-28-EX-00/comparison-baseline.json') | ConvertFrom-Json
 $relative=$selection.module_baselines.PSObject.Properties[$record.module].Value
 $BaselineCsv=Join-Path $repo ('docs/evidence/2026-10-28-EX-00/'+$relative+'/methods.csv')
}
$original=@(Import-Csv -Encoding UTF8 -LiteralPath $BaselineCsv | Where-Object {$_.module -eq $record.module})
$current=@(Import-Csv -Encoding UTF8 -LiteralPath (Join-Path $RunDirectory 'methods.csv'))
if($original.Count -eq 0 -or $current.Count -eq 0){throw '不能以空方法表进行比较'}
$statusMap=@{passed='PASS';failure='FAIL';failed='FAIL';error='ERROR';skipped='SKIP';PASS='PASS';FAIL='FAIL';SKIP='SKIP'}
$base=@{}
$now=@{}
$duplicates=@()
foreach($row in $original){
 $key=$row.classname+'#'+$row.method
 if($base.ContainsKey($key)){$duplicates+='baseline:'+ $key}
 $base[$key]=$statusMap[$row.status]
}
foreach($row in $current){
 $key=$row.classname+'#'+$row.method
 if($now.ContainsKey($key)){$duplicates+='current:'+ $key}
 $now[$key]=$statusMap[$row.status]
}
$missing=@($base.Keys | Where-Object {-not $now.ContainsKey($_)} | Sort-Object)
$newNonPass=@()
$changes=@()
foreach($key in $now.Keys){
 $prior=if($base.ContainsKey($key)){$base[$key]}else{'ABSENT'}
 if($prior -ne $now[$key]){$changes+=[pscustomobject]@{method=$key;before=$prior;after=$now[$key]}}
 if($now[$key] -in @('FAIL','ERROR','SKIP') -and $prior -notin @('FAIL','ERROR','SKIP')){$newNonPass+=[pscustomobject]@{method=$key;before=$prior;after=$now[$key]}}
}
$classLevel=@($current | Where-Object {[string]::IsNullOrWhiteSpace($_.method)})
$result=[pscustomobject]@{module=$record.module;baselineCsv=$BaselineCsv;baselineSha256=(Get-FileHash -LiteralPath $BaselineCsv -Algorithm SHA256).Hash;
 baselineRows=$original.Count;currentRows=$current.Count;baselineUnique=$base.Count;currentUnique=$now.Count;
 missingMethods=$missing;duplicates=$duplicates;classLevelCases=$classLevel;changes=@($changes | Sort-Object method);newNonPass=@($newNonPass | Sort-Object method);
 noNewNonPass=($newNonPass.Count -eq 0);collectionComplete=($missing.Count -eq 0 -and $duplicates.Count -eq 0 -and $classLevel.Count -eq 0);
 allTestsPassed=(@($current | Where-Object {$_.status -ne 'PASS'}).Count -eq 0);note='原非通过与状态变化都保留；本结果不自动豁免既有失败，也不替代独立评审'}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $RunDirectory ($Name+'.json')) -Encoding UTF8
Write-Output ('EX01_COMPARE module='+$record.module+'; baseline='+$original.Count+'; current='+$current.Count+'; missing='+$missing.Count+'; duplicates='+$duplicates.Count+'; newNonPass='+$newNonPass.Count)
