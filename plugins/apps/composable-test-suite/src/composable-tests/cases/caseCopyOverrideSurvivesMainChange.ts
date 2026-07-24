import { TestCase } from "../test-suite/TestCase.ts";
import { Color } from "../model/Color";
import { ShapePropFillColor } from "../model/ShapeProp.ts";
import { OpChangeProperty } from "../operations/OpChangeProperty";
import { OpAssert } from "../operations/OpAssert";
import { OpCreateSimpleComponentWithCopy } from "../operations/OpCreateSimpleComponentWithCopy";
import { OpSequence } from "../operations/OpSequence.ts";

// the three distinct fill colours the case uses (read-back values are lower-case)
const BASELINE = new Color("#aaaaaa");
const OVERRIDE = new Color("#ff0000");
const MAIN_CHANGE = new Color("#00ff00");

/**
 * Case B — an override on a copy survives a later change to the main.
 *
 * Override the copy's child fill, then change the main's child fill to a
 * different colour, and assert the copy still shows the override (a touched
 * property is not overwritten by main propagation).
 */
export function createTestCaseCopyOverrideSurvivesMainChange(): TestCase {
    const fillColor = new ShapePropFillColor();
    const opCreateComponent = new OpCreateSimpleComponentWithCopy(BASELINE);
    const opOverrideCopy = new OpChangeProperty(opCreateComponent.roles.copyChild, fillColor, OVERRIDE);
    return new TestCase(
        "CopyOverrideSurvivesMainChange",
        "A component containing a single rectangle is created, plus a copy of it. The copy's " +
            "rectangle is then given an override: its fill colour is changed directly on the copy. " +
            "Afterwards the main's rectangle is changed to yet another colour. The copy must keep " +
            "its own overridden colour — a property touched on the copy is protected from being " +
            "overwritten by later changes propagating from the main.",
        new OpSequence(
            opCreateComponent,
            opOverrideCopy,
            new OpChangeProperty(opCreateComponent.roles.mainChild, fillColor, MAIN_CHANGE),
            new OpAssert("copy child keeps its override after the main changes", (situation) =>
                opOverrideCopy.assertHasChangedProperty(situation, opCreateComponent.roles.copyChild)
            )
        )
    );
}
