/**
 * Safe guard.
 */
export class SafeGuard {
  /**
   * Maximum time.
   *
   * @readonly
   * @type {number}
   */
  static MAX_TIME = 1000

  /**
   * Maximum time.
   *
   * @type {number}
   */
  #maxTime = SafeGuard.MAX_TIME

  /**
   * Start time.
   *
   * @type {number}
   */
  #startTime = 0

  /**
   * Context
   *
   * @type {string}
   */
  #context = ""

  /**
   * Constructor
   *
   * @param {string} [context]
   * @param {number} [maxTime=SafeGuard.MAX_TIME]
   * @param {number} [startTime=Date.now()]
   */
  constructor(context, maxTime = SafeGuard.MAX_TIME, startTime = Date.now()) {
    this.#context = context
    this.#maxTime = maxTime;
    this.#startTime = startTime;
  }

  /**
   * Safe guard context.
   *
   * @type {string}
   */
  get context() {
    return this.#context
  }

  /**
   * Time elapsed.
   *
   * @type {number}
   */
  get elapsed() {
    return Date.now() - this.#startTime;
  }

  /**
   * Starts the safe guard timer.
   */
  start() {
    this.#startTime = Date.now();
    return this
  }

  /**
   * Updates the safe guard timer.
   *
   * @throws
   */
  update() {
    if (this.elapsed >= this.#maxTime) {
      throw new Error(`Safe guard timeout "${this.#context}"`);
    }
  }
}

export default SafeGuard;
