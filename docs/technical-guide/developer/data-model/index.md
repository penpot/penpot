---
title: 3.2. Data model
---

# Penpot Data Model

This is the conceptual data model. The actual representations of those entities
slightly differ, depending on the environment (frontend app, backend RPC calls
or the SQL database, for example). But the concepts are always the same.

The diagrams use [basic UML notation with PlantUML](https://plantuml.com/en/class-diagram).

## Users, teams and projects

@startuml TeamModel

hide members

class Profile
class Team
class Project
class File
class StorageObject
class CommentThread
class Comment
class ShareLink

Profile "*" -right- "*" Team
Team *--> "*" Project
Profile "*" -- "*" Project
Project *--> "*" File
Profile "*" -- "*" File
File "*" <-- "*" File : libraries
File *--> "*" StorageObject : media_objects
File *--> "*" CommentThread : comment_threads
CommentThread *--> "*" Comment
File *--> "*" ShareLink : share_links

@enduml

A <code class="language-text">Profile</code> holds the personal info of any user of the system. Users belongs to
<code class="language-text">Teams</code> and may create <code class="language-text">Projects</code> inside them.

Inside the projects, there are <code class="language-text">Files</code>. All users of a team may see the projects
and files inside the team. Also, any project and file has at least one user that
is the owner, but may have more relationships with users with other roles.

Files may use other files as shared <code class="language-text">libraries</code>.

The main content of the file is in the "file data" attribute (see next section).
But there are some objects that reside in separate entities:

 * A <code class="language-text">StorageObject</code> represents a file in an external storage, that is embedded
   into a file (currently images and SVG icons, but we may add other media
   types in the future).

 * <code class="language-text">CommentThreads</code>and <code class="language-text">Comments</code> are the comments that any user may add to a
   file.

 * A <code class="language-text">ShareLink</code> contains a token, an URL and some permissions to share the file
   with external users.

## File data

@startuml FileModel

hide members

class File
class Page
class Component
class Color
class MediaItem
class Typography

File *--> "*" Page : pages
(File, Page) .. PagesList

File *--> "*" Component : components
(File, Component) .. ComponentsList

File *--> "*" Color : colors
(File, Color) .. ColorsList

File *--> "*" MediaItem : colors
(File, MediaItem) .. MediaItemsList

File *--> "*" Typography : colors
(File, Typography) .. TypographiesList

@enduml

The data attribute contains the <code class="language-text">Pages</code> and the library assets in the file
(<code class="language-text">Components</code>, <code class="language-text">MediaItems</code>, <code class="language-text">Colors</code> and <code class="language-text">Typographies</code>).

The lists of pages and assets are modelled also as entities because they have a
lot of functions and business logic.

## Pages and components

@startuml PageModel

hide members

class Container
class Page
class Component
class Shape

Container <|-left- Page
Container <|-right- Component

Container *--> "*" Shape : objects
(Container, Shape) .. ShapeTree

Shape <-- Shape : parent

@enduml

Both <code class="language-text">Pages</code> and <code class="language-text">Components</code> contains a tree of shapes, and share many
functions and logic. So, we have modelled a <code class="language-text">Container</code> entity, that is an
abstraction that represents both a page or a component, to use it whenever we
have code that fits the two.

A <code class="language-text">ShapeTree</code> represents a set of shapes that are hierarchically related: the top
frame contains top-level shapes (frames and other shapes). Frames and groups may
contain any non frame shape.

## Shapes

@startuml ShapeModel

hide members

class Shape
class Selrect
class Transform
class Constraints
class Interactions
class Fill
class Stroke
class Shadow
class Blur
class Font
class Content
class Exports

Shape o--> Selrect
Shape o--> Transform
Shape o--> Constraints
Shape o--> Interactions
Shape o--> Fill
Shape o--> Stroke
Shape o--> Shadow
Shape o--> Blur
Shape o--> Font
Shape o--> Content
Shape o--> Exports

Shape <-- Shape : parent

@enduml

A <code class="language-text">Shape</code> is the most important entity of the model. Represents one of the
[layers of our design](https://help.penpot.app/user-guide/layer-basics), and it
corresponds with one SVG node, augmented with Penpot special features.

We have code to render a <code class="language-text">Shape</code> into a SVG tag, with more or less additions
depending on the environment (editable in the workspace, interactive in the
viewer, minimal in the shape exporter or the handoff, or with metadata in the
file export).

Also have code that imports any SVG file and convert elements back into shapes.
If it's a SVG exported by Penpot, it reads the metadata to reconstruct the
shapes exactly as they were. If not, it infers the atributes with a best effort
approach.

In addition to the identifier ones (the id, the name and the type of element),
a shape has a lot of attributes. We tend to group them in related clusters.
Those are the main ones:

 * <code class="language-text">Selrect</code> and other geometric attributes (x, y, width, height...) define the
   position in the diagram and the bounding box.
 * <code class="language-text">Transform</code> is a [2D transformation matrix](https://www.alanzucconi.com/2016/02/10/tranfsormation-matrix/)
   to rotate or stretch the shape.
 * <code class="language-text">Constraints</code> explains how the shape changes when the container shape resizes
   (kind of "responsive" behavior).
 * <code class="language-text">Interactions</code> describe the interactive behavior when the shape is displayed
   in the viewer.
 * <code class="language-text">Fill</code> contains the shape fill color and options.
 * <code class="language-text">Stroke</code> contains the shape stroke color and options.
 * <code class="language-text">Shadow</code> contains the shape shadow options.
 * <code class="language-text">Blur</code> contains the shape blur options.
 * <code class="language-text">Font</code> contains the font options for a shape of type text.
 * <code class="language-text">Content</code> contains the text blocks for a shape of type text.
 * <code class="language-text">Exports</code> are the defined export settings for the shape.

Also a shape contains a reference to its containing shape (parent) and of all
the children.
