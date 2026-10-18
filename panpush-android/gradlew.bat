@rem Gradle wrapper launcher (Windows)
@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.\

set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

if not defined JAVA_HOME goto noJavaHome

set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo [ERROR] JAVA_HOME does not point to a valid JDK: %JAVA_HOME%
echo         This project needs JDK 17+ (AGP 8.13).
exit /b 1

:noJavaHome
echo [ERROR] JAVA_HOME is not set. This project needs JDK 17+ (AGP 8.13).
exit /b 1

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
