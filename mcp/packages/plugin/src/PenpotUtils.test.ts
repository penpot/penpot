import assert from "node:assert/strict";
import test from "node:test";
import { PenpotUtils } from "./PenpotUtils.ts";

// ---------------------------------------------------------------------------
// Minimal penpot global mock
// ---------------------------------------------------------------------------

function makeVariantComponent(calls: string[]) {
    return {
        isVariant: () => true,
        setVariantProperty: (pos: number, value: string) => {
            calls.push(`setVariantProperty(${pos}, ${value})`);
        },
    };
}

function makeMockPenpot(variantComps: ReturnType<typeof makeVariantComponent>[]) {
    const variants = {
        renamePropertyCalls: [] as string[],
        addPropertyCount: 0,
        renameProperty(pos: number, name: string) {
            this.renamePropertyCalls.push(`${pos}:${name}`);
        },
        addProperty() {
            this.addPropertyCount++;
        },
        variantComponents() {
            return variantComps;
        },
    };

    const container = { variants };

    return {
        mock: {
            utils: {
                types: {
                    isVariantComponent: (c: any) => c.isVariant(),
                },
            },
            createVariantFromComponents: (_shapes: any[]) => container,
        } as any,
        variants,
        container,
    };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test("createVariantContainer — single property: renames Property 1 and sets values", () => {
    const calls: string[] = [];
    const comps = [makeVariantComponent(calls), makeVariantComponent(calls), makeVariantComponent(calls)];
    const { mock, variants } = makeMockPenpot(comps);

    (globalThis as any).penpot = mock;

    PenpotUtils.createVariantContainer([
        { shape: {} as any, properties: { Size: "Small" } },
        { shape: {} as any, properties: { Size: "Medium" } },
        { shape: {} as any, properties: { Size: "Large" } },
    ]);

    // renameProperty(0, "Size") — no addProperty since there's only one property
    assert.deepEqual(variants.renamePropertyCalls, ["0:Size"]);
    assert.equal(variants.addPropertyCount, 0);

    // each component gets setVariantProperty(0, value)
    assert.deepEqual(calls, [
        "setVariantProperty(0, Small)",
        "setVariantProperty(0, Medium)",
        "setVariantProperty(0, Large)",
    ]);
});

test("createVariantContainer — two properties: renames first, adds and renames second, sets all values", () => {
    const calls: string[] = [];
    const comps = [makeVariantComponent(calls), makeVariantComponent(calls)];
    const { mock, variants } = makeMockPenpot(comps);

    (globalThis as any).penpot = mock;

    PenpotUtils.createVariantContainer([
        { shape: {} as any, properties: { Size: "Small", State: "Default" } },
        { shape: {} as any, properties: { Size: "Large", State: "Hover" } },
    ]);

    // Property 1 renamed to Size; addProperty() called once; Property 2 renamed to State
    assert.deepEqual(variants.renamePropertyCalls, ["0:Size", "1:State"]);
    assert.equal(variants.addPropertyCount, 1);

    // comp 0: pos 0 = Small, pos 1 = Default; comp 1: pos 0 = Large, pos 1 = Hover
    assert.deepEqual(calls, [
        "setVariantProperty(0, Small)",
        "setVariantProperty(1, Default)",
        "setVariantProperty(0, Large)",
        "setVariantProperty(1, Hover)",
    ]);
});

test("createVariantContainer — property name order follows first-seen order across components", () => {
    const calls: string[] = [];
    const comps = [makeVariantComponent(calls), makeVariantComponent(calls)];
    const { mock, variants } = makeMockPenpot(comps);

    (globalThis as any).penpot = mock;

    // "Color" appears first in comp[0]; "Size" appears first in comp[0] too
    PenpotUtils.createVariantContainer([
        { shape: {} as any, properties: { Color: "Red", Size: "Small" } },
        { shape: {} as any, properties: { Color: "Blue", Size: "Large" } },
    ]);

    assert.deepEqual(variants.renamePropertyCalls, ["0:Color", "1:Size"]);
    assert.deepEqual(calls, [
        "setVariantProperty(0, Red)",
        "setVariantProperty(1, Small)",
        "setVariantProperty(0, Blue)",
        "setVariantProperty(1, Large)",
    ]);
});

test("createVariantContainer — returns the container from createVariantFromComponents", () => {
    const { mock, container } = makeMockPenpot([]);
    (globalThis as any).penpot = mock;

    const result = PenpotUtils.createVariantContainer([{ shape: {} as any, properties: { X: "1" } }]);

    assert.equal(result, container);
});

// ---------------------------------------------------------------------------
// findShapes root handling
// (community report https://community.penpot.app/t/mcp-api-issue-list/10700
// issue #15: including the matching root itself is a footgun — a predicate
// meant for descendants can accidentally select and mutate the container).
// ---------------------------------------------------------------------------

function makeShapeTree() {
    const childA = { name: "child-a", fills: [{}] };
    const childB = { name: "child-b", fills: [{}] };
    const root = { name: "root", fills: [{}], children: [childA, childB] };
    return { root, childA, childB };
}

test("findShapes — matches descendants of the given root", () => {
    const { root, childA, childB } = makeShapeTree();

    const result = PenpotUtils.findShapes((s: any) => s.fills.length > 0, root as any);

    assert.ok(result.includes(childA as any));
    assert.ok(result.includes(childB as any));
});

test("findShapes — excludes the root shape itself from the results", () => {
    const { root } = makeShapeTree();

    const result = PenpotUtils.findShapes((s: any) => s.fills.length > 0, root as any);

    assert.ok(!result.includes(root as any));
});
