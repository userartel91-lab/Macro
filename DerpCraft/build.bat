@echo off
title DerpCraft - build
cd /d "%~dp0"
echo ============================================
echo   DerpCraft build
echo ============================================
where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo Java not found. Install JDK 21 ^(Temurin^): https://adoptium.net/temurin/releases/?version=21
  echo During install enable "Set JAVA_HOME" and "Add to PATH", then run build.bat again.
  pause
  exit /b 1
)
java -version
echo.
echo Downloading Gradle + LWJGL on first run (needs internet), then compiling...
call gradlew.bat installDist --console=plain
if errorlevel 1 (
  echo.
  echo BUILD FAILED. Make sure JDK 21 or newer is installed ^(java -version above^).
  pause
  exit /b 1
)
if not exist game mkdir game
echo.
echo ============================================
echo   Done! Start the game with Play.bat
echo ============================================
pause
