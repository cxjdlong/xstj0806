@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Starting Contacts local server...
where python >nul 2>nul && goto :runpy
where py >nul 2>nul && goto :runpy2
echo.
echo [ERROR] Python 3 not found.
echo Install Python 3 (python.org) OR use the packaged contacts.exe
echo.
pause
exit /b 1
:runpy
python contacts_local.py
goto :end
:runpy2
py contacts_local.py
goto :end
:end
pause
