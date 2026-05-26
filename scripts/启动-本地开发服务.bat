@echo off
title SafeGuard Dev Launcher
setlocal

:: Reset error state and get script directory
ver >nul
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set MISSING=0

echo ============================================
echo    SafeGuard - One-Click Dev Launcher
echo    All services start in separate windows
echo ============================================
echo.

echo [1/5] Checking prerequisites...

where java >nul 2>&1
if errorlevel 1 (
    echo   [FAIL] Java not found
    set MISSING=1
) else (
    echo   [OK]  Java found
)

where node >nul 2>&1
if errorlevel 1 (
    echo   [FAIL] Node not found
    set MISSING=1
) else (
    echo   [OK]  Node found
)

where python >nul 2>&1
if errorlevel 1 (
    echo   [FAIL] Python not found
    set MISSING=1
) else (
    echo   [OK]  Python found
)

if "%MISSING%"=="1" (
    echo.
    echo [ERROR] Please install missing tools and try again.
    pause
    exit /b 1
)

echo.

echo [2/5] Installing Python dependencies...
if exist "%PROJECT_DIR%\ai-services\audio\requirements.txt" (
    pip install -r "%PROJECT_DIR%\ai-services\audio\requirements.txt" -q 2>nul
    echo   [OK]  Python deps done
) else (
    echo   [SKIP] No requirements.txt
)

echo.

echo [3/5] Installing Node dependencies...
if exist "%PROJECT_DIR%\web-admin\package.json" (
    if not exist "%PROJECT_DIR%\web-admin\node_modules" (
        cd "%PROJECT_DIR%\web-admin"
        call npm install
        cd "%PROJECT_DIR%"
        echo   [OK]  Node deps done
    ) else (
        echo   [SKIP] node_modules exists
    )
)

echo.

echo [4/5] Checking backend build...
if exist "%PROJECT_DIR%\backend\target\*.jar" (
    echo   [OK]  Backend already compiled
) else (
    echo   [INFO] Backend will build on first launch
)

echo.

echo [5/5] Starting services...
echo.

echo   Starting Audio Detection (port 5000)...
start "SafeGuard-Audio" /d "%PROJECT_DIR%\ai-services\audio" cmd /c "title SafeGuard-Audio && echo [Audio] Starting on port 5000... && python src\app.py"

timeout /t 2 /nobreak >nul

echo   Starting Backend (port 8080)...
if exist "%PROJECT_DIR%\backend\mvnw.cmd" (
    start "SafeGuard-Backend" /d "%PROJECT_DIR%\backend" cmd /c "title SafeGuard-Backend && .\mvnw spring-boot:run"
) else (
    start "SafeGuard-Backend" /d "%PROJECT_DIR%\backend" cmd /c "title SafeGuard-Backend && mvn spring-boot:run"
)

timeout /t 5 /nobreak >nul

echo   Starting Admin Panel (port 5173)...
start "SafeGuard-Admin" /d "%PROJECT_DIR%\web-admin" cmd /c "title SafeGuard-Admin && npm run dev"

echo.
echo ============================================
echo     All services launched!
echo ============================================
echo.
echo  Service              Port      Window
echo  ------------------------------------------
echo  Audio Detection     :5000     SafeGuard-Audio
echo  Backend API         :8080     SafeGuard-Backend
echo  Admin Panel         :5173     SafeGuard-Admin
echo.
echo  Close a service by closing its window.
echo.
pause
exit /b 0
