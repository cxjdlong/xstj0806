@echo off
chcp 65001 >nul
cd /d "%~dp0"
set PY=
where py >nul 2>nul && set PY=py
if "%PY%"=="" (where python >nul 2>nul && set PY=python)
if "%PY%"=="" (
  echo [ERROR] Python 3 not found. Install Python 3 first.
  pause
  exit /b 1
)
echo Installing PyInstaller...
%PY% -m pip install --upgrade pyinstaller
echo Building contacts.exe ...
%PY% -m PyInstaller --noconfirm --onefile --console --name contacts --add-data "index.html;." contacts_local.py
echo.
echo Done. The EXE is: dist\contacts.exe
echo Copy dist\contacts.exe into this folder and double click it.
pause
