@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -Command "$script = '%~dp0install-startup-task.ps1'; $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File ""' + $script + '""'); exit $process.ExitCode"
if errorlevel 1 (
    echo Startup task installation failed or was cancelled.
    pause
    exit /b 1
)
echo Startup task installed.
pause
