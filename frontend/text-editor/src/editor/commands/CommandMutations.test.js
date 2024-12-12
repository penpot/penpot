import { describe, test, expect } from "vitest";
import CommandMutations from "./CommandMutations.js";

describe("CommandMutations", () => {
  test("should create a new CommandMutations", () => {
    const mutations = new CommandMutations();
    expect(mutations).toHaveProperty("added");
    expect(mutations).toHaveProperty("updated");
    expect(mutations).toHaveProperty("removed");
  });

  test("should create an initialized new CommandMutations", () => {
    const mutations = new CommandMutations([1], [2], [3]);
    expect(mutations.added.size).toBe(1);
    expect(mutations.updated.size).toBe(1);
    expect(mutations.removed.size).toBe(1);
    expect(mutations.added.has(1)).toBe(true);
    expect(mutations.updated.has(2)).toBe(true);
    expect(mutations.removed.has(3)).toBe(true);
  });

  test("should add an added node to a CommandMutations", () => {
    const mutations = new CommandMutations();
    mutations.add(1);
    expect(mutations.added.has(1)).toBe(true);
  });

  test("should add an updated node to a CommandMutations", () => {
    const mutations = new CommandMutations();
    mutations.update(1);
    expect(mutations.updated.has(1)).toBe(true);
  });

  test("should add an removed node to a CommandMutations", () => {
    const mutations = new CommandMutations();
    mutations.remove(1);
    expect(mutations.removed.has(1)).toBe(true);
  });

  test("should clear a CommandMutations", () => {
    const mutations = new CommandMutations();
    mutations.add(1);
    mutations.update(2);
    mutations.remove(3);
    expect(mutations.added.has(1)).toBe(true);
    expect(mutations.added.size).toBe(1);
    expect(mutations.updated.has(2)).toBe(true);
    expect(mutations.updated.size).toBe(1);
    expect(mutations.removed.has(3)).toBe(true);
    expect(mutations.removed.size).toBe(1);

    mutations.clear();
    expect(mutations.added.size).toBe(0);
    expect(mutations.added.has(1)).toBe(false);
    expect(mutations.updated.size).toBe(0);
    expect(mutations.updated.has(1)).toBe(false);
    expect(mutations.removed.size).toBe(0);
    expect(mutations.removed.has(1)).toBe(false);
  });

  test("should dispose a CommandMutations", () => {
    const mutations = new CommandMutations();
    mutations.add(1);
    mutations.update(2);
    mutations.remove(3);
    mutations.dispose();
    expect(mutations.added).toBe(null);
    expect(mutations.updated).toBe(null);
    expect(mutations.removed).toBe(null);
  });
});
