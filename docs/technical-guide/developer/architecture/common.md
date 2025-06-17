---
title: Common code
desc: Learn about architecture, data models, and development environments. See Penpot's technical guide for developers. Dive into common code.
---

# Common code

In penpot, we take advantage of using the same language in frontend and
backend, to have a bunch of shared code.

Sometimes, we use conditional compilation, for small chunks of code that
are different in a Clojure+Java or ClojureScript+JS environments. We use
the <code class="language-clojure">#?</code> construct, like this, for example:

```clojure
(defn ordered-set?
  [o]
  #?(:cljs (instance? lks/LinkedSet o)
     :clj (instance? LinkedSet o)))
```

```text
  ▾ common/src/app/common/
    ▸ geom/
    ▸ pages/
    ▸ path/
    ▸ types/
      ...
```

Some of the modules need some refactoring, to organize them more cleanly.

## Data model and business logic

* **geom** contains functions to manage 2D geometric entities.
    - **point** defines the 2D Point type and many geometric transformations.
    - **matrix** defines the [2D transformation
      matrix](https://www.alanzucconi.com/2016/02/10/tranfsormation-matrix/)
      type and its operations.
    - **shapes** manages shapes as a collection of points with a bounding
      rectangle.
* **path** contains functions to manage SVG paths, transform them and also
  convert other types of shapes into paths.
* **pages** contains the definition of the [Penpot data model](/technical-guide/developer/data-model/) and
  the conceptual business logic (transformations of the model entities,
  independent of the user interface or data storage).
    - **spec** has the definitions of data structures of files and shapes, and
      also of the transformation operations in **changes** module. Uses [Clojure
      spec](https://github.com/clojure/spec.alpha) to define the structure and
      validators.
    - **init** defines the default content of files, pages and shapes.
    - **helpers** are some functions to help manipulating the data structures.
    - **migrations** is in charge to manage the evolution of the data model
      structure over time. It contains a function that gets a file data
      content, identifies its version, and applies the needed migrations. Much
      like the SQL database migrations scripts.
    - **changes** and **changes_builder** define a set of transactional
      operations, that receive a file data content, and perform a semantic
      operation following the business logic (add a page or a shape, change a
      shape attribute, modify some file asset, etc.).
* **types** we are currently in process of refactoring **pages** module, to
  organize it in a way more compliant of [Abstract Data
  Types](https://en.wikipedia.org/wiki/Abstract_data_type) paradigm. We are
  approaching the process incrementally, rewriting one module each time, as
  needed.

## Utilities

The main ones are:

* **data** basic data structures and utility functions that could be added to
  Clojure standard library.
* **math** some mathematic functions that could also be standard.
* **file_builder** functions to parse the content of a <code class="language-text">.penpot</code> exported file
  and build a File data structure from it.
* **logging** functions to generate traces for debugging and usage analysis.
* **text** an adapter layer over the [DraftJS editor](https://draftjs.org) that
  we use to edit text shapes in workspace.
* **transit** functions to encode/decode Clojure objects into
  [transit](https://github.com/cognitect/transit-clj), a format similar to JSON
  but more powerful.
* **uuid** functions to generate [Universally Unique Identifiers
  (UUID)](https://en.wikipedia.org/wiki/Universally_unique_identifier), used
  over all Penpot models to have identifiers for objects that are practically
  ensured to be unique, without having a central control.
