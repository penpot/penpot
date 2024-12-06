/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Adds a series of listeners.
 *
 * @param {EventTarget} target
 * @param {Object.<string, Function>} object
 * @param {EventListenerOptions} [options]
 */
export function addEventListeners(target, object, options) {
  Object.entries(object).forEach(([type, listener]) =>
    target.addEventListener(type, listener, options)
  );
}

/**
 * Removes a series of listeners.
 *
 * @param {EventTarget} target
 * @param {Object.<string, Function>} object
 */
export function removeEventListeners(target, object) {
  Object.entries(object).forEach(([type, listener]) =>
    target.removeEventListener(type, listener)
  );
}
