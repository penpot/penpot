type ErrorWithData = Error & {
    data?: unknown;
};

/**
 * Produces a useful task error message, including structured ClojureScript exception data.
 *
 * @param error - the value thrown while handling a plugin task
 * @returns A human-readable error message
 */
export function formatTaskError(error: unknown): string {
    if (!(error instanceof Error)) {
        return String(error);
    }

    const details = formatErrorData((error as ErrorWithData).data);
    return details ? `${error.message} (${details})` : error.message;
}

/**
 * Formats plain objects and iterable ClojureScript maps as key-value pairs.
 *
 * Falls back to the value's printed representation: in release builds the
 * Closure compiler renames ClojureScript internals (MapEntry `key`/`val`,
 * Keyword `fqn`), so entry extraction can come up empty even though `toString`
 * still prints the data readably (e.g. `{:type :validation}`).
 *
 * @param data - structured exception data
 * @returns Formatted exception details, or an empty string when unavailable
 */
function formatErrorData(data: unknown): string {
    const entries = getEntries(data);
    if (entries.length > 0) {
        return entries.map(([key, value]) => `${formatKey(key)}: ${formatValue(value)}`).join(", ");
    }

    return printedForm(data);
}

/**
 * Returns the value's own printed representation, or an empty string when it
 * only has the default `Object.prototype.toString` one.
 */
function printedForm(value: unknown): string {
    if (!value || typeof value !== "object") {
        return "";
    }

    try {
        const text = String(value);
        return text === "[object Object]" ? "" : text;
    } catch {
        return "";
    }
}

/**
 * Extracts entries from JavaScript objects or ClojureScript map-like values.
 */
function getEntries(data: unknown): Array<[unknown, unknown]> {
    if (!data || typeof data !== "object") {
        return [];
    }

    if (Symbol.iterator in data) {
        try {
            return Array.from(data as Iterable<unknown>).flatMap((entry) => {
                if (Array.isArray(entry) && entry.length >= 2) {
                    return [[entry[0], entry[1]]];
                }

                if (entry && typeof entry === "object") {
                    const mapEntry = entry as { key?: unknown; val?: unknown };
                    if ("key" in mapEntry && "val" in mapEntry) {
                        return [[mapEntry.key, mapEntry.val]];
                    }

                    if (Symbol.iterator in entry) {
                        const pair = Array.from(entry as Iterable<unknown>);
                        return pair.length >= 2 ? [[pair[0], pair[1]]] : [];
                    }
                }

                return [];
            });
        } catch {
            // fall through to ordinary object properties
        }
    }

    return Object.entries(data);
}

/**
 * Formats ClojureScript keywords without their leading colon.
 */
function formatKey(key: unknown): string {
    if (key && typeof key === "object") {
        const keyword = key as { fqn?: unknown; name?: unknown };
        if (typeof keyword.fqn === "string") {
            return keyword.fqn;
        }
        if (typeof keyword.name === "string") {
            return keyword.name;
        }
    }

    return String(key).replace(/^:/, "");
}

/**
 * Formats detail values while keeping nested data readable.
 */
function formatValue(value: unknown): string {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
        return String(value);
    }

    if (value && typeof value === "object") {
        const keyword = value as { fqn?: unknown; name?: unknown };
        if (typeof keyword.fqn === "string") {
            return keyword.fqn;
        }
        if (typeof keyword.name === "string") {
            return keyword.name;
        }

        if (!Array.isArray(value)) {
            const printed = printedForm(value);
            if (printed) {
                return printed.replace(/^:/, "");
            }
        }
    }

    if (value === null || value === undefined) {
        return String(value);
    }

    try {
        return JSON.stringify(value);
    } catch {
        return String(value);
    }
}
