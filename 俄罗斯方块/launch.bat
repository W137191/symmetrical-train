@echo off
REM Quantum Tetris: Void Protocol - launcher (forces a browser)
set "HTML=%~dp0index.html"

REM 1) Chrome
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" (
  start "" "%ProgramFiles%\Google\Chrome\Application\chrome.exe" "%HTML%"
  goto :done
)
if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" (
  start "" "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" "%HTML%"
  goto :done
)
REM 2) Edge
if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (
  start "" "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" "%HTML%"
  goto :done
)
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (
  start "" "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" "%HTML%"
  goto :done
)
REM 3) Firefox
if exist "%ProgramFiles%\Mozilla Firefox\firefox.exe" (
  start "" "%ProgramFiles%\Mozilla Firefox\firefox.exe" "%HTML%"
  goto :done
)
REM 4) fallback to system default handler
start "" "%HTML%"

:done
