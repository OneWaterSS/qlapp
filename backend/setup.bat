@echo off
rem One-click backend setup for qlapp (no non-ASCII on purpose: cmd breaks on UTF-8)
chcp 65001 >nul
cd /d "%~dp0"
echo Running backend setup...
echo.
node setup.mjs
echo.
echo ----------------------------------------
echo If it says you need to login, run this first:
echo     npx wrangler login
echo Then run this file again.
echo ----------------------------------------
echo.
pause
