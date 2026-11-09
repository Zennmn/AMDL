@echo off
setlocal
if exist "%~dp0dist\AMDL-1.1.6\AMDL.exe" (
    start "" "%~dp0dist\AMDL-1.1.6\AMDL.exe"
) else (
    echo Please build AMDL first: pwsh -NoProfile -File scripts\build.ps1
    pause
)
