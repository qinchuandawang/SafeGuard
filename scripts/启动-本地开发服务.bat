@echo off
chcp 65001 >nul
title SafeGuard Dev Launcher
setlocal enabledelayedexpansion

:: Get project root (parent of scripts/)
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "PROJECT_DIR=%PROJECT_DIR%\.."
pushd "%PROJECT_DIR%" 2>nul || (echo [ERROR] Cannot find project directory& pause& exit /b 1)
set "PROJECT_DIR=%CD%"

echo ============================================
echo    SafeGuard - One-Click Dev Launcher
echo ============================================
echo.

:: =============================================
:: Step 1 - Check prerequisites
:: =============================================
echo [1/6] Checking prerequisites...

set MISSING=0

where java >nul 2>&1
if errorlevel 1 (echo   [FAIL] Java not found& set MISSING=1) else (echo   [OK]  Java)

where node >nul 2>&1
if errorlevel 1 (echo   [FAIL] Node not found& set MISSING=1) else (echo   [OK]  Node)

where python >nul 2>&1
if errorlevel 1 (echo   [FAIL] Python not found& set MISSING=1) else (echo   [OK]  Python)

where docker >nul 2>&1
if errorlevel 1 (echo   [FAIL] Docker not found& set MISSING=1) else (echo   [OK]  Docker)

:: Check if MySQL is running on 3306
netstat -ano 2>nul | findstr ":3306.*LISTENING" >nul
if errorlevel 1 (
    echo   [WARN] MySQL not detected on port 3306 - backend will fail to start
) else (
    echo   [OK]  MySQL (port 3306)
)

:: Check if .env exists
if not exist "%PROJECT_DIR%\.env" (
    echo   [WARN] .env file missing, copying from .env.example
    copy "%PROJECT_DIR%\.env.example" "%PROJECT_DIR%\.env" >nul
    echo   [WARN] Please edit .env and fill in DB_PASSWORD and API keys
)

if "%MISSING%"=="1" (
    echo.
    echo [ERROR] Please install missing tools and try again.
    pause
    exit /b 1
)

echo.

:: =============================================
:: Step 2 - Read DB_PASSWORD from .env
:: =============================================
echo [2/6] Reading .env configuration...
set "DB_PASS="
for /f "usebackq tokens=1,2 delims==" %%a in ("%PROJECT_DIR%\.env") do (
    if "%%a"=="DB_PASSWORD" set "DB_PASS=%%b"
)
if "%DB_PASS%"=="" (
    echo   [WARN] DB_PASSWORD not set in .env, using empty password
) else (
    echo   [OK]  DB_PASSWORD loaded
)
echo.

:: =============================================
:: Step 3 - Start Docker services (Qdrant)
:: =============================================
echo [3/6] Starting Docker services (Qdrant)...

:: Try to start Docker Desktop if not running
docker info >nul 2>&1
if errorlevel 1 (
    echo   Docker Desktop not running, launching...
    start "" "Docker Desktop" 2>nul
    echo   Waiting for Docker Engine to be ready...
    call :wait_docker
) else (
    echo   Docker Engine is running
)

docker compose up -d qdrant 2>nul
if errorlevel 1 (
    echo   [FAIL] Qdrant failed to start
    pause
    exit /b 1
)
echo   [OK]  Qdrant started
echo.
goto :step4

:wait_docker
set /a RETRY=0
:retry_loop
timeout /t 3 /nobreak >nul
docker info >nul 2>&1
if not errorlevel 1 exit /b 0
set /a RETRY+=1
if %RETRY% geq 20 (
    echo   [FAIL] Docker did not start after 60s. Please check Docker Desktop.
    pause
    exit /b 1
)
echo   Waiting... (%RETRY%/20)
goto :retry_loop

:step4
:: =============================================
:: Step 4 - Python dependencies
:: =============================================
echo [4/6] Checking Python dependencies...
:: Audio deps
pip show flask >nul 2>&1
if errorlevel 1 (
    echo   Installing audio deps...
    pip install -r "%PROJECT_DIR%\ai-services\audio\requirements.txt" -q
)
:: Video deps
pip show torchvision >nul 2>&1
if errorlevel 1 (
    echo   Installing torchvision...
    pip install torchvision -q
)
pip show dlib-bin >nul 2>&1
if errorlevel 1 (
    echo   Installing dlib (pre-built)...
    pip install dlib-bin -q
)
echo   [OK]  Python deps ready
echo.

:: =============================================
:: Step 5 - AI Services (audio + video)
:: =============================================
echo [5/6] Starting AI detection services...
start "SafeGuard-AI" cmd /c "cd /d "%PROJECT_DIR%\ai-services" && echo ================================== && echo   Audio Detection :5000 && echo   Video Detection :5002 && echo ================================== && python run.py"
echo   [OK]  AI Services launched (ports 5000, 5002)
echo.

:: =============================================
:: Step 6 - Backend + Admin Panel
:: =============================================
echo [6/6] Starting backend and admin panel...

:: Backend (with DB_PASSWORD from .env)
if exist "%PROJECT_DIR%\backend\mvnw.cmd" (
    start "SafeGuard-Backend" cmd /c "cd /d "%PROJECT_DIR%\backend" && set DB_PASSWORD=%DB_PASS% && echo Starting SafeGuard Backend on :8080... && .\mvnw spring-boot:run"
) else (
    start "SafeGuard-Backend" cmd /c "cd /d "%PROJECT_DIR%\backend" && set DB_PASSWORD=%DB_PASS% && echo Starting SafeGuard Backend on :8080... && mvn spring-boot:run"
)

timeout /t 5 /nobreak >nul

:: Admin Panel (Vite)
if not exist "%PROJECT_DIR%\web-admin\node_modules" (
    echo   Installing Node deps for admin panel...
    cd /d "%PROJECT_DIR%\web-admin"
    call npm install
    cd /d "%PROJECT_DIR%"
)
start "SafeGuard-Admin" cmd /c "cd /d "%PROJECT_DIR%\web-admin" && echo Starting Admin Panel on :5173... && npx vite --host"

echo.
echo ============================================
echo     All services launched!
echo ============================================
echo.
echo  Service              Port      Window
echo  ------------------------------------------
echo  Qdrant (Docker)     6333      (background)
echo  AI Audio Detection  :5000     SafeGuard-AI
echo  AI Video Detection  :5002     SafeGuard-AI
echo  Backend API         :8080     SafeGuard-Backend
echo  Admin Panel         :5173     SafeGuard-Admin
echo.
echo  Wait ~10s for backend to fully start, then:
echo    Backend health: http://localhost:8080/api/health
echo    Admin panel:    http://localhost:5173
echo.
echo  Close a service by closing its window.
echo.
pause
exit /b 0
