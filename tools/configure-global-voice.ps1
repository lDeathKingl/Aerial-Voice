param(
 [ValidateSet('Status','Setup','Assistant','Restore')][string]$Mode='Status',
 [string]$Tv='192.168.2.89:5555'
)
$ErrorActionPreference='Stop'
$backupPath=Join-Path $PSScriptRoot 'aerial-voice-system-settings-backup.json'

function Invoke-Adb([string[]]$Arguments) {
 $output=& adb -s $Tv @Arguments 2>&1
 if($LASTEXITCODE -ne 0){
   throw ("ADB failed: "+($Arguments -join ' ')+[Environment]::NewLine+($output -join [Environment]::NewLine))
 }
 return @($output)
}
function Get-Setting([string]$key) {
 $v=(@(Invoke-Adb -Arguments @('shell','settings','get','secure',$key)) -join '').Trim()
 if($v -eq 'null'){return ''}
 return $v
}
function Put-Setting([string]$key,[string]$value){
 if([string]::IsNullOrEmpty($value)){
   [void](Invoke-Adb -Arguments @('shell','settings','delete','secure',$key))
 }else{
   [void](Invoke-Adb -Arguments @('shell','settings','put','secure',$key,$value))
 }
}
function Save-Backup($data){
 $json=ConvertTo-Json -InputObject $data -Depth 5
 [IO.File]::WriteAllText($backupPath,$json,(New-Object System.Text.UTF8Encoding($false)))
}
try{
 & adb version | Out-Null
 if($LASTEXITCODE -ne 0){throw 'adb.exe not found in PATH.'}
 & adb connect $Tv | Out-Host
 if($LASTEXITCODE -ne 0){throw 'ADB connection failed.'}
 $packages=(@(Invoke-Adb -Arguments @('shell','pm','list','packages')) -join [Environment]::NewLine)
 $pkg=''
 if($packages -match '(?m)^package:app\.orchard\.tv\.aerialvoice\.mictest06\s*$'){
   $pkg='app.orchard.tv.aerialvoice.mictest06'
 }elseif($packages -match '(?m)^package:app\.orchard\.tv\.aerialvoice\s*$'){
   $pkg='app.orchard.tv.aerialvoice'
 }
 if(-not $pkg){throw 'Aerial Voice not installed. Run INSTALL_OFFLINE.cmd first.'}
 $keys=@('assistant','voice_interaction_service','enabled_accessibility_services','accessibility_enabled')
 Write-Host ('TV: '+$Tv+' | app: '+$pkg) -ForegroundColor Cyan
 if($Mode -eq 'Status'){
   foreach($key in $keys){Write-Host ($key+' = '+(Get-Setting $key))}
   Write-Host 'Read-only status: no TV settings changed.'
   exit 0
 }
 if($Mode -eq 'Setup'){
   if(Test-Path $backupPath){
     throw "Backup exists: $backupPath . Do not overwrite the original settings; use Restore first."
   }
   $saved=@{}
   foreach($key in $keys){$saved[$key]=Get-Setting $key}
   $saved['tv']=$Tv
   $saved['package']=$pkg
   $saved['savedAtUtc']=[DateTime]::UtcNow.ToString('o')
   $saved['advancedModeEnabled']=$false
   $roles=@(& adb -s $Tv shell cmd role get-role-holders android.app.role.ASSISTANT 2>$null)
   $saved['originalRoleHolders']=($roles -join ' ').Trim()
   Save-Backup $saved
   Write-Host ('Original settings saved: '+$backupPath) -ForegroundColor Green
   # Default setup activates only the optional F5 Accessibility service.
   # Do NOT replace the stock/system assistant or Aerial Korobovo.
   $service="$pkg/app.orchard.tv.aerialvoice.AerialKeyAccessibilityService"
   $previous=$saved['enabled_accessibility_services']
   $entries=@()
   if(-not [string]::IsNullOrEmpty($previous)){
     $entries=@($previous.Split(':') | Where-Object{$_})
   }
   if($entries -notcontains $service){$entries += $service}
   Put-Setting 'enabled_accessibility_services' ($entries -join ':')
   Put-Setting 'accessibility_enabled' '1'
   Write-Host 'F5 Accessibility bridge enabled. Original default assistant was NOT changed.' -ForegroundColor Green
   Write-Host 'If HomeGuard already handles F5, do not enable both bridges until tested.'
 }
 if($Mode -eq 'Assistant'){
   if(-not (Test-Path $backupPath)){
     throw 'First run Setup to save your original settings.'
   }
   $saved=Get-Content -LiteralPath $backupPath -Raw -Encoding UTF8 | ConvertFrom-Json
   if($saved.tv -ne $Tv -or $saved.package -ne $pkg){throw 'Backup belongs to another TV or Android package.'}
   Write-Warning 'Advanced mode WILL replace your Android default assistant. HomeGuard may conflict.'
   $vis="$pkg/app.orchard.tv.aerialvoice.AerialVoiceInteractionService"
   $activity="$pkg/app.orchard.tv.aerialvoice.MainActivity"
   $result=@(& adb -s $Tv shell cmd role add-role-holder android.app.role.ASSISTANT $pkg 2>&1)
   if($LASTEXITCODE -ne 0){Write-Warning ('RoleManager rejected assistant role: '+($result -join ' '))}
   Put-Setting 'assistant' $activity
   Put-Setting 'voice_interaction_service' $vis
   $saved.advancedModeEnabled=$true
   $saved | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $backupPath -Encoding UTF8
   Write-Host 'Advanced system-assistant setting applied.' -ForegroundColor Yellow
 }
 if($Mode -eq 'Restore'){
   if(-not (Test-Path $backupPath)){throw "Backup not found: $backupPath"}
   $saved=Get-Content -LiteralPath $backupPath -Raw -Encoding UTF8 | ConvertFrom-Json
   if($saved.tv -ne $Tv -or $saved.package -ne $pkg){throw 'Backup belongs to another TV or Android package.'}
   foreach($key in $keys){Put-Setting $key ([string]$saved.$key)}
   if($saved.advancedModeEnabled){
     # Only remove role if we changed it in the advanced mode.
     $prior=[string]$saved.originalRoleHolders
     if($prior -notmatch [regex]::Escape($pkg)){
       & adb -s $Tv shell cmd role remove-role-holder android.app.role.ASSISTANT $pkg 2>$null | Out-Null
     }
     if($prior -match '([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)'){
       $old=$Matches[1]
       & adb -s $Tv shell cmd role add-role-holder android.app.role.ASSISTANT $old 2>$null | Out-Null
     }
   }
   Write-Host 'Original secure settings restored (verify Android default assistant on TV).' -ForegroundColor Green
   Write-Host ('Original backup kept: '+$backupPath)
 }
 foreach($key in $keys){Write-Host ($key+' = '+(Get-Setting $key))}
}catch{
 Write-Host ('FAILED: '+$_.Exception.Message) -ForegroundColor Red
 if(Test-Path $backupPath){
   Write-Warning 'Backup exists. Restore with RESTORE_GLOBAL_VOICE.cmd if needed.'
 }
 exit 1
}
