# lbug WASM Support

This document describes the modifications made to the `lbug` crate to enable compilation for the `wasm32-unknown-emscripten` target, and the build requirements for using it in a WASM context.

## Overview

The `lbug` crate is a Rust wrapper around a C++ graph database library. To compile it for WebAssembly using Emscripten, several modifications were necessary to handle:

1. C++ exception handling in WASM
2. Conditional compilation for WASM-specific code paths
3. Proper linking of static libraries for Emscripten
4. CMake configuration for single-threaded mode

## Changes Made to lbug

### 1. `build.rs` Modifications

The build script (`build.rs`) was modified to detect and handle the `wasm32-unknown-emscripten` target:

#### WASM Detection
```rust
fn is_wasm_emscripten() -> bool {
    env::var("TARGET")
        .map(|t| t == "wasm32-unknown-emscripten")
        .unwrap_or(false)
}
```

#### CMake Configuration (`build_bundled_cmake()`)
- **Single-threaded mode**: Sets `SINGLE_THREADED=TRUE` for WASM builds (required by Emscripten)
- The Emscripten toolchain is automatically detected when `CC`/`CXX` point to `emcc`/`em++`

#### FFI Build Configuration (`build_ffi()`)
- **C++20 standard**: Uses `-std=c++20` flag for WASM
- **Exception support**: Enables `-fexceptions` flag (exceptions must be enabled at compile time)
- Note: `-sDISABLE_EXCEPTION_CATCHING=0` is a linker flag and should be set via `EMCC_CFLAGS`

#### Library Linking (`link_libraries()`)
- **Explicit dependency linking**: For WASM, all static dependencies are explicitly linked:
  - `utf8proc`, `antlr4_cypher`, `antlr4_runtime`, `re2`, `fastpfor`
  - `parquet`, `thrift`, `snappy`, `zstd`, `miniz`
  - `mbedtls`, `brotlidec`, `brotlicommon`, `lz4`
  - `roaring_bitmap`, `simsimd`
- **Linking order**: Libraries are linked after FFI compilation for WASM (different from native builds)

### 2. `src/error.rs` Modifications

The error handling code was modified to conditionally compile C++ exception support:

#### Conditional C++ Exception Variant
The `Error::CxxException` variant and related implementations are conditionally compiled:

```rust
#[cfg(not(target_arch = "wasm32"))]
pub enum Error {
    // ... other variants ...
    CxxException(cxx::Exception),
    // ...
}
```

#### Exception Mapping for WASM
In WASM builds, `cxx::Exception` is mapped to `Error::FailedQuery`:

```rust
impl From<cxx::Exception> for Error {
    fn from(item: cxx::Exception) -> Self {
        #[cfg(not(target_arch = "wasm32"))]
        {
            Error::CxxException(item)
        }
        #[cfg(target_arch = "wasm32")]
        {
            // In wasm, CxxException is not available, map to a generic error
            Error::FailedQuery(item.to_string())
        }
    }
}
```

**Note**: This change does not affect the rest of `lbug` due to `#[cfg]` guards, ensuring native builds remain unchanged.

## Build Requirements

### 1. Using the Modified lbug Crate

To use the modified `lbug` crate in your project, add a `[patch.crates-io]` section to your `Cargo.toml`:

```toml
[dependencies]
lbug = "0.12.2"

# Patch lbug to use local version with wasm32-unknown-emscripten support
[patch.crates-io]
lbug = { path = "./lbug-0.12.2" }
```

### 2. Emscripten Environment Setup

The build requires Emscripten to be properly configured. The following environment variables should be set:

#### Memory Configuration
```bash
export EM_INITIAL_HEAP=$((256 * 1024 * 1024))  # 256 MB initial heap
export EM_MAXIMUM_MEMORY=$((4 * 1024 * 1024 * 1024))  # 4 GB maximum
export EM_MEMORY_GROWTH_GEOMETRIC_STEP=0.8
export EM_MALLOC=dlmalloc
```

#### Compiler/Linker Configuration
```bash
# Prevent cc-rs from adding default flags that conflict with Emscripten
export CRATE_CC_NO_DEFAULTS=1

# Emscripten compiler flags
export EMCC_CFLAGS="--no-entry \
    -sASSERTIONS=1 \
    -sALLOW_TABLE_GROWTH=1 \
    -sALLOW_MEMORY_GROWTH=1 \
    -sINITIAL_HEAP=$EM_INITIAL_HEAP \
    -sMEMORY_GROWTH_GEOMETRIC_STEP=$EM_MEMORY_GROWTH_GEOMETRIC_STEP \
    -sMAXIMUM_MEMORY=$EM_MAXIMUM_MEMORY \
    -sERROR_ON_UNDEFINED_SYMBOLS=0 \
    -sDISABLE_EXCEPTION_CATCHING=0 \
    -sEXPORT_NAME=createGraphModule \
    -sEXPORTED_RUNTIME_METHODS=stringToUTF8,HEAPU8 \
    -sENVIRONMENT=web \
    -sMODULARIZE=1 \
    -sEXPORT_ES6=1"
```

#### Function Exports

To control which functions are exported (avoiding issues with `$` symbols in auto-generated exports), use `RUSTFLAGS`:

```bash
export RUSTFLAGS="-C link-arg=-sEXPORTED_FUNCTIONS=@${SCRIPT_DIR}/exports.txt -C link-arg=-sEXPORT_ALL=0"
```

Where `exports.txt` contains the list of functions to export (one per line, with `_` prefix):
```
_hello
_generate_db
_init
_search_similar_shapes
# ... etc
```

### 3. Build Process

1. **Source Emscripten environment**:
   ```bash
   source /opt/emsdk/emsdk_env.sh
   ```

2. **Set build environment**:
   ```bash
   source ./_build_env
   ```

3. **Build**:
   ```bash
   cargo build --target=wasm32-unknown-emscripten
   ```

## Key Differences from Native Builds

1. **Single-threaded**: WASM builds use `SINGLE_THREADED=TRUE` in CMake
2. **Exception handling**: C++ exceptions are enabled at compile time (`-fexceptions`) and runtime (`-sDISABLE_EXCEPTION_CATCHING=0`)
3. **Linking order**: Libraries are linked after FFI compilation for WASM
4. **Error handling**: C++ exceptions are mapped to `FailedQuery` errors in WASM
5. **Function exports**: Manual control of exported functions via `EXPORTED_FUNCTIONS` file

## Troubleshooting

### Missing Symbols
If you encounter "missing function" errors at runtime, ensure:
- All required static libraries are listed in `link_libraries()` for WASM
- Libraries are linked in the correct order (after FFI compilation)
- `EXPORTED_FUNCTIONS` includes all functions you need to call from JavaScript

### Invalid Export Names
If you see errors like `invalid export name: cxxbridge1$exception`:
- Use `EXPORT_ALL=0` and manually specify functions in `exports.txt`
- Avoid using `EXPORT_ALL=1` with auto-generated export lists that may contain `$` symbols

### CMake Compiler Detection Errors
If CMake fails to detect the compiler:
- Ensure `CC` and `CXX` environment variables point to `emcc` and `em++`
- The Emscripten toolchain should be automatically detected by `cmake-rs`

## References

- [Emscripten Documentation](https://emscripten.org/docs/getting_started/index.html)
- [Rust and WebAssembly](https://rustwasm.github.io/docs/book/)
- [cxx crate documentation](https://cxx.rs/)

