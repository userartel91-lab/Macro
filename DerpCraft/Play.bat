@echo off
title DerpCraft
cd /d "%~dp0"
if not exist "build\install\DerpCraft\bin\DerpCraft.bat" (
  echo The game is not built yet. Run build.bat first.
  pause
  exit /b 1
)
if not exist game mkdir game
cd game
call "..\build\install\DerpCraft\bin\DerpCraft.bat" %*
if errorlevel 1 (
  echo.
  echo The game exited with an error. See the log above and the game\crash-reports folder.
  pause
)
