@echo off
setlocal EnableDelayedExpansion
title SafeGuard Demo Launcher

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%backend"
set "AI_DIR=%ROOT%ai-services"
set "AUDIO_DIR=%AI_DIR%\audio"
set "VIDEO_DIR=%AI_DIR%\video"
set "WEB_DIR=%ROOT%web-admin"
set "SCRIPT_DIR=%ROOT%scripts"
set "AI_VENV=%AI_DIR%\.venv\Scripts\python.exe"
set "ROOT_VENV=%ROOT%.venv\Scripts\python.exe"
set "MAVEN_EXE=D:\apache-maven-3.9.13\bin\mvn.cmd"
set "NPM_EXE=npm.cmd"
if not defined SAFEGUARD_DEVICE set "SAFEGUARD_DEVICE=cuda"

cd /d "%ROOT%"

if exist "%ROOT%.env" (
    echo [ENV] Loading %ROOT%.env
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ROOT%.env") do (
        if not "%%a"=="" set "%%a=%%b"
    )
) else (
    echo [WARN] .env not found, services will use built-in defaults.
)

echo ========================================
echo SafeGuard Demo Launcher
echo ========================================
echo.
echo Project: %ROOT%
echo Java Backend: http://localhost:8080
echo Audio Service: http://localhost:5000
echo Video Service: http://localhost:5002
echo Web Admin: http://localhost:5173
echo AI Device Preference: %SAFEGUARD_DEVICE%
echo.

if not exist "%BACKEND_DIR%\pom.xml" (
    echo [ERROR] Missing backend\pom.xml
    pause
    exit /b 1
)

if not exist "%MAVEN_EXE%" (
    echo [ERROR] Missing Maven: %MAVEN_EXE%
    pause
    exit /b 1
)

if not exist "%AUDIO_DIR%\app.py" (
    echo [ERROR] Missing ai-services\audio\app.py
    pause
    exit /b 1
)

if not exist "%VIDEO_DIR%\api\app.py" (
    echo [ERROR] Missing ai-services\video\api\app.py
    pause
    exit /b 1
)

if not exist "%SCRIPT_DIR%\start-audio-service.cmd" (
    echo [ERROR] Missing scripts\start-audio-service.cmd
    pause
    exit /b 1
)

if not exist "%SCRIPT_DIR%\start-java-backend.cmd" (
    echo [ERROR] Missing scripts\start-java-backend.cmd
    pause
    exit /b 1
)

if not exist "%SCRIPT_DIR%\start-video-service.cmd" (
    echo [ERROR] Missing scripts\start-video-service.cmd
    pause
    exit /b 1
)

if not exist "%WEB_DIR%\package.json" (
    echo [ERROR] Missing web-admin\package.json
    pause
    exit /b 1
)

set "PYTHON_EXE=python"
if exist "%AI_VENV%" set "PYTHON_EXE=%AI_VENV%"
if not exist "%AI_VENV%" if exist "%ROOT_VENV%" set "PYTHON_EXE=%ROOT_VENV%"
if defined VIDEO_MAX_FRAMES set "MAX_FRAMES=%VIDEO_MAX_FRAMES%"

where %NPM_EXE% >nul 2>nul
if not "%ERRORLEVEL%"=="0" (
    echo [ERROR] npm.cmd not found in PATH
    pause
    exit /b 1
)

call :IsPortOpen 8080 BACKEND_RUNNING
call :IsPortOpen 5000 AUDIO_RUNNING
call :IsPortOpen 5002 VIDEO_RUNNING
call :IsPortOpen 5173 WEB_RUNNING

if "%BACKEND_RUNNING%"=="1" (
    echo [WARN] Java backend is already running on port 8080, so no new backend console window would be opened.
    call :PrintPortOwner 8080
    set /p "RESTART_BACKEND=Restart Java backend in a visible console window? (Y/N): "
    if /i "!RESTART_BACKEND!"=="Y" (
        call :KillPortOwner 8080
        powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep -Seconds 2" >nul 2>nul
        echo [START] Opening Java backend window...
        start "SafeGuard-Java-Backend" /D "%ROOT%" cmd /d /k call "%SCRIPT_DIR%\start-java-backend.cmd"
    ) else (
        echo [INFO] Keeping existing Java backend process.
    )
) else (
    echo [START] Opening Java backend window...
    start "SafeGuard-Java-Backend" /D "%ROOT%" cmd /d /k call "%SCRIPT_DIR%\start-java-backend.cmd"
)

