import { Board } from "@penpot/plugin-types";
import { Operation } from "../core/Operation";
import { Role } from "../core/Role";
import { RoleBundle } from "../core/RoleBundle";
import { Situation } from "../core/Situation";

/** The roles exposed by {@link OpCreateBoard}. */
class RolesBoard extends RoleBundle {
    /** The free board the operation created. */
    readonly board = new Role<Board>("board");
}

/**
 * Creates a free board on the page, outside any component, and binds it to its
 * `board` role. Gives a case somewhere to move shapes to, or to edit, apart from
 * the configuration under test.
 */
export class OpCreateBoard extends Operation {
    readonly roles = new RolesBoard();

    /**
     * @param boardName - the name given to the new board
     */
    constructor(private readonly boardName: string) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const board = penpot.createBoard();
        board.name = this.boardName;
        board.resize(100, 100);
        situation.applyPosAdvanceX(board);
        situation.bind(this.roles.board, board);
    }

    toString(): string {
        return `create board ${this.boardName}`;
    }
}
