// #region DEBUG
/**
 * Browser-side debug logger. Sends structured entries to the debug server at port 7246.
 * All logs go to .claude/debug.log via the server — never to console.
 */
const DEBUG_URL = 'http://127.0.0.1:7246'

export function debugLog(hypothesis: string, location: string, message: string, data?: Record<string, unknown>): void {
  const payload = JSON.stringify({ h: hypothesis, loc: location, msg: message, data })
  try {
    // Fire-and-forget — don't await, don't block the main thread
    void fetch(DEBUG_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
    }).catch(() => {})
  } catch {
    // Swallow — debug logging must never throw
  }
}

/** Measure a synchronous block and log its duration. Returns the duration in ms. */
export function debugTime(hypothesis: string, location: string, label: string, fn: () => void): number {
  const t0 = performance.now()
  fn()
  const dur = performance.now() - t0
  debugLog(hypothesis, location, `${label} took ${dur.toFixed(2)}ms`, { durationMs: dur })
  return dur
}
// #endregion DEBUG
