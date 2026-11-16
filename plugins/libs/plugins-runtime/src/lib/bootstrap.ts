/**
 * Repairs the JavaScript intrinsics needed by the SES sandbox.
 *
 * SES can throw during this step when the host page already contains a
 * `Temporal` global it does not recognize (for example, one wrapped by a
 * browser extension). The exception happens at module load time in
 * `index.ts`, so an uncaught failure here takes down the whole app.
 *
 * Returns the caught error so callers can report it and disable plugins,
 * instead of propagating it to the bundle loader.
 */
export function bootstrapSES(): Error | null {
  try {
    repairIntrinsics({
      evalTaming: 'unsafeEval',
      stackFiltering: 'verbose',
      errorTaming: 'unsafe',
      consoleTaming: 'unsafe',
      errorTrapping: 'none',
      unhandledRejectionTrapping: 'none',
    });

    return null;
  } catch (err) {
    return err instanceof Error ? err : new Error(String(err));
  }
}
