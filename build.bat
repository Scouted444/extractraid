@echo off
rem Builds ExtractRaid.jar. Needs JDK 21 and Maven on PATH.
rem   winget install EclipseAdoptium.Temurin.21.JDK
rem   winget install Apache.Maven
rem (open a new terminal after installing)

where mvn >nul 2>nul
if errorlevel 1 goto nomaven

call mvn -q clean package
if errorlevel 1 goto failed

echo.
echo Built: target\ExtractRaid.jar
echo Copy it into your server's plugins folder.
pause
exit /b 0

:nomaven
echo Maven was not found on PATH. Install it with:
echo   winget install Apache.Maven
echo then open a new terminal and run build.bat again.
pause
exit /b 1

:failed
echo.
echo BUILD FAILED. Scroll up for the compiler error.
pause
exit /b 1
