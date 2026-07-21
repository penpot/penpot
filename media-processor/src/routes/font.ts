import { Router, type IRouter, type Request, type Response, type NextFunction } from "express";
import { getUpload, getFileInput } from "../upload.js";
import { convertFont } from "../services/font.js";
import { throwValidation } from "../services/errors.js";

const VALID_TARGET_MTYPES = new Set(["font/ttf", "font/otf", "font/woff"]);
const VALID_SOURCE_MTYPES = new Set(["font/ttf", "font/otf", "font/woff", "font/woff2"]);

export function createFontRoutes(): IRouter {
  const router: IRouter = Router();
  const upload = getUpload();

  router.post("/convert", upload.single("file"), async (req: Request, res: Response, next: NextFunction) => {
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
      const result = await convertFont(input, sourceMtype, targetMtype);

      if (!result) {
        throwValidation("invalid-font", `Conversion from ${sourceMtype} to ${targetMtype} is not supported`);
      }

      res.setHeader("Content-Type", targetMtype);
      res.send(result);
    } catch (err) {
      next(err);
    }
  });

  return router;
}
