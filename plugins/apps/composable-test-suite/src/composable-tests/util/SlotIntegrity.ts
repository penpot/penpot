import { Board, Shape } from "@penpot/plugin-types";
import { Assert } from "./Assert";

/** Checks steady-state positional alignment between a component copy and its main. */
export class SlotIntegrity {
    /**
     * Returns copy-child indices whose references differ from the main child at
     * the same index.
     */
    static misalignedIndices(copyRoot: Board, mainRoot: Board): number[] {
        const copyKids: Shape[] = copyRoot.children ?? [];
        const mainKids: Shape[] = mainRoot.children ?? [];
        const bad: number[] = [];
        copyKids.forEach((child, i) => {
            const nearMatch = mainKids[i];
            if (!nearMatch) return; // no positional near match -> no slot required
            const ref = child.componentRefShape ? child.componentRefShape() : null;
            if (!ref || ref.id !== nearMatch.id) bad.push(i);
        });
        return bad;
    }

    /**
     * Asserts that every sub-head of `copyRoot` references its positional slot in
     * `mainRoot`; fails (listing the offending indices) if any does not.
     */
    static assertAligned(copyRoot: Board, mainRoot: Board): void {
        const bad = SlotIntegrity.misalignedIndices(copyRoot, mainRoot);
        Assert.that(
            bad.length === 0,
            `copy sub-heads must reference their positional slot in the main; ` +
                `misaligned indices [${bad.join(", ")}]`
        );
    }
}
