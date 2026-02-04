import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

type CallToolContent = CallToolResult["content"][number];
type TextItem = Extract<CallToolContent, { type: "text" }>;
type ImageItem = Extract<CallToolContent, { type: "image" }>;

export class TextContent implements TextItem {
    [x: string]: unknown;
    readonly type = "text" as const;
    constructor(public text: string) {}

    /**
     * @param data - Text data as string or as object (from JSON representation where indices are mapped to character codes)
     */
    public static textData(data: string | object): string {
        if (typeof data === "object") {
            // convert object containing character codes (as obtained from JSON conversion of string) back to string
            return String.fromCharCode(...(Object.values(data) as number[]));
        } else {
            return data;
        }
    }
}

export class ImageContent implements ImageItem {
    [x: string]: unknown;
    readonly type = "image" as const;

    /**
     * @param data - Base64-encoded image data
     * @param mimeType - MIME type of the image (e.g., "image/png")
     */
    constructor(
        public data: string,
        public mimeType: string
    ) {}

    /**
     * Utility function for ensuring a consistent Uint8Array representation of byte data.
     * Input can be either a Uint8Array or an object (as obtained from JSON conversion of Uint8Array
     * from the plugin).
     *
     * @param data - data as Uint8Array or as object (from JSON conversion of Uint8Array)
     * @return data as Uint8Array
     */
    public static byteData(data: Uint8Array | object): Uint8Array {
        if (typeof data === "object") {
            // convert object (as obtained from JSON conversion of Uint8Array) back to Uint8Array
            return new Uint8Array(Object.values(data) as number[]);
        } else {
            return data;
        }
    }
}

export class PNGImageContent extends ImageContent {
    /**
     * @param data - PNG image data as Uint8Array or as object (from JSON conversion of Uint8Array)
     */
    constructor(data: Uint8Array | object) {
        let array = ImageContent.byteData(data);
        super(Buffer.from(array).toString("base64"), "image/png");
    }
}

export class ToolResponse implements CallToolResult {
    [x: string]: unknown;
    content: CallToolContent[]; // <- IMPORTANT: protocol’s union
    constructor(content: CallToolContent[]) {
        this.content = content;
    }
}

export class TextResponse extends ToolResponse {
    constructor(text: string) {
        super([new TextContent(text)]);
    }

    /**
     * Creates a TextResponse from text data given as string or as object (from JSON representation where indices are mapped to
     * character codes).
     *
     * @param data - Text data as string or as object (from JSON representation where indices are mapped to character codes)
     */
    public static fromData(data: string | object): TextResponse {
        return new TextResponse(TextContent.textData(data));
    }
}

export class PNGResponse extends ToolResponse {
    /**
     * @param data - PNG image data as Uint8Array or as object (from JSON conversion of Uint8Array)
     */
    constructor(data: Uint8Array | object) {
        super([new PNGImageContent(data)]);
    }
}
