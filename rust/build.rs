fn main() {
    // CRITICAL: Link C++ Shared Runtime for Android
    // This fixes the "cannot locate symbol __cxa_pure_virtual" error.
    #[cfg(target_os = "android")]
    println!("cargo:rustc-link-lib=dylib=c++_shared");
}