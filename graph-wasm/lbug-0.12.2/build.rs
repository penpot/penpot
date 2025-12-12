use std::env;
use std::path::{Path, PathBuf};

fn link_mode() -> &'static str {
    if env::var("LBUG_SHARED").is_ok() {
        "dylib"
    } else {
        "static"
    }
}

fn get_target() -> String {
    env::var("PROFILE").unwrap()
}

fn is_wasm_emscripten() -> bool {
    env::var("TARGET")
        .map(|t| t == "wasm32-unknown-emscripten")
        .unwrap_or(false)
}

fn link_libraries() {
    // For wasm32-unknown-emscripten, we need to link lbug and all its dependencies
    // These are built by CMake and need to be linked here
    if is_wasm_emscripten() {
        // Link all dependencies first (built by CMake)
        for lib in [
            "utf8proc",
            "antlr4_cypher",
            "antlr4_runtime",
            "re2",
            "fastpfor",
            "parquet",
            "thrift",
            "snappy",
            "zstd",
            "miniz",
            "mbedtls",
            "brotlidec",
            "brotlicommon",
            "lz4",
            "roaring_bitmap",
            "simsimd",
        ] {
            println!("cargo:rustc-link-lib=static={lib}");
        }
        // Link the lbug static library (built by CMake)
        println!("cargo:rustc-link-lib=static=lbug");
        // Don't link system libraries for wasm (they're handled by Emscripten)
        return;
    }
    
    // This also needs to be set by any crates using it if they want to use extensions
    if !cfg!(windows) && link_mode() == "static" {
        println!("cargo:rustc-link-arg=-rdynamic");
    }
    if cfg!(windows) && link_mode() == "dylib" {
        println!("cargo:rustc-link-lib=dylib=lbug_shared");
    } else if link_mode() == "dylib" {
        println!("cargo:rustc-link-lib={}=lbug", link_mode());
    } else if rustversion::cfg!(since(1.82)) {
        println!("cargo:rustc-link-lib=static:+whole-archive=lbug");
    } else {
        println!("cargo:rustc-link-lib=static=lbug");
    }
    if link_mode() == "static" {
        if cfg!(windows) {
            println!("cargo:rustc-link-lib=dylib=msvcrt");
            println!("cargo:rustc-link-lib=dylib=shell32");
            println!("cargo:rustc-link-lib=dylib=ole32");
        } else if cfg!(target_os = "macos") {
            println!("cargo:rustc-link-lib=dylib=c++");
        } else {
            println!("cargo:rustc-link-lib=dylib=stdc++");
        }

        for lib in [
            "utf8proc",
            "antlr4_cypher",
            "antlr4_runtime",
            "re2",
            "fastpfor",
            "parquet",
            "thrift",
            "snappy",
            "zstd",
            "miniz",
            "mbedtls",
            "brotlidec",
            "brotlicommon",
            "lz4",
            "roaring_bitmap",
            "simsimd",
        ] {
            if rustversion::cfg!(since(1.82)) {
                println!("cargo:rustc-link-lib=static:+whole-archive={lib}");
            } else {
                println!("cargo:rustc-link-lib=static={lib}");
            }
        }
    }
}

fn build_bundled_cmake() -> Vec<PathBuf> {
    let lbug_root = {
        let root = Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("lbug-src");
        if root.is_symlink() || root.is_dir() {
            root
        } else {
            // If the path is not directory, this is probably an in-source build on windows where the
            // symlink is unreadable.
            Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("../..")
        }
    };

    let mut build = cmake::Config::new(&lbug_root);
    build
        .no_build_target(true)
        .define("BUILD_SHELL", "OFF")
        .define("BUILD_SINGLE_FILE_HEADER", "OFF")
        .define("AUTO_UPDATE_GRAMMAR", "OFF");
    
    // Configure for wasm32-unknown-emscripten
    if is_wasm_emscripten() {
        // Same configuration as ladybug/tools/wasm build
        build.define("SINGLE_THREADED", "TRUE");
        // cmake-rs should automatically detect emscripten toolchain when CC/CXX point to emcc/em++
    } else if cfg!(windows) {
        build.generator("Ninja");
        build.cxxflag("/EHsc");
        build.define("CMAKE_MSVC_RUNTIME_LIBRARY", "MultiThreadedDLL");
        build.define("CMAKE_POLICY_DEFAULT_CMP0091", "NEW");
    }
    if let Ok(jobs) = env::var("NUM_JOBS") {
        // SAFETY: Setting environment variables in build scripts is safe
        unsafe {
            env::set_var("CMAKE_BUILD_PARALLEL_LEVEL", jobs);
        }
    }
    let build_dir = build.build();

    let lbug_lib_path = build_dir.join("build").join("src");
    println!("cargo:rustc-link-search=native={}", lbug_lib_path.display());

    for dir in [
        "utf8proc",
        "antlr4_cypher",
        "antlr4_runtime",
        "re2",
        "brotli",
        "alp",
        "fastpfor",
        "parquet",
        "thrift",
        "snappy",
        "zstd",
        "miniz",
        "mbedtls",
        "lz4",
        "roaring_bitmap",
        "simsimd",
    ] {
        let lib_path = build_dir
            .join("build")
            .join("third_party")
            .join(dir)
            .canonicalize()
            .unwrap_or_else(|_| {
                panic!(
                    "Could not find {}/build/third_party/{}",
                    build_dir.display(),
                    dir
                )
            });
        println!("cargo:rustc-link-search=native={}", lib_path.display());
    }

    vec![
        lbug_root.join("src/include"),
        build_dir.join("build/src"),
        build_dir.join("build/src/include"),
        lbug_root.join("third_party/nlohmann_json"),
        lbug_root.join("third_party/fastpfor"),
        lbug_root.join("third_party/alp/include"),
    ]
}

