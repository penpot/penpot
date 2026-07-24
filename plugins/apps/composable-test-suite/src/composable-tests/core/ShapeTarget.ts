import { Shape } from "@penpot/plugin-types";
import { Situation } from "./Situation";
import { Role } from "./Role";

/**
 * A way to obtain a shape from a situation at apply-time: either a `Role` (looked
 * up in the situation) or a function that derives the shape from the situation
 * (e.g. searching an instance's subtree via a creation strategy). Resolving
 * late — rather than capturing a fixed handle — lets an operation follow a role
 * that earlier operations re-point, and lets derived shapes reflect the current
 * configuration.
 */
export type ShapeTarget = Role | ((situation: Situation) => Shape);

/** Resolves `target` to a concrete shape against `situation`. */
export function resolveTarget(target: ShapeTarget, situation: Situation): Shape {
    return target instanceof Role ? situation.get(target) : target(situation);
}
