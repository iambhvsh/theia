@echo off
rem ==============================================================================
rem Theia Model & Engine Setup for Windows (Batch)
rem Fetches the Needle engine (android-arm64) and Whistle + Needle 3 models.
rem ==============================================================================
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%.."
set "ROOT_DIR=%CD%"

echo ==^> Setting up Theia on Windows...

rem Check for Python
where python >nul 2>&1
if %errorlevel% equ 0 (
    set "PYTHON=python"
) else (
    where py >nul 2>&1
    if %errorlevel% equ 0 (
        set "PYTHON=py -3"
    ) else (
        echo Error: Python 3 is required. Install Python from https://python.org and add to PATH.
        exit /b 1
    )
)

set "VENV_DIR=%ROOT_DIR%\.venv"
set "NEEDLE_EXE=%VENV_DIR%\Scripts\needle.exe"

where needle >nul 2>&1
if %errorlevel% equ 0 (
    set "NEEDLE=needle"
) else if exist "%NEEDLE_EXE%" (
    set "NEEDLE=%NEEDLE_EXE%"
) else (
    echo Creating virtual environment in %VENV_DIR%...
    %PYTHON% -m venv "%VENV_DIR%"
    if %errorlevel% neq 0 (
        echo Error: Failed to create virtual environment.
        exit /b 1
    )
    "%VENV_DIR%\Scripts\pip.exe" install --upgrade cactus-needle
    set "NEEDLE=%NEEDLE_EXE%"
)

echo ==^> Fetching engine and models via needle...
set "FETCH_DIR=%ROOT_DIR%\.fetch"
if exist "%FETCH_DIR%" rmdir /s /q "%FETCH_DIR%"
mkdir "%FETCH_DIR%"
cd /d "%FETCH_DIR%"

"%NEEDLE%" download android-arm64
"%NEEDLE%" download whistle
"%NEEDLE%" download needle3

cd /d "%ROOT_DIR%"

rem Ensure directories exist
mkdir "%ROOT_DIR%\app\src\main\cpp\engine" 2>nul
mkdir "%ROOT_DIR%\app\src\main\assets" 2>nul

rem Copy artifacts from .fetch
for /r "%FETCH_DIR%" %%f in (libneedle.a) do (
    copy /y "%%f" "%ROOT_DIR%\app\src\main\cpp\engine\" >nul
)
for /r "%FETCH_DIR%" %%f in (needle.h) do (
    copy /y "%%f" "%ROOT_DIR%\app\src\main\cpp\engine\" >nul
)
for /r "%FETCH_DIR%" %%f in (whistle.cact) do (
    copy /y "%%f" "%ROOT_DIR%\app\src\main\assets\" >nul
)
for /r "%FETCH_DIR%" %%f in (needle3.cact) do (
    copy /y "%%f" "%ROOT_DIR%\app\src\main\assets\" >nul
)

rem Check user cache if not in .fetch
if not exist "%ROOT_DIR%\app\src\main\assets\whistle.cact" (
    if exist "%USERPROFILE%\.cache" (
        for /r "%USERPROFILE%\.cache" %%f in (whistle.cact) do (
            copy /y "%%f" "%ROOT_DIR%\app\src\main\assets\" >nul
        )
    )
)
if not exist "%ROOT_DIR%\app\src\main\assets\needle3.cact" (
    if exist "%USERPROFILE%\.cache" (
        for /r "%USERPROFILE%\.cache" %%f in (needle3.cact) do (
            copy /y "%%f" "%ROOT_DIR%\app\src\main\assets\" >nul
        )
    )
)

echo ==^> Windows setup complete!
dir "%ROOT_DIR%\app\src\main\cpp\engine"
dir "%ROOT_DIR%\app\src\main\assets"
exit /b 0

