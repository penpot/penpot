/**
 * "Statically covered" coverage targets.
 *
 * These members ARE exercised behaviourally by the test suite, but the recording
 * proxy structurally cannot credit them, so they would otherwise show as
 * uncovered. The reasons are all recorder limitations: frozen SES values (the
 * proxy must return them raw), base-interface attribution (members redeclared on
 * concrete types are credited there), type maps (events are credited on
 * `Penpot.on/off`), type-guard narrowing the recorder can't perform, and methods
 * whose return type the surface generator couldn't resolve (the result is handed
 * back raw). See README.md "Coverage notes".
 *
 * Keys are `Interface.member#mode` (mode ∈ get/set/call), exactly matching the
 * recorder's accessed-set keys and the targets in `computeCoverage`. Only add a
 * target here when a named test genuinely exercises it — this set feeds the
 * "effective" coverage number, so over-claiming makes that number dishonest.
 *
 * Recorder-credited (recorded) coverage always wins over this set: a target that
 * turns out to be recorded simply never shows as static.
 */
export const STATIC_COVERAGE: ReadonlySet<string> = new Set<string>([
  // ShapeBase.fills — every concrete shape redeclares `fills`, so accesses are
  // attributed to the concrete type (Rectangle.fills, …); exercised pervasively
  // (fills-strokes.test.ts, misc.test.ts).
  'ShapeBase.fills#get',
  'ShapeBase.fills#set',

  // utils.types predicates — `penpot.utils.types` is a frozen data property, so
  // its members can't be wrapped. Exercised in platform.test.ts.
  'ContextTypesUtils.isBoard#call',
  'ContextTypesUtils.isBool#call',
  'ContextTypesUtils.isEllipse#call',
  'ContextTypesUtils.isGroup#call',
  'ContextTypesUtils.isMask#call',
  'ContextTypesUtils.isPath#call',
  'ContextTypesUtils.isRectangle#call',
  'ContextTypesUtils.isSVG#call',
  'ContextTypesUtils.isText#call',
  'ContextTypesUtils.isVariantComponent#call',
  'ContextTypesUtils.isVariantContainer#call',

  // utils.geometry.center — `penpot.utils.geometry` is likewise a frozen data
  // property, so the call can't be wrapped. Exercised (and verified) in
  // platform.test.ts.
  'ContextGeometryUtils.center#call',

  // shapesColors() returns objects whose declared type the surface generator
  // couldn't resolve (it records as `type: null`), so the recorder hands the
  // result back raw and cannot credit nested access. The members are exercised
  // in colors.test.ts (entry.shapesInfo[0].property / .shapeId).
  'ColorShapeInfo.shapesInfo#get',
  'ColorShapeInfoEntry.index#get',
  'ColorShapeInfoEntry.property#get',
  'ColorShapeInfoEntry.shapeId#get',

  // Deterministic events — `on`/`off` are credited on `Penpot`, never as
  // `EventsMap` members. Exercised in events.test.ts. The remaining events
  // (pagechange/filechange/themechange/contentsave/finish) are not triggered
  // deterministically headless and stay genuinely uncovered.
  'EventsMap.selectionchange#get',
  'EventsMap.shapechange#get',

  // LibraryVariantComponent — the recorder types a component as LibraryComponent
  // and can't narrow via the isVariant() type-guard; the behaviour is exercised
  // via VariantContainer.variants in variants.test.ts.
  'LibraryVariantComponent.variants#get',
  'LibraryVariantComponent.variantProps#get',
  'LibraryVariantComponent.addVariant#call',
  'LibraryVariantComponent.setVariantProperty#call',
]);
