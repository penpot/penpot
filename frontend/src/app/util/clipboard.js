/**
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
const maxParseableSize = 16 * 1024 * 1024;

const allowedTypes = [
  "image/webp",
  "image/png",
  "image/jpeg",
  "image/svg+xml",
  "application/transit+json",
  "text/html",
  "text/plain",
];

const exclusiveTypes = [
  "application/transit+json",
  "text/html",
  "text/plain"
];

/**
 * @typedef {Object} ClipboardSettings
 * @property {Function} [decodeTransit]
 */

/**
 *
 * @param {string} text
 * @param {ClipboardSettings} options
 * @param {Blob} [defaultReturn]
 * @returns {Blob}
 */
function parseText(text, options) {
  options = options || {};

  const decodeTransit = options["decodeTransit"];

  if (decodeTransit) {
    try {
      decodeTransit(text);
      return new Blob([text], { type: "application/transit+json" });
    } catch (_error) {
      // NOOP
    }
  }

  if (/^<svg[\s>]/i.test(text)) {
    return new Blob([text], { type: "image/svg+xml" });
  } else {
    return new Blob([text], { type: "text/plain" });
  }
}

/**
 *
 * @param {ClipboardSettings} [options]
 * @returns {Promise<Array<Blob>>}
 */
export async function fromNavigator(options) {
  const items = await navigator.clipboard.read();
  return Promise.all(
    Array.from(items).map(async (item) => {
      const itemAllowedTypes = Array.from(item.types)
        .filter((type) => allowedTypes.includes(type))
        .sort((a, b) => allowedTypes.indexOf(a) - allowedTypes.indexOf(b));

      if (
        itemAllowedTypes.length === 1 &&
        itemAllowedTypes.at(0) === "text/plain"
      ) {
        const blob = await item.getType("text/plain");
        if (blob.size < maxParseableSize) {
          const text = await blob.text();
          return parseText(text, options);
        } else {
          return blob;
        }
      }

      const type = itemAllowedTypes.at(0);
      return item.getType(type);
    }),
  );
}

/**
 *
 * @param {DataTransfer} dataTransfer
 * @param {ClipboardSettings} [options]
 * @returns {Promise<Array<Blob>>}
 */
export async function fromDataTransfer(dataTransfer, options) {
  const items = await Promise.all(
    Array.from(dataTransfer.items)
      .filter((item) => allowedTypes.includes(item.type))
      .sort(
        (a, b) => allowedTypes.indexOf(a.type) - allowedTypes.indexOf(b.type),
      )
      .map(async (item) => {
        if (item.kind === "file") {
          return Promise.resolve(item.getAsFile());
        } else if (item.kind === "string") {
          return new Promise((resolve) => {
            const type = item.type;
            item.getAsString((text) => {
              if (type === "text/plain") {
                return resolve(parseText(text, options));
              } else {
                return resolve(new Blob([text], { type }));
              }
            });
          });
        }
        return Promise.resolve(null);
      }),
  );
  return items
    .filter((item) => !!item)
    .reduce((filtered, item) => {
      if (
        exclusiveTypes.includes(item.type) &&
        filtered.find((filteredItem) => exclusiveTypes.includes(filteredItem.type))
      ) {
        return filtered;
      }
      filtered.push(item);
      return filtered;
    }, []);
}

/**
 *
 * @param {*} clipboardData
 * @param {ClipboardSettings} [options]
 * @returns {Promise<Array<Blob>>}
 */
export function fromClipboardData(clipboardData, options) {
  return fromDataTransfer(clipboardData, options);
}

/**
 *
 * @param {*} e
 * @param {ClipboardSettings} [options]
 * @returns {Promise<Array<Blob>>}
 */
export function fromClipboardEvent(e, options) {
  return fromClipboardData(e.clipboardData, options);
}
