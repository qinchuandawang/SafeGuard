@echo off
setlocal
title SafeGuard-Video-Service

set "ROOT=%~dp0.."
set "VIDEO_DIR=%ROOT%\ai-services\video"
set "AI_VENV=%ROOT%\ai-services\.venv\Scripts\python.exe"
set "ROOT_VENV=%ROOT%\.venv\Scripts\python.exe"
set "PYTHON_EXE=python"

if exist "%AI_VENV%" set "PYTHON_EXE=%AI_VENV%"
if not exist "%AI_VENV%" if exist "%ROOT_VENV%" set "PYTHON_EXE=%ROOT_VENV%"
if defined VIDEO_MAX_FRAMES set "MAX_FRAMES=%VIDEO_MAX_FRAMES%"

cd /d "%VIDEO_DIR%"
set "PORT=5002"
if not defined SAFEGUARD_DEVICE set "SAFEGUARD_DEVICE=cuda"

echo [SafeGuard] Video service console
echo Directory: %CD%
echo URL: http://localhost:5002/api/health
echo MAX_FRAMES=%MAX_FRAMES%
echo Python: %PYTHON_EXE%
echo Device preference: %SAFEGUARD_DEVICE%
echo.
"%PYTHON_EXE%" -u api/app.py

echo.
echo [EXIT] Video service stopped with code %ERRORLEVEL%.
pause
