;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0033-test
  "Regression coverage for the shape tree traversal of the
  `0033-normalize-main-instance-names` migration.

  The migration walks a container from its root down, following the
  `:shapes` vectors. A file can hold a `:shapes` entry pointing to a
  shape that is not in the `:objects` map (a dangling reference). The
  traversal must skip it instead of storing a nil shape into the
  `:objects` map, and must keep normalizing the names of the shapes
  that do exist."
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private migration "0033-normalize-main-instance-names")

(defn- make-root
  [children]
  (cts/setup-shape {:id uuid/zero
                    :type :frame
                    :name "Root"
                    :shapes children}))

(defn- make-main-instance
  "A frame declared as the main instance of a component."
  [main-id file-id component-id name]
  (-> (cts/setup-shape {:id main-id :type :frame :name name})
      (assoc :main-instance true
             :component-id component-id
             :component-file file-id)))

(defn- page-data
  "File data with a single page owning `objects`."
  [page-id objects]
  {:pages-index {page-id {:id page-id :objects objects}}})

(defn- component-data
  "File data with a single component owning `objects`."
  [component-id objects]
  {:components {component-id {:id component-id :objects objects}}})

;; --- The guard: a missing child must not become a nil shape

(t/deftest missing-child-of-a-page-does-not-create-a-nil-shape
  (let [page-id   (uuid/next)
        child-id  (uuid/next)
        root      (make-root [child-id])
        data      (page-data page-id {uuid/zero root})
        migrated  (cfm/migrate-data data migration)
        objects'  (get-in migrated [:pages-index page-id :objects])]

    (t/is (not (contains? objects' nil))
          "no nil key is added to the objects map")
    (t/is (= 1 (count objects'))
          "the objects map keeps only the shapes that exist")
    (t/is (= root (get objects' uuid/zero))
          "the root is left untouched")))

(t/deftest missing-child-of-a-component-does-not-create-a-nil-shape
  (let [component-id (uuid/next)
        child-id     (uuid/next)
        root         (make-root [child-id])
        data         (component-data component-id {uuid/zero root})
        migrated     (cfm/migrate-data data migration)
        objects'     (get-in migrated [:components component-id :objects])]

    (t/is (not (contains? objects' nil))
          "no nil key is added to the objects map")
    (t/is (= 1 (count objects'))
          "the objects map keeps only the shapes that exist")
    (t/is (= root (get objects' uuid/zero))
          "the component root is left untouched")))

(t/deftest missing-child-deep-in-the-tree-does-not-create-a-nil-shape
  (let [page-id    (uuid/next)
        frame-id   (uuid/next)
        missing-id (uuid/next)
        frame      (-> (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
                       (assoc :shapes [missing-id]))
        data       (page-data page-id {uuid/zero (make-root [frame-id])
                                       frame-id frame})
        migrated   (cfm/migrate-data data migration)
        objects'   (get-in migrated [:pages-index page-id :objects])]

    (t/is (not (contains? objects' nil))
          "no nil key is added to the objects map")
    (t/is (= frame (get objects' frame-id))
          "the parent of the missing child is left untouched")))

;; --- The behavior: names keep being normalized next to a missing child

(t/deftest main-instance-of-a-page-is-renamed-next-to-a-missing-child
  (let [file-id      (uuid/next)
        page-id      (uuid/next)
        component-id (uuid/next)
        main-id      (uuid/next)
        missing-id   (uuid/next)
        main-shape   (make-main-instance main-id file-id component-id "Group/Name")
        data         (assoc (page-data page-id {uuid/zero (make-root [missing-id main-id])
                                                main-id main-shape})
                            :components {component-id {:id component-id
                                                       :name "Name"
                                                       :path "Group"
                                                       :main-instance-id main-id}})
        migrated     (cfm/migrate-data data migration)
        objects'     (get-in migrated [:pages-index page-id :objects])]

    (t/is (not (contains? objects' nil))
          "no nil key is added to the objects map")
    (t/is (= "Group / Name" (get-in objects' [main-id :name]))
          "the main instance takes the path and name of its component")))

(t/deftest main-instance-of-a-component-is-renamed-next-to-a-missing-child
  (let [file-id      (uuid/next)
        component-id (uuid/next)
        main-id      (uuid/next)
        missing-id   (uuid/next)
        main-shape   (make-main-instance main-id file-id component-id "Group/Name")
        data         (component-data component-id {uuid/zero (make-root [missing-id main-id])
                                                   main-id main-shape})
        data         (assoc-in data [:components component-id :name] "Name")
        data         (assoc-in data [:components component-id :path] "Group")
        data         (assoc-in data [:components component-id :main-instance-id] main-id)
        migrated     (cfm/migrate-data data migration)
        objects'     (get-in migrated [:components component-id :objects])]

    (t/is (not (contains? objects' nil))
          "no nil key is added to the objects map")
    (t/is (= "Group / Name" (get-in objects' [main-id :name]))
          "the main instance takes the path and name of its component")))
