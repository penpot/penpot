all:
	mkdir -p build
	em++ -std=c++20 -O3 -sINCOMING_MODULE_JS_API=['print'] -sENVIRONMENT=web,node,worker -sFILESYSTEM=0 src/main.cpp -o build/main.js
