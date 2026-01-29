import { readFileSync, existsSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";
import yaml from "js-yaml";
import { createLogger } from "./logger.js";

/**
 * Interface defining the structure of the prompts configuration file.
 */
export interface PromptsConfig {
    /** Initial instructions displayed when the server starts or connects to a client */
    initial_instructions: string;
    [key: string]: any; // Allow for future extension with additional prompt types
}

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
    private promptsConfig: PromptsConfig | null = null;

    /**
     * Creates a new configuration loader instance.
     *
     * @param baseDir - Base directory for resolving configuration file paths
     */
    constructor(baseDir: string) {
        this.baseDir = baseDir;
    }

    /**
     * Loads the prompts configuration from the YAML file.
     *
     * Reads and parses the prompts.yml file, providing cached access
     * to configuration values on subsequent calls.
     *
     * @returns The parsed prompts configuration object
     */
    public getPromptsConfig(): PromptsConfig {
        if (this.promptsConfig !== null) {
            return this.promptsConfig;
        }

        const promptsPath = join(this.baseDir, "data", "prompts.yml");

        if (!existsSync(promptsPath)) {
            throw new Error(`Prompts configuration file not found at ${promptsPath}, using defaults`);
        }

        const fileContent = readFileSync(promptsPath, "utf8");
        const parsedConfig = yaml.load(fileContent) as PromptsConfig;

        this.promptsConfig = parsedConfig || {};
        this.logger.info(`Loaded prompts configuration from ${promptsPath}`);

        return this.promptsConfig;
    }

    /**
     * Gets the initial instructions for the MCP server.
     *
     * @returns The initial instructions string, or undefined if not configured
     */
    public getInitialInstructions(): string {
        const config = this.getPromptsConfig();
        return config.initial_instructions;
    }

    /**
     * Reloads the configuration from disk.
     *
     * Forces a fresh read of the configuration file on the next access,
     * useful for development or when configuration files are updated at runtime.
     */
    public reloadConfiguration(): void {
        this.promptsConfig = null;
        this.logger.info("Configuration cache cleared, will reload on next access");
    }
}
