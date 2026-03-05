import { z } from "zod";
import { Tool } from "../Tool";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { ExecuteCodePluginTask } from "../tasks/ExecuteCodePluginTask";

const TOKEN_TYPES = [
    "borderRadius",
    "shadow",
    "color",
    "dimension",
    "fontFamilies",
    "fontSizes",
    "fontWeights",
    "letterSpacing",
    "number",
    "opacity",
    "rotation",
    "sizing",
    "spacing",
    "borderWidth",
    "textCase",
    "textDecoration",
    "typography",
] as const;

/**
 * List design tokens: returns sets, themes, and token overview.
 */
export class ListDesignTokensArgs {
    static schema = {};
}

export class ListDesignTokensTool extends Tool<ListDesignTokensArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, ListDesignTokensArgs.schema);
    }

    getToolName(): string {
        return "list_design_tokens";
    }

    getToolDescription(): string {
        return (
            "Lists all design tokens in the current file's local library: token sets (with id, name, active), " +
            "themes (with id, group, name, active), and a token overview per set (set name -> type -> token names). " +
            "Use the returned ids when creating or updating tokens, sets, or themes."
        );
    }

    protected async executeCore(): Promise<ToolResponse> {
        const code = `
const catalog = penpot.library.local.tokens;
const sets = catalog.sets.map(s => ({ id: s.id, name: s.name, active: s.active }));
const themes = catalog.themes.map(t => ({ id: t.id, group: t.group, name: t.name, active: t.active }));
const tokenOverview = penpotUtils.tokenOverview();
const tokensBySet = catalog.sets.map(s => ({ setId: s.id, setName: s.name, tokens: s.tokens.map(t => ({ id: t.id, name: t.name, type: t.type })) }));
return { sets, themes, tokenOverview, tokensBySet };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to list design tokens.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}

/**
 * Create a token set.
 */
export class CreateTokenSetArgs {
    static schema = {
        name: z
            .string()
            .min(1, "name cannot be empty")
            .describe("Name of the token set (may contain group path, e.g. 'brand/light')."),
    };
    name!: string;
}

export class CreateTokenSetTool extends Tool<CreateTokenSetArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, CreateTokenSetArgs.schema);
    }

    getToolName(): string {
        return "create_token_set";
    }

    getToolDescription(): string {
        return "Creates a new design token set in the local library. Returns the created set's id and name.";
    }

    protected async executeCore(args: CreateTokenSetArgs): Promise<ToolResponse> {
        const code = `
const set = penpot.library.local.tokens.addSet({ name: ${JSON.stringify(args.name)} });
return { id: set.id, name: set.name };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to create token set.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}

/**
 * Create a token theme.
 */
export class CreateTokenThemeArgs {
    static schema = {
        group: z.string().describe("Theme group (e.g. 'color-scheme'). Can be empty string."),
        name: z.string().min(1, "name cannot be empty").describe("Name of the theme."),
    };
    group!: string;
    name!: string;
}

export class CreateTokenThemeTool extends Tool<CreateTokenThemeArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, CreateTokenThemeArgs.schema);
    }

    getToolName(): string {
        return "create_token_theme";
    }

    getToolDescription(): string {
        return "Creates a new design token theme (preset of active sets). Returns the created theme's id, group, and name.";
    }

    protected async executeCore(args: CreateTokenThemeArgs): Promise<ToolResponse> {
        const code = `
const theme = penpot.library.local.tokens.addTheme({ group: ${JSON.stringify(args.group)}, name: ${JSON.stringify(args.name)} });
return { id: theme.id, group: theme.group, name: theme.name };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to create token theme.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}

/**
 * Create a design token inside a set.
 */
export class CreateTokenArgs {
    static schema = {
        setId: z.string().min(1, "setId cannot be empty").describe("Id of the token set (from list_design_tokens)."),
        type: z.enum(TOKEN_TYPES).describe("Token type (e.g. color, dimension, spacing)."),
        name: z
            .string()
            .min(1, "name cannot be empty")
            .describe("Token name (e.g. 'color.primary', may contain dots)."),
        value: z
            .union([z.string(), z.array(z.string())])
            .describe(
                "Token value. For color use hex e.g. '#0066FF'; for dimension e.g. '16px'; references use '{token.name}'."
            ),
        description: z.string().optional().describe("Optional description for the token."),
    };
    setId!: string;
    type!: (typeof TOKEN_TYPES)[number];
    name!: string;
    value!: string | string[];
    description?: string;
}

export class CreateTokenTool extends Tool<CreateTokenArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, CreateTokenArgs.schema);
    }

    getToolName(): string {
        return "create_token";
    }

    getToolDescription(): string {
        return (
            "Creates a new design token in the given set. Value format depends on type (e.g. color: '#hex', dimension: '16px'). " +
            "Returns the created token's id, name, type, and value."
        );
    }

    protected async executeCore(args: CreateTokenArgs): Promise<ToolResponse> {
        const valueJson = JSON.stringify(args.value);
        const descJson = args.description !== undefined ? JSON.stringify(args.description) : "undefined";
        const code = `
const set = penpot.library.local.tokens.getSetById(${JSON.stringify(args.setId)});
if (!set) throw new Error('Token set not found: ' + ${JSON.stringify(args.setId)});
const token = set.addToken({ type: ${JSON.stringify(args.type)}, name: ${JSON.stringify(args.name)}, value: ${valueJson} });
if (${descJson} !== undefined) token.description = ${descJson};
return { id: token.id, name: token.name, type: token.type, value: token.value, description: token.description };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to create token.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}

