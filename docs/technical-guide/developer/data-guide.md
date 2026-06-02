---
title: 3.08. Data Guide
desc: Learn about data structures, code organization, file operations, migrations, shape editing, and component syncing. See Penpot's technical guide. Try it free!
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
