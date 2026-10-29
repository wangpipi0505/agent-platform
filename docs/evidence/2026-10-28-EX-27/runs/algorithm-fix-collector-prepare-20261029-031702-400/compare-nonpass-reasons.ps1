$ErrorActionPreference='Stop'
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=Join-Path $PSScriptRoot 'full'
function Get-TextHash([string]$Text){
 $sha=[Security.Cryptography.SHA256]::Create()
 try{[BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))).Replace('-','')}finally{$sha.Dispose()}
}
function Read-ReasonMap([string]$Directory){
 $map=@{}
 foreach($file in Get-ChildItem -LiteralPath $Directory -File -Filter 'TEST-*.xml'){
  $doc=New-Object System.Xml.XmlDocument
  $doc.XmlResolver=$null
  $doc.Load($file.FullName)
  foreach($case in $doc.SelectNodes("//*[local-name()='testcase']")){
   $names=New-Object 'System.Collections.Generic.List[string]'
   $parent=$case.ParentNode
   while($parent -and $parent -is [System.Xml.XmlElement]){if($parent.LocalName -eq 'testsuite'){$names.Insert(0,$parent.GetAttribute('name'))};$parent=$parent.ParentNode}
   $key=$case.GetAttribute('classname')+'|'+$case.GetAttribute('name')+'|'+$case.GetAttribute('displayName')+'|'+($names -join ' > ')
   if($map.ContainsKey($key)){throw 'DUPLICATE_REPORT_IDENTITY'}
   $status='passed';$event=$null
   foreach($candidate in @('error','failure','skipped')){$event=$case.SelectSingleNode("./*[local-name()='$candidate']");if($event){$status=$candidate;break}}
   $types=@();$frame='';$rootType='';$message='';$detail=''
   if($event){
    $rootType=$event.GetAttribute('type');$message=$event.GetAttribute('message');$detail=$event.InnerText.Replace("`r`n","`n")
    $types=@([regex]::Matches($detail,'(?m)^\s*Caused by:\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)')|ForEach-Object{$_.Groups[1].Value})
    $match=[regex]::Match($detail,'(?m)^\s*at\s+(cn\.agentplatform\.[\w.$]+)\([\w.$]+\.java:\d+\)')
    if($match.Success){$frame=$match.Groups[1].Value}
   }
   $map[$key]=[pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');status=$status;type=$rootType;causeTypes=($types -join ' > ');firstProjectFrame=$frame;messageSha256=(Get-TextHash $message);detailSha256=(Get-TextHash $detail);xml=$file.Name}
  }
 }
 return $map
}
$taskBefore=Read-ReasonMap "$taskRoot/docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/skill-registry-xml"
$taskOldFull=Read-ReasonMap "$taskRoot/docs/evidence/2026-10-28-EX-03/after/full-20261028-234917-584/skill-registry-xml"
$taskAfter=Read-ReasonMap (Join-Path $taskRun 'xml')
$taskRows=@(foreach($key in $taskBefore.Keys|Sort-Object){
 $before=$taskBefore[$key]
 if($before.status -eq 'passed'){continue}
 $after=$taskAfter[$key]
 [pscustomobject]@{classname=$before.classname;method=$before.method;beforeStatus=$before.status;afterStatus=if($after){$after.status}else{'MISSING'};beforeType=$before.type;afterType=if($after){$after.type}else{''};beforeCauseTypes=$before.causeTypes;afterCauseTypes=if($after){$after.causeTypes}else{''};beforeFirstProjectFrame=$before.firstProjectFrame;afterFirstProjectFrame=if($after){$after.firstProjectFrame}else{''};categoryAndCauseFramesSame=($after -and $before.status -ceq $after.status -and $before.type -ceq $after.type -and $before.causeTypes -ceq $after.causeTypes -and $before.firstProjectFrame -ceq $after.firstProjectFrame);exactMessageSame=($after -and $before.messageSha256 -ceq $after.messageSha256);beforeMessageSha256=$before.messageSha256;afterMessageSha256=if($after){$after.messageSha256}else{''};exactDetailSame=($after -and $before.detailSha256 -ceq $after.detailSha256);beforeXml=$before.xml;afterXml=if($after){$after.xml}else{''}}
})
$taskRows|Export-Csv -LiteralPath (Join-Path $taskRun 'retained-nonpass-reasons.csv') -NoTypeInformation -Encoding UTF8
$taskDelta=@(foreach($key in $taskOldFull.Keys|Sort-Object){$old=$taskOldFull[$key];$current=$taskAfter[$key];if(-not $current -or $old.status -cne $current.status){[pscustomobject]@{classname=$old.classname;method=$old.method;beforeStatus=$old.status;afterStatus=if($current){$current.status}else{'MISSING'}}}})
$taskDelta|Export-Csv -LiteralPath (Join-Path $taskRun 'original-full-status-delta.csv') -NoTypeInformation -Encoding UTF8
$taskSummary=[ordered]@{analysis='FILE_XML_ONLY_NO_PRODUCT_COMMAND';baselineCases=$taskBefore.Count;originalFullCases=$taskOldFull.Count;currentCases=$taskAfter.Count;baselineNonpass=$taskRows.Count;missing=@($taskRows|Where-Object{$_.afterStatus -eq 'MISSING'}).Count;categoryOrCauseFrameChanges=@($taskRows|Where-Object{-not $_.categoryAndCauseFramesSame}).Count;exactMessageChanges=@($taskRows|Where-Object{-not $_.exactMessageSame}).Count;exactDetailChanges=@($taskRows|Where-Object{-not $_.exactDetailSame}).Count;originalFullStatusDelta=$taskDelta;rawMessagesPrinted=$false;normalizationApplied=$false;oldRawXmlPreserved=$true;newRawXmlPreserved=$true}
[IO.File]::WriteAllText((Join-Path $taskRun 'reason-comparison-summary.json'),(ConvertTo-Json -InputObject $taskSummary -Depth 7),(New-Object Text.UTF8Encoding($false)))
$taskSummary|ConvertTo-Json -Depth 7
