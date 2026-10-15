import * as path from "node:path";

/** Where the devenv mounts the repository in its container. */
export const CONTAINER_ROOT = "/home/penpot/penpot";

/**
 * Where a file named by an agent lives in the container, or why it cannot be read there.
 *
 * Agents run on the host and name files by their host path; the server runs in the container, which sees only
 * the repository, mounted at `containerRoot`. A path is accepted when it is under the host path of the
 * repository (`hostRoot`, the devenv's PENPOT_SOURCE_PATH), under `containerRoot`, or relative, which is read as
 * relative to the repository root. `..` cannot leave the repository.
 */
export function containerPath(
    given: string,
    hostRoot: string | undefined,
    containerRoot = CONTAINER_ROOT
): { path: string } | { refusal: string } {
    const normal = path.normalize(given);
    let relative: string | null = null;
    if (!path.isAbsolute(normal)) {
        relative = normal;
    } else if (hostRoot && isUnder(normal, path.normalize(hostRoot))) {
        relative = path.relative(path.normalize(hostRoot), normal);
    } else if (isUnder(normal, containerRoot)) {
        relative = path.relative(containerRoot, normal);
    }
    if (relative === null || relative === ".." || relative.startsWith(`..${path.sep}`)) {
        const where = hostRoot ? `inside the repository (${hostRoot})` : "inside the repository";
        return {
            refusal:
                `${given} is not ${where}, the only files the server can read. ` +
                "Pass the file's text as file_content instead, or keep scratch files under tmp/ at the repository root, which git ignores.",
        };
    }
    return { path: path.join(containerRoot, relative) };
}

function isUnder(file: string, root: string): boolean {
    return file === root || file.startsWith(root.endsWith(path.sep) ? root : root + path.sep);
}
