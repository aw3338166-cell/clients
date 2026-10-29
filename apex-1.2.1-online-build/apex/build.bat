@echo off
rem One-step build. Needs JDK 21 and Gradle 9.x on PATH (only for the first run, to create the wrapper).
if not exist gradlew.bat call gradle wrapper
call gradlew.bat build
echo.
echo Done: build\libs\apex-1.2.1.jar
pause
