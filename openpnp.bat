@echo off
setlocal

rem The jar targets 64-bit Java 25; an older java would fail with UnsupportedClassVersionError.
set required_java=25
set java_spec=
for /f "tokens=2 delims==" %%v in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.specification.version"') do set java_spec=%%v
if not defined java_spec (
    set java_found=no java on the PATH, or one too old to report its version
    goto too_old
)
set java_spec=%java_spec: =%
set java_found=%java_spec%
if "%java_spec:~0,2%"=="1." goto too_old
for /f "delims=." %%m in ("%java_spec%") do set java_major=%%m
if %java_major% LSS %required_java% goto too_old

java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar "%~dp0target\openpnp-gui-0.0.1-alpha-SNAPSHOT.jar"
exit /b

:too_old
echo OpenPnP requires Java %required_java% or later; found %java_found%.
rem Keep the message on screen when started from Explorer, which closes the window on exit.
echo %cmdcmdline% | find /i "/c" >nul && pause
exit /b 1


