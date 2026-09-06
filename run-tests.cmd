@echo off
REM A-7. Windows entry point. Mirrors run-tests.sh so the documented profiles are
REM runnable without a bash shell. SUITE_ENV selects the configuration profile.
setlocal
if "%~1"=="" goto usage
if "%SUITE_ENV%"=="" set SUITE_ENV=local

if /I "%~1"=="smoke"      set TAGS=@smoke&                       goto run
if /I "%~1"=="functional" set TAGS=@functional and @critical&    goto run
if /I "%~1"=="contract"   set TAGS=@contract&                    goto run
if /I "%~1"=="full"       set TAGS=not @nightly&                 goto run
if /I "%~1"=="nightly"    set TAGS=@nightly&                     goto run
goto usage

:run
call mvn test -Pkafka -Dsuite.env=%SUITE_ENV% -Dcucumber.filter.tags="%TAGS%"
exit /b %ERRORLEVEL%

:usage
echo Unknown profile: %~1
echo.
echo Usage: run-tests.cmd ^<profile^>
echo   smoke        @smoke                     - 60s slice
echo   functional   @functional and @critical
echo   contract     @contract
echo   full         everything except @nightly
echo   nightly      @nightly
exit /b 2
