# Pure file extraction helper. Defining this function never constructs a fixture or connects a service.
function Export-Ex04ProjectionEvidence {
 param([Parameter(Mandatory=$true)][string]$RunDirectory)
 $ErrorActionPreference='Stop'
 $file=Join-Path $RunDirectory 'xml/TEST-cn.agentplatform.skill.application.OntologyProjectionImportTest.xml'
 if(-not (Test-Path -LiteralPath $file -PathType Leaf)){throw 'PROJECTION_XML_MISSING'}
 $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load((Resolve-Path -LiteralPath $file).Path)
 if($doc.DocumentElement.GetAttribute('name') -cne 'cn.agentplatform.skill.application.OntologyProjectionImportTest'){throw 'PROJECTION_XML_OWNER_MISMATCH'}
 $projection=New-Object 'System.Collections.Generic.List[object]'
 $batches=New-Object 'System.Collections.Generic.List[object]'
 $ordinal=0
 foreach($output in $doc.SelectNodes("//*[local-name()='system-out']")){
  foreach($line in ($output.InnerText -split "`r?`n")){
   $ordinal++
   if($line.StartsWith('EX04_PERSISTED_PROJECTION=')){
    if($line -cnotmatch '^EX04_PERSISTED_PROJECTION=(\{.*\})$'){throw 'PROJECTION_PREFIX_SHAPE_MISMATCH'}
    $raw=$Matches[1];$value=$raw|ConvertFrom-Json
    $projection.Add([pscustomobject]@{raw=$raw;value=$value;line=$ordinal})
   }
   if($line.StartsWith('EX04_BATCH_COUNTS=')){
    if($line -cnotmatch '^EX04_BATCH_COUNTS=(\{.*\})$'){throw 'BATCH_PREFIX_SHAPE_MISMATCH'}
    $raw=$Matches[1];$value=$raw|ConvertFrom-Json
    $batches.Add([pscustomobject]@{raw=$raw;value=$value;line=$ordinal})
   }
  }
 }
 if($projection.Count -ne 1 -or $batches.Count -ne 4){throw 'ACTUAL_OUTPUT_PREFIX_COUNT_MISMATCH'}
 function Assert-Ex04Keys($Value,[string[]]$Expected){
  if($null -eq $Value){throw 'SAFE_OUTPUT_OBJECT_MISSING'}
  $keys=@($Value.PSObject.Properties|Select-Object -ExpandProperty Name)
  if($keys.Count -ne $Expected.Count -or @($keys|Where-Object{$Expected -cnotcontains $_}).Count -ne 0){throw 'SAFE_OUTPUT_UNKNOWN_KEY'}
 }
 function Test-Ex04Integer($Value){($Value -is [int] -or $Value -is [long]) -and $Value -ge 0 -and $Value -le [int]::MaxValue}
 $p=$projection[0].value
 Assert-Ex04Keys $p @('exposure','pagination')
 Assert-Ex04Keys $p.exposure @('kind')
 if(@('QUERY','MUTATION') -cnotcontains $p.exposure.kind){throw 'SAFE_OUTPUT_EXPOSURE_MISMATCH'}
 Assert-Ex04Keys $p.pagination @('mode','request','maxPageSize','maxRows')
 Assert-Ex04Keys $p.pagination.request @('offset','size')
 if($p.pagination.mode -cne 'OFFSET' -or -not (Test-Ex04Integer $p.pagination.maxPageSize) -or $p.pagination.maxPageSize -lt 1 -or -not (Test-Ex04Integer $p.pagination.maxRows) -or $p.pagination.maxRows -lt 1){throw 'SAFE_OUTPUT_PAGINATION_MISMATCH'}
 foreach($name in @($p.pagination.request.offset,$p.pagination.request.size)){
  if($name -isnot [string] -or $name -cnotmatch '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'){throw 'SAFE_OUTPUT_PARAMETER_NAME_MISMATCH'}
 }
 foreach($entry in $batches){
  $v=$entry.value;Assert-Ex04Keys $v @('before','after','drafts','rejected')
  if(-not (Test-Ex04Integer $v.before) -or -not (Test-Ex04Integer $v.after) -or -not (Test-Ex04Integer $v.drafts) -or $v.rejected -isnot [bool] -or -not $v.rejected -or $v.before -ne $v.after -or $v.drafts -ne 0){throw 'SAFE_OUTPUT_BATCH_POSTCONDITION_MISMATCH'}
 }
 # No output file is written until every actual prefix and its safe shape has passed.
 $projectionFile=Join-Path $RunDirectory 'ex04-persisted-projection.json'
 $batchFile=Join-Path $RunDirectory 'ex04-batch-counts.json'
 [IO.File]::WriteAllText($projectionFile,$projection[0].raw,(New-Object Text.UTF8Encoding($false)))
 $batchValues=@($batches|ForEach-Object{$_.value})
 [IO.File]::WriteAllText($batchFile,(ConvertTo-Json -InputObject $batchValues -Depth 5),(New-Object Text.UTF8Encoding($false)))
 $proof=[ordered]@{passed=$true;source='ACTUAL_FRESH_PASSING_PROJECTION_XML_ONLY';xml='xml/TEST-cn.agentplatform.skill.application.OntologyProjectionImportTest.xml';xmlSha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash;projectionPrefixCount=$projection.Count;projectionLine=$projection[0].line;projectionKeys=@('exposure','pagination');projectionJsonSavedAsExactEmittedUtf8Bytes=$true;projectionJsonSha256=(Get-FileHash -LiteralPath $projectionFile -Algorithm SHA256).Hash;batchPrefixCount=$batches.Count;batchLines=@($batches|ForEach-Object{$_.line});batchJsonSha256=(Get-FileHash -LiteralPath $batchFile -Algorithm SHA256).Hash;constructedBeforeActualRun=$false;containsCredentialTenantEndpointOrPayload=$false}
 [IO.File]::WriteAllText((Join-Path $RunDirectory 'ex04-output-provenance.json'),($proof|ConvertTo-Json -Depth 6),(New-Object Text.UTF8Encoding($false)))
 [pscustomobject]$proof
}
