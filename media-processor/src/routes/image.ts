import { Router, type IRouter, type Request, type Response, type NextFunction } from "express";
import { getUpload, getFileInput } from "../upload.js";
import { getImageInfo, generateThumbnail } from "../services/image.js";
import { throwValidation } from "../services/errors.js";
import type { ThumbnailParams } from "../types.js";

export function createImageRoutes(): IRouter {
  const router: IRouter = Router();
  const upload = getUpload();

  router.post("/info", upload.single("file"), async (req: Request, res: Response, next: NextFunction) => {
    try {
      if (!req.file) {
        throwValidation("invalid-image", "No file uploaded");
      }

      const input = getFileInput(req.file!);
      const info = await getImageInfo(input, req.file!.size);
      res.locals.opMeta = `mtype=${info.mtype}, size=${info.width}x${info.height}`;
      res.json(info);
    } catch (err) {
      next(err);
    }
  });

  router.post("/thumbnail", upload.single("file"), async (req: Request, res: Response, next: NextFunction) => {
    try {
      if (!req.file) {
        throwValidation("invalid-image", "No file uploaded");
      }

      const input = getFileInput(req.file!);
      const width = parseInt(req.query.width as string, 10);
      const height = parseInt(req.query.height as string, 10);
      const quality = parseInt(req.query.quality as string, 10) || 85;
      const format = (req.query.format as string) || "jpeg";
      const mode = (req.query.mode as string) || "fit";

      if (isNaN(width) || isNaN(height) || width <= 0 || height <= 0) {
        throwValidation("invalid-image", "width and height must be positive integers");
      }

      if (!["jpeg", "webp", "png"].includes(format)) {
        throwValidation("invalid-image", `Unsupported format: ${format}`);
      }

      if (!["fit", "crop"].includes(mode)) {
        throwValidation("invalid-image", `Unsupported mode: ${mode}`);
      }

      const params: ThumbnailParams = {
        width,
        height,
        quality: Math.min(100, Math.max(1, quality)),
        format: format as "jpeg" | "webp" | "png",
        mode: mode as "fit" | "crop",
      };

      res.locals.opMeta = `size=${width}x${height}, fmt=${format}, mode=${mode}, q=${params.quality}`;
      const { data, mtype } = await generateThumbnail(input, params);
      res.setHeader("Content-Type", mtype);
      res.send(data);
    } catch (err) {
      next(err);
    }
  });

  return router;
}
