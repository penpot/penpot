import { Shape, Board } from "@penpot/plugin-types";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { Operation } from "../core/Operation";
import { Role } from "../core/Role";
import { RoleBundle } from "../core/RoleBundle";

/**
 * The roles exposed by a "component with a copy" configuration. The participants
 * that take part in propagation are the CHILD shapes of the main and of the copy
 * (the component roots are boards); these are named here, along with the copy's
 * root. The foundation op owns an instance of this bundle and binds its roles.
 */
class RolesComponent extends RoleBundle {
    /** The main component's child shape (the one an edit-to-main targets). */
    readonly mainChild = new Role<Shape>("main-child");

    /** The copy instance's corresponding child shape. */
    readonly copyChild = new Role<Shape>("copy-child");

    /** The copy instance's root (the instantiated board). */
    readonly copyRoot = new Role<Board>("copy-root");
}

/**
 * The foundation operation for a "simple component with a copy" configuration: a
 * one-child component (a board containing a single rectangle) plus one instance of
 * it. As the first step of a trajectory it creates them and binds the main's and
 * the copy's child rectangles to its `mainChild` / `copyChild` roles and the
 * copy's root to `copyRoot`. The child rectangle starts with a known baseline
 * fill, so a later "value followed" check is distinguishable from coincidence.
 */
export class OpCreateSimpleComponentWithCopy extends Operation {
    readonly roles = new RolesComponent();

    /**
     * @param baselineColor - the child rectangle's initial fill colour
     */
    constructor(private readonly baselineColor: Color) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        // create the board + child rectangle that will become the component
        const board = penpot.createBoard();
        board.name = "ComponentRoot";
        board.resize(100, 100);

        const rect = penpot.createRectangle();
        rect.name = "Child";
        rect.resize(50, 50);
        rect.fills = [{ fillColor: this.baselineColor.hex, fillOpacity: this.baselineColor.opacity }];
        board.appendChild(rect);

        // turn the board into a component; its main instance is the board itself
        const component = penpot.library.local.createComponent([board]);
        const mainRoot = component.mainInstance();
        situation.applyPosAdvanceX(mainRoot);

        // instantiate a copy of the component on the current page
        const copyRoot = component.instance();
        situation.applyPosAdvanceX(copyRoot);

        situation.bind(this.roles.mainChild, this.onlyChildOf(mainRoot));
        situation.bind(this.roles.copyChild, this.onlyChildOf(copyRoot));
        situation.bind(this.roles.copyRoot, copyRoot);
    }

    toString(): string {
        return "create simple component with a copy";
    }

    /** Returns the single child of `root`, failing if it does not have exactly one. */
    private onlyChildOf(root: Shape): Shape {
        const children = (root as Board).children;
        if (!children || children.length !== 1) {
            throw new Error(`Expected "${root.name}" to have exactly one child, found ${children?.length ?? 0}`);
        }
        return children[0];
    }
}
