import { Board } from "@penpot/plugin-types";
import { Operation } from "../core/Operation";
import { Situation } from "../core/Situation";
import { Role } from "../core/Role";
import { ContentCreationStrategy } from "../content-creation/ContentCreationStrategy";

/**
 * Instantiates content into a fresh board and binds that board to a role. Creates
 * a board, fills it via the content-creation strategy (which may, for example,
 * append an instance of an existing component), and binds the board to `target`
 * so later steps can act on it. A general "place this content somewhere and track
 * it" step.
 */
export class OpInstantiateContent extends Operation {
    /**
     * @param strategy - fills the new board with content
     * @param target - the role to bind the created board to
     */
    constructor(
        private readonly strategy: ContentCreationStrategy,
        private readonly target: Role<Board>
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const board = penpot.createBoard();
        board.name = "Content";
        board.resize(100, 100);
        this.strategy.createContent(situation, board);
        situation.bind(this.target, board);
    }

    toString(): string {
        return "instantiate content";
    }
}
