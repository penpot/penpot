import * as penpot from "#self";
import { writeFile, readFile } from "fs/promises";
import { createWriteStream } from "fs";
import { Writable } from "stream";

// console.log(penpot);

(async function () {
  const context = penpot.createBuildContext({referer:"playground"});

  {
    context.addFile({ name: "Test File 1" });
    context.addPage({ name: "Foo Page" });

    // Add image media
    const buffer = await readFile("./playground/sample.jpg");
    const blob = new Blob([buffer], { type: "image/jpeg" });

    const mediaId = context.addFileMedia(
      {
        name: "avatar.jpg",
        width: 512,
        height: 512,
      },
      blob
    );

    // Add image color asset
    const assetColorId = context.addLibraryColor({
      name: "Avatar",
      opacity: 1,
      image: {
        ...context.getMediaAsImage(mediaId),
        keepAspectRatio: true,
      },
    });

    const boardId = context.addBoard({
      name: "Foo Board",
      x: 0,
      y: 0,
      width: 500,
      height: 1000,
    });

    const fill = {
      fillColorRefId: assetColorId,
      fillColorRefFile: context.currentFileId,
      fillImage: {
        ...context.getMediaAsImage(mediaId),
        keepAspectRatio: true,
      },
    };

    const stroke = {
      strokeColorRefId: assetColorId,
      strokeColorRefFile: context.currentFileId,
      strokeWidth: 48,
      strokeAlignment: "inner",
      strokeStyle: "solid",
      strokeOpacity: 1,
      strokeImage: {
        ...context.getMediaAsImage(mediaId),
        keepAspectRatio: true,
      },
    };

    context.addRect({
      name: "Rect 1",
      x: 20,
      y: 20,
      width: 500,
      height: 1000,
      fills: [fill],
      strokes: [stroke],
    });

    context.closeBoard();
    context.closeFile();
  }

  {
    const onProgress = (opts) => {
      console.log(`Procesing ${opts.item}/${opts.total}: ${opts.path}`);
    };

    let result = await penpot.exportAsBytes(context, {onProgress});
    await writeFile("sample-sync.zip", result);
  }

  // {
  //   // Create a file stream to write the zip to
  //   const output = createWriteStream('sample-stream.zip');
  //   // Wrap Node's stream in a WHATWG WritableStream
  //   const writable = Writable.toWeb(output);
  //   await penpot.exportStream(context, writable);
  // }
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
