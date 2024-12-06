---
title: 3.7. Data Guide
---

# Data Guide

The data structures are one of the most complex and important parts of Penpot.
It's critical that the data integrity is always maintained throughout the whole
usage, and also file exports & imports and data model evolution.

To modify the data structure (the most typical case will be to add a new attribute
to the shapes), this list must be checked. This is not an exhaustive list, but
all of this is important in general.

## General considerations

* We prefer that the page and shape attributes are optional. I.E. there is a
  default object behavior, that occurs when the attribute is not present, and
  its presence activates some feature (example: if there is no <code class="language-clojure">fill-color</code>,
  the shape is not filled). When you revert to the default state, it's better
  to remove the attribute than leaving it with <code class="language-clojure">null</code> value. There are some
  process (for example import & export) that filter out and remove all
  attributes that are <code class="language-clojure">null</code>.

* So never expect that attribute with <code class="language-clojure">null</code> value is a different state that
  without the attribute.

* In objects attribute names we don't use special symbols that are allowed by
  Clojure (for example ending it with ? for boolean values), because this may
  cause problems when exporting.

## Code organization in abstraction levels

Initially, Penpot data model implementation was organized in a different way.
We are currently in a process of reorganization. The objective is to have data
manipulation code structured in abstraction layers, with well-defined
boundaries.

At this moment the namespace structure is already organized as described here,
but there is much code that does not comply with these rules, and needs to be
moved or refactored. We expect to be refactoring existing modules incrementally,
each time we do an important functionality change.

### Abstract data types

```text
▾ common/
  ▾ src/app/common/
    ▾ types/
        file.cljc
        page.cljc
        shape.cljc
        color.cljc
        component.cljc
        ...
```

Namespaces here represent a single data structure, or a fragment of one, as an
abstract data type. Each structure has:

* A **schema spec** that defines the structure of the type and its values:

     ```clojure
     (sm/define! ::fill
       [:map {:title "Fill"}
        [:fill-color {:optional true} ::ctc/rgb-color]
        [:fill-opacity {:optional true} ::sm/safe-number]
        ...)

     (sm/define! ::shape-attrs
       [:map {:title "ShapeAttrs"}
        [:name {:optional true} :string]
        [:selrect {:optional true} ::grc/rect]
        [:points {:optional true} ::points]
        [:blocked {:optional true} :boolean]
        [:fills {:optional true}
         [:vector {:gen/max 2} ::fill]]
        ...)
     ```

* **Helper functions** to create, query and manipulate the structure. Helpers
    at this level only are allowed to see the internal attributes of a type.
 Updaters receive an object of the type, and return a new object modified,
    also ensuring the internal integrity of the data after the change.

    ```clojure
    (defn setup-shape
     "A function that initializes the geometric data of the shape. The props must
     contain at least :x :y :width :height."
     [{:keys [type] :as props}]
     ...)

    (defn has-direction?
    [interaction]
      (#{:slide :push} (-> interaction :animation :animation-type)))

    (defn set-direction
    [interaction direction]
      (dm/assert!
       "expected valid interaction map"
       (check-interaction! interaction))
      (dm/assert!
       "expected valid direction"
       (contains? direction-types direction))
      (dm/assert!
       "expected compatible interaction map"
       (has-direction? interaction))
      (update interaction :animation assoc :direction direction))
    ```

> IMPORTANT: we should always use helper functions to access and modify these data
> structures. Avoid direct attribute read or using functions like <code class="language-clojure">assoc</code> or
> <code class="language-clojure">update</code>, even if the information is contained in a single attribute. This way
> it will be much simpler to add validation checks or to modify the internal
> representation of a type, and will be easier to search for places in the code
> where this data item is used.
>
> Currently much of Penpot code does not follow this requirement, but it
should do in new code or in any refactor.

### File operations

```text
▾ common/
  ▾ src/app/common/
    ▾ files/
        helpers.cljc
        shapes_helpers.cljc
        ...
```

Functions that modify a file object (or a part of it) in place, returning the
file object changed. They ensure the referential integrity within the file, or
between a file and its libraries.

