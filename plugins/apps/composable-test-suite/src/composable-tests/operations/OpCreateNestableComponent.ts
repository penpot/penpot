import { Board, Shape } from "@penpot/plugin-types";
import { Situation } from "../core/Situation";
import { Operation } from "../core/Operation";
import { Role } from "../core/Role";
import { RoleBundle } from "../core/RoleBundle";
import { DataKey } from "../core/DataKey";
import { ShapeUtil } from "../util/ShapeUtil.ts";
import { ContentCreationStrategy } from "../content-creation/ContentCreationStrategy.ts";

/** The name of the board that becomes the original (innermost) component root. */
const ORIGINAL_COMPONENT_NAME = "ComponentRoot";

/** The name of the board that wraps a copy to form an outer nesting level. */
const NESTED_COMPONENT_NAME = "OuterComponentRoot";

/**
 * The instance roles of a nestable-component configuration. Three component
 * INSTANCES are tracked, with these meanings as the configuration evolves:
 *   - `remoteInstance` — the originally created main instance (the fixed origin);
 *     never re-pointed.
 *   - `mainInstance` — the CURRENT outermost main; re-pointed to the new outer
 *     component on each nesting.
 *   - `copyInstance` — the instance whose deep content reflects propagation; set
 *     by instantiate, and replaced on each nesting by the new outer instance.
 * Content shapes (e.g. a child rect) are not roles here — they are found inside a
 * given instance via the content-creation strategy.
 */
class RolesNestableComponent extends RoleBundle {
    readonly remoteInstance = new Role<Board>("remote-instance");
    readonly mainInstance = new Role<Board>("main-instance");
    readonly copyInstance = new Role<Board>("copy-instance");
}

/**
 * The mutable per-lineage state of a nestable component, accumulated as the
 * configuration is built. Created once by the foundation operation and mutated in
 * place by the operations it vends (instantiate, nest) — they alter this object
 * rather than replacing it. Held in the situation under the foundation op's own
 * key, and reached by related ops through the foundation op's accessor.
 */
export class NestingData {
    /**
     * The per-level copy instances, in nesting order: index 0 is the innermost
     * (the first instantiated copy), each subsequent entry an outer level that
     * wraps the one below. Appended to as nesting deepens.
     */
    readonly copyInstances: Board[] = [];
}

/**
 * Instantiates the current main component and binds the result as the copy, and
 * appends it as the innermost (level 0) copy of the lineage's nesting data.
 */
class OpInstantiateCopy extends Operation {
    constructor(private readonly parent: OpCreateNestableComponent<ContentCreationStrategy>) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const roles = this.parent.roles;
        const main = situation.get(roles.mainInstance);
        const component = main.component();
        if (component === null) {
            throw new Error(`"${main.name}" is not a component instance; cannot instantiate a copy`);
        }
        const copy = component.instance() as Board;
        situation.bind(roles.copyInstance, copy);
        situation.applyPosAdvanceX(copy);

        // the freshly instantiated copy is the innermost (level 0) copy
        this.parent.getNestingData(situation).copyInstances.push(copy);
    }

    toString(): string {
        return "instantiate copy";
    }
}

/**
 * Adds a nesting level around the current copy. Wraps the `copyInstance` in a new
 * outer board, turns that board into a component, re-points `mainInstance` to the
 * new (outer) main, then instantiates the new component and binds it as the new
 * `copyInstance`. `remoteInstance` is left untouched (the fixed origin). The new
 * outer instance is appended as the next (outer) level's copy.
 */
class OpMakeNestedComponent extends Operation {
    constructor(private readonly parent: OpCreateNestableComponent<ContentCreationStrategy>) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const roles = this.parent.roles;
        const inner = situation.get(roles.copyInstance);

        // a new outer board containing the current copy, made into a component
        const outerBoard = penpot.createBoard();
        outerBoard.name = NESTED_COMPONENT_NAME;
        outerBoard.appendChild(inner);
        const outerComponent = penpot.library.local.createComponent([outerBoard]);
        situation.applyPosAdvanceX(outerComponent.mainInstance());

        // the outer main becomes the current main; remote stays fixed
        situation.bind(roles.mainInstance, outerComponent.mainInstance() as Board);

        // an instance of the outer component becomes the new copy
        const outerInstance = outerComponent.instance() as Board;
        situation.bind(roles.copyInstance, outerInstance);

        // the new outer instance is the copy for the newly added (outer) level
        this.parent.getNestingData(situation).copyInstances.push(outerInstance);
    }

    toString(): string {
        return "make nested component";
    }
}

