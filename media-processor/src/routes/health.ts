import type { Request, Response } from "express";

export function healthRoutes(_req: Request, res: Response): void {
  res.json({ status: "ok" });
}
