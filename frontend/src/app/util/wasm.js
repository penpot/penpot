/**
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

goog.provide("app.util.wasm");

goog.scope(function () {
  const self = app.util.wasm;

  async function instantiate(module, imports = {}) {
    const adaptedImports = {
      env: Object.assign(Object.create(globalThis), imports.env || {}, {
        abort(message, fileName, lineNumber, columnNumber) {
          // ~lib/builtins/abort(~lib/string/String | null?, ~lib/string/String | null?, u32?, u32?) => void
          message = __liftString(message >>> 0);
          fileName = __liftString(fileName >>> 0);
          lineNumber = lineNumber >>> 0;
          columnNumber = columnNumber >>> 0;
          (() => {
            // @external.js
            throw Error(`${message} in ${fileName}:${lineNumber}:${columnNumber}`);
          })();
        },
        "console.log"(text) {
          text = __liftString(text >>> 0);
          console.log(text);
        },
      }),
    };

    const {
      instance: { exports },
    } = await WebAssembly.instantiate(module, adaptedImports);
    const memory = exports.memory || imports.env.memory;
    const adaptedExports = Object.setPrototypeOf({}, exports);

    function __liftString(pointer) {
      if (!pointer) return null;
      const end = (pointer + new Uint32Array(memory.buffer)[(pointer - 4) >>> 2]) >>> 1,
        memoryU16 = new Uint16Array(memory.buffer);
      let start = pointer >>> 1,
        string = "";
      while (end - start > 1024) string += String.fromCharCode(...memoryU16.subarray(start, (start += 1024)));
      return string + String.fromCharCode(...memoryU16.subarray(start, end));
    }

    return adaptedExports;
  }

  async function load(uri, imports = {}) {
    console.log("load", uri, imports);
    const response = await fetch(uri);
    console.log(response);
    const arrayBuffer = await response.arrayBuffer();
    console.log(arrayBuffer);
    return instantiate(arrayBuffer, imports);
  }

  /**
   * This is part of the AssemblyScript runtime.
   *
   * See resize.debug.js for more information.
   */
  self.instantiate = instantiate
  self.load = load
});
