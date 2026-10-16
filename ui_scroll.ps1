param([string]$Prefix,[int]$Screens=8)
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$all=@()
for($i=1;$i -le $Screens;$i++){
  & $adb shell uiautomator dump "/sdcard/$Prefix$i.xml" 2>&1 | Out-Null
  & $adb exec-out cat "/sdcard/$Prefix$i.xml" 2>&1 | Out-File -Encoding utf8 "$Prefix$i.xml"
  $x=[xml](Get-Content "$Prefix$i.xml" -Raw)
  $all += "=== $Prefix$i ==="
  $x.SelectNodes('//node') | Where-Object{ $_.GetAttribute('text') -ne '' } | ForEach-Object{ $all += $_.GetAttribute('text') }
  # scroll inside the LazyColumn bounds [42,1162][1038,2172], starting at x=1000 (clear of item buttons)
  & $adb shell input swipe 1000 2100 1000 1300 400 2>&1 | Out-Null
  Start-Sleep -Milliseconds 900
}
$all | Out-File -Encoding utf8 "$Prefix.all.txt"
"SCREENS=$Screens" | Out-File -Encoding utf8 -Append "$Prefix.all.txt"
