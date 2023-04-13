/**
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

goog.provide("app.util.wasm.types");

goog.scope(function() {
  const self = app.util.wasm.types;

  /**
   * Primitive types supported by WebAssembly
   * Memory.
   */
  const PrimitiveTypes = {
    // type: size, getter, setter
    "u8": [1, "getUint8", "setUint8"],
    "u16": [2, "getUint16", "setUint16"],
    "u32": [4, "getUint32", "setUint32"],
    "u64": [8, "getBigUint64", "setBigUint64"],
    "s8": [1, "getInt8", "setInt8"],
    "s16": [2, "getInt16", "setInt16"],
    "s32": [4, "getInt32", "setInt32"],
    "s64": [8, "getBigInt64", "setBigInt64"],
    "f32": [4, "getFloat32", "setFloat32"],
    "f64": [8, "getFloat64", "setFloat64"],
    "pointer": [4, "getUint32", null],
  };

  /**
   * This is a type register for interacting with
   * WebAssembly Memory.
   */
  const TypeRegistry = (() => {
    const types = new Map();
    return {
      has(type) {
        return types.has(type);
      },
      get(type) {
        if (!types.has(type)) {
          throw new Error(`Type ${type} doesn't exists`);
        }
        return types.get(type);
      },
      register(type, ...args) {
        if (types.has(type)) {
          throw new Error(`Redefinition of type ${type}`);
        }
        const newType = Type.create(...args);
        types.set(type, newType);
        return newType;
      },
      unregister(type) {
        return types.delete(type);
      },
    };
  })();

  /**
   * This is a Type reference (pointer into
   * a type).
   */
  class TypeRef {
    /**
     * Constructor
     *
     * @param {WebAssembly.Memory} memory
     * @param {number} offset
     * @param {Type} type
     */
    constructor(memory, offset, type) {
      this._memory = memory;
      this._offset = offset;
      this._type = type;
      this._dataView = null;
      this._proxyCache = new Map();
    }

    /**
     * @type {DataView}
     */
    get dataView() {
      if (!this._dataView || this._dataView.buffer !== this._memory.buffer) {
        this._dataView = new DataView(
          this._memory.buffer,
          this._offset,
          this._type.size
        );
      }
      return this._dataView;
    }

    /**
     * @type {Type}
     */
    get type() {
      return this._type
    }

    /**
     * Clears the cache and the DataView.
     */
    clear() {
      this._proxyCache.clear();
      this._dataView = null;
    }

    /**
     * Disposes the TypeRef.
     */
    dispose() {
      this.clear();
      this._type = null;
      this._memory = null;
    }

    /**
     * Gets a property value.
     *
     * @param {string} name
     * @returns {any}
     */
    get(name) {
      const property = this._type.get(name);
      if (property.type in PrimitiveTypes) {
        return this.dataView[property.getter](property.offset, true);
      }
      if (!this._proxyCache.has(name)) {
        this._proxyCache.set(
          name,
          TypeRegistry.get(property.type).ref(
            this._memory,
            this.dataView[property.getter](property.offset, true)
          )
        );
      }
      return this._proxyCache.get(name);
    }

    /**
     * Sets a property value.
     *
     * @param {string} name
     * @param {any} value
     * @returns {boolean}
     */
    set(name, value) {
      const property = this._type.get(name);
      if (property.type in PrimitiveTypes) {
        return this.dataView[property.setter](property.offset, value, true);
      }
      return false;
    }
  }

  /**
   * Alternative to create a new Type Proxy.
   *
   * @param {Function} ProxyConstructor
   * @param {TypeRef} typeRef
   * @param {TypeProxyOptions} options
   * @param {...any} args
   * @returns {Object<ProxyConstructor>}
   */
  function createTypeProxyAlt(ProxyConstructor, typeRef, options, ...args) {
    const obj = {}
    for (const [name] of typeRef.type.properties) {
      Object.defineProperty(obj, name, {
        get() {
          return typeRef.get(name)
        },
        set(value) {
          typeRef.set(name, value)
        },
      })
    }
    Object.setPrototypeOf(obj, ProxyConstructor.prototype)
    return obj
  }

  /**
   * Creates a new Type Proxy.
   *
   * @param {Function} ProxyConstructor
   * @param {TypeRef} typeRef
   * @param {Type} type
   * @param {TypeProxyOptions} options
   * @param {...any} args
   * @returns {Proxy<ProxyConstructor>}
   */
  function createTypeProxy(ProxyConstructor, typeRef, options, ...args) {
    return new Proxy(
      new ProxyConstructor(...args),
      {
        has(target, name) {
          return typeRef.type.has(name) || Reflect.has(target, name);
        },
        get(target, name) {
          if (!typeRef.type.has(name)) {
            return Reflect.get(target, name);
          }
          return typeRef.get(name);
        },
        set(target, name, value) {
          if (options && options.readOnly) {
            return false;
          }
          if (!typeRef.type.has(name)) {
            return Reflect.set(target, name, value);
          }
          return typeRef.set(name, value);
        },
      }
    );
  }

  /**
   * Type.
   */
  class Type {
    /**
     * Create a new type (this is a factory method and you
     * should never use the constructor directly)
     *
     * @param {Object} props
     * @param {Function} proxyConstructor
     * @returns {Type}
     */
    static create(props, proxyConstructor = Object) {
      let offset = 0;
      const properties = new Map();
      for (const [name, definition] of Object.entries(props)) {
        const [size, getter, setter] =
        definition.type in PrimitiveTypes
          ? PrimitiveTypes[definition.type]
          : PrimitiveTypes.pointer;
        properties.set(
          name,
          {
            type: definition.type,
            offset: definition.offset ?? offset,
            size: size,
            getter: getter,
            setter: setter,
          }
        );
        offset += size;
      }
      return new Type(properties, proxyConstructor);
    }

    /**
     * Constructor
     *
     * @param {Map<string, TypeProperty>} properties
     * @param {Function} [proxyConstructor=Object]
     */
    constructor(properties, proxyConstructor = Object) {
      this._properties = properties;
      this._size = Array.from(this._properties.values()).reduce((acc, prop) => acc + prop.size, 0);
      this._proxyConstructor = proxyConstructor;
    }

    /**
     * Type properties
     *
     * @type {Map<string, TypeProperty>}
     */
    get properties() {
      return this._properties
    }

    /**
     * Type size
     *
     * @type {number}
     */
    get size() {
      return this._size;
    }

    /**
     * Check if a type has a property definition by name.
     *
     * @param {string} name
     * @returns {boolean}
     */
    has(name) {
      return this._properties.has(name);
    }

    /**
     * Get a type property definition by name.
     *
     * @param {string} name
     * @returns {TypeProperty}
     */
    get(name) {
      const property = this._properties.get(name);
      if (!property) {
        throw new Error(`Property ${name} doesn't exists`);
      }
      return property
    }

    /**
     * Creates a new TypeRef to a Type.
     *
     * @param {WebAssembly.Memory} memory
     * @param {number} offset
     * @param {object} options
     * @param {...any} args
     * @returns {Proxy}
     */
    ref(memory, offset, options, ...args) {
      const typeRef = new TypeRef(memory, offset, this);
      const ProxyConstructor = this._proxyConstructor;
      return createTypeProxyAlt(
        ProxyConstructor,
        typeRef,
        this,
        options,
        ...args
      );
    }
  }

  /**
   * Now we need to register some types to
   * interact with the resize process.
   */
  // in here we register some useful schemas.
  TypeRegistry.register(
    "Point",
    {
      x: { type: "f32" },
      y: { type: "f32" },
    },
    app.common.geom.point.Point
  );

  TypeRegistry.register(
    "Rect",
    {
      position: { type: "Point" },
      size: { type: "Point" },
    }
  );

  TypeRegistry.register(
    "Matrix",
    {
      a: { type: "f32" },
      b: { type: "f32" },
      c: { type: "f32" },
      d: { type: "f32" },
      e: { type: "f32" },
      f: { type: "f32" },
    },
    app.common.geom.matrix.Matrix
  );

  TypeRegistry.register(
    "ResizeInput",
    {
      rotation: { type: "f32" },
      handler: { type: "u32" },
      shouldLock: { type: "u32" },
      shouldCenter: { type: "u32" },
      selRect: { type: "Rect" },
      start: { type: "Point" },
      current: { type: "Point" },
      snap: { type: "Point" },
      shouldTransform: { type: "u32" },
      transform: { type: "Matrix" },
      transformInverse: { type: "Matrix" },
    }
  );

  TypeRegistry.register(
    "ResizeOutput",
    {
      origin: { type: "Point" },
      vector: { type: "Point" },
      displacement: { type: "Point" },
    }
  );

  TypeRegistry.register(
    "TransformInput",
    {
      matrix: { type: "Matrix" },
      shouldTransform: { type: "u32" },
      transform: { type: "Matrix" },
      transformInverse: { type: "Matrix" },
      origin: { type: "Point" },
      vector: { type: "Point" },
      center: { type: "Point" },
      rotation: { type: "f32" },
    }
  );

  TypeRegistry.register("TransformOutput", {
    matrix: { type: "Matrix" },
  });

  self.from = function from(assembly, varName, typeName) {
    const type = TypeRegistry.get(typeName)
    const ref = type.ref(assembly.memory, assembly[varName].value);
    return ref
  };
})
