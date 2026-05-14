@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Creating virtual environment in tunnel\.venv ...
  python -m venv .venv
  if errorlevel 1 exit /b 1
  call .venv\Scripts\pip.exe install -r requirements.txt
  if errorlevel 1 exit /b 1
)
".venv\Scripts\python.exe" server.py %*
