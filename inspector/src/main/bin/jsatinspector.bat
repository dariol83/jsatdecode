@echo off
setlocal DisableDelayedExpansion
set "APP_HOME=%~dp0.."
if defined JAVA_HOME (
    set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_COMMAND=java.exe"
)
"%JAVA_COMMAND%" --module-path "%APP_HOME%\lib" --module eu.dariolucia.jsatdecode.inspector/eu.dariolucia.jsatdecode.inspector.JSatInspector %*
exit /b %ERRORLEVEL%
