import { LibraryComponent } from "@penpot/plugin-types";
import { TestCase } from "../test-suite/TestCase.ts";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { ShapePropFillColor, ShapePropOpacity } from "../model/ShapeProp.ts";
import { OpChangeProperty } from "../operations/OpChangeProperty";
import { OpAssert } from "../operations/OpAssert";
import { OpCreateNestableComponent } from "../operations/OpCreateNestableComponent";
import { ContentCreationStrategyRectangle } from "../content-creation/ContentCreationStrategyRectangle.ts";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpSwap } from "../operations/OpSwap.ts";
import { OpReset } from "../operations/OpReset.ts";
import { Assert } from "../util/Assert.ts";

// distinct fill colours (read-back values are lower-case)
const BASELINE = new Color("#aaaaaa");
const RED = new Color("#ff0000"); // the component the copy swaps to

/**
 * Case AB — resetting a nested swap leaves the rest of the top copy alone.
 *
 * A copy of a component that nests another overrides its own root's opacity,
 * swaps its nested copy for a third component, then resets the nested copy. The
 * reset undoes the swap and must change nothing outside the nested copy; today it
 * also clears the root's opacity override (F12 in the stabilization plan), which
 * the case pins as a known failure.
 */
export function createTestCaseResetOfNestedSwapKeepsTopCopyOverrides(): TestCase {
    const opCreateComponent = new OpCreateNestableComponent(new ContentCreationStrategyRectangle(BASELINE));
    const opCreateRed = new OpCreateNestableComponent(new ContentCreationStrategyRectangle(RED), "red");
    const fillColor = new ShapePropFillColor();
    const opacity = new ShapePropOpacity();
    const { copyInstance } = opCreateComponent.roles;

    const copyNested = (s: Situation) => opCreateComponent.getNestedInstance(s, 1);
    const redComponent = (s: Situation): LibraryComponent => {
        const component = s.get(opCreateRed.roles.mainInstance).component();
        if (component === null) {
            throw new Error("the red main instance has no component");
        }
        return component;
    };

    return new TestCase(
        "ResetOfNestedSwapKeepsTopCopyOverrides",
        "A component containing a rectangle is created and nested once in an outer component; " +
            "another component with a red rectangle is created. A copy of the outer component " +
            "lowers its own opacity, swaps its nested copy for the red component, and then resets " +
            "the nested copy. The reset must undo the swap (the nested copy shows the original " +
            "rectangle again) and leave the copy's own opacity override alone. Today the reset " +
            "also clears that override (known failure F12).",
        new OpSequence(
            opCreateComponent,
            opCreateRed,
            opCreateComponent.createOpInstantiate(),
            opCreateComponent.createOpMakeNested(),
            new OpChangeProperty(copyInstance, opacity, 0.5, "copy"),
            new OpSwap(copyNested, redComponent, "the copy's nested copy for the red component"),
            new OpReset(copyNested, "the copy's nested copy"),
            new OpAssert("the reset undoes the swap and keeps the copy's opacity", (s) => {
                const head = copyNested(s);
                Assert.that(head.component()?.id !== redComponent(s).id, "the nested copy is no longer red");
                fillColor.assertEqual(fillColor.read(opCreateComponent.contentCreator.getRectangle(head)), BASELINE);
                // the reset clears the opacity, which the API then reads as null
                Assert.knownFailure<number | null>(
                    "F12",
                    opacity.read(s.get(copyInstance)) ?? null,
                    0.5,
                    null,
                    (a, b) => a === b
                );
            })
        )
    );
}
