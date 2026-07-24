import { TestCase } from "../test-suite/TestCase.ts";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { ShapePropFillColor } from "../model/ShapeProp.ts";
import { OpAssert } from "../operations/OpAssert";
import { OpSequence } from "../operations/OpSequence.ts";
import { OpOptional } from "../operations/OpOptional.ts";
import { OpCreateVariantContainer } from "../operations/OpCreateVariantContainer";
import { OpCreateNestableComponent } from "../operations/OpCreateNestableComponent";
import { OpSwitchVariant } from "../operations/OpSwitchVariant";
import { ContentCreationStrategyRectangle } from "../content-creation/ContentCreationStrategyRectangle.ts";

/**
 * Case M — a variant switch propagates like a swap (the variant-switch flavour of
 * the swap sweep).
 *
 * Build a variant SET of peer members (member i is a rectangle of colour
 * COLORS[i]) and nest the base member at EVERY level, so each level has a variant
 * head. Then OPTIONALLY switch the head at each level to a differently-coloured
 * sibling (a switch at level i selects member i+1, colour SWAP_COLORS[i]) and
 * assert the colour that surfaces at every level. A switch at level i propagates
 * OUTWARD to level i and every outer level until a switch at a higher level
 * overrides it — so the colour at level i is the switch applied at the HIGHEST
 * index j ≤ i, else the base member's colour.
 */
export function createTestCaseVariantSwitchPropagates(): TestCase {
    const fillColor = new ShapePropFillColor();
    // the variant members' colours: index 0 is the base member, 1..3 the siblings a
    // switch can select. swapColors[i] is the colour a switch at level i selects.
    const baseColor = new Color("#aaaaaa");
    const swapColors = [new Color("#ff0000"), new Color("#00ff00"), new Color("#0000ff")];
    const variantColors = [baseColor, ...swapColors]; // variant i has colour COLORS[i]
    const nestingLevels = 3;

    // the variant set: one rectangle member per colour, addressed by index value
    const opCreateVariantContainer = new OpCreateVariantContainer(
        ...variantColors.map((color) => new ContentCreationStrategyRectangle(color))
    );

    // nest the base member (variant 0) at every level: its instance is the head at
    // each nesting level, exactly the target a per-level switch acts on
    const opCreateComponent = new OpCreateNestableComponent(opCreateVariantContainer.createContentCreationStrategy(0));

    // switch[i] switches level i's variant instance to member i+1 (colour SWAP_COLORS[i]).
    // The nestable op yields the structural nested head; the variant container finds
    // the variant instance within it — keeping variant knowledge out of the nestable op.
    const opVariantSwitches = Array.from(
        { length: nestingLevels },
        (_unused, i) =>
            new OpSwitchVariant(
                (s: Situation) =>
                    opCreateVariantContainer.getVariantInstance(opCreateComponent.getNestedInstance(s, i)),
                opCreateVariantContainer.valueForVariant(i + 1)
            )
    );

    // the colour expected at level i: the switch applied at the highest j ≤ i, else base
    const expectedAtLevel = (s: Situation, level: number): Color => {
        for (let j = level; j >= 0; j--) {
            if (s.wasApplied(opVariantSwitches[j])) return swapColors[j];
        }
        return baseColor;
    };

    return new TestCase(
        "VariantSwitchPropagates",
        "A variant set with four differently coloured rectangle members is created. Its base " +
            "member is placed inside a component, from which a copy is instantiated and then " +
            "wrapped in further components, one nesting level at a time — so for each level there " +
            "is a copy carrying the variant instance at that depth. At each level, that variant " +
            "instance is optionally switched to a differently coloured member. Each level's " +
            "rectangle must show the colour selected by the switch applied at the highest level " +
            "at or below it: a variant switch must propagate through nesting exactly like a " +
            "component swap.",
        new OpSequence(
            // foundations: create the variants, a component with an instance
            // and nest the instance several times
            opCreateVariantContainer,
            opCreateComponent, // creates component with a variant instance
            opCreateComponent.createOpInstantiate(), // level 0
            opCreateComponent.createOpMakeNested(), // level 1
            opCreateComponent.createOpMakeNested(), // level 2
            // optionally switch the variant to a unique colour at each nesting level
            ...opVariantSwitches.map((sw) => new OpOptional(sw)),
            // check the colour that surfaces at every level
            new OpAssert("each level shows the highest-precedence applied switch", (s) => {
                for (let level = 0; level < nestingLevels; level++) {
                    const nestedInstance = opCreateComponent.getNestedInstance(s, level);
                    const rect = ContentCreationStrategyRectangle.findRectangle(nestedInstance);
                    fillColor.assertEqual(fillColor.read(rect), expectedAtLevel(s, level));
                }
            })
        )
    );
}
