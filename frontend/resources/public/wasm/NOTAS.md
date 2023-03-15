# Notas

## TO DO

- [ ] Mover todo esto a algún otro sitio mejor que no sea `resources/public`.
- [ ] Implementar tanto `clang-format` como `clang-tidy` para formatear el código.
- [ ] Implementar algún sistema de testing (Catch2, Google Test, CppUnit, etc).
- [ ] Implementar CMake para construir el proyecto.

Para compilar el código en C++ se puede usar la siguiente línea:

```sh
g++ -std=c++20 src/main.cpp -o main
```

## Emscripten

### Instalación

1. Clonar repositorio:

```sh
git clone https://github.com/emscripten-core/emsdk.git
cd emsdk
```

2. Actualizar e instalar dependencias:

```sh
git pull
./emsdk install latest
./emsdk activate latest
source ./emsdk_env.sh
```

3. Probar:

:bulb: Ahora deberíamos tener disponibles herramientas como `emcc` (equivalente a
`gcc` o `clang`), `em++` (equivalente a `g++` o `clang++`), `emmake`
(equivalente a `make`) o `emcmake` (equivalente a `cmake`).

Puedes compilar el proyecto con:

```sh
emmake make
```

## WebAssembly

### Memoria

La memoria de WebAssembly se crea cuando se instancia el módulo de WebAssembly, aunque esta memoria puede crecer. Si no se pasa un `WebAssembly.Memory` al módulo en la instanciación, crea una memoria por defecto con el número de páginas que indique el módulo.

:bulb: Para averiguar cuál es este valor por defecto podemos usar `wasm-objdump -x <module.wasm> | grep 'pages:'`.

La memoria de WebAssembly se reserva usando páginas (una página equivale a 64KB).

El máximo de memoria que puede reservar un módulo de WebAssembly ahora mismo son 4GB (65536 páginas).

:bulb: Ahora mismo existen dos _proposals_ para ampliar estos límites: [Memory64](https://github.com/WebAssembly/memory64) y [Multi-Memory](https://github.com/WebAssembly/multi-memory/blob/master/proposals/multi-memory/Overview.md)

## Documentos

- [C++ Core Guidelines](https://isocpp.github.io/CppCoreGuidelines/CppCoreGuidelines#S-introduction)

## Recursos

- [Compiling C to WebAssembly without Emscripten](https://surma.dev/things/c-to-webassembly/)
- [Emscripten: C/C++ compiler toolchain](https://emscripten.org/)
- [Emscripten: settings.js](https://github.com/emscripten-core/emscripten/blob/main/src/settings.js)
- [WABT: WebAssembly Binary Toolkit](https://github.com/WebAssembly/wabt)
- [Binaryen: Compiler Toolchain](https://github.com/WebAssembly/binaryen)
