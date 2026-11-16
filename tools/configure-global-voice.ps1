param(
 [ValidateSet('Status','Setup','Restore')][string]$Mode='Status',
 [string]$Tv='192.168.2.89:5555'
)
$ErrorActionPreference='Stop'
$backupPath=Join-Path $PSScriptRoot 'aerial-voice-system-settings-backup.json'

function Invoke-Adb([string[]]$Arguments) {
 $output = & adb -s $Tv @Arguments 2>&1
 if($LASTEXITCODE -ne 0) {
   throw ("ADB failed: " + ($Arguments -join ' ') + [Environment]::NewLine + ($output -join [Environment]::NewLine))
 }
 return @($output)
}
function Get-Setting([string]$key) {
 $value=(@(Invoke-Adb -Arguments @('shell','settings','get','secure',$key)) -join '').Trim()
 if ($value -eq 'null') { return '' }
 return $value
}
function Put-Setting([string]$key,[string]$value) {
 if ([string]::IsNullOrEmpty($value)) {
   [void](Invoke-Adb -Arguments @('shell','settings','delete','secure',$key))
 } else {
   [void](Invoke-Adb -Arguments @('shell','settings','put','secure',$key,$value))
 }
}
try {
 & adb version | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Android Platform Tools (adb) not found in PATH.'}
 & adb connect $Tv | Out-Host
 if($LASTEXITCODE -ne 0){throw 'ADB connection failed.'}
 $pkglist=(@(Invoke-Adb -Arguments @('shell','pm','list','packages')) -join [Environment]::NewLine)
 $pkg=''
 if($pkglist -match '(?m)^package:app\.orchard\.tv\.aerialvoice\.mictest06\s*$') {
   $pkg='app.orchard.tv.aerialvoice.mictest06'
 } elseif($pkglist -match '(?m)^package:app\.orchard\.tv\.aerialvoice\s*$') {
   $pkg='app.orchard.tv.aerialvoice'
 }
 if(-not $pkg){throw 'Aerial Voice not installed. Run INSTALL_OFFLINE.cmd first.'}
 $keys=@('assistant','voice_interaction_service','enabled_accessibility_services','accessibility_enabled')
 Write-Host ('Target: '+$Tv+' | Package: '+$pkg) -ForegroundColor Cyan
 if($Mode -eq 'Status'){
   foreach($key in $keys){Write-Host ($key+' = '+(Get-Setting $key))}
   Write-Host 'Status only. No system setting was changed.'
   exit 0
 }
 if($Mode -eq 'Setup'){
   if(Test-Path $backupPath){throw "Backup already exists: $backupPath . Restore the previous setup first."}
   $saved=@{}
   foreach($key in $keys){$saved[$key]=Get-Setting $key}
   $saved['tv']=$Tv
   $saved['installedPackage']=$pkg
   $saved['savedAtUtc']=[DateTime]::UtcNow.ToString('o')
   $roleBefore=@(& adb -s $Tv shell cmd role get-role-holders android.app.role.ASSISTANT 2>$null)
   $saved['originalRoleHolders']=($roleBefore -join ' ').Trim()
   $json=ConvertTo-Json -InputObject $saved -Depth 4
   [IO.File]::WriteAllText($backupPath,$json,(New-Object System.Text.UTF8Encoding($false)))
   Write-Host "Original system settings backed up: $backupPath" -ForegroundColor Green
   $component="$pkg/app.orchard.tv.aerialvoice.MainActivity"
   $vis="$pkg/app.orchard.tv.aerialvoice.AerialVoiceInteractionService"
   $keySvc="$pkg/app.orchard.tv.aerialvoice.AerialKeyAccessibilityService"
   try {
     $role=& adb -s $Tv shell cmd role add-role-holder android.app.role.ASSISTANT $pkg 2>&1
     Write-Host ('RoleManager: '+($role -join ' '))
     if($LASTEXITCODE -ne 0){Write-Warning 'RoleManager returned nonzero; secure-settings fallback will be attempted.'}
   }catch{Write-Warning 'RoleManager ASSISTANT unavailable; using secure-settings fallback.'}
   Put-Setting 'assistant' $component
   Put-Setting 'voice_interaction_service' $vis
   $orig=$saved['enabled_accessibility_services']
   $entries=@()
   if(-not [string]::IsNullOrEmpty($orig)){$entries=@($orig.Split(':') | Where-Object{$_})}
   if($entries -notcontains $keySvc){$entries += $keySvc}
   Put-Setting 'enabled_accessibility_services' ($entries -join ':')
   Put-Setting 'accessibility_enabled' '1'
   Write-Host 'Global assistant and F5 bridge configured. Verify the key on the TV.' -ForegroundColor Green
   Write-Host 'To revert, run RESTORE_GLOBAL_VOICE.cmd from this folder.'
 }
 if($Mode -eq 'Restore'){
   if(-not (Test-Path $backupPath)){throw "Backup file not found: $backupPath"}
   $saved=Get-Content -LiteralPath $backupPath -Raw -Encoding UTF8 | ConvertFrom-Json
   if($saved.tv -ne $Tv){throw "This backup is for $($saved.tv), not $Tv"}
   foreach($key in $keys){Put-Setting $key ([string]$saved.$key)}
   Write-Host 'Restored original secure settings.' -ForegroundColor Green
   # Restore the explicit assistant role when RoleManager permits it.
   & adb -s $Tv shell cmd role remove-role-holder android.app.role.ASSISTANT $pkg 2>$null | Out-Null
   $oldRole=[string]$saved.originalRoleHolders
   if($oldRole -match '(com\.[a-zA-Z0-9_\.]+)') {
     $prior=$Matches[1]
     if($prior -ne $pkg) {
       & adb -s $Tv shell cmd role add-role-holder android.app.role.ASSISTANT $prior 2>$null | Out-Null
     }
   }
   Write-Host 'Assistant role restoration attempted; check Android default-assistant settings.'
 }
 foreach($key in $keys){Write-Host ($key+' = '+(Get-Setting $key))}
}catch{
 Write-Host ('FAILED: '+$_.Exception.Message) -ForegroundColor Red
 if($Mode -eq 'Setup' -and (Test-Path $backupPath)){
   Write-Warning 'Partial setup possible. Run RESTORE_GLOBAL_VOICE.cmd to revert.'
 }
 exit 1
}
