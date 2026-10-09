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
import { OpSwap } from "../operations/OpSwap.ts";
import { Assert } from "../util/Assert.ts";

// distinct fill colours (read-back values are lower-case)
const BASELINE = new Color("#aaaaaa");
const RED = new Color("#ff0000"); // the component the copy swaps to
const GREEN = new Color("#00ff00"); // the component the main swaps to
const BLUE = new Color("#0000ff"); // remote edit

/**
 * Case AA — a copy's swap survives changes to the main.
 *
 * A component nests a copy of another; a copy of the outer component swaps its
 * nested copy for a third component. Optionally the outer main then swaps its own
 * nested copy for a fourth component, and optionally the nested component's main
 * changes its rect. The copy's nested head stays an instance of the component it
 * swapped to, showing that component's rect.
 */
export function createTestCaseCopySwapSurvivesMainChanges(): TestCase {
    const opCreateComponent = new OpCreateNestableComponent(new ContentCreationStrategyRectangle(BASELINE));
    const opCreateRed = new OpCreateNestableComponent(new ContentCreationStrategyRectangle(RED), "red");
    const opCreateGreen = new OpCreateNestableComponent(new ContentCreationStrategyRectangle(GREEN), "green");
    const fillColor = new ShapePropFillColor();
    const { remoteInstance } = opCreateComponent.roles;

    // the nested copy inside the outer copy, and inside the outer main
    const copyNested = (s: Situation) => opCreateComponent.getNestedInstance(s, 1);
    const mainNested = (s: Situation) => opCreateComponent.getCopyInstance(s, 0);

    return new TestCase(
        "CopySwapSurvivesMainChanges",
        "A component containing a rectangle is created and nested once in an outer component, " +
            "and two more components with differently coloured rectangles are created. A copy " +
            "of the outer component swaps its nested copy for the first of them. Optionally the " +
            "outer main swaps its own nested copy for the second, and optionally the nested " +
            "component's main recolours its rectangle. The copy's swap must survive: its nested " +
            "copy stays an instance of the component it swapped to and shows its colour.",
        new OpSequence(
            opCreateComponent,
            opCreateRed,
            opCreateGreen,
            opCreateComponent.createOpInstantiate(),
            opCreateComponent.createOpMakeNested(),
            new OpSwap(
                copyNested,
                (s) => opCreateRed.getMainComponent(s),
                "the copy's nested copy for the red component"
            ),
            new OpOptional(
                new OpSwap(
                    mainNested,
                    (s) => opCreateGreen.getMainComponent(s),
                    "the main's nested copy for the green component"
                )
            ),
            new OpOptional(
                new OpChangeProperty(
                    (s: Situation) => opCreateComponent.contentCreator.getRectangle(s.get(remoteInstance)),
                    fillColor,
                    BLUE,
                    "remote rect"
                )
            ),
            new OpAssert("the copy's nested copy keeps its swap", (s) => {
                const head = copyNested(s);
                Assert.that(
                    head.component()?.id === opCreateRed.getMainComponent(s).id,
                    "the nested copy is a red instance"
                );
                fillColor.assertEqual(fillColor.read(opCreateComponent.contentCreator.getRectangle(head)), RED);
            })
        )
    );
}
