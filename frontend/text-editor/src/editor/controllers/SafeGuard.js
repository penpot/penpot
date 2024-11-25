/**
 * Max. amount of time we should allow.
 *
 * @type {number}
 */
const SAFE_GUARD_TIME = 1000;

/**
 * Time at which the safeguard started.
 *
 * @type {number}
 */
let startTime = Date.now();

/**
 * Marks the start of the safeguard.
 */
export function start() {
  startTime = Date.now();
}

/**
 * Checks if the safeguard should throw.
 */
export function update() {
  if (Date.now - startTime >= SAFE_GUARD_TIME) {
    throw new Error('Safe guard timeout');
  }
}

export default {
  start,
  update,
}
