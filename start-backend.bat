@echo off
rem Starts the Elaashop / Elaatech backend on http://localhost:8080.
rem Double-click this file, or type start-backend in a terminal opened in this folder. Press Ctrl+C to stop it.
cd /d "%~dp0backend"
call mvn spring-boot:run
pause
