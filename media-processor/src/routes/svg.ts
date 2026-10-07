import { Router, type IRouter, type Request, type Response, type NextFunction } from "express";
import { getUpload, getFileBuffer } from "../upload.js";
import { sanitizeSvg, getSvgMaxSize } from "../services/svg.js";
import { throwValidation, throwRestriction } from "../services/errors.js";
import { cleanupMiddleware } from "../middleware/cleanup.js";

export function createSvgRoutes(): IRouter {
  const router: IRouter = Router();
  const upload = getUpload();

  router.post(
    "/sanitize",
    upload.single("file"),
    cleanupMiddleware,
    async (req: Request, res: Response, next: NextFunction) => {
      const releaseQueue = (res as any).locals?.releaseQueue;
      try {
        if (!req.file) {
          throwValidation("invalid-svg-file", "No file uploaded");
        }

        if (req.file!.size > getSvgMaxSize()) {
          throwRestriction(
            "svg-too-large",
            `SVG size ${req.file!.size} exceeds the maximum of ${getSvgMaxSize()} bytes`
          );
        }

        res.locals.opMeta = `size=${req.file!.size}`;
        const clean = await sanitizeSvg((await getFileBuffer(req.file!)).toString("utf8"));

        res.setHeader("Content-Type", "image/svg+xml");
        res.send(Buffer.from(clean, "utf8"));
      } catch (err) {
        next(err);
      } finally {
        if (releaseQueue) releaseQueue();
      }
    }
  );

  return router;
}
