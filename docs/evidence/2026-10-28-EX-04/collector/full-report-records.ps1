# File-only report reader shared by both Full module entries and later reason comparison.
function Get-Ex04Identity($Row){$Row.classname+'|'+$Row.method+'|'+$Row.display_name+'|'+$Row.suite_path}
function Get-Ex04TextSha([string]$Text){
 $sha=[Security.Cryptography.SHA256]::Create()
 try{[BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))).Replace('-','')}finally{$sha.Dispose()}
}
function Read-Ex04FullReports([string]$Directory){
 $rows=New-Object 'System.Collections.Generic.List[object]'
 $classes=New-Object 'System.Collections.Generic.List[string]'
 $issues=New-Object 'System.Collections.Generic.List[object]'
 $outcomes=New-Object 'System.Collections.Generic.List[object]'
 $diagCount=0
 foreach($file in Get-ChildItem -LiteralPath $Directory -File -Filter 'TEST-*.xml'|Sort-Object Name){
  $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($file.FullName)
  $classes.Add($doc.DocumentElement.GetAttribute('name'))
  $index=0
  foreach($case in $doc.SelectNodes("//*[local-name()='testcase']")){
   $index++;$status='passed';$event=$null
   foreach($candidate in @('error','failure','skipped')){$event=$case.SelectSingleNode("./*[local-name()='$candidate']");if($event){$status=$candidate;break}}
   $type='';$message='';$detail='';$causes='';$frame=''
   if($event){
    $type=$event.GetAttribute('type');$message=$event.GetAttribute('message');$detail=$event.InnerText.Replace("`r`n","`n")
    $causes=@([regex]::Matches($detail,'(?m)^\s*Caused by:\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)')|ForEach-Object{$_.Groups[1].Value}) -join ' > '
    $match=[regex]::Match($detail,'(?m)^\s*at\s+(cn\.agentplatform\.[\w.$]+)\([\w.$]+\.java:\d+\)');if($match.Success){$frame=$match.Groups[1].Value}
   }
   $suites=New-Object 'System.Collections.Generic.List[string]';$parent=$case.ParentNode
   while($parent -and $parent -is [Xml.XmlElement]){if($parent.LocalName -eq 'testsuite'){$suites.Insert(0,$parent.GetAttribute('name'))};$parent=$parent.ParentNode}
   $method=$case.GetAttribute('name')
   # Empty names are retained as class-level events, including FAIL as well as ERROR.
   $rows.Add([pscustomobject]@{classname=$case.GetAttribute('classname');method=$method;display_name=$case.GetAttribute('displayName');suite_path=($suites -join ' > ');record_kind=if($method){'NAMED_CASE'}else{'CLASS_EVENT'};status=$status;exception_type=$type;cause_types=$causes;first_project_frame=$frame;message_sha256=(Get-Ex04TextSha $message);detail_sha256=(Get-Ex04TextSha $detail);case_index=$index;xml=('xml/'+$file.Name)})
  }
  foreach($suite in $doc.SelectNodes("//*[local-name()='testsuite']")){
   $cases=@($suite.SelectNodes(".//*[local-name()='testcase']"))
   $observed=@{tests=$cases.Count;errors=@($cases|Where-Object{$_.SelectSingleNode('./error')}).Count;failures=@($cases|Where-Object{$_.SelectSingleNode('./failure')}).Count;skipped=@($cases|Where-Object{$_.SelectSingleNode('./skipped')}).Count}
   foreach($key in $observed.Keys){if($suite.HasAttribute($key) -and [int]$suite.GetAttribute($key) -ne $observed[$key]){$issues.Add([pscustomobject]@{xml=$file.Name;suite=$suite.GetAttribute('name');field=$key;declared=$suite.GetAttribute($key);observed=$observed[$key]})}}
  }
  if($doc.DocumentElement.GetAttribute('name') -ceq 'cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest'){
   foreach($output in $doc.SelectNodes("//*[local-name()='system-out']")){
    foreach($line in ($output.InnerText -split "`r?`n")){
     if($line.StartsWith('ALGORITHM_INTERRUPT_DIAGNOSTIC')){$diagCount++}
     if($line -cmatch '^ALGORITHM_INTERRUPT stage=(PUT|GET|GET_BODY) returnMs=([0-9]+) flag=(true|false) inputClosed=([0-9]+) handoff=(true|false) explicitUnconfirmed=(true|false) firstQuiescent=(true|false)$'){
      $outcomes.Add([pscustomobject]@{stage=$Matches[1];returnMs=[long]$Matches[2];interrupted=[bool]::Parse($Matches[3]);inputClosed=[int]$Matches[4];handoff=[bool]::Parse($Matches[5]);explicitUnconfirmed=[bool]::Parse($Matches[6]);firstQuiescent=[bool]::Parse($Matches[7])})
     }
    }
   }
  }
 }
 $map=@{};foreach($row in $rows){$key=Get-Ex04Identity $row;if($map.ContainsKey($key)){throw 'DUPLICATE_REPORT_IDENTITY'};$map[$key]=$row}
 [pscustomobject]@{rows=$rows.ToArray();classes=$classes.ToArray();issues=$issues.ToArray();outcomes=$outcomes.ToArray();diagnosticCount=$diagCount}
}
