@echo off
chcp 65001 >nul
cd /d %~dp0
set PY=C:\Users\W\.workbuddy\binaries\python\versions\3.13.12\python.exe
if not exist "%PY%" set PY=python
echo ======================================================
echo   Qwen Campus Chat - starting local server...
echo   Browser: http://localhost:8000
echo   Close this window to stop.
echo ======================================================
where msedge >nul 2>nul
if %errorlevel%==0 (start msedge http://localhost:8000) else (start "" http://localhost:8000)
"%PY%" chat-server.py
pause
