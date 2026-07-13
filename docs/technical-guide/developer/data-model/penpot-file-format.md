---
title: 3.02.01. Penpot file format (.penpot)
desc: Complete technical specification for the .penpot file format, including structure, schemas, and inspection methods.
---

# Penpot File Format (.penpot)

The `.penpot` file format is Penpot's native export format for design files. It's a ZIP archive containing JSON metadata and binary assets, designed to be open, inspectable, and efficient.

## Overview

The `.penpot` format (version 3) uses a ZIP container with JSON files for metadata and binary files for media assets. This approach provides several advantages:

- **Open and inspectable**: All metadata is human-readable JSON
- **Efficient**: ZIP compression reduces file size
- **Interoperable**: Standard formats enable third-party tooling
- **Versioned**: Clear versioning system for format and data evolution

## Version History

| Version | Description | Status |
|---------|-------------|--------|
| v1 | Custom binary format | Deprecated |
| v2 | SQLite-based format | Never released (internal only) |
| v3 | ZIP + JSON format | **Current** |

## File Structure

A `.penpot` file contains the following structure:

```
pencil.penpot (ZIP archive)
├── manifest.json                          # Root metadata
├── files/
│   ├── {file-id}.json                     # File metadata
│   └── {file-id}/
│       ├── pages/
│       │   ├── {page-id}.json             # Page metadata
│       │   └── {page-id}/
│       │       └── {shape-id}.json        # Individual shapes
│       ├── media/
│       │   └── {media-id}.json            # Media references
│       ├── colors/
│       │   └── {color-id}.json            # Library colors
│       ├── components/
│       │   └── {component-id}.json        # Library components
│       ├── typographies/
│       │   └── {typography-id}.json       # Library typographies
│       ├── tokens.json                    # Design tokens library
│       └── thumbnails/
│           └── {tag}/{page-id}/{frame-id}.json  # Thumbnail metadata
└── objects/
    ├── {uuid}.json                        # Storage object metadata
    └── {uuid}.{ext}                       # Binary media files (png, jpg, etc.)
```

## Manifest Specification

The `manifest.json` file is the root of the archive and contains metadata about the export.

### Schema

```json
{
  "version": 1,
  "type": "penpot/export-files",
  "generatedBy": "penpot/2.12.0",
  "refer": "penpot",
  "files": [
    {
      "id": "uuid",
      "name": "File Name",
      "features": ["feature1", "feature2"]
    }
  ],
  "relations": []
}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | integer | Yes | Format version (currently `1`) |
| `type` | string | Yes | Must be `"penpot/export-files"` |
| `generatedBy` | string | No | Penpot version that created the file |
| `refer` | string | No | Source system (typically `"penpot"`) |
| `files` | array | Yes | List of files in the archive |
| `files[].id` | UUID | Yes | File identifier |
| `files[].name` | string | Yes | File name |
| `files[].features` | array | Yes | Set of feature flags |
| `relations` | array | No | Library relationships `[file-id, library-id]` |

### Example

```json
{
  "type": "penpot/export-files",
  "version": 1,
  "generatedBy": "penpot/2.12.0-RC1-99-g40c27591f",
  "refer": "penpot",
  "files": [
    {
      "id": "73b59a94-3ea3-8189-8007-3d36adc8c3e3",
      "name": "Pencil | Penpot Design System",
      "features": [
        "fdata/path-data",
        "design-tokens/v1",
        "variants/v1",
        "layout/grid",
        "components/v2",
        "fdata/shape-data-type"
      ]
    }
  ],
  "relations": []
}
```

## File Metadata

Each file has a JSON file at `files/{file-id}.json` containing the file's metadata.

### Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | File identifier |
| `name` | string | Yes | File name |
| `revn` | integer | Yes | Revision number |
| `vern` | integer | No | Version number |
| `createdAt` | timestamp | Yes | Creation timestamp |
| `modifiedAt` | timestamp | Yes | Last modification timestamp |
| `deletedAt` | timestamp | No | Deletion timestamp (if soft-deleted) |
| `projectId` | UUID | No | Project identifier |
| `teamId` | UUID | No | Team identifier |
| `isShared` | boolean | No | Whether file is a shared library |
| `hasMediaTrimmed` | boolean | No | Whether media has been trimmed |
| `features` | array | Yes | Set of enabled feature flags |
| `migrations` | array | No | List of applied data migrations |
| `options` | object | No | File-level options |

### Features

The `features` field is a set of strings indicating which features are enabled in the file. Common features include:

| Feature | Description |
|---------|-------------|
| `fdata/path-data` | Path data format |
| `fdata/shape-data-type` | Shape data type system |
| `design-tokens/v1` | Design tokens support |
| `variants/v1` | Component variants |
| `layout/grid` | Grid layout system |
| `components/v2` | Component system v2 |
| `plugins/runtime` | Plugin runtime support |

### Migrations

The `migrations` field lists all data migrations applied to the file. This ensures backward compatibility when the data model evolves.

### Example

```json
{
  "id": "73b59a94-3ea3-8189-8007-3d36adc8c3e3",
  "name": "Pencil | Penpot Design System",
  "revn": 28425,
  "vern": 0,
  "createdAt": "2025-12-10T10:24:18.686066Z",
  "modifiedAt": "2025-12-10T12:13:49.799076Z",
  "teamId": "b62e1aa4-d9a7-8147-8005-2813bed4056e",
  "projectId": "f23add0e-6b77-8069-8005-41b48b93a5da",
  "isShared": true,
  "features": [
    "fdata/path-data",
    "design-tokens/v1",
    "variants/v1",
    "layout/grid",
    "components/v2",
    "fdata/shape-data-type"
  ],
  "migrations": [
    "legacy-2",
    "legacy-3",
    "0001-remove-tokens-from-groups",
    "0002-normalize-bool-content-v2"
  ],
  "options": {
    "componentsV2": true
  }
}
```

## Pages and Shapes

### Page Structure

Each page is stored at `files/{file-id}/pages/{page-id}.json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Page identifier |
| `name` | string | Yes | Page name |
| `index` | integer | No | Page order in the file |
| `options` | object | No | Page options (guides, etc.) |
| `background` | string | No | Background color (hex) |
| `flows` | object | No | Prototype flows |
| `guides` | object | No | Ruler guides |

