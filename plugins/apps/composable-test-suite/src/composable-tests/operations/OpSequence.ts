import { Situation } from "../core/Situation.ts";
import { Operation } from "../core/Operation.ts";

/**
 * An ordered composition of operations. Applies its steps left-to-right against
 * the same situation, threading the (mutated) situation through each, and marks
 * each applied step in the situation. The primary composition operator.
 */
export class OpSequence extends Operation {
    readonly steps: readonly Operation[];

    constructor(...steps: Operation[]) {
        super();
        this.steps = steps;
    }

    async applyTo(situation: Situation): Promise<void> {
        for (const step of this.steps) {
            await step.applyTo(situation);
            if (step.isRecorded()) {
                situation.markApplied(step);
                situation.recordApplication(step.toString());
            }
        }
    }

    toString(): string {
        return `sequence(${this.steps.map((s) => s.toString()).join(", ")})`;
    }

    /**
     * A sequence's variants are the cartesian product of its steps' variants: one
     * concrete sequence per combination, each preserving the original order.
     */
    override enumerateVariants(): Operation[] {
        return cartesianProduct(this.steps.map((step) => step.enumerateVariants())).map(
            (steps) => new OpSequence(...steps)
        );
    }
}

/** The cartesian product of the given lists, preserving order. */
function cartesianProduct<T>(lists: T[][]): T[][] {
    return lists.reduce<T[][]>((acc, list) => acc.flatMap((prefix) => list.map((item) => [...prefix, item])), [[]]);
}
