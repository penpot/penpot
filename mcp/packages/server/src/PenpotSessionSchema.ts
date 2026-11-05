import type { PenpotSession } from "@penpot/mcp-common";
import { z } from "zod";

/**
 * Schema of a Penpot session as reported by the plugin and exchanged between MCP instances.
 *
 * A session without an open file (e.g. a plugin connected from the dashboard) has null `fileId` and `fileName`.
 */
export const penpotSessionSchema = z.object({
    sessionId: z.string().min(1).max(128),
    fileId: z.string().min(1).max(128).nullable(),
    fileName: z.string().nullable(),
    workspaceState: z.enum(["none", "loading", "ready"]).optional(),
}) satisfies z.ZodType<PenpotSession>;
