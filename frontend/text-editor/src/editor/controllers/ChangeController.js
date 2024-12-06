/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Change controller is responsible of notifying when a change happens.
 */
export class ChangeController extends EventTarget {
  /**
   * Keeps the timeout id.
   *
   * @type {number}
   */
  #timeout = null;

  /**
   * Keeps the time at which we're going to
   * call the debounced change calls.
   *
   * @type {number}
   */
  #time = 1000;

  /**
   * Keeps if we have some pending changes or not.
   *
   * @type {boolean}
   */
  #hasPendingChanges = false;

  /**
   * Constructor
   *
   * @param {number} [time=500]
   */
  constructor(time = 500) {
    super()
    if (typeof time === "number" && (!Number.isInteger(time) || time <= 0)) {
      throw new TypeError("Invalid time");
    }
    this.#time = time ?? 500;
  }

  /**
   * Indicates that there are some pending changes.
   *
   * @type {boolean}
   */
  get hasPendingChanges() {
    return this.#hasPendingChanges;
  }

  #onTimeout = () => {
    this.dispatchEvent(new Event("change"));
  };

  /**
   * Tells the ChangeController that a change has been made
   * but that you need to delay the notification (and debounce)
   * for sometime.
   */
  notifyDebounced() {
    this.#hasPendingChanges = true;
    clearTimeout(this.#timeout);
    this.#timeout = setTimeout(this.#onTimeout, this.#time);
  }

  /**
   * Tells the ChangeController that a change should be notified
   * immediately.
   */
  notifyImmediately() {
    clearTimeout(this.#timeout);
    this.#onTimeout();
  }

  /**
   * Disposes the referenced resources.
   */
  dispose() {
    if (this.hasPendingChanges) {
      this.notifyImmediately();
    }
    clearTimeout(this.#timeout);
  }
}

export default ChangeController;
