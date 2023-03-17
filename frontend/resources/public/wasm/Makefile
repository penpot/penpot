all:
	mkdir -p build
	clang \
		-target wasm32 \
		-nostdlib \
		-Wl,--no-entry \
		-Wl,--export-all \
		-o build/resize.wasm \
		src/resize.c

