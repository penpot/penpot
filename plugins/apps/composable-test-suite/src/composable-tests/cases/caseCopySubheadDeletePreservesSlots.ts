import { Board, Shape } from "@penpot/plugin-types";
import { TestCase } from "../test-suite/TestCase.ts";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { ShapePropHeight } from "../model/ShapeProp.ts";
import { OpAssert } from "../operations/OpAssert";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpOneOf } from "../operations/OpOneOf.ts";
import { OpOptional } from "../operations/OpOptional.ts";
import { OpChangeProperty } from "../operations/OpChangeProperty";
import { OpCreateNestableComponent } from "../operations/OpCreateNestableComponent";
import { OpDeleteShape } from "../operations/OpDeleteShape";
import { ContentCreationStrategySiblingInstances } from "../content-creation/ContentCreationStrategySiblingInstances";
import { SlotIntegrity } from "../util/SlotIntegrity";

const BASELINE = new Color("#aaaaaa");
const NESTED_COUNT = 3;
const LAYOUT = "grid" as const;
const REFLOW_HEIGHT = 500; // any size change of the grid root forces a reflow

/**
 * Case D — deleting a nested sub-head of a copy is well-behaved.
 *
 * Builds a component whose main holds several nested component instances inside
 * a GRID layout, plus a copy of it, then sweeps: optionally delete ONE of the
 * copy's nested sub-heads (first or last), optionally resize the copy root
 * afterwards (which forces a grid reflow), asserting every remaining visible
 * sub-head still references its positional slot in the main (see
 * {@link SlotIntegrity}).
 *
 * Deleting a sub-head of a COPY only hides it (a deleted-subinstance), so the
 * copy must stay aligned and valid. The sweep pins down a real crash: a grid
 * reflow used to move the hidden (cell-less) child to the FRONT of the copy's
 * children, shifting every sibling out of its positional slot and failing the
 * file's referential-integrity validation (:missing-slot on every sub-head).
 * Deleting the FIRST sub-head masked the bug (moving it to the front is a
 * no-op) — hence the first/last sweep. The main-side counterpart of the crash
 * is caseMainReorderKeepsCopySlots; the clj regression tests live in
 * `common/test/.../comp_main_edit_breaks_copy_slots_test.cljc` and
 * `common/test/.../types/shape_layout_test.cljc`.
 */
export function createTestCaseCopySubheadDeletePreservesSlots(): TestCase {
    const foundation = new OpCreateNestableComponent(
        new ContentCreationStrategySiblingInstances(NESTED_COUNT, BASELINE, LAYOUT)
    );
    // domain vocabulary for the generic roles: the outer component's main and its copy
    const outerMain = foundation.roles.mainInstance;
    const outerCopy = foundation.roles.copyInstance;

    // the copy's first/last nested sub-heads, resolved at apply-time
    const firstSubhead = (s: Situation): Shape => (s.get(outerCopy).children ?? [])[0];
    const lastSubhead = (s: Situation): Shape => {
        const children = s.get(outerCopy).children ?? [];
        return children[children.length - 1];
    };
    const deleteFirstSubhead = new OpDeleteShape(firstSubhead, "first copy sub-head");
    const deleteLastSubhead = new OpDeleteShape(lastSubhead, "last copy sub-head");

    // resizing the copy root forces a grid reflow over the (possibly hidden) children
    const reflowCopy = new OpChangeProperty(outerCopy, new ShapePropHeight(), REFLOW_HEIGHT, "copy root");

    return new TestCase(
        "CopySubheadDeletePreservesSlots",
        "A component whose main holds several nested component instances in a grid layout is " +
            "created, plus a copy of it. Optionally, the first or the last of the copy's nested " +
            "sub-heads is deleted, and optionally the copy root is resized afterwards, forcing a " +
            "grid reflow. Every remaining visible sub-head of the copy must still reference the " +
            "slot at its position in the main — neither the copy-side delete nor the reflow may " +
            "corrupt the positional slot matching that Penpot's file validation enforces.",
        new OpSequence(
            foundation,
            foundation.createOpInstantiate(),
            // sweep which sub-head is deleted, if any (last is the crash repro:
            // the grid reflow used to move the hidden child to the front)
            new OpOptional(new OpOneOf(deleteFirstSubhead, deleteLastSubhead)),
            // sweep with/without a grid reflow after the delete
            new OpOptional(reflowCopy),
            new OpAssert("every copy sub-head still references its positional slot in the main", (s) => {
                SlotIntegrity.assertAligned(s.get(outerCopy) as Board, s.get(outerMain) as Board);
            })
        )
    );
}
