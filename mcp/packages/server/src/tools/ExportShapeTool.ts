import { z } from "zod";
import { Tool } from "../Tool";
import { ImageContent, PNGImageContent, PNGResponse, TextContent, TextResponse, ToolResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { ExecuteCodePluginTask } from "../tasks/ExecuteCodePluginTask";
import { FileUtils } from "../utils/FileUtils";
import sharp from "sharp";

/**
 * Arguments class for ExportShapeTool
 */
export class ExportShapeArgs {
    static schema = {
        shapeId: z
            .string()
            .min(1, "shapeId cannot be empty")
            .describe(
                "Identifier of the shape to export. Use the special identifier 'selection' to " +
                    "export the first shape currently selected by the user."
            ),
        format: z.enum(["svg", "png"]).default("png").describe("The output format, either 'png' (default) or 'svg'."),
        mode: z
            .enum(["shape", "fill"])
            .default("shape")
            .describe(
                "The export mode: either 'shape' (full shape as it appears in the design, including descendants; the default) or " +
                    "'fill' (export the raw image that is used as a fill for the shape; PNG format only)"
            ),
        filePath: z
            .string()
            .optional()
            .describe(
                "Optional file path to save the exported image to. If not provided, " +
                    "the image data is returned directly for you to see."
            ),
    };

    shapeId!: string;

    format: "svg" | "png" = "png";

    mode: "shape" | "fill" = "shape";

    filePath?: string;
}

/**
 * Tool for executing JavaScript code in the Penpot plugin context
 */
export class ExportShapeTool extends Tool<ExportShapeArgs> {
    /**
     * Creates a new ExecuteCode tool instance.
     *
     * @param mcpServer - The MCP server instance
     */
    constructor(mcpServer: PenpotMcpServer) {
        let schema: any = ExportShapeArgs.schema;
        if (!mcpServer.isFileSystemAccessEnabled()) {
            // remove filePath key from schema
            schema = { ...schema };
            delete schema.filePath;
        }
        super(mcpServer, schema);
    }

    public getToolName(): string {
        return "export_shape";
    }

    public getToolDescription(): string {
        let description =
            "Exports a shape (or a shape's image fill) from the Penpot design to a PNG or SVG image, " +
            "such that you can get an impression of what it looks like. ";
        if (this.mcpServer.isFileSystemAccessEnabled()) {
            description += "\nAlternatively, you can save it to a file.";
        }
        return description;
    }

    protected async executeCore(args: ExportShapeArgs): Promise<ToolResponse> {
        // check arguments
        if (args.filePath) {
            FileUtils.checkPathIsAbsolute(args.filePath);
        }

        // create code for exporting the shape
        let shapeCode: string;
        if (args.shapeId === "selection") {
            shapeCode = `penpot.selection[0]`;
        } else {
            shapeCode = `penpotUtils.findShapeById("${args.shapeId}")`;
        }
        const asSvg = args.format === "svg";
        const code = `return penpotUtils.exportImage(${shapeCode}, "${args.mode}", ${asSvg});`;

        // execute the code and obtain the image data
        const task = new ExecuteCodePluginTask({ code: code });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const imageData = result.data!.result;

        // handle output and return response
        if (!args.filePath) {
            // return image data directly (for the LLM to "see" it)
            if (args.format === "png") {
                return new PNGResponse(await this.toPngImageBytes(imageData));
            } else {
                return TextResponse.fromData(imageData);
            }
        } else {
            // save to file requested: make sure file system access is enabled
            if (!this.mcpServer.isFileSystemAccessEnabled()) {
                throw new Error("File system access is not enabled on the MCP server!");
            }
            // save to file
            if (args.format === "png") {
                FileUtils.writeBinaryFile(args.filePath, await this.toPngImageBytes(imageData));
            } else {
                FileUtils.writeTextFile(args.filePath, TextContent.textData(imageData));
            }
            return new TextResponse(`The shape has been exported to ${args.filePath}`);
        }
    }

    /**
     * Converts image data to PNG format if necessary.
     *
     * @param data - The original image data as Uint8Array or as object (from JSON conversion of Uint8Array)
     * @return The image data as PNG bytes
     */
    private async toPngImageBytes(data: Uint8Array | object): Promise<Uint8Array> {
        const originalBytes = ImageContent.byteData(data);

        // use sharp to detect format and convert to PNG if necessary
        const image = sharp(originalBytes);
        const metadata = await image.metadata();

        // if already PNG, return as-is to avoid unnecessary re-encoding
        if (metadata.format === "png") {
            return originalBytes;
        }

        // convert to PNG
        const pngBuffer = await image.png().toBuffer();
        return new Uint8Array(pngBuffer);
    }
}
