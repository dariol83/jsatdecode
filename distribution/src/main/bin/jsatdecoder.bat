@echo off
setlocal DisableDelayedExpansion
set "APP_HOME=%~dp0.."
if defined JAVA_HOME (
    set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_COMMAND=java.exe"
)
"%JAVA_COMMAND%" --module-path "%APP_HOME%\lib" --add-modules ALL-MODULE-PATH --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.cli.Main %*
exit /b %ERRORLEVEL%
