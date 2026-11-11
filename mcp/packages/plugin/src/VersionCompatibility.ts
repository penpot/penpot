/**
 * Extracts the major.minor prefix from a version string.
 *
 * Patch releases of Penpot do not change the plugin API the MCP server is
 * written against, and the MCP package version is only set per minor
 * release, so the patch component is not part of the compatibility check.
 *
 * @param version - a version string starting with major.minor (e.g. "2.18.1")
 * @returns the major.minor prefix, or the original string if it does not match
 */
export function extractMinorVersion(version: string): string {
    const match = version.match(/^(\d+\.\d+)(?:\.|$)/);
    return match ? match[1] : version;
}

/**
 * Returns whether an MCP build is intended for the running Penpot version.
 *
 * Local development builds of Penpot report "0.0.0" and are always treated
 * as compatible.
 *
 * @param penpotVersion - the running Penpot version, as major.minor prefix
 * @param mcpVersion - the version the MCP build targets, as major.minor prefix
 */
export function isCompatibleVersion(penpotVersion: string, mcpVersion: string): boolean {
    return penpotVersion === "0.0" || penpotVersion === mcpVersion;
}
