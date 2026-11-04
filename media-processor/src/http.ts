/**
 * Small helpers shared by the HTTP clients that talk to the backend.
 *
 * They live here so the jobs client and the storage client cannot drift on
 * how they build a URL or how much of an error body they keep.
 */

/** Bytes of a failed response body that are logged, never returned. */
export const ERROR_SNIPPET_BYTES = 4 * 1024;

export function joinUri(base: string, path: string): string {
  return new URL(path, base.endsWith("/") ? base : `${base}/`).toString();
}

/**
 * A JSON object. Arrays are rejected on purpose: a transit map is encoded as
 * a JSON array, so a client that did not negotiate `Accept: application/json`
 * would otherwise be accepted as a record and fail later with a confusing
 * "unexpected action: undefined".
 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
