import * as penpot from "#self";
import { writeFile, readFile } from "fs/promises";

(async function () {
  const context = penpot.createBuildContext();

  {
    context.addFile({ name: "Test File 1" });
    context.addPage({ name: "Foo Page" });

    const groupId = context.addGroup({
      name: "Bool Group"
    })

    context.addRect({
      name: "Rect 1",
      x: 20,
      y: 20,
      width:100,
      height:100,
    });

    context.addRect({
      name: "Rect 2",
      x: 90,
      y: 90,
      width:100,
      height:100,
      fills: [{fillColor: "#fabada", fillOpacity:1}]
    });

    context.closeGroup();
    context.addBool({
      groupId: groupId,
      type: "union"
    });

    context.closeBoard();
    context.closeFile();
  }

  {
    let result = await penpot.exportAsBytes(context);
    await writeFile("sample-bool.zip", result);
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