/**
 * Update an existing token (name, value, description).
 */
export class UpdateTokenArgs {
    static schema = {
        tokenId: z
            .string()
            .min(1, "tokenId cannot be empty")
            .describe("Id of the token to update (from list_design_tokens or create_token)."),
        name: z.string().optional().describe("New name for the token."),
        value: z
            .union([z.string(), z.array(z.string())])
            .optional()
            .describe("New value for the token."),
        description: z.string().optional().describe("New description for the token."),
    };
    tokenId!: string;
    name?: string;
    value?: string | string[];
    description?: string;
}

export class UpdateTokenTool extends Tool<UpdateTokenArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, UpdateTokenArgs.schema);
    }

    getToolName(): string {
        return "update_token";
    }

    getToolDescription(): string {
        return "Updates an existing design token's name, value, and/or description. Provide only the fields to change.";
    }

    protected async executeCore(args: UpdateTokenArgs): Promise<ToolResponse> {
        const updates: string[] = [];
        if (args.name !== undefined) updates.push(`token.name = ${JSON.stringify(args.name)}`);
        if (args.value !== undefined) updates.push(`token.value = ${JSON.stringify(args.value)}`);
        if (args.description !== undefined) updates.push(`token.description = ${JSON.stringify(args.description)}`);
        if (updates.length === 0) {
            return new TextResponse("Provide at least one of: name, value, description.");
        }
        const code = `
const catalog = penpot.library.local.tokens;
let token = null;
for (const set of catalog.sets) {
  token = set.getTokenById(${JSON.stringify(args.tokenId)});
  if (token) break;
}
if (!token) throw new Error('Token not found: ' + ${JSON.stringify(args.tokenId)});
${updates.join("\n")}
return { id: token.id, name: token.name, type: token.type, value: token.value, description: token.description };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to update token.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}

/**
 * Update a token set (rename).
 */
export class UpdateTokenSetArgs {
    static schema = {
        setId: z.string().min(1, "setId cannot be empty").describe("Id of the token set to update."),
        name: z.string().min(1, "name cannot be empty").describe("New name for the set."),
    };
    setId!: string;
    name!: string;
}

export class UpdateTokenSetTool extends Tool<UpdateTokenSetArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, UpdateTokenSetArgs.schema);
    }

    getToolName(): string {
        return "update_token_set";
    }

    getToolDescription(): string {
        return "Renames an existing design token set. Returns the set id and new name.";
    }

    protected async executeCore(args: UpdateTokenSetArgs): Promise<ToolResponse> {
        const code = `
const set = penpot.library.local.tokens.getSetById(${JSON.stringify(args.setId)});
if (!set) throw new Error('Token set not found: ' + ${JSON.stringify(args.setId)});
set.name = ${JSON.stringify(args.name)};
return { id: set.id, name: set.name };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to update token set.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}

/**
 * Update a token theme (name and/or group).
 */
export class UpdateTokenThemeArgs {
    static schema = {
        themeId: z.string().min(1, "themeId cannot be empty").describe("Id of the theme to update."),
        name: z.string().optional().describe("New name for the theme."),
        group: z.string().optional().describe("New group for the theme."),
    };
    themeId!: string;
    name?: string;
    group?: string;
}

export class UpdateTokenThemeTool extends Tool<UpdateTokenThemeArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, UpdateTokenThemeArgs.schema);
    }

    getToolName(): string {
        return "update_token_theme";
    }

    getToolDescription(): string {
        return "Updates an existing design token theme's name and/or group. Provide only the fields to change.";
    }

    protected async executeCore(args: UpdateTokenThemeArgs): Promise<ToolResponse> {
        const updates: string[] = [];
        if (args.name !== undefined) updates.push(`theme.name = ${JSON.stringify(args.name)}`);
        if (args.group !== undefined) updates.push(`theme.group = ${JSON.stringify(args.group)}`);
        if (updates.length === 0) {
            return new TextResponse("Provide at least one of: name, group.");
        }
        const code = `
const theme = penpot.library.local.tokens.getThemeById(${JSON.stringify(args.themeId)});
if (!theme) throw new Error('Theme not found: ' + ${JSON.stringify(args.themeId)});
${updates.join("\n")}
return { id: theme.id, group: theme.group, name: theme.name };
`;
        const task = new ExecuteCodePluginTask({ code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data?.result;
        if (data === undefined) {
            return new TextResponse("Failed to update token theme.");
        }
        return new TextResponse(JSON.stringify(data, null, 2));
    }
}
