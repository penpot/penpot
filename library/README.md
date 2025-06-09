# Penpot Library

A library that exposes a limited set of Penpot's features for
programmatic use. Currently, it provides methods to build Penpot files
in memory and export them as .penpot files (ZIP archives containing
all the necessary data to import them into a Penpot application).

## User Guide

### How to install

```bash
yarn add @penpot/library
```

### Example of use

```js
import * as penpot from "@penpot/library";
import { createWriteStream } from 'fs';
import { Writable } from "stream";

const context = penpot.createBuildContext();


context.addFile({name: "Test File 1"});
context.addPage({name: "Foo Page"});

context.addBoard({
  name: "Foo Board",
  x: 0,
  y: 0,
  width: 500,
  height: 300,
});

context.closeBoard();
context.closeFile();

// Create a file stream to write the zip to
const output = createWriteStream('sample-stream.zip');

// Wrap Node's stream in a WHATWG WritableStream
const writable = Writable.toWeb(output);
await penpot.exportStream(context, writable);
```

## Developer Guide

### How to publish

Build the library:

```bash
./scripts/build
```

Login on npm:

```bash
yarn npm login
```

Publish on npm:


```bash
yarn npm publish --access public
```

## License

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) KALEIDOS INC
```

