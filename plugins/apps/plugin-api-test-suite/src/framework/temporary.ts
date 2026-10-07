// Shared by the tests (in the sandbox) and the CI driver (in Node), so it must
// stay free of imports.

/**
 * Name prefix of every team and project the tests create. Tests delete the
 * teams they create; the live CI driver deletes the teams and projects with
 * this prefix after a run. Runs from the plugin UI leave the projects behind.
 */
export const TEMPORARY_PREFIX = '__api_test_tmp__';

/** Returns a unique name with {@link TEMPORARY_PREFIX} for a test project. */
export function temporaryName(label: string): string {
  return `${TEMPORARY_PREFIX} ${label} ${Date.now()}`;
}
