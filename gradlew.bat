@echo off
setlocal
set VERSION=8.11.1
set DIST=gradle-%VERSION%-bin
if not "%GRADLE_USER_HOME%"=="" (set GH=%GRADLE_USER_HOME%) else (set GH=%USERPROFILE%\.gradle)
set GD=%GH%\wrapper\dists\%DIST%\local\gradle-%VERSION%
if exist "%GD%\bin\gradle.bat" goto run
set ZIP=%TEMP%\%DIST%.zip
echo Gradle %VERSION% absent : telechargement du compilateur...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/%DIST%.zip' -OutFile '%ZIP%'"
if errorlevel 1 exit /b 1
if not exist "%GH%\wrapper\dists\%DIST%\local" mkdir "%GH%\wrapper\dists\%DIST%\local"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%GH%\wrapper\dists\%DIST%\local'"
if errorlevel 1 exit /b 1
del "%ZIP%"
:run
call "%GD%\bin\gradle.bat" %*
endlocal
