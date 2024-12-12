/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Command mutations
 */
export class CommandMutations {
  #added = new Set();
  #removed = new Set();
  #updated = new Set();

  constructor(added, updated, removed) {
    if (added && Array.isArray(added)) this.#added = new Set(added);
    if (updated && Array.isArray(updated)) this.#updated = new Set(updated);
    if (removed && Array.isArray(removed)) this.#removed = new Set(removed);
  }

  get added() {
    return this.#added;
  }

  get removed() {
    return this.#removed;
  }

  get updated() {
    return this.#updated;
  }

  clear() {
    this.#added.clear();
    this.#removed.clear();
    this.#updated.clear();
  }

  dispose() {
    this.#added.clear();
    this.#added = null;
    this.#removed.clear();
    this.#removed = null;
    this.#updated.clear();
    this.#updated = null;
  }

  add(node) {
    this.#added.add(node);
    return this;
  }

  remove(node) {
    this.#removed.add(node);
    return this;
  }

  update(node) {
    this.#updated.add(node);
    return this;
  }
}

export default CommandMutations;
