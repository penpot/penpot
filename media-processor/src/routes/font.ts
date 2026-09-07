import { Router, type IRouter, type Request, type Response, type NextFunction } from "express";
import { getUpload, getFileInput } from "../upload.js";
import { convertFont } from "../services/font.js";
import { throwValidation } from "../services/errors.js";
import { cleanupMiddleware } from "../middleware/cleanup.js";

const VALID_TARGET_MTYPES = new Set(["font/ttf", "font/otf", "font/woff"]);
const VALID_SOURCE_MTYPES = new Set(["font/ttf", "font/otf", "font/woff", "font/woff2"]);

export function createFontRoutes(): IRouter {
  const router: IRouter = Router();
  const upload = getUpload();

  router.post(
    "/convert",
    upload.single("file"),
    cleanupMiddleware,
    async (req: Request, res: Response, next: NextFunction) => {
      const releaseQueue = (res as any).locals?.releaseQueue;
      const signal = (req as any).abortController?.signal;
      try {
        if (!req.file) {
          throwValidation("invalid-font", "No file uploaded");
        }

        const input = getFileInput(req.file!);
        const sourceMtype = req.file!.mimetype;
        if (!VALID_SOURCE_MTYPES.has(sourceMtype)) {
          throwValidation("invalid-font", `Unrecognized font mime-type: ${sourceMtype}`);
        }

        const targetMtype = req.query["target-type"] as string;
        if (!targetMtype || !VALID_TARGET_MTYPES.has(targetMtype)) {
          throwValidation("invalid-font", `Invalid target-type. Must be one of: font/ttf, font/otf, font/woff`);
        }

        res.locals.opMeta = `src=${sourceMtype}, dest=${targetMtype}`;
        const result = await convertFont(input, sourceMtype, targetMtype, signal);

        if (!result) {
          throwValidation("invalid-font", `Conversion from ${sourceMtype} to ${targetMtype} is not supported`);
        }

        res.setHeader("Content-Type", targetMtype);
        res.send(result);
      } catch (err) {
        next(err);
      } finally {
        if (releaseQueue) releaseQueue();
      }
    }
  );

  return router;
}
