import { existsSync, readFileSync } from "fs";
import { join } from "path";
import { createLogger } from "./logger.js";

/**
 * Configuration loader for prompts and server settings.
 *
 * Handles loading and parsing of YAML configuration files,
 * providing type-safe access to configuration values with
 * appropriate fallbacks for missing files or values.
 */
export class ConfigurationLoader {
    private readonly logger = createLogger("ConfigurationLoader");
    private readonly baseDir: string;
    private initialInstructions: string;

    /**
     * Creates a new configuration loader instance.
     *
     * @param baseDir - Base directory for resolving configuration file paths
     */
    constructor(baseDir: string) {
        this.baseDir = baseDir;
        this.initialInstructions = this.loadFileContent(join(this.baseDir, "data", "initial_instructions.md"));
    }

    private loadFileContent(filePath: string): string {
        if (!existsSync(filePath)) {
            throw new Error(`Configuration file not found at ${filePath}`);
        }
        return readFileSync(filePath, "utf8");
    }

    /**
     * Gets the initial instructions for the MCP server.
     *
     * @returns The initial instructions string, or undefined if not configured
     */
    public getInitialInstructions(): string {
        return this.initialInstructions;
    }
}