if "%AUDIO_RUNNING%"=="1" (
    echo [OK] Audio service is already running on port 5000.
    call :PrintPortOwner 5000
) else (
    echo [START] Opening Audio service window...
    echo [INFO] Python executable: %PYTHON_EXE%
    start "SafeGuard-Audio-Service" /D "%ROOT%" cmd /d /k call "%SCRIPT_DIR%\start-audio-service.cmd"
)

if "%VIDEO_RUNNING%"=="1" (
    echo [OK] Video service is already running on port 5002.
    call :PrintPortOwner 5002
) else (
    echo [START] Opening Video service window...
    echo [INFO] Python executable: %PYTHON_EXE%
    start "SafeGuard-Video-Service" /D "%ROOT%" cmd /d /k call "%SCRIPT_DIR%\start-video-service.cmd"
)

if "%WEB_RUNNING%"=="1" (
    echo [OK] Web admin is already running on port 5173.
    call :PrintPortOwner 5173
) else (
    echo [START] Opening Web admin window...
    start "SafeGuard-Web-Admin" /D "%WEB_DIR%" cmd /d /k "echo [SafeGuard] Web admin console && echo Directory: %WEB_DIR% && echo URL: http://localhost:5173 && echo. && %NPM_EXE% run dev"
)

echo.
echo ========================================
echo Launcher finished.
echo Keep the opened service windows running during the demo.
echo.
echo Java Backend: http://localhost:8080
echo Audio Service: http://localhost:5000
echo Video Service: http://localhost:5002
echo Web Admin: http://localhost:5173
echo ========================================
echo.
echo Waiting for services and warming up local models...
echo [INFO] Audio / video health checks will preload local models before the demo.
call :WarmUrl "Java backend" "http://localhost:8080/api/health" 30 5
call :WarmUrl "Audio model" "http://localhost:5000/health" 40 60
call :WarmUrl "Video model" "http://localhost:5002/api/health" 40 120
call :WarmUrl "Web admin" "http://localhost:5173" 20 5
echo.
pause
exit /b 0

:IsPortOpen
set "%~2=0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "if ((Get-NetTCPConnection -State Listen -LocalPort %~1 -ErrorAction SilentlyContinue) -ne $null) { exit 0 } else { exit 1 }" >nul 2>nul
if "%ERRORLEVEL%"=="0" set "%~2=1"
exit /b 0

:PrintPortOwner
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c = Get-NetTCPConnection -State Listen -LocalPort %~1 -ErrorAction SilentlyContinue | Select-Object -First 1; if ($null -ne $c) { $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue; if ($null -ne $p) { Write-Host ('     PID=' + $p.Id + ' Process=' + $p.ProcessName + ' Path=' + $p.Path) } else { Write-Host ('     PID=' + $c.OwningProcess) } }"
exit /b 0

:KillPortOwner
powershell -NoProfile -ExecutionPolicy Bypass -Command "$items = Get-NetTCPConnection -State Listen -LocalPort %~1 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; foreach ($ownerPid in $items) { if ($ownerPid -gt 0) { Write-Host ('     Stopping PID=' + $ownerPid); Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue } }"
exit /b 0

:CheckUrl
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -UseBasicParsing -Uri '%~2' -TimeoutSec 3; if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>nul
if "%ERRORLEVEL%"=="0" (
    echo [OK] %~1 health check passed: %~2
) else (
    echo [WARN] %~1 health check failed: %~2
)
exit /b 0

:WarmUrl
set "WARM_NAME=%~1"
set "WARM_URL=%~2"
set "WARM_RETRIES=%~3"
set "WARM_TIMEOUT=%~4"
if "%WARM_RETRIES%"=="" set "WARM_RETRIES=20"
if "%WARM_TIMEOUT%"=="" set "WARM_TIMEOUT=10"
echo [WARMUP] %WARM_NAME% - %WARM_URL%
for /l %%i in (1,1,%WARM_RETRIES%) do (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -UseBasicParsing -Uri '%WARM_URL%' -TimeoutSec %WARM_TIMEOUT%; if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>nul
    if "!ERRORLEVEL!"=="0" (
        echo [OK] %WARM_NAME% ready and warmed.
        exit /b 0
    )
    echo [WAIT] %WARM_NAME% not ready yet, retry %%i/%WARM_RETRIES%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep -Seconds 2" >nul 2>nul
)
echo [WARN] %WARM_NAME% warmup failed: %WARM_URL%
exit /b 1
