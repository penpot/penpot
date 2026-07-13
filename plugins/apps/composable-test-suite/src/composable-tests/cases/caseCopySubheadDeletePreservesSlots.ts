import { Board, Shape } from "@penpot/plugin-types";
import { TestCase } from "../test-suite/TestCase.ts";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { OpAssert } from "../operations/OpAssert";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpOptional } from "../operations/OpOptional.ts";
import { OpCreateNestableComponent } from "../operations/OpCreateNestableComponent";
import { OpDeleteShape } from "../operations/OpDeleteShape";
import { ContentCreationStrategySiblingInstances } from "../content-creation/ContentCreationStrategySiblingInstances";
import { SlotIntegrity } from "../util/SlotIntegrity";

const BASELINE = new Color("#aaaaaa");
const NESTED_COUNT = 3;
const LAYOUT = "grid" as const;

/**
 * Case D — CHARACTERIZATION: deleting a nested sub-head of a copy is well-behaved.
 *
 * Builds a component whose main holds several nested component instances, plus a
 * copy of it, then SWEEPS whether one of the copy's nested sub-heads is deleted,
 * asserting every remaining sub-head still references its positional slot in the
 * main (see {@link SlotIntegrity}).
 *
 * This case PASSES for every layout (none / flex / grid): deleting a sub-head of a
 * COPY only hides it (a deleted-subinstance), so the copy stays aligned and valid.
 * We initially suspected the layout (flex, then grid) was the trigger; it is not —
 * the copy-side delete is correct. The actual :missing-slot crash comes from a
 * MAIN-side edit; see {@link ../cases/caseE} (and the clj regression test
 * `common/test/.../comp_main_edit_breaks_copy_slots_test.cljc`). This case is kept
 * as a characterization guard that copy-side deletes never corrupt the copy.
 */
export function createTestCaseCopySubheadDeletePreservesSlots(): TestCase {
    const foundation = new OpCreateNestableComponent(
        new ContentCreationStrategySiblingInstances(NESTED_COUNT, BASELINE, LAYOUT)
    );
    // domain vocabulary for the generic roles: the outer component's main and its copy
    const outerMain = foundation.roles.mainInstance;
    const outerCopy = foundation.roles.copyInstance;

    // the copy's first nested sub-head, resolved at apply-time
    const firstSubhead = (s: Situation): Shape => (s.get(outerCopy).children ?? [])[0];
    const deleteFirstSubhead = new OpDeleteShape(firstSubhead, "first copy sub-head");

    return new TestCase(
        "CopySubheadDeletePreservesSlots",
        "A component whose main holds several nested component instances is created, plus a copy " +
            "of it. One of the copy's nested sub-heads is optionally deleted. Every remaining " +
            "sub-head of the copy must still reference the slot at its position in the main — a " +
            "copy-side delete must not corrupt the positional slot matching that Penpot's file " +
            "validation enforces.",
        new OpSequence(
            foundation,
            foundation.createOpInstantiate(),
            // sweep with/without the deletion (the delete variant is the repro)
            new OpOptional(deleteFirstSubhead),
            new OpAssert("every copy sub-head still references its positional slot in the main", (s) => {
                SlotIntegrity.assertAligned(s.get(outerCopy) as Board, s.get(outerMain) as Board);
            })
        )
    );
}
