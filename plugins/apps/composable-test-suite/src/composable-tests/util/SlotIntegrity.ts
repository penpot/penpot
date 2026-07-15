import { Board, Shape } from "@penpot/plugin-types";
import { Assert } from "./Assert";

/**
 * Checks the positional slot alignment between a component copy and its main.
 *
 * Background: in a copy of a component, each nested sub-instance head should, by
 * position, reference (its `shape-ref`) the child at the SAME index in the
 * component's main. Penpot's validator (`check-required-swap-slot` in
 * `common/.../files/validate.cljc`) requires a swap slot only when a sub-head's
 * `shape-ref` is no longer a child of the near main parent at all (a real swap;
 * error `:missing-slot`); a pure positional mismatch is a reorder that the
 * component sync realigns. Still, in the steady state (after propagation has
 * settled) a copy that was never swapped must be POSITIONALLY aligned with its
 * main — this helper asserts that stronger invariant through the Plugin API, so
 * a test catches any operation that knocks a copy's child order out of sync
 * with its main.
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