/**
 * The foundation operation for a component that can be instantiated and nested. As
 * the first step of a trajectory it creates the component (its content supplied by
 * a content-creation strategy), binds both `remoteInstance` and `mainInstance` to
 * its main instance, and initialises the lineage's `NestingData`. It owns the
 * three instance roles and provides the operations that grow the configuration —
 * a case obtains them from this op (`op.createOpInstantiate()`,
 * `op.createOpMakeNested()`). Those operations hold a reference to this op and
 * mutate its nesting data, so the data's key stays private here. The strategy type
 * `TStrategy` is preserved so its content accessors are available to the case.
 *
 * The lineage's per-level instances are exposed via `getNestedInstanceHead` /
 * `getNestedInstance`, so a case can address the instance at any nesting level
 * (e.g. as the target of a variant switch) without knowing how nesting is stored.
 */
export class OpCreateNestableComponent<TContentCreator extends ContentCreationStrategy> extends Operation {
    readonly roles = new RolesNestableComponent();

    /** Private key under which this lineage's (mutable) nesting data is stored. */
    private readonly nestingDataKey = new DataKey<NestingData>("nesting-data");

    /**
     * @param contentCreator - creates and locates the component's content
     */
    constructor(public readonly contentCreator: TContentCreator) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        // start this lineage's nesting data (mutated in place by related ops)
        situation.setKeyedData(this.nestingDataKey, new NestingData());

        // create the board, fill it via the strategy, and make it a component
        const board = penpot.createBoard();
        board.name = ORIGINAL_COMPONENT_NAME;
        board.resize(100, 100);
        this.contentCreator.createContent(situation, board);
        situation.applyPosAdvanceX(board);

        const component = penpot.library.local.createComponent([board]);
        const main = component.mainInstance() as Board;

        // remote and main both start at the original main; copy is set by instantiate
        situation.bind(this.roles.remoteInstance, main);
        situation.bind(this.roles.mainInstance, main);
    }

    toString(): string {
        return "create nestable component";
    }

    /** An operation that instantiates the current main and binds it as the copy. */
    createOpInstantiate(): Operation {
        return new OpInstantiateCopy(this);
    }

    /** An operation that adds a nesting level around the current copy. */
    createOpMakeNested(): Operation {
        return new OpMakeNestedComponent(this);
    }

    /**
     * This lineage's nesting data in `situation` — created by this op's application
     * and mutated by the operations it vends. Throws if this op has not yet run.
     */
    getNestingData(situation: Situation): NestingData {
        const data = situation.getKeyedData(this.nestingDataKey);
        if (data === undefined) {
            throw new Error("nesting data not initialised; run the create-nestable-component op first");
        }
        return data;
    }

    /**
     * @param situation - the situation
     * @param level - the nesting level to retrieve (0 = initially created instance, least nested)
     * @return the instance of the originally created component at nesting `level`, i.e. the descendant
     *   of the copy instance that corresponds to the instance of the originally created component.
     *   At level 0 this is the copy instance itself; at deeper levels it is the instance nested inside
     *   that level's copy, surrounded by the outer boards added by the nesting operations.
     *   Note: This is the shape that directly contains the content creation strategy's content.
     */
    getNestedInstance(situation: Situation, level: number): Shape {
        const copy = this.getCopyInstance(situation, level);
        // the copy itself is the head at level 0; deeper levels wrap it, so descend
        // to the (innermost) original component root
        const head =
            copy.name === ORIGINAL_COMPONENT_NAME
                ? copy
                : ShapeUtil.findShape(copy, (shape) => shape.name === ORIGINAL_COMPONENT_NAME);
        if (head === null) {
            throw new Error(`no nested head "${ORIGINAL_COMPONENT_NAME}" found at level ${level}`);
        }
        return head;
    }

    /**
     * @param situation - the situation
     * @param level - the nesting level to retrieve (0 = initially created instance, least nested)
     * @return the copy instance root shape at nesting `level`, i.e. the root of the copy instance that was created
     *   at that level.
     *   Note: A copy instance is created by the instantiate operation (first copy) and by each nesting
     *   operation.
     */
    getCopyInstance(situation: Situation, level: number): Board {
        const copies = this.getNestingData(situation).copyInstances;
        const copy = copies[level];
        if (copy === undefined) {
            throw new Error(`no copy at level ${level} (have ${copies.length})`);
        }
        return copy;
    }
}
