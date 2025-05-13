import * as penpot from "../target/library/penpot.js";
import { writeFile } from 'fs/promises';
import { createWriteStream } from 'fs';
import { Writable } from "stream";

console.log(penpot);

(async function() {
  const file = penpot.createFile({name: "Test"});

  file.addPage({name: "Foo Page"})
  const boardId = file.addArtboard({name: "Foo Board"})
  const rectId  = file.addRect({name: "Foo Rect", width:100, height: 200})

  file.addLibraryColor({color: "#fabada", opacity: 0.5})

  // console.log("created board", boardId);
  // console.log("created rect", rectId);

  // const board = file.getShape(boardId);
  // console.log("=========== BOARD =============")
  // console.dir(board, {depth: 10});

  // const rect = file.getShape(rectId);
  // console.log("=========== RECT =============")
  // console.dir(rect, {depth: 10});

  {
    let result = await penpot.exportAsBytes(file)
    await writeFile("sample-sync.zip", result);
  }

  {
    // Create a file stream to write the zip to
    const output = createWriteStream('sample-stream.zip');

    // Wrap Node's stream in a WHATWG WritableStream
    const writable = Writable.toWeb(output);

    await penpot.exportStream(file, writable);
  }

})().catch((cause) => {
  console.log(cause);
  process.exit(-1);
}).finally(() => {
  process.exit(0);
})