```clojure
(defn resolve-component
  "Retrieve the referenced component, from the local file or from a library"
  [shape file libraries & {:keys [include-deleted?] :or {include-deleted? False}}]
  (if (= (:component-file shape) (:id file))
        (ctkl/get-component (:data file) (:component-id shape) include-deleted?)
        (get-component libraries
                      (:component-file shape)
                      (:component-id shape)
                      :include-deleted? include-deleted?)))

(defn delete-component
"Mark a component as deleted and store the main instance shapes inside it, to
be able to be recovered later."
[file-data component-id skip-undelete? Main-instance]
(let [components-v2 (dm/get-in file-data [:options :components-v2])]
  (if (or (not components-v2) skip-undelete?)
        (ctkl/delete-component file-data component-id)
        (let [set-main-instance ;; If there is a saved main-instance, restore it.
               #(if main-instance
                  (assoc-in % [:objects (:main-instance-id %)] main-instance)
                  %)]
        (-> file-data
           (ctkl/update-component component-id load-component-objects)
           (ctkl/update-component component-id set-main-instance)
           (ctkl/mark-component-deleted component-id))))))
```

> This module is still needing an important refactor. Mainly to take functions
> from common.types and move them here.

#### File validation and repair

There is a function in <code class="language-clojure">app.common.files.validate</code> that checks a file for
referential and semantic integrity. It's called automatically when file changes
are sent to backend, but may be invoked manually whenever it's needed.

### File changes objects

```text
▾ common/
  ▾ src/app/common/
    ▾ files/
        changes_builder.cljc
        changes.cljc
        ...
```

Wrap the update functions in file operations module into <code class="language-clojure">changes</code> objects, that
may be serialized, stored, sent to backend and executed to actually modify a file
object. They should not contain business logic or algorithms. Only adapt the
interface to the file operations or types.

```clojure
(sm/define! ::changes
  [:map {:title "changes"}
   [:redo-changes vector?]
   [:undo-changes seq?]
   [:origin {:optional true} any?]
   [:save-undo? {:optional true} boolean?]
   [:stack-undo? {:optional true} boolean?]
   [:undo-group {:optional true} any?]])

(defmethod process-change :add-component
  [file-data params]
  (ctkl/add-component file-data params))
```

### Business logic

```text
▾ common/
  ▾ src/app/common/
    ▾ logic/
        shapes.cljc
        libraries.cljc
```

Functions that implement semantic user actions, in an abstract way (independent
of UI). They don't directly modify files, but generate changes objects, that
may be executed in frontend or sent to backend.

```clojure
(defn generate-instantiate-component
"Generate changes to create a new instance from a component."
[changes objects file-id component-id position page libraries old-id parent-id
  frame-id {:keys [force-frame?] :or {force-frame? False}}]
 (let [component        (ctf/get-component libraries file-id component-id)
        parent          (when parent-id (get objects parent-id))
        library         (get libraries file-id)
        components-v2 (dm/get-in library [:data :options :components-v2])
        [new-shape new-shapes]º
        (ctn/make-component-instance page
                                        Component
                                        (:data library)
                                        Position
                                        Components-v2
                                        (cond-> {}
                                   force-frame? (assoc :force-frame-id frame-id)))
        changes (cond-> (pcb/add-object changes first-shape {:ignore-touched true})
                 (some? old-id) (pcb/amend-last-change #(assoc % :old-id old-id)))
        changes (reduce #(pcb/add-object %1 %2 {:ignore-touched true})
                       changes
                       (rest new-shapes))]
[new-shape changes]))
```

## Data migrations

```text
▾ common/
  ▾ src/app/common/
    ▾ files/
        migrations.cljc
```

When changing the model it's essential to take into account that the existing
Penpot files must keep working without changes. If you follow the general
considerations stated above, usually this is automatic, since the objects
already in the database just have the default behavior, that should be the same
as before the change. And the new features apply to new or edited objects.

But if this is not possible, and we are talking of a breaking change, you can
write a data migration. Just define a new data version and a migration script
in <code class="language-text">migrations.cljc</code> and increment <code class="language-text">file-version</code> in <code class="language-text">common.cljc</code>.

From then on, every time a file is loaded from the database, if its version
number is lower than the current version in the app, the file data will be
handled to all the needed migration functions. If you later modify and save
the file, it will be now updated in database.

## Shape edit forms

![Sidebar edit form](/img/sidebar-form.png)

```text
▾ frontend/
  ▾ src/
    ▾ app/
      ▾ main/
        ▾ ui/
          ▾ workspace/
            ▾ sidebar/
              ▾ options/
                ▸ menus/
                ▸ rows/
                ▾ shapes/
                    bool.cljs
                    circle.cljs
                    frame.cljs
                    group.cljs
                    image.cljs
                    multiple.cljs
                    path.cljs
                    rect.cljs
                    svg_raw.cljs
                    text.cljs
```

