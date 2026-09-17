export const WEBGL_OPTIONS = Object.freeze({
  antialias: false,
  depth: true,
  stencil: true,
  alpha: true,
  preserveDrawingBuffer: true,
});

export function deriveSeed(masterSeed, caseId) {
  let seed = 2166136261;
  for (const character of `${masterSeed}\0${caseId}`) {
    seed = Math.imul(seed ^ character.charCodeAt(0), 16777619) >>> 0;
  }
  return seed;
}

function serializable(value, path = "case") {
  if (value === null || typeof value === "string" || typeof value === "boolean")
    return;
  if (typeof value === "number" && Number.isFinite(value)) return;
  if (Array.isArray(value)) {
    value.forEach((v, i) => serializable(v, `${path}[${i}]`));
    return;
  }
  if (value && Object.getPrototypeOf(value) === Object.prototype) {
    for (const [key, v] of Object.entries(value))
      serializable(v, `${path}.${key}`);
    return;
  }
  throw new Error(`Non-serializable value: ${path}`);
}

function viewValid(view) {
  return (
    view &&
    Number.isFinite(view.scale) &&
    view.scale > 0 &&
    Number.isFinite(view.x) &&
    Number.isFinite(view.y)
  );
}

export function collectCases(scenarios, masterSeed, filter = "") {
  const cases = [];
  const seen = new Set();
  const scenarioIds = new Set();
  for (const scenario of scenarios) {
    if (
      !scenario.id ||
      scenarioIds.has(scenario.id) ||
      !Number.isInteger(scenario.version) ||
      typeof scenario.createScene !== "function" ||
      typeof scenario.upload !== "function" ||
      !Array.isArray(scenario.cases) ||
      !scenario.cases.length
    )
      throw new Error("Invalid or duplicate scenario");
    scenarioIds.add(scenario.id);
    for (const c of scenario.cases) {
      serializable(c);
      const baseId = `${scenario.id}/${c.id}`;
      if (!c.id || seen.has(baseId))
        throw new Error(`Missing or duplicate case ID: ${baseId}`);
      seen.add(baseId);
      if (!c.params || !viewValid(c.initialView))
        throw new Error(`Invalid parameters/view: ${baseId}`);
      for (const kind of ["pan", "zoom"]) {
        const interaction = c.interactions?.[kind];
        if (
          !interaction ||
          !Array.isArray(interaction.frames) ||
          !interaction.frames.length ||
          !interaction.frames.every(viewValid) ||
          !Number.isFinite(interaction.settleMs) ||
          interaction.settleMs < 0
        )
          throw new Error(`Incomplete ${kind} interaction: ${baseId}`);
      }
      // Validate static workloads before any build or browser measurement. Browser
      // construction still owns its descriptors and remains outside timed spans.
      scenario.createScene(c.params, deriveSeed(masterSeed, baseId));
      for (const kind of ["load", "pan", "zoom"]) {
        const id = `${baseId}/${kind}`;
        if (filter && !id.includes(filter)) continue;
        cases.push({
          id,
          scenarioId: scenario.id,
          scenarioVersion: scenario.version,
          description: scenario.description,
          sceneSeed: deriveSeed(masterSeed, baseId),
          params: c.params,
          initialView: c.initialView,
          interaction: kind === "load" ? null : c.interactions[kind],
          group: kind === "load" ? "fresh-module-context" : `warm-${kind}`,
          preparation:
            kind === "load"
              ? "new-context-module; upload; set-view-end; full"
              : "case-owned-context; upload; full; restore-view-and-full-before-each-attempt; evolving-caches",
          status: "unattempted",
          attempts: [],
        });
      }
    }
  }
  if (!cases.length) throw new Error(`No matching cases: ${filter}`);
  return cases;
}

export function validateMetrics(metrics) {
  return Object.fromEntries(
    Object.entries(metrics)
      .filter(
        ([key, value]) =>
          !Number.isFinite(value) ||
          value < 0 ||
          (value === 0 &&
            !["setViewEndMs", "settlingRequestedMs"].includes(key)),
      )
      .map(([key, value]) => [
        key,
        Number.isFinite(value) ? "below-timer-resolution" : "non-finite",
      ]),
  );
}
