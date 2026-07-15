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
 * Regression test for the referential-integrity crash (:missing-slot).
 * Copy sub-heads used to be matched to the main's children purely BY POSITION
 * (`find-near-match`): reordering a sub-head inside the MAIN changed that
 * order while the copies kept their shape-refs, so every copy sub-head was
 * "swapped" relative to its position without a slot -> validation failed, and
 * the reorder through the Plugin API hung the app (in the workspace UI it
 * crashed the document). Validation now treats a ref that is still a child of
 * the near main parent as a reorder (no slot required), and the component sync
 * realigns the copies' order — which this case asserts end to end: after the
 * main-side reorder settles, every copy sub-head must again reference its
 * positional slot in the main.
 *
 * The layout is irrelevant (the equivalent clj test reproduces it with no
 * layout); the trigger is purely the main-side reorder. The copy-side edits in
 * case D were always correct. The clj regression tests for the same bug family
 * live in `common/test/.../comp_main_edit_breaks_copy_slots_test.cljc`.
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
