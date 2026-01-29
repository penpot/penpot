import * as path from "path";
import * as fs from "fs";

export class FileUtils {
    /**
     * Checks whether the given file path is absolute and raises an error if not.
     *
     * @param filePath - The file path to check
     */
    public static checkPathIsAbsolute(filePath: string): void {
        if (!path.isAbsolute(filePath)) {
            throw new Error(`The specified file path must be absolute: ${filePath}`);
        }
    }

    public static createParentDirectories(filePath: string): void {
        const dir = path.dirname(filePath);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
    }

    /**
     * Writes binary data to a file at the specified path, creating the parent directories if necessary.
     *
     * @param filePath - The absolute path to the file where data should be written
     * @param bytes - The binary data to write to the file
     */
    public static writeBinaryFile(filePath: string, bytes: Uint8Array): void {
        this.createParentDirectories(filePath);
        fs.writeFileSync(filePath, Buffer.from(bytes));
    }

    /**
     * Writes text data to a file at the specified path, creating the parent directories if necessary.
     *
     * @param filePath - The absolute path to the file where data should be written
     * @param text - The text data to write to the file
     */
    public static writeTextFile(filePath: string, text: string): void {
        this.createParentDirectories(filePath);
        fs.writeFileSync(filePath, text, { encoding: "utf-8" });
    }
}
