import express from "express";
import compression from "compression";

import { fileURLToPath } from "url";
import path from "path";

const app = express();
const port = 3000;

app.use(compression());

const staticPath = path.join(
  fileURLToPath(import.meta.url),
  "../../resources/public",
);
app.use(express.static(staticPath));

app.listen(port, () => {
  console.log(`Listening at 0.0.0.0:${port}`);
});
