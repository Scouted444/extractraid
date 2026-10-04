@echo off
title Extraction Server
rem Put the Paper jar (renamed to paper.jar) in this folder. Needs Java 21.
rem Change 4G to however much RAM you can give the server.
java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -jar paper.jar nogui
echo.
echo Server stopped.
pause
