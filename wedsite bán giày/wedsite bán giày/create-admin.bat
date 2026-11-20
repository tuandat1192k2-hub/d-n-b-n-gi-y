@echo off
setlocal
cd /d "%~dp0"

set "SWOOSH_ROOT=%CD%"
set "SWOOSH_JDBC_URL=jdbc:sqlserver://localhost:1433;databaseName=BeginnerRunningShoesDB;encrypt=true;trustServerCertificate=true;integratedSecurity=true;authenticationScheme=NativeAuthentication"
set "APP_JAR=%CD%\java-backend\target\swoosh-store-1.0.0.jar"
set "SQL_AUTH_DIR=%CD%\java-backend\target\native"

where java >nul 2>&1
if errorlevel 1 (
    echo Java was not found. Run start-store.bat once, then try again.
    pause
    exit /b 1
)

if not exist "%APP_JAR%" (
    echo The Java application JAR is missing. Run start-store.bat first.
    pause
    exit /b 1
)

if not exist "%SQL_AUTH_DIR%\mssql-jdbc_auth-12.8.1.x64.dll" (
    echo SQL Server authentication is not prepared. Run start-store.bat first.
    pause
    exit /b 1
)

java -Djava.library.path="%SQL_AUTH_DIR%" -jar "%APP_JAR%" create-admin
if errorlevel 1 (
    echo Admin creation failed. Check the message above and try again.
    pause
    exit /b 1
)

echo You can now sign in at http://127.0.0.1:8081/#login
pause
