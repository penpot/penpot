export class WASMModuleWrapper {
  #module;

  constructor(module) {
    this.#module = module;
  }

  get module() {
    return this.#module;
  }

  get gl() {
    return this.#module.GL;
  }

  get heapu8() {
    return this.#module.HEAPU8;
  }

  get heapu32() {
    return this.#module.HEAPU32;
  }

  set(ptr, size, data, byteSize = data instanceof Uint32Array ? 4 : 1) {
    const heap = byteSize === 4 ? this.#module.HEAPU32 : this.#module.HEAPU8;
    const typedArray = byteSize === 4 ? Uint32Array : Uint8Array;
    const length = size / byteSize;
    const mem = new typedArray(heap.buffer, ptr, length);
    mem.set(data);
  }

  viewOf(ptr, size) {
    return new DataView(this.#module.HEAPU8.buffer, ptr, size);
  }

  registerContext(canvas, contextId, contextAttributes) {
    const gl = this.gl;
    const context = canvas.getContext(contextId, contextAttributes);
    const handle = gl.registerContext(context, { majorVersion: 2 });
    gl.makeContextCurrent(handle);
    return context;
  }

  call(name, ...args) {
    const _name = `_${name}`;
    if (!(_name in this.#module)) {
      throw new Error(`${name} not found in WASM module`);
    }
    console.log("Calling", name, ...args);
    return this.#module[_name](...args);
  }
}
