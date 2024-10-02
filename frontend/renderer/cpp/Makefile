all:
	# -fno-rtti: Removes C++ Run-Time Type Info support.
	# --no-entry: Disables the necessity of an entry point.
	# -sALLOW_MEMORY_GROWTH: Creates a resizable memory ArrayBuffer.
	# -sMODULARIZE: Exports emscripten as a CommonJS/AMD module.
	# -sENVIRONMENT: Removes unnecessary environments (node,worker,etc).
	# -sUSE_PTHREADS: Disables pthreads.
	# -sMAX_WEBGL_VERSION: Max WebGL set to 2
	# -sUSE_WEBGL2: Uses WebGL2 by default.
	em++ \
		-std=c++20 \
		-lembind \
		-fno-rtti \
		--no-entry \
		-sALLOW_MEMORY_GROWTH \
		-sUSE_PTHREADS=0 \
		-sMODULARIZE=1 \
		-sDISABLE_EXCEPTION_CATCHING \
		-sNODEJS_CATCH_EXIT=0 \
		-sMAX_WEBGL_VERSION=2 \
		-sUSE_WEBGL2=1 \
		-sFORCE_FILESYSTEM=0 \
		-sDYNAMIC_EXECUTION=0 \
		-sFILESYSTEM=0 \
		-sENVIRONMENT='web' \
		-sINITIAL_MEMORY=128MB \
		-DCK_ENABLE_WEBGL \
		-DCK_NO_FONTS \
		-DSK_RELEASE \
		-DSK_DISABLE_TRACING \
		-DSK_FORCE_AAA \
		-DSK_FORCE_8_BYTE_ALIGNMENT \
		-DSK_SHAPER_HARFBUZZ_AVAILABLE \
		-DCK_INCLUDE_PARAGRAPH \
		-DCK_SERIALIZE_SKP \
		-DSK_GANESH \
		-DSK_DISABLE_LEGACY_SHADERCONTEXT \
		-DCK_INCLUDE_PATHOPS \
		-DCK_INCLUDE_RUNTIME_EFFECT \
		-DSKSL_ENABLE_TRACING \
		-DNDEBUG \
		-DSK_TRIVIAL_ABI="[[clang::trivial_abi]]" \
		-DSK_TYPEFACE_FACTORY_FREETYPE \
		-DSK_GL \
		-DSK_CODEC_DECODES_JPEG \
		-DSK_CODEC_DECODES_PNG \
		-DSK_CODEC_DECODES_WEBP \
		-DSK_HAS_WUFFS_LIBRARY \
		-DSK_ENABLE_SKSL \
		-DSK_ENABLE_PRECOMPILE \
		-DSKNX_NO_SIMD \
		-DSK_ASSUME_WEBGL=1 \
		-DSK_USE_WEBGL \
		-DSK_ENABLE_PARAGRAPH \
		-DSK_UNICODE_AVAILABLE \
		-DSK_UNICODE_ICU_IMPLEMENTATION \
		-DSK_ENABLE_SKOTTIE \
		-DSK_ENABLE_SKOTTIE_SKSLEFFECT \
		-DEMSCRIPTEN_HAS_UNBOUND_TYPE_NAMES=0 \
		--pre-js js/preamble.js \
		--pre-js js/postamble.js \
		-I/tmp/skia \
		-o out/renderer.js \
		/tmp/skia/out/wasm/modules/canvaskit/fonts/NotoMono-Regular.ttf.ninja.cpp \
		/tmp/skia/out/wasm/libskia.a \
		src/main.cpp
