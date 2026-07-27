import { TestCase } from "../test-suite/TestCase.ts";
import { Color } from "../model/Color";
import { ShapePropFillColor, ShapePropHeight, ShapePropRotation } from "../model/ShapeProp.ts";
import { Assert } from "../util/Assert.ts";
import { OpAssert } from "../operations/OpAssert";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpOneOf } from "../operations/OpOneOf.ts";
import { OpOptional } from "../operations/OpOptional.ts";
import { OpChangeProperty } from "../operations/OpChangeProperty";
import { OpCreateSimpleComponentWithCopy } from "../operations/OpCreateSimpleComponentWithCopy";

const BASELINE = new Color("#aaaaaa");
const SYNC_COLOR = new Color("#00ff00");
const SYNC_HEIGHT = 80; // the rect starts at 50x50
const ROTATION_DEGREES = 45;

/**
 * Case C — an edit to the main's rectangle syncs to the copy, also when the copy
 * is rotated.
 *
 * Create a one-rectangle component with a copy, then sweep two choice points:
 * OPTIONALLY rotate the entire copy root by 45°, and apply ONE of several edits to
 * the MAIN's rectangle (fill colour, height). Assert that whichever edit was
 * applied is reflected on the COPY's rectangle. The rotation sweep guards the
 * regression class where a transformed copy stops receiving main propagation.
 */
export function createTestCaseMainEditSyncs(): TestCase {
    const foundation = new OpCreateSimpleComponentWithCopy(BASELINE);
    const { mainChild, copyChild, copyRoot } = foundation.roles;

    // optionally rotate the whole copy instance (its root board)
    const rotateCopy = new OpChangeProperty(copyRoot, new ShapePropRotation(), ROTATION_DEGREES, "copy root");

    // one of several edits to the main's rectangle
    const mainEdits = [
        new OpChangeProperty(mainChild, new ShapePropFillColor(), SYNC_COLOR, "main rect"),
        new OpChangeProperty(mainChild, new ShapePropHeight(), SYNC_HEIGHT, "main rect"),
    ];

    return new TestCase(
        "MainEditSyncs",
        "A component containing a single rectangle is created, plus a copy of it. The copy is " +
            "optionally rotated by 45° as a whole. Then one of several properties (fill colour, " +
            "height) is changed on the main's rectangle. The change must propagate to the copy's " +
            "rectangle — also when the copy was rotated beforehand.",
        new OpSequence(
            foundation,
            new OpOptional(rotateCopy),
            new OpOneOf(...mainEdits),
            new OpAssert("the applied main edit is reflected on the copy's rectangle", (s) => {
                const applied = mainEdits.filter((edit) => s.wasApplied(edit));
                Assert.that(applied.length === 1, `exactly one main edit should be applied, found ${applied.length}`);
                applied[0].assertHasChangedProperty(s, copyChild);
            })
        )
    );
}
