// Browser-independent protocol; the runtime owns the clock and cancellation.
export async function drain(
  rt,
  { flags = 0, origin = rt.now(), immediate = false } = {},
) {
  const slices = [];
  let viewportReadyMs;
  do {
    rt.check();
    const timestamp = immediate ? rt.now() : await rt.frame();
    immediate = false;
    rt.check();
    const start = rt.now();
    const frameType = rt.module._render(timestamp, flags);
    const end = rt.now();
    slices.push({ timestamp, flags, frameType, durationMs: end - start });
    rt.recordSlice?.(slices.at(-1));
    rt.check();
    if (![1, 2, 3].includes(frameType))
      throw new Error(`Unexpected frame type: ${frameType}`);
    if (frameType === 3 || frameType === 2) viewportReadyMs ??= end - origin;
    if (frameType === 2)
      return { slices, viewportReadyMs, fullMs: end - origin };
    // The production scheduler passes the previous FrameType as flags.
    flags = frameType;
  } while (true);
}

export async function restore(rt, view) {
  rt.check();
  rt.module._set_view_start();
  rt.module._set_view(view.scale, view.x, view.y);
  rt.module._set_view_end();
  return drain(rt, { flags: 4, immediate: true });
}

export async function interact(rt, interaction) {
  rt.check();
  const start = rt.now();
  rt.module._set_view_start();
  const cachedSlices = [];
  let lastInput;
  for (const view of interaction.frames) {
    const timestamp = await rt.frame();
    rt.check();
    lastInput = rt.now();
    rt.module._set_view(view.scale, view.x, view.y);
    const cacheStart = rt.now();
    rt.module._render_from_cache(0);
    cachedSlices.push({ timestamp, durationMs: rt.now() - cacheStart });
    rt.recordCached?.(cachedSlices.at(-1));
  }
  const activeEnd = rt.now();
  await rt.sleep(interaction.settleMs);
  rt.check();
  const finalizationStart = rt.now();
  rt.module._set_view_end();
  const setViewEndMs = rt.now() - finalizationStart;
  const result = await drain(rt, {
    flags: 4,
    origin: finalizationStart,
    immediate: true,
  });
  return {
    metrics: {
      activeInteractionMs: activeEnd - start,
      settlingRequestedMs: interaction.settleMs,
      settlingActualMs: finalizationStart - activeEnd,
      setViewEndMs,
      timeToViewportReadyMs: result.viewportReadyMs,
      timeToFullMs: result.fullMs,
      lastInputToFullMs: rt.now() - lastInput,
    },
    slices: result.slices,
    cachedSlices,
  };
}
