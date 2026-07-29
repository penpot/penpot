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

/** Sweeps copy sub-head deletion and grid reflow while preserving alignment with the main. */
export function createTestCaseCopySubheadDeletePreservesSlots(): TestCase {
    const content = new ContentCreationStrategySiblingInstances(NESTED_COUNT, BASELINE, LAYOUT);
    const foundation = new OpCreateNestableComponent(content);
    // domain vocabulary for the generic roles: the outer component's main and its copy
    const outerMain = foundation.roles.mainInstance;
    const outerCopy = foundation.roles.copyInstance;

    // Resolve the copy's boundary sub-heads at apply time.
    const firstSubhead = (s: Situation): Shape => content.getSibling(s.get(outerCopy), 0);
    const lastSubhead = (s: Situation): Shape => content.getSibling(s.get(outerCopy), NESTED_COUNT - 1);
    const deleteFirstSubhead = new OpDeleteShape(firstSubhead, "first copy sub-head");
    const deleteLastSubhead = new OpDeleteShape(lastSubhead, "last copy sub-head");

    // Resizing the copy root reflows its grid children.
    const reflowCopy = new OpChangeProperty(outerCopy, new ShapePropHeight(), REFLOW_HEIGHT, "copy root");

    return new TestCase(
        "CopySubheadDeletePreservesSlots",
        "A grid component with nested instances and a copy is created; a boundary copy sub-head " +
            "is optionally deleted and the copy optionally reflowed; remaining copy sub-heads " +
            "must stay positionally aligned with the main.",
        new OpSequence(
            foundation,
            foundation.createOpInstantiate(),
            // Optionally delete the first or last copy sub-head.
            new OpOptional(new OpOneOf(deleteFirstSubhead, deleteLastSubhead)),
            // Optionally reflow the grid after deletion.
            new OpOptional(reflowCopy),
            new OpAssert("every copy sub-head still references its positional slot in the main", (s) => {
                SlotIntegrity.assertAligned(s.get(outerCopy) as Board, s.get(outerMain) as Board);
            })
        )
    );
}
