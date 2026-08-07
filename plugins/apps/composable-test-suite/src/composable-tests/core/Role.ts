import { Shape } from "@penpot/plugin-types";

/**
 * A typed, named binding key for a meaningful shape in a situation. A role
 * identifies a participant of the configuration under test (e.g. the copy's
 * child shape) independently of its concrete id, so operations and assertions
 * refer to participants by role rather than by raw handle. The phantom type
 * parameter `T` records the kind of shape the role is expected to bind.
 */
export class Role<T extends Shape = Shape> {
    /** Phantom marker carrying the role's shape type; never read at runtime. */
    declare private readonly _shapeType: T;

    /**
     * @param name - a stable, human-readable role name (used in diagnostics)
     */
    constructor(public readonly name: string) {}

    toString(): string {
        return this.name;
    }
}
