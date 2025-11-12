# CHANGELOG

## 1.0.11

- Set correct path if it is not provided on addComponent


## 1.0.10

- Enable variant/v1 feature by default
- Add variant attrs handling to addComponent method


## 1.0.9

- Fix dependencies declaration on package.json


## 1.0.8

- Update penpot runtime


## 1.0.7

- Add the ability to provide refereron creating build context

```js
const context = penpot.createBuildContext({referer:"my-referer"});
```

The referer will be added as an additional field on the manifest.json


## 1.0.6

- Fix unexpected issue on library color decoding


## 1.0.5

- Add progress reporting support
- Remove leaked console.log

## 1.0.4

- Fix incorrect shapes filtering on creating boolean shapes within components


## 1.0.3

- Add missing isLocal field on file media for fix compatibility of the
  library with penpot 2.7.xx


## 1.0.2

- Fix incorrect boolean type assignation
- Fix fill and stroke handling on boolean shape creation
- Add sample-bool.js to the playground directory
- Fix compatibility issue on file media with penpot 2.7.x


## 1.0.1

- Make the library generate a .penpot file compatible with penpot 2.7.x
- Remove useless method `addComponentInstance`


## 1.0.0

The library entrypoint API object has been changed. From now you start creating a new
build context, from where you can add multiple files and attach media. This change add the
ability to build more than one file at same time and export them in an unique .penpot
file.

```js
const context = penpot.createBuildContext()

context.addFile({name:"aa"})
context.addPage({name:"aa"})
context.closePage()
context.closeFile()

;; barray is instance of Uint8Array
const barray = penpot.exportAsBytes(context);
```

The previous `file.export()` method has been removed and several alternatives are
added as first level functions on penpot library API entrypoint:

- `exportAsBytes(BuildContext context) -> Promise<Uint8Array>`
- `exportAsBlob(BuildContext context) -> Promise<Blob>`
- `exportStream(BuildContext context, WritableStream stream) -> Promise<Void>`

The stream variant allows writting data as it is generated to the stream, without the need
to store the generated output entirelly in the memory.

There are also relevant semantic changes in how components should be created: this
refactor removes all notions of the old components (v1). Since v2, the shapes that are
part of a component live on a page. So, from now on, to create a component, you should
first create a frame, then add shapes and/or groups to that frame, and then create a
component by declaring that frame as the component root.

A non exhaustive list of changes:

- Change the signature of the `addPage` method: it now accepts an object (as a single argument) where you can pass `id`,
  `name`, and `background` props (instead of the previous positional arguments)
- Rename the `createRect` method to `addRect`
- Rename the `createCircle` method to `addCircle`
- Rename the `createPath` method to `addPath`
- Rename the `createText` method to `addText`
- Rename the `addArtboard` method to `addBoard`
- Rename `startComponent` to `addComponent` (to preserve the naming style)
- Rename `createComponentInstance` to `addComponentInstance` (to preserve the naming style)
- Remove `lookupShape`
- Remove `asMap`
- Remove `updateLibraryColor` (use `addLibraryColor` if you just need to replace a color)
- Remove `deleteLibraryColor` (this library is intended to build files)
- Remove `updateLibraryTypography` (use `addLibraryTypography` if you just need to replace a typography)
- Remove `deleteLibraryTypography` (this library is intended to build files)
- Remove `add/update/deleteLibraryMedia` (they are no longer supported by Penpot and have been replaced by components)
- Remove `deleteObject` (this library is intended to build files)
- Remove `updateObject` (this library is intended to build files)
- Remove `finishComponent` (it is no longer necessary; see below for more details on component creation changes)

- Change the `getCurrentPageId` function to a read-only `currentPageId` property
- Add `currentFileId` read-only property
- Add `currentFrameId` read-only property
- Add `lastId` read-only property


## 0.0.0

Unreleased, initial protype version.
