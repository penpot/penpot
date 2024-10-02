# Renderer

## How this works?

First of all we need a proper environment to build Skia, this
environment is heavily based on the [Skia docker image](https://github.com/google/skia/blob/main/docker/skia-release/Dockerfile) but with some tweaks to support building
a C++ WebAssembly module using [Emscripten](https://emscripten.org/index.html).

## Building everything

From the root directory of `frontend/renderer` just run:

```sh
./build
```

This is going to build the docker image and run the container to build
the artifacts and then copy them to the necessary directories.

> :smile_cat: Be patient, the first time the docker image is built usually takes
> a few minutes.

## Building the Skia build tools Docker image

To build just the Skia build tools image:

```sh
cd frontend/renderer
docker build . -t skia-build-tools
```

## Building the renderer WebAssembly module

Just run the container and it will generate all the necessary
artifacts in the `out` directory.

```sh
cd frontend/renderer
docker run -t -v ${PWD}:/tmp/renderer skia-build-tools
```

Once the `renderer.js` and `renderer.wasm` are created in the `out` directory
we need to move them where Penpot can have access to them, so we need to execute
`./scripts/copy-artifacts`.

## C++ <-> JS

To add some extra functionality to the exported `Module` by the Emscripten
compiler, we use a series of javascript scripts that exist on the `js` directory.
