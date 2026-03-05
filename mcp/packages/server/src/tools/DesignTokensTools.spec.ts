import { describe, it, expect, vi } from "vitest";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import type { ExecuteCodePluginTask } from "../tasks/ExecuteCodePluginTask";
import {
    ListDesignTokensTool,
    CreateTokenSetTool,
    CreateTokenThemeTool,
    CreateTokenTool,
    UpdateTokenTool,
    UpdateTokenSetTool,
    UpdateTokenThemeTool,
} from "./DesignTokensTools";

function createMockServer(taskResult: { data?: { result?: unknown } }) {
    const executePluginTask = vi.fn().mockResolvedValue(taskResult);
    return {
        pluginBridge: { executePluginTask },
    } as unknown as PenpotMcpServer;
}

function createRejectingMockServer(errorMessage: string) {
    const executePluginTask = vi.fn().mockRejectedValue(new Error(errorMessage));
    return {
        pluginBridge: { executePluginTask },
    } as unknown as PenpotMcpServer;
}

describe("DesignTokensTools", () => {
    describe("tool names and descriptions", () => {
        const mockServer = createMockServer({});

        it("ListDesignTokensTool has correct name and description", () => {
            const tool = new ListDesignTokensTool(mockServer);
            expect(tool.getToolName()).toBe("list_design_tokens");
            expect(tool.getToolDescription()).toContain("design tokens");
            expect(tool.getToolDescription()).toContain("sets");
            expect(tool.getToolDescription()).toContain("themes");
        });

        it("CreateTokenSetTool has correct name and description", () => {
            const tool = new CreateTokenSetTool(mockServer);
            expect(tool.getToolName()).toBe("create_token_set");
            expect(tool.getToolDescription()).toContain("token set");
        });

        it("CreateTokenThemeTool has correct name and description", () => {
            const tool = new CreateTokenThemeTool(mockServer);
            expect(tool.getToolName()).toBe("create_token_theme");
            expect(tool.getToolDescription()).toContain("theme");
        });

        it("CreateTokenTool has correct name and description", () => {
            const tool = new CreateTokenTool(mockServer);
            expect(tool.getToolName()).toBe("create_token");
            expect(tool.getToolDescription()).toContain("token");
        });

        it("UpdateTokenTool has correct name and description", () => {
            const tool = new UpdateTokenTool(mockServer);
            expect(tool.getToolName()).toBe("update_token");
            expect(tool.getToolDescription()).toContain("name");
            expect(tool.getToolDescription()).toContain("value");
        });

        it("UpdateTokenSetTool has correct name and description", () => {
            const tool = new UpdateTokenSetTool(mockServer);
            expect(tool.getToolName()).toBe("update_token_set");
            expect(tool.getToolDescription()).toContain("Renames");
        });

        it("UpdateTokenThemeTool has correct name and description", () => {
            const tool = new UpdateTokenThemeTool(mockServer);
            expect(tool.getToolName()).toBe("update_token_theme");
            expect(tool.getToolDescription()).toContain("name");
            expect(tool.getToolDescription()).toContain("group");
        });
    });

    describe("list_design_tokens", () => {
        it("returns JSON with sets, themes, tokenOverview, tokensBySet when plugin returns data", async () => {
            const listResult = {
                sets: [{ id: "set-1", name: "Brand", active: true }],
                themes: [{ id: "theme-1", group: "scheme", name: "Light", active: true }],
                tokenOverview: { Brand: { color: ["color.primary"] } },
                tokensBySet: [
                    { setId: "set-1", setName: "Brand", tokens: [{ id: "t1", name: "color.primary", type: "color" }] },
                ],
            };
            const mockServer = createMockServer({ data: { result: listResult } });
            const tool = new ListDesignTokensTool(mockServer);

            const response = await tool.execute({});

            expect(response.content).toHaveLength(1);
            expect(response.content[0].type).toBe("text");
            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.sets).toEqual(listResult.sets);
            expect(parsed.themes).toEqual(listResult.themes);
            expect(parsed.tokenOverview).toEqual(listResult.tokenOverview);
            expect(parsed.tokensBySet).toEqual(listResult.tokensBySet);
        });

        it("returns failure message when plugin returns no result", async () => {
            const mockServer = createMockServer({ data: {} });
            const tool = new ListDesignTokensTool(mockServer);

            const response = await tool.execute({});

            const text = (response.content[0] as { text: string }).text;
            expect(text).toContain("Failed to list design tokens");
        });

        it("sends code that uses penpot.library.local.tokens and penpotUtils.tokenOverview", async () => {
            const executePluginTask = vi.fn().mockResolvedValue({
                data: { result: { sets: [], themes: [], tokenOverview: {}, tokensBySet: [] } },
            });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new ListDesignTokensTool(mockServer);

            await tool.execute({});

            expect(executePluginTask).toHaveBeenCalledTimes(1);
            const task = executePluginTask.mock.calls[0][0] as ExecuteCodePluginTask;
            const code = (task as { params: { code: string } }).params.code;
            expect(code).toContain("penpot.library.local.tokens");
            expect(code).toContain("penpotUtils.tokenOverview");
            expect(code).toContain("tokensBySet");
        });
    });

    describe("create_token_set", () => {
        it("returns id and name when plugin succeeds", async () => {
            const mockServer = createMockServer({
                data: { result: { id: "new-set-id", name: "brand/light" } },
            });
            const tool = new CreateTokenSetTool(mockServer);

            const response = await tool.execute({ name: "brand/light" });

            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.id).toBe("new-set-id");
            expect(parsed.name).toBe("brand/light");
        });

        it("sends code that calls addSet with the given name", async () => {
            const executePluginTask = vi.fn().mockResolvedValue({ data: { result: { id: "x", name: "My Set" } } });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new CreateTokenSetTool(mockServer);

            await tool.execute({ name: "My Set" });

            const task = executePluginTask.mock.calls[0][0] as { params: { code: string } };
            expect(task.params.code).toContain('addSet({ name: "My Set" })');
        });

        it("returns failure message when plugin returns no result", async () => {
            const mockServer = createMockServer({ data: {} });
            const tool = new CreateTokenSetTool(mockServer);

            const response = await tool.execute({ name: "X" });

            const text = (response.content[0] as { text: string }).text;
            expect(text).toContain("Failed to create token set");
        });
    });

    describe("create_token_theme", () => {
        it("returns id, group, name when plugin succeeds", async () => {
            const mockServer = createMockServer({
                data: { result: { id: "th-1", group: "scheme", name: "Dark" } },
            });
            const tool = new CreateTokenThemeTool(mockServer);

            const response = await tool.execute({ group: "scheme", name: "Dark" });

            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.id).toBe("th-1");
            expect(parsed.group).toBe("scheme");
            expect(parsed.name).toBe("Dark");
        });

        it("sends code that calls addTheme with group and name", async () => {
            const executePluginTask = vi
                .fn()
                .mockResolvedValue({ data: { result: { id: "t", group: "g", name: "N" } } });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new CreateTokenThemeTool(mockServer);

            await tool.execute({ group: "color-scheme", name: "Light" });

            const task = executePluginTask.mock.calls[0][0] as { params: { code: string } };
            expect(task.params.code).toContain('addTheme({ group: "color-scheme", name: "Light" })');
        });
    });

    describe("create_token", () => {
        it("returns token fields when plugin succeeds", async () => {
            const mockServer = createMockServer({
                data: {
                    result: {
                        id: "tok-1",
                        name: "color.primary",
                        type: "color",
                        value: "#0066FF",
                        description: "Brand primary",
                    },
                },
            });
            const tool = new CreateTokenTool(mockServer);

            const response = await tool.execute({
                setId: "set-1",
                type: "color",
                name: "color.primary",
                value: "#0066FF",
                description: "Brand primary",
            });

            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.id).toBe("tok-1");
            expect(parsed.name).toBe("color.primary");
            expect(parsed.type).toBe("color");
            expect(parsed.value).toBe("#0066FF");
            expect(parsed.description).toBe("Brand primary");
        });

        it("sends code that calls getSetById and addToken with correct args", async () => {
            const executePluginTask = vi.fn().mockResolvedValue({
                data: { result: { id: "t", name: "color.primary", type: "color", value: "#0066FF" } },
            });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new CreateTokenTool(mockServer);

            await tool.execute({ setId: "set-1", type: "color", name: "color.primary", value: "#0066FF" });

            const task = executePluginTask.mock.calls[0][0] as { params: { code: string } };
            const code = task.params.code;
            expect(code).toContain('getSetById("set-1")');
            expect(code).toContain('addToken({ type: "color", name: "color.primary", value: "#0066FF" })');
        });

        it("returns error message when plugin task throws", async () => {
            const mockServer = createRejectingMockServer("Token set not found: set-1");
            const tool = new CreateTokenTool(mockServer);

            const response = await tool.execute({ setId: "set-1", type: "color", name: "c", value: "#000" });

            const text = (response.content[0] as { text: string }).text;
            expect(text).toContain("Token set not found");
        });
    });

    describe("update_token", () => {
        it("returns success response when plugin returns data", async () => {
            const mockServer = createMockServer({
                data: {
                    result: { id: "tok-1", name: "color.accent", type: "color", value: "#00CC00", description: "" },
                },
            });
            const tool = new UpdateTokenTool(mockServer);

            const response = await tool.execute({ tokenId: "tok-1", name: "color.accent", value: "#00CC00" });

            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.name).toBe("color.accent");
            expect(parsed.value).toBe("#00CC00");
        });

        it("sends code that finds token by id and applies name/value/description updates", async () => {
            const executePluginTask = vi.fn().mockResolvedValue({
                data: { result: { id: "t", name: "n", type: "color", value: "#fff" } },
            });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new UpdateTokenTool(mockServer);

            await tool.execute({ tokenId: "tid-1", name: "new.name", value: "#111" });

            const task = executePluginTask.mock.calls[0][0] as { params: { code: string } };
            const code = task.params.code;
            expect(code).toContain('getTokenById("tid-1")');
            expect(code).toContain('token.name = "new.name"');
            expect(code).toContain('token.value = "#111"');
        });

        it("returns message when no updates provided", async () => {
            const mockServer = createMockServer({});
            const tool = new UpdateTokenTool(mockServer);

            const response = await tool.execute({ tokenId: "tid-1" });

            const text = (response.content[0] as { text: string }).text;
            expect(text).toContain("Provide at least one of: name, value, description");
        });
    });

    describe("update_token_set", () => {
        it("returns id and name when plugin succeeds", async () => {
            const mockServer = createMockServer({
                data: { result: { id: "set-1", name: "Brand (renamed)" } },
            });
            const tool = new UpdateTokenSetTool(mockServer);

            const response = await tool.execute({ setId: "set-1", name: "Brand (renamed)" });

            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.id).toBe("set-1");
            expect(parsed.name).toBe("Brand (renamed)");
        });

        it("sends code that calls getSetById and sets name", async () => {
            const executePluginTask = vi.fn().mockResolvedValue({ data: { result: { id: "s", name: "New" } } });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new UpdateTokenSetTool(mockServer);

            await tool.execute({ setId: "set-1", name: "New Name" });

            const task = executePluginTask.mock.calls[0][0] as { params: { code: string } };
            expect(task.params.code).toContain('getSetById("set-1")');
            expect(task.params.code).toContain('set.name = "New Name"');
        });
    });

    describe("update_token_theme", () => {
        it("returns id, group, name when plugin succeeds", async () => {
            const mockServer = createMockServer({
                data: { result: { id: "th-1", group: "new-group", name: "New Name" } },
            });
            const tool = new UpdateTokenThemeTool(mockServer);

            const response = await tool.execute({ themeId: "th-1", group: "new-group", name: "New Name" });

            const text = (response.content[0] as { text: string }).text;
            const parsed = JSON.parse(text);
            expect(parsed.group).toBe("new-group");
            expect(parsed.name).toBe("New Name");
        });

        it("sends code that calls getThemeById and sets name/group", async () => {
            const executePluginTask = vi
                .fn()
                .mockResolvedValue({ data: { result: { id: "t", group: "g", name: "n" } } });
            const mockServer = { pluginBridge: { executePluginTask } } as unknown as PenpotMcpServer;
            const tool = new UpdateTokenThemeTool(mockServer);

            await tool.execute({ themeId: "th-1", name: "Renamed" });

            const task = executePluginTask.mock.calls[0][0] as { params: { code: string } };
            expect(task.params.code).toContain('getThemeById("th-1")');
            expect(task.params.code).toContain('theme.name = "Renamed"');
        });

        it("returns message when no updates provided", async () => {
            const mockServer = createMockServer({});
            const tool = new UpdateTokenThemeTool(mockServer);

            const response = await tool.execute({ themeId: "th-1" });

            const text = (response.content[0] as { text: string }).text;
            expect(text).toContain("Provide at least one of: name, group");
        });
    });
});
