# --- HELPER: CMake & Cargo require forward slashes ---
function Normalize-Path {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $Path }
    # Resolve to absolute path, then convert slashes
    $AbsPath =[System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PWD.Path, $Path))
    return $AbsPath.Replace('\', '/')
}

# --- CONFIGURATION (Zero-Redundancy SSOT) ---
$PSScriptRootNorm = Normalize-Path $PSScriptRoot
$EnvFilePath = Normalize-Path "$PSScriptRootNorm/../build.env"

if (Test-Path $EnvFilePath) {
    # Read file, ignore comments/empty lines, and inject into $env:
    Get-Content $EnvFilePath | Where-Object { $_ -match '^\s*[^#]' -and $_ -match '=' } | ForEach-Object {
        $name, $value = $_.Split('=', 2)
        Set-Item -Path "env:$($name.Trim())" -Value $value.Trim()
    }
} else {
    Write-Error "CRITICAL: build.env file not found at $EnvFilePath"
    exit 1
}

$NDK_VERSION = $env:NDK_VERSION
$CMAKE_VERSION = $env:CMAKE_VERSION
$RUST_TARGET = $env:RUST_TARGET

$ANDROID_SDK_ROOT = Normalize-Path ($env:ANDROID_HOME ?? (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
$NDK_PATH = Normalize-Path "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
$CMAKE_BIN = Normalize-Path "$ANDROID_SDK_ROOT/cmake/$CMAKE_VERSION/bin"

$NINJA_EXE = if ($IsWindows) { "ninja.exe" } else { "ninja" }
function Get-NdkHostTag {
    $os = if ($IsWindows) {
        "windows"
    } elseif ($IsLinux) {
        "linux"
    } elseif ($IsMacOS) {
        "darwin"
    } else {
        throw "Unsupported OS for NDK."
    }

    $arch = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture) {
        "X64"   { "x86_64" }
        "Arm64" { "aarch64" }
        default { throw "Unsupported architecture for NDK." }
    }

    return "$os-$arch"
}
$NDK_HOST_TAG = Get-NdkHostTag

# --- VALIDATION ---
if (-not (Test-Path $NDK_PATH)) { Write-Error "NDK not found at $NDK_PATH"; exit 1 }
if (-not (Test-Path "$CMAKE_BIN/$NINJA_EXE")) { Write-Error "Ninja not found at $CMAKE_BIN"; exit 1 }

# --- SETUP ENVIRONMENT ---
Write-Host "Setting up build environment..." -ForegroundColor Cyan

$env:ANDROID_NDK_HOME = $NDK_PATH
$env:NDK_HOME = $NDK_PATH
$env:ANDROID_NDK_ROOT = $NDK_PATH

# --- GENERATE WRAPPER TOOLCHAIN ---
$WrapperFile = "$PSScriptRootNorm/android_wrapper.cmake"
$RealToolchainPath = "$NDK_PATH/build/cmake/android.toolchain.cmake"

# CHANGE: Set STL to c++_shared (The standard for Android)
$WrapperContent = @"
set(ANDROID_ABI "arm64-v8a" CACHE STRING "ABI" FORCE)
set(ANDROID_PLATFORM "android-30" CACHE STRING "Platform" FORCE)
set(ANDROID_STL "c++_shared" CACHE STRING "STL" FORCE)
include("$RealToolchainPath")
"@

Set-Content -Path $WrapperFile -Value $WrapperContent

# --- CONFIGURE CMAKE (FOR ANDROID) ---
$env:CMAKE_TOOLCHAIN_FILE = $WrapperFile
$env:CMAKE_GENERATOR = "Ninja"
$env:CMAKE_MAKE_PROGRAM = "$CMAKE_BIN/$NINJA_EXE"
if ($env:PATH -notmatch [regex]::Escape($CMAKE_BIN)) {
    $env:PATH = "$CMAKE_BIN" + [IO.Path]::PathSeparator + $env:PATH
}

# --- CLEAN & BUILD ---
# Write-Host "Cleaning previous builds..." -ForegroundColor Yellow
# cargo clean

Write-Host "Building for Android (arm64-v8a) API 30..." -ForegroundColor Green

$JniLibsDir = Normalize-Path "$PSScriptRootNorm/../app/src/main/jniLibs"
cargo ndk -t arm64-v8a --platform 30 -o $JniLibsDir build --release
$CargoExitCode = $LASTEXITCODE

# --- HOST ENVIRONMENT CLEANUP ---
# CRITICAL: We MUST wipe the Android CMake variables from the environment!
# Otherwise, the `cargo run` command below (which compiles for Windows/Mac)
# will try to compile host dependencies using the Android cross-compiler.
$env:CMAKE_TOOLCHAIN_FILE = $null
$env:CMAKE_GENERATOR = $null
$env:CMAKE_MAKE_PROGRAM = $null

if ($CargoExitCode -eq 0) {
    Write-Host "Rust Build Success!" -ForegroundColor Cyan

    # Path to libc++ in NDK r21+ (LLVM toolchain)
    $StlSource = Normalize-Path "$NDK_PATH/toolchains/llvm/prebuilt/$NDK_HOST_TAG/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    $StlDest = Normalize-Path "$JniLibsDir/arm64-v8a/libc++_shared.so"

    if (Test-Path $StlSource) {
        Copy-Item -Path $StlSource -Destination $StlDest -Force
        Write-Host "Copied libc++_shared.so to jniLibs." -ForegroundColor Green
    } else {
        Write-Host "Could not find libc++_shared.so at $StlSource"
        # Try fallback path for older NDK layouts just in case
        $StlSourceOld = Normalize-Path "$NDK_PATH/sources/cxx-stl/llvm-libc++/libs/arm64-v8a/libc++_shared.so"
        if (Test-Path $StlSourceOld) {
            Copy-Item -Path $StlSourceOld -Destination $StlDest -Force
            Write-Host "Copied libc++_shared.so (Legacy Path) to jniLibs." -ForegroundColor Green
        } else {
            Write-Error "CRITICAL: Could not find libc++_shared.so"
            exit 1
        }
    }

    # --- AUTOMATIC BINDING GENERATION ---
    # Dynamically construct the path using the RUST_TARGET from build.env
    $LibPath = Normalize-Path "$PSScriptRootNorm/target/$RUST_TARGET/release/libwalkie_talkie_engine.so"
    $OutDir = Normalize-Path "$PSScriptRootNorm/../app/src/main/java"

    Write-Host "Generating Kotlin bindings from $LibPath..." -ForegroundColor Yellow
    cargo run --bin uniffi-bindgen -- generate --library $LibPath --language kotlin --out-dir $OutDir

    if ($LASTEXITCODE -eq 0) {
        Write-Host "Bindings generated successfully!" -ForegroundColor Green
    } else {
        Write-Error "Binding generation failed!"
        exit 1
    }

    # Cleanup wrapper
    Remove-Item $WrapperFile -ErrorAction SilentlyContinue
} else {
    Write-Error "Build Failed."
    exit 1
}