import * as penpot from "#self";
import { writeFile, readFile } from "fs/promises";

(async function () {
  const context = penpot.createBuildContext();

  {
    context.addFile({ name: "Test File 1" });
    context.addPage({ name: "Foo Page" });

    const pathContent = [
      {
        "command": "move-to",
        "params": {
          "x": 480.0,
          "y": 839.0
        }
      },
      {
        "command": "line-to",
        "params": {
          "x": 439.0,
          "y": 802.0
        }
      },
      {
        "command": "curve-to",
        "params": {
          "c1x": 368.0,
          "c1y": 737.0,
          "c2x": 310.0,
          "c2y": 681.0,
          "x": 264.0,
          "y": 634.0
        }
      },
      {
        "command": "close-path",
        "params": {}
      }
    ];

    context.addPath({
      name: "Path 1",
      content: pathContent
    });

    context.closeBoard();
    context.closeFile();
  }

  {
    let result = await penpot.exportAsBytes(context);
    await writeFile("sample-path.zip", result);
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