### Shapes

Individual shapes are stored at `files/{file-id}/pages/{page-id}/{shape-id}.json`.

#### Shape Types

Penpot supports 9 shape types:

| Type | Description |
|------|-------------|
| `frame` | Container frame (artboard) |
| `group` | Group of shapes |
| `rect` | Rectangle |
| `circle` | Circle/Ellipse |
| `path` | Vector path |
| `text` | Text shape |
| `image` | Image |
| `bool` | Boolean operation |
| `svg-raw` | Raw SVG element |

#### Base Shape Attributes

All shapes share these base attributes:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Shape identifier |
| `name` | string | Yes | Shape name |
| `type` | string | Yes | Shape type (see above) |
| `selrect` | object | Yes | Selection rectangle `{x, y, width, height}` |
| `points` | array | Yes | Array of points `[{x, y}, ...]` |
| `transform` | array | Yes | 2D transformation matrix |
| `transformInverse` | array | Yes | Inverse transformation matrix |
| `parentId` | UUID | Yes | Parent shape identifier |
| `frameId` | UUID | Yes | Containing frame identifier |

#### Geometry Attributes

Shapes with geometry (frame, rect, circle, image, svg-raw, text):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `x` | number | Yes | X position |
| `y` | number | Yes | Y position |
| `width` | number | Yes | Width |
| `height` | number | Yes | Height |

#### Generic Attributes

Optional attributes available on all shapes:

| Field | Type | Description |
|-------|------|-------------|
| `fills` | array | Fill styles |
| `strokes` | array | Stroke styles |
| `opacity` | number | Opacity (0-1) |
| `blendMode` | string | Blend mode |
| `shadow` | array | Shadow effects |
| `blur` | object | Blur effect |
| `constraintsH` | string | Horizontal constraint |
| `constraintsV` | string | Vertical constraint |
| `r1`, `r2`, `r3`, `r4` | number | Border radius corners |
| `blocked` | boolean | Shape is locked |
| `hidden` | boolean | Shape is hidden |
| `collapsed` | boolean | Shape is collapsed |
| `componentId` | UUID | Component reference |
| `componentFile` | UUID | Component library file |
| `shapeRef` | UUID | Shape reference for components |
| `touched` | array | Modified component properties |
| `interactions` | array | Prototype interactions |
| `exports` | array | Export settings |
| `grids` | array | Grid configurations |
| `appliedTokens` | object | Applied design tokens |
| `pluginData` | object | Plugin-specific data |

#### Type-Specific Attributes

**Frame**
- `shapes`: array of child shape UUIDs
- `showContent`: boolean
- `hideInViewer`: boolean

