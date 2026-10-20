import { z } from "zod";
import type { Feedback, PenpotBackend } from "../PenpotBackendClient";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import { Tool } from "../Tool";
import { TextResponse, type ToolResponse } from "../ToolResponse";

/** Arguments for submitting a plain-text feedback report. */
export class SubmitFeedbackArgs implements Feedback {
    static schema = {
        subject: z
            .string()
            .min(1)
            .max(120)
            .regex(/\S/)
            .regex(/^[^\r\n]*$/)
            .describe("Short, specific summary without line breaks (max 120 characters)."),
        feedback: z
            .string()
            .min(1)
            .max(5000)
            .regex(/\S/)
            .describe("Concise plain-text feedback (max 5,000 characters)."),
    };

    subject!: string;
    feedback!: string;
}

/** Tool for user-approved feedback about the Penpot MCP integration. */
export class SubmitFeedbackTool extends Tool<SubmitFeedbackArgs> {
    constructor(
        mcpServer: PenpotMcpServer,
        private readonly backend: PenpotBackend
    ) {
        super(mcpServer, SubmitFeedbackArgs.schema);
    }

    public getToolName(): string {
        return "submit_feedback";
    }

    public getToolDescription(): string {
        return (
            "Submit feedback about Penpot MCP, provided that the user explicitly requests or approves submission " +
            "and there is a concrete, actionable issue to report. " +
            "Focus on bugs, limitations, or unclear documentation that blocked work or caused substantial rework. " +
            "Briefly propose what to report and ask the user at a natural pause. " +
            "Skip vague dissatisfaction, routine transient errors, and your own coding mistakes. " +
            "Describe the operation, expected and actual behavior, and any workaround. Exclude sensitive content."
        );
    }

    protected formatArgs(_args: SubmitFeedbackArgs): string {
        return "[feedback omitted]";
    }

    protected async executeCore(args: SubmitFeedbackArgs): Promise<ToolResponse> {
        const userToken = this.getSessionContext()?.userToken;
        if (!userToken?.trim()) {
            throw new Error("Include userToken in the MCP server URL to submit feedback.");
        }
        await this.backend.submitFeedback(userToken, { subject: args.subject, feedback: args.feedback });
        return new TextResponse("Feedback queued.");
    }
}
