import { Shape } from "@penpot/plugin-types";
import { TestCase } from "../test-suite/TestCase.ts";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { ShapePropFillColor } from "../model/ShapeProp.ts";
import { OpChangeProperty } from "../operations/OpChangeProperty";
import { OpAssert } from "../operations/OpAssert";
import { OpCreateNestableComponent } from "../operations/OpCreateNestableComponent";
import { ContentCreationStrategyRectangle } from "../content-creation/ContentCreationStrategyRectangle.ts";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpOptional } from "../operations/OpOptional.ts";
import { OpDetach } from "../operations/OpDetach.ts";
import { Assert } from "../util/Assert.ts";

// distinct fill colours (read-back values are lower-case)
const BASELINE = new Color("#aaaaaa");
const GREEN = new Color("#00ff00"); // main edit
const BLUE = new Color("#0000ff"); // remote edit

/**
 * Case Q — a detached copy's nested copies stay linked.
 *
 * At nesting depth 0, 1 or 2, optionally change the main's rect, detach the
 * copy, then change the remote (original) main's rect. At depth 0 the copy's
 * rect is detached and keeps its value. Deeper, the rect lies in a nested copy,
 * which stays a copy one level up and inherits the main's override: with it the
 * rect keeps the main's colour, without it the rect follows the remote main.
 */
export function createTestCaseDetachKeepsNestedCopiesLinked(): TestCase {
    const opCreateComponent = new OpCreateNestableComponent(new ContentCreationStrategyRectangle(BASELINE));
    const fillColor = new ShapePropFillColor();
    const { remoteInstance, mainInstance, copyInstance } = opCreateComponent.roles;

    const rectOf =
        (role: typeof remoteInstance) =>
        (s: Situation): Shape =>
            opCreateComponent.contentCreator.getRectangle(s.get(role));

    const nestings = [opCreateComponent.createOpMakeNested(), opCreateComponent.createOpMakeNested()];
    const changeMain = new OpChangeProperty(rectOf(mainInstance), fillColor, GREEN, "main rect");
    const changeRemote = new OpChangeProperty(rectOf(remoteInstance), fillColor, BLUE, "remote rect");

    return new TestCase(
        "DetachKeepsNestedCopiesLinked",
        "A component containing a rectangle is created and a copy is instantiated from it, " +
            "optionally nested one or two levels deep. Optionally the current main's rectangle " +
            "is coloured. The copy is then detached, and the originally created (remote) main's " +
            "rectangle is coloured. Without nesting the copy's rectangle is no longer linked and " +
            "keeps its colour. With nesting it lies in a nested copy that stays linked: it keeps " +
            "the main's colour when the main was coloured, and otherwise follows the remote main.",
        new OpSequence(
            opCreateComponent,
            opCreateComponent.createOpInstantiate(),
            new OpOptional(nestings[0]),
            new OpOptional(nestings[1]),
            new OpOptional(changeMain),
            new OpDetach(copyInstance, "copy"),
            changeRemote,
            new OpAssert("the copy's rect follows the rule for its depth", (s) => {
                const rect = rectOf(copyInstance)(s);
                const nested = nestings.some((n) => s.wasApplied(n));
                const main = s.wasApplied(changeMain);
                if (nested) {
                    Assert.that(rect.isComponentInstance(), "the nested rect stays linked");
                    fillColor.assertEqual(fillColor.read(rect), main ? GREEN : BLUE);
                } else {
                    Assert.that(!rect.isComponentInstance(), "the detached rect is not linked");
                    fillColor.assertEqual(fillColor.read(rect), main ? GREEN : BASELINE);
                }
            })
        )
    );
}
