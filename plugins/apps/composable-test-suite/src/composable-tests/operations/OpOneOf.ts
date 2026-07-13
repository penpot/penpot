import { Operation } from "../core/Operation.ts";
import { Situation } from "../core/Situation.ts";

/**
 * A branch point: exactly one of its alternatives is taken. It is not applied
 * directly — it exists to be ENUMERATED, expanding a composition into one
 * concrete trajectory per alternative. Applying it directly is a usage error.
 */
export class OpOneOf extends Operation {
    readonly alternatives: readonly Operation[];

    constructor(...alternatives: Operation[]) {
        super();
        this.alternatives = alternatives;
    }

    async applyTo(_situation: Situation): Promise<void> {
        throw new Error("OneOf must be enumerated, not applied directly");
    }

    toString(): string {
        return `one-of(${this.alternatives.map((a) => a.toString()).join(" | ")})`;
    }

    /**
     * A branch point's variants are the union of its alternatives' variants (each
     * alternative is itself expanded, so nested choices flatten out).
     */
    override enumerateVariants(): Operation[] {
        return this.alternatives.flatMap((alternative) => alternative.enumerateVariants());
    }
}
