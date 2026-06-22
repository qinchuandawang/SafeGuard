@echo off
setlocal
title SafeGuard-Java-Backend

set "ROOT=%~dp0.."
set "BACKEND_DIR=%ROOT%\backend"
set "MAVEN_EXE=D:\apache-maven-3.9.13\bin\mvn.cmd"

cd /d "%ROOT%"

if exist "%ROOT%\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ROOT%\.env") do (
        if not "%%a"=="" set "%%a=%%b"
    )
)

cd /d "%BACKEND_DIR%"

echo [SafeGuard] Java backend console
echo Directory: %CD%
echo URL: http://localhost:8080/api/health
echo Maven: %MAVEN_EXE%
echo LLM=%LLM_API_KEY:~0,8%... SiliconFlow=%SILICONFLOW_API_KEY:~0,8%...
echo.
"%MAVEN_EXE%" spring-boot:run

echo.
echo [EXIT] Java backend stopped with code %ERRORLEVEL%.
pause
