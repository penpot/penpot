/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
"use strict";

export class WeakEqMap {
  constructor({ equals, hash }) {
    this._equals = equals;
    this._hash = hash;

    // buckets: Map<hash, Array<Entry>>
    this._buckets = new Map();

    // Token -> (hash) so the FR cleanup can find & remove dead entries
    // We store {hash, token} as heldValue for FinalizationRegistry
    this._fr = new FinalizationRegistry(({ hash, token }) => {
      const bucket = this._buckets.get(hash);
      if (!bucket) return;
      // Remove the entry whose token matches or whose key has been collected
      let i = 0;
      while (i < bucket.length) {
        const e = bucket[i];
        const dead = e.keyRef.deref() === undefined;
        if (dead || e.token === token) {
          // swap-remove for O(1)
          bucket[i] = bucket[bucket.length - 1];
          bucket.pop();
          continue;
        }
        i++;
      }
      if (bucket.length === 0) this._buckets.delete(hash);
    });
  }

  _getBucket(hash) {
    let b = this._buckets.get(hash);
    if (!b) {
      b = [];
      this._buckets.set(hash, b);
    }
    return b;
  }

  _findEntry(bucket, key) {
    // Sweep dead entries opportunistically
    let i = 0;
    let found = null;
    while (i < bucket.length) {
      const e = bucket[i];
      const k = e.keyRef.deref();
      if (k === undefined) {
        bucket[i] = bucket[bucket.length - 1];
        bucket.pop();
        continue;
      }
      if (found === null && this._equals(k, key)) {
        found = e;
      }
      i++;
    }
    return found;
  }

  set(key, value) {
    if (key === null || (typeof key !== 'object' && typeof key !== 'function')) {
      throw new TypeError('WeakEqMap keys must be objects (like WeakMap).');
    }
    const hash = this._hash(key);
    const bucket = this._getBucket(hash);
    const existing = this._findEntry(bucket, key);
    if (existing) {
      existing.value = value;
      return this;
    }
    const token = Object.create(null); // unique identity
    const entry = { keyRef: new WeakRef(key), value, token };
    bucket.push(entry);
    // Register for cleanup when key is GC’d
    this._fr.register(key, { hash, token }, entry);
    return this;
  }

  get(key) {
    const hash = this._hash(key);
    const bucket = this._buckets.get(hash);
    if (!bucket) return undefined;
    const e = this._findEntry(bucket, key);
    return e ? e.value : undefined;
  }

  has(key) {
    const hash = this._hash(key);
    const bucket = this._buckets.get(hash);
    if (!bucket) return false;
    return !!this._findEntry(bucket, key);
  }

  delete(key) {
    const hash = this._hash(key);
    const bucket = this._buckets.get(hash);
    if (!bucket) return false;
    let i = 0;
    while (i < bucket.length) {
      const e = bucket[i];
      const k = e.keyRef.deref();
      if (k === undefined) {
        // clean dead
        bucket[i] = bucket[bucket.length - 1];
        bucket.pop();
        continue;
      }
      if (this._equals(k, key)) {
        // Unregister and remove
        this._fr.unregister(e); // unregister via the registration "unregisterToken" = entry
        bucket[i] = bucket[bucket.length - 1];
        bucket.pop();
        if (bucket.length === 0) this._buckets.delete(hash);
        return true;
      }
      i++;
    }
    if (bucket.length === 0) this._buckets.delete(hash);
    return false;
  }
}
