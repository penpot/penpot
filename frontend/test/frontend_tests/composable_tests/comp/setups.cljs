;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.composable-tests.comp.setups
  "Component-specific setups for the test model: named functions that build an
   in-memory file value for a particular component configuration. They are the
   subject-specific counterpart to the generic engine; a 'simple component with
   a copy' is an entirely component-shaped configuration, so it lives behind the
   `comp` boundary. See `mem:frontend/composable-component-tests`.

   Setups are kept as plain functions (not data): structure resists
   declarativization and forcing it here has the worst cost/benefit. Each returns
   a *situation* (file + ROLE bindings — meaningful named objects of the
   configuration, e.g. :main-instance, :copy-instance), which the test retrieves
   via the accessors below and asserts on directly.

   For these one-child-per-instance configurations the meaningful objects are the
   child shapes that participate in propagation, so :main-instance is the main's
   child and :copy-instance is the copy's child (this makes a test read like
   `(has-attr? change-attr (copy-instance situation))`)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [frontend-tests.composable-tests.core :as tm]))

(defn simple-component-with-copy
  "A situation with a simple component (root + one child) and one clean copy.
   Shape labels: :main-root :main-child :copy-root :copy-child. Roles:
   :main-instance -> the main child, :copy-instance -> the copy child."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :copy-root-params {:children-labels [:copy-child]}))]
    (tm/make-situation file {:main-instance :main-child
                             :copy-instance :copy-child
                             :main-root     :main-root
                             :copy-root     :copy-root})))


(defn empty-situation
  "A situation with an empty file and NO roles — the starting point for scenarios
   that build their own configuration via operations (e.g. `create-component`,
   `make-nested-component`, `instantiate-copy`). Use as the `:setup` when the first
   operation creates the component."
  []
  (tm/make-situation (thf/sample-file :file1)))

(defn simple-component-with-labeled-copy
  "Like `simple-component-with-copy`, but the main child starts with a known fill
   (so 'value stayed' is distinguishable from coincidence). Same labels and roles."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :main-child-params {:fills (ths/sample-fills-color :fill-color "#abcdef")}
                  :copy-root-params  {:children-labels [:copy-child]}))]
    (tm/make-situation file {:main-instance :main-child
                             :copy-instance :copy-child
                             :main-root     :main-root
                             :copy-root     :copy-root})))

(defn main-instance
  "The main child shape of the configuration (role :main-instance), live."
  [situation]
  (tm/role-shape situation :main-instance))

(defn copy-instance
  "The copy child shape of the configuration (role :copy-instance), live."
  [situation]
  (tm/role-shape situation :copy-instance))

(defn main-root
  "The main component root shape (role :main-root), live."
  [situation]
  (tm/role-shape situation :main-root))

(defn copy-root
  "The copy root shape (role :copy-root), live."
  [situation]
  (tm/role-shape situation :copy-root))


(defn component-with-many-children
  "A situation with a component whose main has three children and one clean copy.
   Shape labels: :main-root, :main-child1/2/3, :copy-root, :copy-child1/2/3.
   Roles: :main-root, :copy-root, and :copy-child-1/2/3 -> the copy's children
   (so a test can assert their post-operation order in the copy root)."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-component-with-many-children-and-copy
                  :component1
                  :main-root
                  [:main-child1 :main-child2 :main-child3]
                  :copy-root
                  :copy-root-params {:children-labels [:copy-child1 :copy-child2 :copy-child3]}))]
    (tm/make-situation file {:main-root    :main-root
                             :copy-root    :copy-root
                             :copy-child-1 :copy-child1
                             :copy-child-2 :copy-child2
                             :copy-child-3 :copy-child3})))

(defn copy-child
  "The copy child shape for 1-based index `n` (role :copy-child-n), live. Lets a
   test retrieve specific copy children to assert their order/identity."
  [situation n]
  (tm/role-shape situation (keyword (str "copy-child-" n))))


