;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0035a-test
  "Coverage for `0035-fix-dangling-children`.

  A shape can list a child id that is not present in the `:objects` map
  of the page or component owning it (a dangling reference). Any
  traversal of the shape tree trips on it, and the file becomes
  unopenable. The migration drops those ids and keeps every shape that
  really exists."
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.types.file :as ctf]
   [app.common.types.objects-map :as omap]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private migration "0035-fix-dangling-children")

(defn- make-root
  [children]
  (cts/setup-shape {:id uuid/zero
                    :type :frame
                    :name "Root"
                    :shapes children}))

(defn- make-frame
  [frame-id child-ids]
  (-> (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
      (assoc :shapes child-ids)))

(defn- page-data
  [page-id objects]
  {:pages-index {page-id {:id page-id :objects objects}}})

(defn- component-data
  [component-id objects]
  {:components {component-id {:id component-id :objects objects}}})

(t/deftest removes-the-dangling-children-of-a-page
  (let [page-id   (uuid/next)
        frame-id  (uuid/next)
        child-id  (uuid/next)
        frame     (make-frame frame-id [child-id])
        data      (page-data page-id {uuid/zero (make-root [frame-id child-id])
                                      frame-id  frame})
        migrated  (cfm/migrate-data data migration)
        objects'  (get-in migrated [:pages-index page-id :objects])]

    (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
          "the dangling id is removed from the root children list")
    (t/is (= [] (get-in objects' [frame-id :shapes]))
          "the dangling id is removed from the frame children list")
    (t/is (= 2 (count objects'))
          "every shape that exists is kept")
    (t/is (some? (get objects' frame-id))
          "the frame itself is kept")))

(t/deftest removes-the-dangling-children-of-a-component
  (let [component-id (uuid/next)
        frame-id     (uuid/next)
        child-id     (uuid/next)
        frame        (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
        data         (component-data component-id {uuid/zero (make-root [frame-id child-id])
                                                   frame-id frame})
        migrated     (cfm/migrate-data data migration)
        objects'     (get-in migrated [:components component-id :objects])]

    (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
          "the dangling id is removed from the children list")
    (t/is (= 2 (count objects'))
          "every shape that exists is kept")
    (t/is (= frame (get objects' frame-id))
          "the existing shape is left untouched")))

(t/deftest migration-works-with-an-objects-map
  ;; The backend stores the page :objects as an ObjectsMap (the lazy
  ;; representation enabled by the fdata/objects-map feature). It is an
  ;; IPersistentMap that does not implement IFn on the JVM: a migration
  ;; must consult it with contains?/get, never by calling it as a
  ;; function.
  (let [page-id   (uuid/next)
        frame-id  (uuid/next)
        child-id  (uuid/next)
        frame     (make-frame frame-id [child-id])
        objects   (omap/wrap {uuid/zero (make-root [frame-id child-id])
                              frame-id  frame})
        data      (page-data page-id objects)
        migrated  (cfm/migrate-data data migration)
        objects'  (get-in migrated [:pages-index page-id :objects])]

    (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
          "the dangling id is removed with the ObjectsMap representation")
    (t/is (= [] (get-in objects' [frame-id :shapes]))
          "the frame children list is pruned the same way")))

(t/deftest keeps-the-order-and-the-shapes-of-a-valid-tree
  (let [page-id  (uuid/next)
        frame-id (uuid/next)
        rect-id  (uuid/next)
        frame    (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
        rect     (cts/setup-shape {:id rect-id :type :rect :name "Rect"})
        data     (page-data page-id {uuid/zero (make-root [frame-id rect-id])
                                     frame-id  frame
                                     rect-id   rect})
        migrated (cfm/migrate-data data migration)
        objects' (get-in migrated [:pages-index page-id :objects])]

    (t/is (= [frame-id rect-id] (get-in objects' [uuid/zero :shapes]))
          "the children list is kept as it was")
    (t/is (= data migrated)
          "a tree without dangling references is not modified")))

(t/deftest leaves-shapes-with-empty-children-alone
  (let [page-id  (uuid/next)
        frame    (cts/setup-shape {:id (uuid/next) :type :frame :name "Frame"})
        data     (page-data page-id {uuid/zero (make-root [])
                                     (:id frame) frame})
        migrated (cfm/migrate-data data migration)]

    (t/is (= data migrated)
          "empty children vectors are left alone")))

(t/deftest leaves-shapes-without-children-key-alone
  (let [page-id  (uuid/next)
        rect-id  (uuid/next)
        rect     (cts/setup-shape {:id rect-id :type :rect :name "Rect"})
        data     (page-data page-id {uuid/zero (make-root [])
                                     rect-id rect})
        migrated (cfm/migrate-data data migration)]

    (t/is (= data migrated)
          "shapes without a children key are left alone")
    (t/is (not (contains? (get-in migrated [:pages-index page-id :objects rect-id]) :shapes))
          "no children key is created on the shape")))

(t/deftest migration-is-idempotent
  (let [page-id  (uuid/next)
        child-id (uuid/next)
        data     (page-data page-id {uuid/zero (make-root [child-id])})
        migrated (cfm/migrate-data data migration)]

    (t/is (= migrated (cfm/migrate-data migrated migration))
          "applying the migration twice does not change the result")))

(t/deftest file-with-dangling-children-passes-the-whole-migration
  (let [file-id     (uuid/next)
        page-id     (uuid/next)
        frame-id    (uuid/next)
        missing-id  (uuid/next)
        frame       (make-frame frame-id [missing-id])
        base        (ctf/make-file-data file-id page-id)
        data        (-> base
                        (assoc-in [:pages-index page-id :objects uuid/zero]
                                  (make-root [frame-id missing-id]))
                        (assoc-in [:pages-index page-id :objects frame-id] frame))
        ;; The file reached the state before 0033 ran, so 0033, 0034 and
        ;; 0035 are all pending, exactly as in the reported failure.
        file        {:id file-id
                     :data data
                     :migrations (disj cfm/available-migrations
                                       "0033-normalize-main-instance-names"
                                       "0034-fix-path-content-offset"
                                       migration)}]

    (t/is (cfm/need-migration? file)
          "the file is scheduled for migration")

    (let [migrated (cfm/migrate-file file {})
          objects' (get-in migrated [:data :pages-index page-id :objects])]

      (t/is (contains? (:migrations migrated) migration)
            "the migration is recorded as applied")
      (t/is (not (contains? objects' nil))
            "the migrated data has no nil shape entry")
      (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
            "the dangling reference of the root is removed")
      (t/is (= [] (get-in objects' [frame-id :shapes]))
            "the dangling reference of the frame is removed")
      (t/is (some? (get objects' frame-id))
            "the frame that exists is kept"))))
