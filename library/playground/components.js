import * as penpot from "#self";
import { createWriteStream } from 'fs';
import { Writable } from "stream";

// Example of creating component and instance out of order

(async function() {
  const context = penpot.createBuildContext();

  {
    context.addFile({name: "Test File 1"});
    context.addPage({name: "Foo Page"})

    const mainBoardId = context.genId();
    const mainRectId = context.genId();

    // First create instance (just for with the purpose of teaching
    // that it can be done, without putting that under obligation to
    // do it in this order or the opposite)

    context.addBoard({
      name: "Board Instance 1",
      x: 700,
      y: 0,
      width: 500,
      height: 300,
      shapeRef: mainBoardId,
      touched: ["name-group"]
    })

    context.addRect({
      name: "Rect Instance 1",
      x: 800,
      y: 20,
      width:100,
      height:200,
      shapeRef: mainRectId,
      touched: ["name-group"]
    });

    // this function call takes the current board from context, but it
    // also can be passed as parameter on an explicit way if you
    // prefer
    context.addComponentInstance({
      componentId: "00000000-0000-0000-0000-000000000001"
    });

    context.closeBoard();

    // Then, create the main instance
    context.addBoard({
      id: mainBoardId,
      name: "Board",
      x: 0,
      y: 0,
      width: 500,
      height: 300,
    })

    context.addRect({
      id: mainRectId,
      name: "Rect 1",
      x: 20,
      y: 20,
      width:100,
      height:200,
    });

    context.addComponent({
      componentId: "00000000-0000-0000-0000-000000000001",
      name: "Component 1",
    });

    context.closeBoard();
    context.closeFile();
  }

  {
    // Create a file stream to write the zip to
    const output = createWriteStream('sample-with-components.zip');
    // Wrap Node's stream in a WHATWG WritableStream
    const writable = Writable.toWeb(output);
    await penpot.exportStream(context, writable);
  }

})().catch((cause) => {
  console.error(cause);

  const causeExplain = cause.explain;
  if (causeExplain) {
    console.log("EXPLAIN:")
    console.error(cause.explain);
  }

  // const innerCause = cause.cause;
  // if (innerCause) {
  //   console.log("INNER:");
  //   console.error(innerCause);
  // }
  process.exit(-1);
}).finally(() => {
  process.exit(0);
})
