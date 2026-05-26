@echo off
title SafeGuard Docker Setup
setlocal

:: Get script directory from %~dp0 and strip trailing backslash
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

echo ============================================
echo    SafeGuard Docker One-Click Setup
echo ============================================
echo.

echo [1/5] Checking Docker...

where docker >nul 2>&1
if errorlevel 1 (
    echo [FAIL] Docker not found.
    echo Opening download page...
    start https://www.docker.com/products/docker-desktop/
    pause
    exit /b 1
)
echo   [OK]  Docker installed

echo [2/5] Checking Docker Engine...

docker ps >nul 2>&1
if errorlevel 1 (
    echo [FAIL] Docker Engine not running.
    echo   Start Docker Desktop and wait for green indicator.
    pause
    exit /b 1
)
echo   [OK]  Docker Engine running

echo [3/5] Checking WSL...

wsl --status >nul 2>&1
if errorlevel 1 (
    echo   [INFO] WSL not found (optional)
) else (
    echo   [OK]  WSL installed
)

echo [4/5] Setting up .env...

if not exist "%PROJECT_DIR%\.env" (
    if exist "%PROJECT_DIR%\.env.example" (
        copy "%PROJECT_DIR%\.env.example" "%PROJECT_DIR%\.env" >nul
        echo   [DONE] Created .env from .env.example
        echo.
        echo   Edit .env with your API keys, then save and close.
        echo   (Core features work without API keys)
        notepad "%PROJECT_DIR%\.env"
        echo.
        pause
    ) else (
        echo   [INFO] Creating default .env...
        >"%PROJECT_DIR%\.env" echo # SafeGuard Environment
        >>"%PROJECT_DIR%\.env" echo DB_PASSWORD=root123
        >>"%PROJECT_DIR%\.env" echo DEEPSEEK_API_KEY=
        >>"%PROJECT_DIR%\.env" echo SILICONFLOW_API_KEY=
        >>"%PROJECT_DIR%\.env" echo WECHAT_APPID=
        >>"%PROJECT_DIR%\.env" echo WECHAT_SECRET=
    )
) else (
    echo   [OK]  .env exists
)

echo.
echo  Select deployment mode:
echo    1. Core only  (MySQL + Qdrant + Backend, 2GB RAM)
echo    2. Core + AI  (+ Audio/Video detection, 4GB RAM)
echo    3. Full       (All services, 6GB RAM)
echo.
set /P MODE="Choose (1/2/3, default 1): "
if "%MODE%"=="" set MODE=1

echo.

echo [5/5] Deploying with Docker...

set PROFILE_ARGS=
if "%MODE%"=="2" set PROFILE_ARGS=--profile ai
if "%MODE%"=="3" set PROFILE_ARGS=--profile ai --profile web

echo Building and starting (first run may take 5-15 minutes)...

docker compose down 2>nul
docker compose %PROFILE_ARGS% up -d --build

if errorlevel 1 (
    echo.
    echo [FAIL] Deployment failed. Check errors above.
    pause
    exit /b 1
)

echo   [OK]  Waiting for services...
timeout /t 8 /nobreak >nul

echo.
echo ============================================
echo    Deployment Complete!
echo ============================================
echo.
echo   Backend API:     http://localhost:8080
echo   Admin (Thymeleaf): http://localhost:8080/admin
if "%MODE%"=="3" echo   SPA Frontend:   http://localhost:8085
if "%MODE%"=="2" echo   Audio Detection: http://localhost:5000
if "%MODE%"=="2" echo   Video Detection: http://localhost:5002
echo   MySQL:           localhost:3307 (root/%DB_PASSWORD:root123%)
echo   Qdrant:          localhost:6333
echo.
echo   Commands:
echo     docker compose logs -f backend       View backend logs
echo     docker compose down                  Stop all services
echo.
pause
exit /b 0
