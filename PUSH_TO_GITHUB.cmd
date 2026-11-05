@echo off
setlocal
cd /d "%~dp0"

echo Pushing Ashke Shadek Android project to:
echo https://github.com/aamiqram/Ashke-Sadek---Android-project.git
echo.

git remote get-url origin >nul 2>&1
if errorlevel 1 git remote add origin https://github.com/aamiqram/Ashke-Sadek---Android-project.git
if errorlevel 1 goto :failed

git branch -M main
git push -u origin main
if errorlevel 1 goto :failed

echo.
echo Push completed successfully.
pause
exit /b 0

:failed
echo.
echo Push did not complete. Review the Git message above.
echo This script never force-pushes or overwrites remote history.
pause
exit /b 1
