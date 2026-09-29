import { Board } from "@penpot/plugin-types";
import { TestCase } from "../test-suite/TestCase.ts";
import { Color } from "../model/Color";
import { ShapePropFillColor } from "../model/ShapeProp.ts";
import { Assert } from "../util/Assert.ts";
import { OpAssert } from "../operations/OpAssert";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpOptional } from "../operations/OpOptional.ts";
import { OpChangeProperty } from "../operations/OpChangeProperty";
import { OpCreateBoard } from "../operations/OpCreateBoard";
import { OpCreateSimpleComponentWithCopy } from "../operations/OpCreateSimpleComponentWithCopy";
import { OpMoveShape } from "../operations/OpMoveShape";

const BASELINE = new Color("#aaaaaa");
const EDIT_COLOR = new Color("#ff0000");

/**
 * A shape moved out of a main leaves its copies, also when another edit lands
 * right after the move, before propagation has run.
 *
 * The baseline assertion waits for the setup to settle; the setup's own commits
 * would otherwise sync the copy in the same pass and hide the result. The move
 * and the optional edit then run back to back, so their commits arrive together;
 * without the edit, the move is the last commit.
 */
export function createTestCaseMainChildMovedOutLeavesCopy(): TestCase {
    const foundation = new OpCreateSimpleComponentWithCopy(BASELINE);
    const { mainChild, copyRoot } = foundation.roles;
    const outside = new OpCreateBoard("Outside");
    const outsideBoard = outside.roles.board;

    const move = new OpMoveShape(mainChild, outsideBoard, "main rect", "outside board");

    return new TestCase(
        "MainChildMovedOutLeavesCopy",
        "A component containing a single rectangle is created, plus a copy of it and a board " +
            "outside the component. The main's rectangle is moved into that board, optionally " +
            "followed at once by a fill change on the board. The copy must lose its rectangle " +
            "either way.",
        new OpSequence(
            foundation,
            outside,
            new OpAssert("the copy starts with its rectangle", (s) => {
                const copyChildren = (s.get(copyRoot) as Board).children;
                Assert.that(copyChildren.length === 1, `the copy should have one child, found ${copyChildren.length}`);
            }),
            move,
            new OpOptional(new OpChangeProperty(outsideBoard, new ShapePropFillColor(), EDIT_COLOR, "outside board")),
            new OpAssert("the rectangle left the main and the copy", (s) => {
                move.assertHasMoved(s, mainChild);
                const copyChildren = (s.get(copyRoot) as Board).children;
                Assert.that(
                    copyChildren.length === 0,
                    `the copy should have no children, found ${copyChildren.map((c) => c.name).join(", ")}`
                );
            })
        )
    );
}
