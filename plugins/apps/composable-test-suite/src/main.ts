// The plugin UI (runs in the iframe; has the DOM, talks to the sandbox via
// postMessage). It renders the enumerated test tree as selectable, foldable rows
// grouped by case, and updates each row in place — addressed by stable id — as the
// sandbox streams test start/finish notifications.

import "./style.css";

// ── theme (plugin-styles colour variables are gated on [data-theme]) ───────────
function applyTheme(theme: string | null): void {
    document.documentElement.setAttribute("data-theme", theme === "light" ? "light" : "dark");
}
applyTheme(new URLSearchParams(window.location.search).get("theme"));

// ── messages exchanged with the sandbox ────────────────────────────────────────
interface TestInfo {
    id: string;
    name: string;
}
interface TestGroupInfo {
    identifier: string;
    description: string;
    tests: TestInfo[];
}
interface TestTree {
    groups: TestGroupInfo[];
}
interface ResultMessage {
    passed: boolean;
    errorMessage?: string;
    transcript: string[];
}

type TestState = "idle" | "scheduled" | "running" | "passed" | "failed";

// ── a single test row ───────────────────────────────────────────────────────────
class TestRowView {
    readonly id: string;
    readonly element: HTMLElement;
    private readonly checkbox: HTMLInputElement;
    private readonly circle: HTMLElement;
    private readonly status: HTMLElement;
    private readonly details: HTMLElement;
    private state: TestState = "idle";
    private expanded = false;

    /**
     * @param info - the test's wire info (stable id and display name)
     * @param identifier - the test's composite case identifier (the case's
     *     identifier plus the 1-based index, e.g. "MainEditSyncs-2"); assigned
     *     to the checkbox as its DOM id, for direct addressing
     */
    constructor(info: TestInfo, identifier: string) {
        this.id = info.id;

        this.element = document.createElement("div");
        this.element.className = "row test-row";

        const header = document.createElement("div");
        header.className = "row-header";

        this.checkbox = document.createElement("input");
        this.checkbox.type = "checkbox";
        this.checkbox.className = "checkbox-input";
        this.checkbox.id = identifier;

        this.circle = document.createElement("span");
        this.circle.className = "status-circle";

        const name = document.createElement("span");
        name.className = "row-name body-s";
        name.textContent = info.name;

        this.status = document.createElement("span");
        this.status.className = "row-status body-s";

        // clicking the row (but not the checkbox) toggles the details fold
        header.addEventListener("click", (event) => {
            if (event.target !== this.checkbox) this.toggle();
        });

        header.append(this.checkbox, this.circle, name, this.status);

        this.details = document.createElement("div");
        this.details.className = "row-details";
        this.details.hidden = true;
        this.showPlaceholder();

        this.element.append(header, this.details);
        this.setState("idle");
    }

    get selected(): boolean {
        return this.checkbox.checked;
    }

    set selected(value: boolean) {
        this.checkbox.checked = value;
    }

    onSelectionChange(handler: () => void): void {
        this.checkbox.addEventListener("change", handler);
    }

    /** Sets the visual state (drives the status circle and the right-side label). */
    setState(state: TestState): void {
        this.state = state;
        this.element.dataset.state = state;
        this.circle.dataset.state = state;
        this.status.textContent = state === "scheduled" ? "Pending…" : state === "running" ? "Running…" : "";
        // a (re)scheduled test has no current result; show the placeholder again
        if (state === "scheduled") this.showPlaceholder();
    }

    /** Shows the "not run yet" placeholder in the details fold. */
    private showPlaceholder(): void {
        this.details.replaceChildren();
        const hint = document.createElement("div");
        hint.className = "details-hint body-s";
        hint.textContent = "Run this test to see details.";
        this.details.appendChild(hint);
    }

    /** Fills the fold with this test's applied steps and any failure message. */
    setDetails(result: ResultMessage): void {
        this.setState(result.passed ? "passed" : "failed");
        this.details.replaceChildren();

        if (!result.passed && result.errorMessage) {
            const error = document.createElement("div");
            error.className = "details-error body-s";
            error.textContent = result.errorMessage;
            this.details.appendChild(error);
        }

        const steps = document.createElement("ol");
        steps.className = "details-steps body-s";
        for (const step of result.transcript) {
            const item = document.createElement("li");
            item.textContent = step;
            steps.appendChild(item);
        }
        this.details.appendChild(steps);
    }

