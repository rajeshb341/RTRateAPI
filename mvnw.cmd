@echo off
rem Maven Wrapper Script for Windows

set MAVEN_VERSION=3.9.6
set MAVEN_DISTRIBUTION_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip
set MAVEN_WRAPPER_JAR_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar

set WRAPPER_DIR=%~dp0.mvn\wrapper
set WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar
set WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties

rem Download wrapper jar if not present
if not exist "%WRAPPER_JAR%" (
    echo Downloading Maven Wrapper...
    if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
    curl -fsSL -o "%WRAPPER_JAR%" "%MAVEN_WRAPPER_JAR_URL%"
    if errorlevel 1 (
        echo Failed to download Maven Wrapper
        exit /b 1
    )
)

rem Execute Maven Wrapper
java -jar "%WRAPPER_JAR%" %*