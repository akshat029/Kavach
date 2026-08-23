@rem
@rem Gradle start script for Windows.
@rem
@rem Slim counterpart to ./gradlew. gradle-wrapper.jar is a binary and is not committed
@rem to this repository, so this script checks for it and prints an actionable message
@rem instead of failing with a ClassNotFoundException.
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem  Kavach Gradle wrapper
@rem ##########################################################################

setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

@rem ---------------------------------------------------------------- find Java
if defined JAVA_HOME goto findJavaFromJavaHome

where java >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set JAVA_EXE=java.exe
    goto checkWrapper
)

echo.
echo ERROR: No Java found.
echo.
echo Kavach needs JDK 17. Install it and either put java on your PATH or set JAVA_HOME.
echo.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto checkWrapper

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Set JAVA_HOME to a JDK 17 installation, or unset it to use the java on your PATH.
echo.
goto fail

@rem ------------------------------------------------------- find the wrapper jar
:checkWrapper
if exist "%WRAPPER_JAR%" goto execute

where gradle >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo gradle-wrapper.jar is missing. Regenerating it with your system Gradle...
    pushd "%APP_HOME%"
    call gradle wrapper --gradle-version 8.9 --distribution-type bin
    popd
    if exist "%WRAPPER_JAR%" goto execute
)

echo.
echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing.
echo.
echo This repository is text-only, so the wrapper binary is not committed. Pick one:
echo.
echo   1. Open the project in Android Studio. It will set the wrapper up for you.
echo   2. Install Gradle 8.9 and run:  gradle wrapper --gradle-version 8.9
echo   3. Let CI build it: push to GitHub and download the APK from the Build workflow.
echo.
goto fail

@rem ----------------------------------------------------------------- launch
:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
if not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
endlocal