(defn nested-component-with-copy
  "A situation with a NESTED component configuration (depth axis): component2's
   main contains a nested instance of component1 (`:main2-nested-head`); a clean
   copy of component2 exists, so the copy contains a copy of that nested instance
   whose child (`:copy2-child`) reaches the main through a :shape-ref CHAIN.

   This is the depth-1 analogue of the simple-with-copy setup: editing
   `:main2-nested-head` (the nested instance inside component2's main) and
   propagating component2 must reach `:copy2-child` — the same attribute-
   propagation property as the flat case, one level deeper. (Editing the
   INNERMOST :main1-child and propagating only :component1 would instead be a
   CHAINED propagation across two component levels — a different property; not
   this setup.)

   Shape labels from the helper: :main1-root/:main1-child, :main2-root,
   :main2-nested-head, :copy2-root, and :copy2-child (the deep copy child, labeled
   via the helper's children-labels). Roles: :main-instance -> :main2-nested-head,
   :copy-instance -> :copy2-child, plus :main2-root / :copy-root for structure."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-nested-component-with-copy
                  :component1 :main1-root :main1-child
                  :component2 :main2-root :main2-nested-head
                  :copy2-root
                  :copy2-root-params {:children-labels [:copy2-child]}))]
    (tm/make-situation file {:main-instance :main2-nested-head
                             :copy-instance :copy2-child
                             :main2-root    :main2-root
                             :copy-root     :copy2-root})))


(defn- change-main-child-fill
  "Apply `fill-color` to the library's :main-child via the production change path
   (mirrors how a real edit to the main would have changed the library), returning
   the updated library file value. Used to make the library main DIVERGE from the
   already-instantiated copy, so a subsequent cross-file sync has something to pull."
  [library fill-color]
  (let [page    (thf/current-page library)
        main-id (thi/id :main-child)
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{main-id}
                 (fn [shape] (assoc shape :fills (ths/sample-fills-color :fill-color fill-color)))
                 (:objects page)
                 {})]
    (thf/apply-changes library changes)))

(defn cross-file-component-with-copy
  "LOCALITY axis (case H): a component whose main lives in a SEPARATE, shared
   LIBRARY file, and a clean copy that lives in a CONSUMING file. The library main
   has since DIVERGED from the copy (its :main-child now carries `new-fill`, while
   the copy still carries the original `#abcdef`), modelling 'the library was
   changed elsewhere'. Propagating that change to the copy is therefore a CROSS-
   FILE sync (the library-update flow), not the in-file watcher.

   The returned situation's PRIMARY file is the CONSUMING file (the current file,
   where the copy lives and which the copy roles resolve against); the library
   travels as an AUXILIARY file (see `tm/with-aux-files`) so an interpreter can
   install it alongside (e.g. into the frontend store's `:files`) and run the
   cross-file sync. Roles: :copy-instance -> the copy child, :copy-root -> the copy
   root (both in the consuming file). `new-fill` should match the value the test
   uses for its expected-value descriptor.

   Labels: in the LIBRARY — :component1, :main-root, :main-child; in the CONSUMING
   file — :copy-root, :copy-child."
  [new-fill]
  (let [library  (-> (thf/sample-file :library :is-shared true)
                     (tho/add-simple-component
                      :component1 :main-root :main-child
                      :child-params {:fills (ths/sample-fills-color :fill-color "#abcdef")}))
        ;; instantiate the copy in the consuming file FIRST (copy gets the old fill)
        file     (-> (thf/sample-file :file)
                     (thc/instantiate-component :component1 :copy-root
                                                :library library
                                                :children-labels [:copy-child]))
        ;; THEN diverge the library main (copy is now stale)
        library' (change-main-child-fill library new-fill)]
    (-> (tm/make-situation file {:copy-instance :copy-child
                                 :copy-root     :copy-root})
        (tm/with-aux-files {(:id library') library'}))))

(defn library-id
  "The id of the library file in a cross-file situation (the single auxiliary
   file). Used by the cross-file sync operation."
  [situation]
  (-> situation tm/aux-files keys first))
