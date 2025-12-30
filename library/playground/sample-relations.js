import * as penpot from "#self";
import { writeFile, readFile } from "fs/promises";

(async function () {
  const context = penpot.createBuildContext();

  {
    const file1 = context.addFile({ name: "Test File 1" });
    const file2 = context.addFile({ name: "Test File 1" });

    context.addRelation(file1, file2);
  }

  {
    let result = await penpot.exportAsBytes(context);
    await writeFile("sample-relations.zip", result);
  }
})()
  .catch((cause) => {
    console.error(cause);

    const innerCause = cause.cause;
    if (innerCause) {
      console.error("Inner cause:", innerCause);
    }
    process.exit(-1);
  })
  .finally(() => {
    process.exit(0);
  });
