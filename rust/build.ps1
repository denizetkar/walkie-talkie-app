# --- CONFIGURATION ---
$NDK_VERSION = "29.0.14206865"
$CMAKE_VERSION = "4.1.2"

$ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$NDK_PATH = "$ANDROID_SDK_ROOT\ndk\$NDK_VERSION"
$CMAKE_BIN = "$ANDROID_SDK_ROOT\cmake\$CMAKE_VERSION\bin"

# --- VALIDATION ---
if (-not (Test-Path $NDK_PATH)) { Write-Error "NDK not found at $NDK_PATH"; exit 1 }
if (-not (Test-Path "$CMAKE_BIN\ninja.exe")) { Write-Error "Ninja not found at $CMAKE_BIN"; exit 1 }

# --- SETUP ENVIRONMENT ---
Write-Host "Setting up build environment..." -ForegroundColor Cyan

$env:ANDROID_NDK_HOME = $NDK_PATH
$env:NDK_HOME = $NDK_PATH

# --- GENERATE WRAPPER TOOLCHAIN ---
$WrapperFile = "$PSScriptRoot\android_wrapper.cmake"
$RealToolchainPath = "$NDK_PATH\build\cmake\android.toolchain.cmake".Replace("\", "/")

# CHANGE: Set STL to c++_shared (The standard for Android)
$WrapperContent = @"
set(ANDROID_ABI "arm64-v8a" CACHE STRING "ABI" FORCE)
set(ANDROID_PLATFORM "android-30" CACHE STRING "Platform" FORCE)
set(ANDROID_STL "c++_shared" CACHE STRING "STL" FORCE)
include("$RealToolchainPath")
"@

Set-Content -Path $WrapperFile -Value $WrapperContent

# --- CONFIGURE CMAKE ---
$env:CMAKE_TOOLCHAIN_FILE = $WrapperFile
$env:CMAKE_GENERATOR = "Ninja"
$env:CMAKE_MAKE_PROGRAM = "$CMAKE_BIN\ninja.exe"
$env:PATH = "$CMAKE_BIN;" + $env:PATH

# --- CLEAN & BUILD ---
Write-Host "Cleaning previous builds..." -ForegroundColor Yellow
# cargo clean

Write-Host "Building for Android (arm64-v8a) API 30..." -ForegroundColor Green

cargo ndk -t arm64-v8a --platform 30 -o ../app/src/main/jniLibs build --release

if ($LASTEXITCODE -eq 0) {
    Write-Host "Rust Build Success!" -ForegroundColor Cyan

    # ... (Keep the libc++_shared.so copy logic here) ...
    # Path to libc++ in NDK r21+ (LLVM toolchain)
    $StlSource = "$NDK_PATH\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so"
    $StlDest = "..\app\src\main\jniLibs\arm64-v8a\libc++_shared.so"

    if (Test-Path $StlSource) {
        Copy-Item -Path $StlSource -Destination $StlDest -Force
        Write-Host "Copied libc++_shared.so to jniLibs." -ForegroundColor Green
    } else {
        Write-Error "CRITICAL: Could not find libc++_shared.so at $StlSource"
        # Try fallback path for older NDK layouts just in case
        $StlSourceOld = "$NDK_PATH\sources\cxx-stl\llvm-libc++\libs\arm64-v8a\libc++_shared.so"
        if (Test-Path $StlSourceOld) {
             Copy-Item -Path $StlSourceOld -Destination $StlDest -Force
             Write-Host "Copied libc++_shared.so (Legacy Path) to jniLibs." -ForegroundColor Green
        } else {
             exit 1
        }
    }

    # Cleanup wrapper
    Remove-Item $WrapperFile -ErrorAction SilentlyContinue
} else {
    Write-Error "Build Failed."
    exit 1
}