* In <code class="language-text">shapes/*.cljs</code> there are the components that show the edit menu of each
  shape type.

* In <code class="language-text">menus/*.cljs</code> there are the building blocks of these menus.

* And in <code class="language-text">rows/*.cljs</code> there are some pieces, for example color input and
  picker.

## Multiple edit

When modifying the shape edit forms, you must take into account that these
forms may edit several shapes at the same time, even of different types.

When more than one shape is selected, the form inside <code class="language-text">multiple.cljs</code> is used.
At the top of this module, a couple of maps define what attributes may be edited
and how, for each type of shape.

Then, the blocks in <code class="language-text">menus/*.cljs</code> are used, but they are not given a shape, but
a values map. For each attribute, if all shapes have the same value, it is taken;
if not, the attribute will have the value <code class="language-clojure">:multiple</code>.

The form blocks must be prepared for this value, display something useful to
the user in this case, and do a meaningful action when changing the value.
Usually this will be to set the attribute to a fixed value in all selected
shapes, but **only** those that may have the attribute (for example, only text
shapes have font attributes, or only rects has border radius).

## Component synchronization

```text
▾ common/
  ▾ src/app/common/
    ▾ types/
        component.cljc
```

For all shape attributes, you must take into account what happens when the
attribute in a main component is changed and then the copies are synchronized.

In <code class="language-text">component.cljc</code> there is a structure <code class="language-clojure">sync-attrs</code> that maps shape
attributes to sync groups. When an attribute is changed in a main component,
the change will be propagated to its copies. If the change occurs in a copy,
the group will be marked as *touched* in the copy shape, and from then on,
further changes in the main to this attribute, or others in the same group,
will not be propagated.

Any attribute that is not in this map will be ignored in synchronizations.

## Render shapes, export & import

```text
▾ frontend/
  ▾ src/
    ▾ app/
      ▾ main/
        ▾ ui/
          ▾ shapes/
            ▸ text/
              attrs.cljs
              bool.cljs
              circle.cljs
              custom_stroke.cljs
              embed.cljs
              export.cljs
              fill_image.cljs
              filters.cljs
              frame.cljs
              gradients.cljs
              group.cljs
              image.cljs
              mask.cljs
              path.cljs
              rect.cljs
              shape.cljs
              svg_defs.cljs
              svg_raw.cljs
              text.cljs
      ▾ worker/
        ▾ import/
            parser.cljs
```

To export a penpot file, basically we use the same system that is used to
display shapes in the workspace or viewer. In <code class="language-text">shapes/*.cljs</code> there are
components that render one shape of each type into a SVG node.

But to be able to import the file later, some attributes that not match
directly to SVG properties need to be added as metadata (for example,
proportion locks, constraints, stroke alignment...). This is done in the
<code class="language-text">export.cljs</code> module.

Finally, to import a file, we make use of <code class="language-text">parser.cljs</code>, a module that
contains the <code class="language-clojure">parse-data</code> function. It receives a SVG node (possibly with
children) and converts it into a Penpot shape object. There are auxiliary
functions to read and convert each group of attributes, from the node
properties or the metadata (with the <code class="language-clojure">get-meta</code> function).

Any attribute that is not included in the export and import functions
will not be exported and will be lost if reimporting the file again.

## Code generation

```text
▾ frontend/
  ▾ src/
    ▾ app/
      ▾ main/
        ▾ ui/
          ▾ viewer/
            ▾ inspect/
              ▾ attributes/
                  blur.cljs
                  common.cljs
                  fill.cljs
                  image.cljs
                  layout.cljs
                  shadow.cljs
                  stroke.cljs
                  svg.cljs
                  text.cljs
                attributes.cljs
                code.cljs
      ▾ util/
          code_gen.cljs
          markup_html.cljs
          markup_svg.cljs
          style_css.cljs
          style_css_formats.cljs
          style_css_values.cljs
```

In the inspect panel we have two modes:

![Inspect info](/img/handoff-info.png)

For the Info tab, the <code class="language-text">attributes.cljs</code> module and all modules under
<code class="language-text">attributes/*.cljs</code> have the components that extract the attributes to inspect
each type of shape.

![Inspect code](/img/handoff-code.png)

For the Code tab, the <code class="language-text">util/code_gen.cljs</code> module is in charge. It calls the
other modules in <code class="language-text">util/</code> depending on the format.

For HTML and CSS, there are functions that generate the code as needed from the
shapes. For SVG, it simply takes the nodes from the viewer main viewport and
prettily formats it.
