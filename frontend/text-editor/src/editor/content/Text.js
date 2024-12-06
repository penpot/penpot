/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/**
 * Throws if the passed value is not a valid offset value.
 *
 * @param {*} offset
 * @throws {TypeError}
 */
function tryOffset(offset) {
  if (!Number.isInteger(offset) || offset < 0)
    throw new TypeError("Invalid offset");
}

/**
 * Throws if the passed value is not a valid string.
 *
 * @param {*} str
 * @throws {TypeError}
 */
function tryString(str) {
  if (typeof str !== "string") throw new TypeError("Invalid string");
}

/**
 * Inserts string into a string.
 *
 * @param {string} str
 * @param {number} offset
 * @param {string} text
 * @returns {string}
 */
export function insertInto(str, offset, text) {
  tryString(str);
  tryOffset(offset);
  tryString(text);
  return str.slice(0, offset) + text + str.slice(offset);
}

/**
 * Replaces a part of a string with a string.
 *
 * @param {string} str
 * @param {number} startOffset
 * @param {number} endOffset
 * @param {string} text
 * @returns {string}
 */
export function replaceWith(str, startOffset, endOffset, text) {
  tryString(str);
  tryOffset(startOffset);
  tryOffset(endOffset);
  tryString(text);
  return str.slice(0, startOffset) + text + str.slice(endOffset);
}

/**
 * Removes text backward from specified offset.
 *
 * @param {string} str
 * @param {number} offset
 * @returns {string}
 */
export function removeBackward(str, offset) {
  tryString(str);
  tryOffset(offset);
  if (offset === 0) {
    return str;
  }
  return str.slice(0, offset - 1) + str.slice(offset);
}

/**
 * Removes text forward from specified offset.
 *
 * @param {string} str
 * @param {number} offset
 * @returns {string}
 */
export function removeForward(str, offset) {
  tryString(str);
  tryOffset(offset);
  return str.slice(0, offset) + str.slice(offset + 1);
}

/**
 * Removes a slice of text.
 *
 * @param {string} str
 * @param {number} start
 * @param {number} end
 * @returns {string}
 */
export function removeSlice(str, start, end) {
  tryString(str);
  tryOffset(start);
  tryOffset(end);
  return str.slice(0, start) + str.slice(end);
}
