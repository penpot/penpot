/**
 * Formats one argument of a captured `console` call for the execution log.
 *
 * Objects are shown as indented JSON. Errors have no enumerable fields, so
 * JSON would print them as `{}`; they are shown by their string form (name
 * and message) instead. Values JSON cannot serialize (circular references,
 * BigInt) fall back to their string form, so logging never makes the
 * executed code throw.
 *
 * @param arg - the value passed to the console method
 * @returns the text to append to the log
 */
export function formatLogArgument(arg: unknown): string {
    if (arg instanceof Error) {
        return String(arg);
    }
    if (typeof arg === "object") {
        try {
            return JSON.stringify(arg, null, 2);
        } catch {
            return String(arg);
        }
    }
    return String(arg);
}