**Group**
- `shapes`: array of child shape UUIDs

**Bool**
- `shapes`: array of child shape UUIDs
- `boolType`: string (`union`, `difference`, `exclude`, `intersection`)
- `content`: path data

**Path**
- `content`: path data (SVG path commands)

**Text**
- `content`: text content with formatting
- `positionData`: glyph position data

**Image**
- `metadata`: object with `width`, `height`, `mtype`, `id`

### Example Shape

```json
{
  "id": "260aea33-4e55-808c-8007-3d4f2efe4230",
  "name": "Rectangle",
  "type": "rect",
  "x": 100,
  "y": 100,
  "width": 200,
  "height": 150,
  "selrect": {
    "x": 100,
    "y": 100,
    "width": 200,
    "height": 150
  },
  "points": [
    {"x": 100, "y": 100},
    {"x": 300, "y": 100},
    {"x": 300, "y": 250},
    {"x": 100, "y": 250}
  ],
  "transform": [1, 0, 0, 1, 0, 0],
  "transformInverse": [1, 0, 0, 1, 0, 0],
  "parentId": "00000000-0000-0000-0000-000000000000",
  "frameId": "00000000-0000-0000-0000-000000000001",
  "fills": [
    {
      "color": "#FF5733",
      "opacity": 1
    }
  ],
  "r1": 8,
  "r2": 8,
  "r3": 8,
  "r4": 8
}
```

## Library Assets

### Colors

Stored at `files/{file-id}/colors/{color-id}.json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Color identifier |
| `name` | string | Yes | Color name |
| `path` | string | No | Path in the library tree |
| `opacity` | number | No | Opacity (0-1) |
| `color` | string | Conditional | Hex color (for plain colors) |
| `gradient` | object | Conditional | Gradient definition |
| `image` | object | Conditional | Image fill definition |

#### Plain Color Example

```json
{
  "id": "abc123...",
  "name": "Primary Blue",
  "path": "Brand/Primary",
  "color": "#0066CC",
  "opacity": 1
}
```

#### Gradient Color Example

```json
{
  "id": "def456...",
  "name": "Sunset Gradient",
  "gradient": {
    "type": "linear",
    "startX": 0,
    "startY": 0,
    "endX": 1,
    "endY": 1,
    "stops": [
      {"color": "#FF6B6B", "offset": 0, "opacity": 1},
      {"color": "#4ECDC4", "offset": 1, "opacity": 1}
    ]
  }
}
```

### Components

Stored at `files/{file-id}/components/{component-id}.json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Component identifier |
| `name` | string | Yes | Component name |
| `path` | string | Yes | Path in the library tree |
| `mainInstanceId` | UUID | Yes | Root shape of main instance |
| `mainInstancePage` | UUID | Yes | Page containing main instance |
| `modifiedAt` | timestamp | No | Last modification |
| `objects` | object | No | Captured shapes (if deleted) |

### Typographies

Stored at `files/{file-id}/typographies/{typography-id}.json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Typography identifier |
| `name` | string | Yes | Typography name |
| `fontId` | string | Yes | Font identifier |
| `fontFamily` | string | Yes | Font family name |
| `fontVariantId` | string | Yes | Font variant |
| `fontSize` | string | Yes | Font size |
| `fontWeight` | string | Yes | Font weight |
| `fontStyle` | string | Yes | Font style |
| `lineHeight` | string | Yes | Line height |
| `letterSpacing` | string | Yes | Letter spacing |
| `textTransform` | string | Yes | Text transform |

### Design Tokens

Stored at `files/{file-id}/tokens.json`.

The tokens library contains:

- **Sets**: Collections of tokens organized hierarchically
- **Themes**: Named combinations of token sets
- **Active Themes**: Currently applied themes

#### Token Structure

```json
{
  "sets": {
    "core": {
      "id": "uuid",
      "name": "Core",
      "tokens": {
        "color": {
          "primary": {
            "id": "uuid",
            "name": "primary",
            "type": "color",
            "value": "#0066CC"
          }
        }
      }
    }
  },
  "themes": {
    "light": {
      "id": "uuid",
      "name": "Light",
      "sets": ["core"]
    }
  },
  "activeThemes": ["light"]
}
```

## Media and Storage Objects

### Storage Objects

Binary assets (images, fonts, etc.) are stored in the `objects/` directory.

Each storage object has:
- `objects/{uuid}.json` - Metadata
- `objects/{uuid}.{ext}` - Binary content (png, jpg, svg, etc.)

#### Metadata Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Storage object identifier |
| `size` | integer | Yes | File size in bytes |
| `contentType` | string | Yes | MIME type |
| `bucket` | string | Yes | Storage bucket |
| `hash` | string | No | Content hash (blake2b) |

#### Example

```json
{
  "id": "0039433d-adc8-430d-b2c3-d884dea6e050",
  "size": 575,
  "contentType": "image/png",
  "bucket": "file-media-object",
  "hash": "blake2b:77d447db38eb5daf31acb7344a504cacc6b79aa11855a00501d9475c595053d0"
}
```

### Media References

File media references are stored at `files/{file-id}/media/{media-id}.json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | UUID | Yes | Media identifier |
| `name` | string | Yes | File name |
| `width` | integer | Yes | Image width |
| `height` | integer | Yes | Image height |
| `mtype` | string | Yes | MIME type |
| `mediaId` | UUID | Yes | Reference to storage object |
| `thumbnaillId` | UUID | No | Reference to thumbnail |
| `isLocal` | boolean | No | Whether media is local to file |

