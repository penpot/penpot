import * as penpot from "#self";
import { writeFile } from "fs/promises";

(async function () {
  const context = penpot.createBuildContext();

  {
    context.addFile({ name: "Test File 1" });
    context.addPage({ name: "Foo Page" });

    const componentId = context.genId();

    const mainInstanceId = context.addBoard({
      name: "Artboard 1",
      componentFile: context.currentFileId,
      componentId,
      componentRoot: true,
      mainInstance: true,
      x: 20,
      y: 20,
      width: 100,
      height: 200
    });

    const groupId = context.addGroup({
      name: "Group 1",
      x: 20,
      y: 20,
      width: 100,
      height: 200
    });

    const rectId = context.addRect({
      name: "Rect 1",
      x: 20,
      y: 20,
      width: 100,
      height: 200
    });

    const circleId = context.addCircle({
      name: "Circle 1",
      x: 20,
      y: 20,
      width: 100,
      height: 100
    });

    context.closeGroup();

    context.addBool({
      groupId,
      type: "intersection"
    });

    context.closeBoard();

    context.addBoard({
      name: "Artboard 1",
      componentFile: context.currentFileId,
      componentId,
      componentRoot: true,
      shapeRef: mainInstanceId,
      x: 20,
      y: 20,
      width: 100,
      height: 200
    });

    const groupId2 = context.addGroup({
      name: "Group 1",
      shapeRef: groupId,
      x: 20,
      y: 20,
      width: 100,
      height: 200
    });

    context.addRect({
      name: "Rect 1",
      shapeRef: rectId,
      x: 20,
      y: 20,
      width: 100,
      height: 200
    });

    context.addCircle({
      name: "Circle 1",
      shapeRef: circleId,
      x: 20,
      y: 20,
      width: 100,
      height: 100
    });

    context.closeGroup();

    context.addBool({
      groupId: groupId2,
      type: "intersection"
    });

    context.closeBoard();

    context.addComponent({
      componentId: componentId,
      fileId: context.currentFileId,
      name: "Artboard 1",
      frameId: mainInstanceId,
    });

    context.closeFile();
  }

  {
    let result = await penpot.exportAsBytes(context);
    await writeFile("sample-bool-and-comp.zip", result);
  }
})()
  .catch(cause => {
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
