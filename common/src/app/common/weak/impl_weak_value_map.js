/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
"use strict";

export class WeakValueMap {
  constructor() {
    this._map = new Map(); // key -> {ref, token}
    this._registry = new FinalizationRegistry((token) => {
      this._map.delete(token.key);
    });
  }

  set(key, value) {
    const ref = new WeakRef(value);
    const token = { key };
    this._map.set(key, { ref, token });
    this._registry.register(value, token, token);
    return this;
  }

  get(key) {
    const entry = this._map.get(key);
    if (!entry) return undefined;
    const value = entry.ref.deref();
    if (value === undefined) {
      // Value was GC’d, clean up
      this._map.delete(key);
      return undefined;
    }
    return value;
  }

  has(key) {
    const entry = this._map.get(key);
    if (!entry) return false;
    if (entry.ref.deref() === undefined) {
      this._map.delete(key);
      return false;
    }
    return true;
  }

  delete(key) {
    const entry = this._map.get(key);
    if (!entry) return false;
    this._registry.unregister(entry.token);
    return this._map.delete(key);
  }
}
