@echo off
setlocal
if "%JAVA_HOME%"=="" (
    echo JAVA_HOME is not set. Please point it to JDK 17 or JDK 21 first.
    exit /b 1
)
call gradlew.bat :app:assembleDebug
endlocal