fn build_ffi(
    bridge_file: &str,
    out_name: &str,
    source_file: &str,
    bundled: bool,
    include_paths: &Vec<PathBuf>,
) {
    let mut build = cxx_build::bridge(bridge_file);
    build.file(source_file);

    if bundled {
        build.define("LBUG_BUNDLED", None);
    }
    if get_target() == "debug" || get_target() == "relwithdebinfo" {
        build.define("ENABLE_RUNTIME_CHECKS", "1");
    }
    if link_mode() == "static" {
        build.define("LBUG_STATIC_DEFINE", None);
    }

    build.includes(include_paths);

    println!("cargo:rerun-if-env-changed=LBUG_SHARED");

    println!("cargo:rerun-if-changed=include/lbug_rs.h");
    println!("cargo:rerun-if-changed=src/lbug_rs.cpp");
    // Note that this should match the lbug-src/* entries in the package.include list in Cargo.toml
    // Unfortunately they appear to need to be specified individually since the symlink is
    // considered to be changed each time.
    println!("cargo:rerun-if-changed=lbug-src/src");
    println!("cargo:rerun-if-changed=lbug-src/cmake");
    println!("cargo:rerun-if-changed=lbug-src/third_party");
    println!("cargo:rerun-if-changed=lbug-src/CMakeLists.txt");
    println!("cargo:rerun-if-changed=lbug-src/tools/CMakeLists.txt");

    if is_wasm_emscripten() {
        // For emscripten, use C++20 and enable exceptions
        build.flag("-std=c++20");
        build.flag("-fexceptions");
        // Note: -sDISABLE_EXCEPTION_CATCHING=0 is a linker flag, not a compiler flag
        // It should be set via EMCC_CFLAGS environment variable or cargo rustc-link-arg
    } else if cfg!(windows) {
        build.flag("/std:c++20");
        build.flag("/MD");
    } else {
        build.flag("-std=c++2a");
    }
    build.compile(out_name);
}

fn main() {
    if env::var("DOCS_RS").is_ok() {
        // Do nothing; we're just building docs and don't need the C++ library
        return;
    }

    let mut bundled = false;
    let mut include_paths =
        vec![Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("include")];

    if let (Ok(lbug_lib_dir), Ok(lbug_include)) =
        (env::var("LBUG_LIBRARY_DIR"), env::var("LBUG_INCLUDE_DIR"))
    {
        println!("cargo:rustc-link-search=native={lbug_lib_dir}");
        println!("cargo:rustc-link-arg=-Wl,-rpath,{lbug_lib_dir}");
        include_paths.push(Path::new(&lbug_include).to_path_buf());
    } else {
        include_paths.extend(build_bundled_cmake());
        bundled = true;
    }
    // For wasm, we need to link libraries after building FFI to ensure proper symbol resolution
    if !is_wasm_emscripten() && link_mode() == "static" {
        link_libraries();
    }
    build_ffi(
        "src/ffi.rs",
        "lbug_rs",
        "src/lbug_rs.cpp",
        bundled,
        &include_paths,
    );

    if cfg!(feature = "arrow") {
        build_ffi(
            "src/ffi/arrow.rs",
            "lbug_arrow_rs",
            "src/lbug_arrow.cpp",
            bundled,
            &include_paths,
        );
    }
    // For wasm, link libraries after FFI; for dylib, link after FFI
    if is_wasm_emscripten() || link_mode() == "dylib" {
        link_libraries();
    }
}