    isPassed(): boolean {
        return this.state === "passed";
    }
    isFailed(): boolean {
        return this.state === "failed";
    }
    /** Indicates whether the test takes part in a run that is still in flight. */
    isActive(): boolean {
        return this.state === "running" || this.state === "scheduled";
    }

    private toggle(): void {
        this.expanded = !this.expanded;
        this.details.hidden = !this.expanded;
        this.element.dataset.expanded = String(this.expanded);
    }
}

// ── a group of rows (one test case) ─────────────────────────────────────────────
class GroupView {
    readonly element: HTMLElement;
    readonly rows: TestRowView[];
    private readonly checkbox: HTMLInputElement;
    private readonly circle: HTMLElement;
    private readonly count: HTMLElement;
    private readonly passFail: HTMLElement;
    private readonly caret: HTMLElement;
    private readonly rowsContainer: HTMLElement;
    private expanded = false;

    constructor(info: TestGroupInfo, onSelectionChange: () => void) {
        this.rows = info.tests.map((test, i) => new TestRowView(test, `${info.identifier}-${i + 1}`));

        this.element = document.createElement("div");
        this.element.className = "group";

        const header = document.createElement("div");
        header.className = "group-header";

        this.caret = document.createElement("span");
        this.caret.className = "caret";

        this.checkbox = document.createElement("input");
        this.checkbox.type = "checkbox";
        this.checkbox.className = "checkbox-input";
        this.checkbox.id = info.identifier;
        this.checkbox.addEventListener("change", () => {
            for (const row of this.rows) row.selected = this.checkbox.checked;
            onSelectionChange();
        });

        this.circle = document.createElement("span");
        this.circle.className = "status-circle";

        const identifier = document.createElement("span");
        identifier.className = "group-name body-s";
        identifier.textContent = info.identifier;

        this.count = document.createElement("span");
        this.count.className = "group-count body-s";
        this.count.textContent = `[${info.tests.length} tests]`;

        // passed / failed counts, kept current by refresh()
        this.passFail = document.createElement("span");
        this.passFail.className = "group-passfail body-s";

        // clicking the header (but not the checkbox) folds the group open/closed
        header.addEventListener("click", (event) => {
            if (event.target !== this.checkbox) this.toggle();
        });

        header.append(this.caret, this.checkbox, this.circle, identifier, this.count, this.passFail);

        // the full description, shown as its own box in the unfolded section
        const description = document.createElement("div");
        description.className = "group-description body-s";
        description.textContent = info.description;

        this.rowsContainer = document.createElement("div");
        this.rowsContainer.className = "group-rows";
        this.rowsContainer.appendChild(description);
        for (const row of this.rows) {
            row.onSelectionChange(onSelectionChange);
            this.rowsContainer.appendChild(row.element);
        }

        this.element.append(header, this.rowsContainer);
        this.setExpanded(false);
        this.refresh();
    }

    /** Expands or collapses the group's rows. */
    private toggle(): void {
        this.setExpanded(!this.expanded);
    }

    private setExpanded(expanded: boolean): void {
        this.expanded = expanded;
        this.rowsContainer.hidden = !expanded;
        this.element.dataset.expanded = String(expanded);
    }

    /** Updates the group checkbox, aggregate circle, and count from its rows. */
    refresh(): void {
        const selected = this.rows.filter((r) => r.selected).length;
        this.checkbox.checked = selected === this.rows.length && this.rows.length > 0;
        this.checkbox.indeterminate = selected > 0 && selected < this.rows.length;

        const passed = this.rows.filter((r) => r.isPassed()).length;
        const failed = this.rows.filter((r) => r.isFailed()).length;
        this.passFail.textContent = `${passed} / ${failed}`;
        // while any of the group's tests is still running or queued, the aggregate
        // shows "in progress"; the final verdict appears once the run settles
        const active = this.rows.some((r) => r.isActive());
        this.circle.dataset.state = active
            ? "running"
            : failed > 0
              ? "failed"
              : passed === this.rows.length && passed > 0
                ? "passed"
                : "idle";
    }
}

