import sharp from "sharp";
import type { FileInput, ImageInfo, ThumbnailParams } from "../types.js";
import { throwValidation, throwRestriction } from "./errors.js";
import { createLogger } from "../logger.js";

const logger = createLogger("image");

const SUPPORTED_MIMES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);

function orientationSwapDimensions(
  width: number,
  height: number,
  orientation: number
): { width: number; height: number } {
  if (orientation === 6 || orientation === 8) {
    return { width: height, height: width };
  }
  return { width, height };
}

let imageMaxPixels = 128_000_000;
let imageMaxWidth = 16384;
let imageMaxHeight = 16384;

export function configureImageLimits(opts: { maxPixels: number; maxWidth: number; maxHeight: number }): void {
  imageMaxPixels = opts.maxPixels;
  imageMaxWidth = opts.maxWidth;
  imageMaxHeight = opts.maxHeight;
}

function validateImageDimensions(width: number, height: number): void {
  if (width > imageMaxWidth || height > imageMaxHeight) {
    throwRestriction(
      "image-dimensions-exceeded",
      `Image dimensions ${width}x${height} exceed maximum ${imageMaxWidth}x${imageMaxHeight}`
    );
  }
  const pixels = width * height;
  if (pixels > imageMaxPixels) {
    throwRestriction("image-pixel-count-exceeded", `Image pixel count ${pixels} exceeds maximum ${imageMaxPixels}`);
  }
}

export async function getImageInfo(input: FileInput, size: number): Promise<ImageInfo> {
  const metadata = await sharp(input).metadata();

  if (!metadata.width || !metadata.height) {
    throwValidation("invalid-image", "Could not read image dimensions");
  }

  const mtype = metadata.format ? `image/${metadata.format}` : undefined;
  if (!mtype || !SUPPORTED_MIMES.has(mtype)) {
    throwValidation("invalid-image", `Unsupported image format: ${metadata.format}`);
  }

  const orientation = metadata.orientation ?? 1;
  const { width, height } = orientationSwapDimensions(metadata.width!, metadata.height!, orientation);

  validateImageDimensions(width, height);

  logger.debug({ width, height, mtype: mtype!, size }, "Image info extracted");

  return {
    width,
    height,
    mtype: mtype!,
    size,
    orientation,
  };
}

const FORMAT_MIMES: Record<string, string> = {
  jpeg: "image/jpeg",
  webp: "image/webp",
  png: "image/png",
};

export async function generateThumbnail(
  input: FileInput,
  params: ThumbnailParams
): Promise<{ data: Buffer; mtype: string }> {
  // Pre-validate source image dimensions using the same sharp instance
  // that will be used for the resize pipeline — avoids decoding the image twice.
  const source = sharp(input);
  const srcMeta = await source.metadata();
  if (srcMeta.width == null || srcMeta.height == null) {
    throwValidation("invalid-image", "Could not read source image dimensions");
  }
  const orientation = srcMeta.orientation ?? 1;
  const { width: displayWidth, height: displayHeight } = orientationSwapDimensions(
    srcMeta.width,
    srcMeta.height,
    orientation
  );
  validateImageDimensions(displayWidth, displayHeight);

  logger.debug(
    { width: params.width, height: params.height, format: params.format, mode: params.mode },
    "Generating thumbnail"
  );

  let pipeline = source.rotate().flatten({ background: { r: 255, g: 255, b: 255 } });

  if (params.mode === "fit") {
    pipeline = pipeline.resize(params.width, params.height, {
      fit: "inside",
      withoutEnlargement: true,
    });
  } else {
    pipeline = pipeline.resize(params.width, params.height, {
      fit: "cover",
      position: "center",
    });
  }

  switch (params.format) {
    case "jpeg":
      pipeline = pipeline.jpeg({ quality: params.quality });
      break;
    case "webp":
      pipeline = pipeline.webp({ quality: params.quality });
      break;
    case "png":
      pipeline = pipeline.png();
      break;
  }

  const data = await pipeline.toBuffer();
  return { data, mtype: FORMAT_MIMES[params.format] };
}
