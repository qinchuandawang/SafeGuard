@echo off
setlocal
title SafeGuard-Audio-Service

set "ROOT=%~dp0.."
set "AUDIO_DIR=%ROOT%\ai-services\audio"
set "AI_VENV=%ROOT%\ai-services\.venv\Scripts\python.exe"
set "ROOT_VENV=%ROOT%\.venv\Scripts\python.exe"
set "PYTHON_EXE=python"

if exist "%AI_VENV%" set "PYTHON_EXE=%AI_VENV%"
if not exist "%AI_VENV%" if exist "%ROOT_VENV%" set "PYTHON_EXE=%ROOT_VENV%"

cd /d "%AUDIO_DIR%"
set "PORT=5000"
if not defined SAFEGUARD_DEVICE set "SAFEGUARD_DEVICE=cuda"

echo [SafeGuard] Audio service console
echo Directory: %CD%
echo URL: http://localhost:5000/health
echo Python: %PYTHON_EXE%
echo Device preference: %SAFEGUARD_DEVICE%
echo.
"%PYTHON_EXE%" -u app.py

echo.
echo [EXIT] Audio service stopped with code %ERRORLEVEL%.
pause