// ── controller: builds the tree, wires selection and the run buttons ────────────
class TestPanel {
    private readonly groups: GroupView[] = [];
    private readonly rowsById = new Map<string, TestRowView>();
    private readonly treeEl = document.getElementById("tree") as HTMLElement;
    private readonly summaryEl = document.getElementById("summary") as HTMLElement;
    private readonly runAllBtn = document.getElementById("run-all-btn") as HTMLButtonElement;
    private readonly runSelectedBtn = document.getElementById("run-selected-btn") as HTMLButtonElement;
    private readonly clearSelectionBtn = document.getElementById("clear-selection-btn") as HTMLButtonElement;

    constructor() {
        this.runAllBtn.addEventListener("click", () => this.run(this.allIds()));
        this.runSelectedBtn.addEventListener("click", () => this.run(this.selectedIds()));
        this.clearSelectionBtn.addEventListener("click", () => this.clearSelection());
    }

    /** Deselects every test and updates the group aggregates. */
    private clearSelection(): void {
        for (const row of this.rowsById.values()) row.selected = false;
        this.onSelectionChange();
    }

    /** Renders the enumerated tree (all tests idle, none selected). */
    render(tree: TestTree): void {
        this.groups.length = 0;
        this.rowsById.clear();
        this.treeEl.replaceChildren();

        for (const groupInfo of tree.groups) {
            const group = new GroupView(groupInfo, () => this.onSelectionChange());
            this.groups.push(group);
            for (const row of group.rows) this.rowsById.set(row.id, row);
            this.treeEl.appendChild(group.element);
        }
        this.updateSummary();
    }

    markRunning(id: string): void {
        this.rowsById.get(id)?.setState("running");
        this.refresh();
    }

    markFinished(id: string, result: ResultMessage): void {
        this.rowsById.get(id)?.setDetails(result);
        this.refresh();
    }

    runFinished(): void {
        this.setRunning(false);
        this.refresh();
    }

    private run(ids: string[]): void {
        if (ids.length === 0) return;
        this.setRunning(true);
        for (const id of ids) this.rowsById.get(id)?.setState("scheduled");
        this.refresh();
        parent.postMessage({ type: "run-tests", ids }, "*");
    }

    private setRunning(running: boolean): void {
        this.runAllBtn.disabled = running;
        this.runSelectedBtn.disabled = running;
    }

    private allIds(): string[] {
        return [...this.rowsById.keys()];
    }

    private selectedIds(): string[] {
        return [...this.rowsById.values()].filter((r) => r.selected).map((r) => r.id);
    }

    private onSelectionChange(): void {
        for (const group of this.groups) group.refresh();
    }

    private refresh(): void {
        for (const group of this.groups) group.refresh();
        this.updateSummary();
    }

    private updateSummary(): void {
        const rows = [...this.rowsById.values()];
        const passed = rows.filter((r) => r.isPassed()).length;
        const failed = rows.filter((r) => r.isFailed()).length;
        this.summaryEl.textContent = `${rows.length} tests · ${passed} passed · ${failed} failed`;
    }
}

// ── wire up ─────────────────────────────────────────────────────────────────────
const panel = new TestPanel();

window.addEventListener("message", (event: MessageEvent) => {
    const data = event.data;
    if (typeof data !== "object" || data === null) return;

    switch (data.type) {
        case "theme":
            applyTheme(data.theme as string);
            break;
        case "test-tree":
            panel.render(data.tree as TestTree);
            break;
        case "test-started":
            panel.markRunning(data.id as string);
            break;
        case "test-finished":
            panel.markFinished(data.id as string, data.result as ResultMessage);
            break;
        case "run-complete":
        case "run-error":
            panel.runFinished();
            break;
    }
});

// tell the sandbox we are ready to receive the tree
parent.postMessage({ type: "ready" }, "*");
