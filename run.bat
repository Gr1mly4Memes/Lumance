@echo off
REM Lumance server - pass extra server args after this script, e.g. run.bat nogui
for %%f in (lumance-*-server.jar) do set JAR=%%f
if not defined JAR (
    echo No lumance-*-server.jar found. Copy the bootstrap jar next to this script first.
    pause
    exit /b 1
)
java -Xmx4G -jar "%JAR%" nogui %*
pause
