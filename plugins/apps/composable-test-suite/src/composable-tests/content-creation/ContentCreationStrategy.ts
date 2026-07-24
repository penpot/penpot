import { Board } from "@penpot/plugin-types";
import { Situation } from "../core/Situation.ts";

/**
 * A strategy for creating the CONTENT of a component: given the board that will
 * become a component's root, it fills the board with the component's child shapes.
 * The abstract contract is just this one step — what a component contains. Anything
 * needed to locate that content afterwards (e.g. in an instance's subtree) is the
 * concrete strategy's own affair.
 *
 * The current situation is passed in so a strategy may resolve runtime values from
 * it (e.g. a shape produced by an earlier operation, via a `ShapeTarget`);
 * strategies that create content from scratch can ignore it.
 */
export abstract class ContentCreationStrategy {
    /** Populates `board` with the component's content, drawing on `situation` as needed. */
    abstract createContent(situation: Situation, board: Board): void;
}
