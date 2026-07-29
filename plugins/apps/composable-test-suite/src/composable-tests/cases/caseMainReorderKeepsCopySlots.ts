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

/** Verifies that main-side reordering realigns copies without swap slots. */
export function createTestCaseMainReorderKeepsCopySlots(): TestCase {
    // Reorder behavior is independent of layout.
    const content = new ContentCreationStrategySiblingInstances(NESTED_COUNT, BASELINE);
    const foundation = new OpCreateNestableComponent(content);
    // Bind the generic roles to the outer component instances.
    const outerMain = foundation.roles.mainInstance;
    const outerCopy = foundation.roles.copyInstance;

    // Resolve the main's first nested sub-head at apply time.
    const firstMainSubhead = (s: Situation): Shape => content.getSibling(s.get(outerMain), 0);

    return new TestCase(
        "MainReorderKeepsCopySlots",
        "A component with nested instances and a copy is created; the first main sub-head is moved " +
            "to the end; component sync must restore positional alignment without swap slots.",
        new OpSequence(
            foundation,
            foundation.createOpInstantiate(),
            // Move the main's first sub-head to the end.
            new OpReorderShape(firstMainSubhead, NESTED_COUNT - 1, "first MAIN sub-head"),
            new OpAssert("every copy sub-head still references its positional slot in the main", (s) => {
                SlotIntegrity.assertAligned(s.get(outerCopy) as Board, s.get(outerMain) as Board);
            })
        )
    );
}
