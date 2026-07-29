import { Operation } from "../core/Operation.ts";
import { OpSkip } from "./OpSkip.ts";
import { OpOneOf } from "./OpOneOf.ts";

/**
 * Sweeps "with and without `operation`": a branch point between applying it and
 * skipping it (a `OneOf` over `[operation, Skip]`). Enumerates to two trajectories,
 * one including `operation` and one not. The same `operation` instance can then be
 * queried via `Situation.wasApplied` inside an assertion to branch on which
 * trajectory ran.
 */
export class OpOptional extends OpOneOf {
    constructor(operation: Operation) {
        super(operation, new OpSkip());
    }
}