### Thumbnails

Page thumbnails are stored at `files/{file-id}/thumbnails/{tag}/{page-id}/{frame-id}.json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `fileId` | UUID | Yes | File identifier |
| `pageId` | UUID | Yes | Page identifier |
| `frameId` | UUID | Yes | Frame identifier |
| `tag` | string | Yes | Thumbnail tag |
| `mediaId` | UUID | Yes | Reference to storage object |

## Plugin Data

Plugins can store custom data on files, pages, shapes, components, colors, and typographies using the `pluginData` field.

### Structure

```json
{
  "pluginData": {
    "plugin-id": {
      "key1": "value1",
      "key2": "value2"
    }
  }
}
```

The plugin ID is a keyword (e.g., `"my-plugin"`), and the values are string key-value pairs.

## Inspecting a .penpot File

### List Contents

```bash
unzip -l design.penpot
```

### Extract and View

```bash
# Extract to temporary directory
unzip design.penpot -d /tmp/penpot-inspect

# View manifest
cat /tmp/penpot-inspect/manifest.json | jq .

# List all files
find /tmp/penpot-inspect -name "*.json" | head -20

# View a specific shape
cat /tmp/penpot-inspect/files/*/pages/*/*.json | jq .
```

### Quick Inspection Script

```bash
#!/bin/bash
# Inspect .penpot file structure

FILE=$1
TMPDIR=$(mktemp -d)

unzip -q "$FILE" -d "$TMPDIR"

echo "=== Manifest ==="
cat "$TMPDIR/manifest.json" | jq '{type, version, files: [.files[] | {id, name}]}'

echo -e "\n=== Files ==="
for f in "$TMPDIR"/files/*.json; do
  echo "- $(jq -r '.name' "$f")"
done

echo -e "\n=== Pages ==="
for f in "$TMPDIR"/files/*/pages/*.json; do
  echo "- $(jq -r '.name' "$f")"
done

echo -e "\n=== Storage Objects ==="
ls -lh "$TMPDIR"/objects/*.{png,jpg,svg} 2>/dev/null | wc -l
echo "media files"

rm -rf "$TMPDIR"
```

## Cross-References

- [Data Model](/technical-guide/developer/data-model/) - Conceptual data model
- [Data Guide](/technical-guide/developer/data-guide/) - Working with data structures
- [Export/Import Files](/user-guide/export-import/export-import-files/) - User guide for exporting and importing

## Source Code References

The authoritative schema definitions are in the Penpot source code:

- **Manifest**: `backend/src/app/binfile/v3.clj` (schema:manifest)
- **File**: `common/src/app/common/types/file.cljc` (schema:file)
- **Page**: `common/src/app/common/types/page.cljc` (schema:page)
- **Shape**: `common/src/app/common/types/shape.cljc` (schema:shape)
- **Component**: `common/src/app/common/types/component.cljc` (schema:component)
- **Color**: `common/src/app/common/types/color.cljc` (schema:library-color)
- **Typography**: `common/src/app/common/types/typography.cljc` (schema:typography)
- **Tokens**: `common/src/app/common/types/tokens_lib.cljc` (schema:tokens-lib)
- **Plugin Data**: `common/src/app/common/types/plugins.cljc` (schema:plugin-data)
- **Features**: `common/src/app/common/features.cljc` (schema:features)
