import { existsSync, readFileSync } from "fs";
import { join } from "path";
import { createLogger } from "./logger.js";

/**
 * Configuration loader for prompts and server settings.
 */
export class ConfigurationLoader {
    private readonly logger = createLogger("ConfigurationLoader");
    private readonly baseDir: string;
    private readonly initialInstructions: string;
    private readonly baseInstructions: string;

    /**
     * Creates a new configuration loader instance.
     *
     * @param baseDir - Base directory for resolving configuration file paths
     */
    constructor(baseDir: string) {
        this.baseDir = baseDir;
        this.initialInstructions = this.loadFileContent(join(this.baseDir, "data", "initial_instructions.md"));
        this.baseInstructions = this.loadFileContent(join(this.baseDir, "data", "base_instructions.md"));
    }

    private loadFileContent(filePath: string): string {
        if (!existsSync(filePath)) {
            throw new Error(`Configuration file not found at ${filePath}`);
        }
        return readFileSync(filePath, "utf8");
    }

    /**
     * Gets the initial instructions for the MCP server corresponding to the
     * 'Penpot High-Level Overview'
     *
     * @returns The initial instructions string
     */
    public getInitialInstructions(): string {
        return this.initialInstructions;
    }

    /**
     * Gets the base instructions which shall be provided to clients when connecting to
     * the MCP server
     *
     * @returns The initial instructions string
     */
    public getBaseInstructions(): string {
        return this.baseInstructions;
    }
}
