import { Board, Shape } from "@penpot/plugin-types";
import { Assert } from "./Assert";

/**
 * Checks the positional swap-slot invariant that Penpot's file validator enforces
 * for component copies.
 *
 * Background: in a copy of a component, each nested sub-instance head must, by
 * position, reference (its `shape-ref`) the child at the SAME index in the
 * component's main. Penpot finds the "near match" purely by position
 * (`find-near-match` in `common/.../types/file.cljc`) and then requires a swap
 * slot whenever a sub-head's `shape-ref` is not that positional near match
 * (`check-required-swap-slot` in `common/.../files/validate.cljc`, error
 * `:missing-slot` — "Shape has been swapped, should have swap slot").
 *
 * When the copy's child order diverges from the main's (e.g. after deleting one
 * sub-head, which shifts the rest) while their shape-refs still point to their
 * original slots, the invariant breaks and — with no swap slot recorded — the file
 * fails referential-integrity validation, crashing the project.
 *
 * This helper replicates that positional check through the Plugin API so a test
 * can assert the invariant holds.
 */
export class SlotIntegrity {
    /**
     * Returns the indices of `copyRoot`'s children whose `shape-ref` does not point
     * to the child at the same index in `mainRoot` (the positional near match) —
     * i.e. the sub-heads that would require a swap slot.
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
                `mismatch (missing swap slot) at indices [${bad.join(", ")}]`
        );
    }
}
