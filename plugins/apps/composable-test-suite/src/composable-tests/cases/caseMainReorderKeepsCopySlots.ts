import { Board, Shape } from "@penpot/plugin-types";
import { TestCase } from "../test-suite/TestCase.ts";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { OpAssert } from "../operations/OpAssert";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpCreateNestableComponent } from "../operations/OpCreateNestableComponent";
import { OpReorderShape } from "../operations/OpReorderShape";
import { ContentCreationStrategySiblingInstances } from "../content-creation/ContentCreationStrategySiblingInstances";
import { SlotIntegrity } from "../util/SlotIntegrity";

const BASELINE = new Color("#aaaaaa");
const NESTED_COUNT = 3;

/**
 * Case E — reordering a sub-head IN THE MAIN must not break copies.
 *
 * This is the ACTUAL cause of the referential-integrity crash (:missing-slot).
 * `find-near-match` matches a copy's nested sub-heads to the main's children BY
 * POSITION. Reordering a sub-head inside the MAIN changes that order, but the
 * copies keep their shape-refs and are NOT given swap slots — so every copy is
 * now "swapped" relative to its position without a slot -> validation fails.
 *
 * The layout is irrelevant (the equivalent clj test reproduces it with no layout);
 * the trigger is purely the main-side reorder. The copy-side edits in case D are
 * correct — it's this main-side edit that corrupts.
 *
 * ⚠ WARNING — this case reproduces the bug through the LIVE app, and the app
 * currently CHOKES on the corrupt state: reordering a main sub-head via the Plugin
 * API hangs (and in the workspace UI it crashes the document). Because the test
 * runner lives inside that same app, running this case will HANG the panel — it
 * cannot report a normal pass/fail until the underlying bug is fixed. It is
 * included as an executable, documented reproduction, NOT as a routine test; do
 * NOT include it in a "run all". The reliable, non-hanging capture of this exact
 * bug is the clj test `common/test/.../comp_main_edit_breaks_copy_slots_test.cljc`.
 * Once Penpot propagates swap slots to copies on a main reorder, this case will
 * complete and the assertion will pass.
 */
export function createTestCaseMainReorderKeepsCopySlots(): TestCase {
    // layout "none": the bug does not depend on the layout, only on the main reorder
    const foundation = new OpCreateNestableComponent(
        new ContentCreationStrategySiblingInstances(NESTED_COUNT, BASELINE)
    );
    // domain vocabulary for the generic roles: the outer component's main and its copy
    const outerMain = foundation.roles.mainInstance;
    const outerCopy = foundation.roles.copyInstance;

    // the MAIN's first nested sub-head, resolved at apply-time
    const firstMainSubhead = (s: Situation): Shape => (s.get(outerMain).children ?? [])[0];

    return new TestCase(
        "MainReorderKeepsCopySlots",
        "A component whose main holds several nested component instances is created, plus a copy " +
            "of it. A nested sub-instance is then reordered INSIDE THE MAIN, which changes the " +
            "positional matching that copies rely on. Penpot must keep the copies' slot references " +
            "valid (assigning swap slots where needed); every copy sub-head must still reference " +
            "its positional slot in the main afterwards.",
        new OpSequence(
            foundation,
            foundation.createOpInstantiate(),
            // move the main's first sub-head to the end — the corrupting operation
            new OpReorderShape(firstMainSubhead, NESTED_COUNT - 1, "first MAIN sub-head"),
            new OpAssert("every copy sub-head still references its positional slot in the main", (s) => {
                SlotIntegrity.assertAligned(s.get(outerCopy) as Board, s.get(outerMain) as Board);
            })
        )
    );
}
