param([string]$Name,[string]$Targets)
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$list=$Targets -split ','
& $adb shell uiautomator dump "/sdcard/$Name.xml" 2>&1 | Out-Null
& $adb exec-out cat "/sdcard/$Name.xml" 2>&1 | Out-File -Encoding utf8 "$Name.xml"
$x=[xml](Get-Content "$Name.xml" -Raw)
$rows=@()
foreach($t in $list){
  $n=$x.SelectNodes('//node') | Where-Object{ $_.GetAttribute('text') -eq $t } | Select-Object -First 1
  if($null -eq $n){ $rows += "$t | NOT VISIBLE"; continue }
  $p=$n; $guard=0
  while($null -ne $p -and $p.NodeType -eq 'Element' -and $p.GetAttribute('clickable') -ne 'true' -and $guard -lt 6){ $p=$p.ParentNode; $guard++ }
  $target=if($null -ne $p -and $p.NodeType -eq 'Element' -and $p.GetAttribute('clickable') -eq 'true'){$p.GetAttribute('bounds')}else{'no-clickable-ancestor'}
  $rows += "$t | text=$($n.GetAttribute('bounds')) | clickTarget=$target"
}
$rows | Out-File -Encoding utf8 "$Name.targets.txt"
$rows
