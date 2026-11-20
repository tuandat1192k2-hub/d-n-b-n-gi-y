$ErrorActionPreference = 'Stop'

$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$launcher = Join-Path $PSScriptRoot 'start-store.bat'
$action = New-ScheduledTaskAction -Execute $env:ComSpec -Argument ('/c ""' + $launcher + '" --silent"')
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest

Register-ScheduledTask -TaskName 'Swoosh Store' -Action $action -Trigger $trigger `
    -Principal $principal -Description 'Starts the Swoosh Store Java backend and opens its page at sign-in.' `
    -Force | Out-Null

Write-Host 'Swoosh Store will start automatically when this Windows account signs in.'
