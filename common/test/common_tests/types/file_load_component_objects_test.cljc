;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.file-load-component-objects-test
  (:require
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; Soft-delete snapshots the main instance into the component via
;; `load-component-objects`. When the main id is already missing from
;; the page, the old path called `gsh/move` on nil and indexed by `:id`,
;; storing `{nil {:selrect nil :points []}}` — which later breaks
;; duplicate-file schema validation (#12336).

(defn- root
  [children]
  (cts/setup-shape {:id uuid/zero
                    :type :frame
                    :name "Root"
                    :shapes children}))

(defn- file-data
  [page-id component]
  {:pages-index {page-id {:id page-id
                          :name "Page"
                          :objects {uuid/zero (root [])}}}
   :components {(:id component) component}})

(t/deftest load-component-objects-skips-missing-main
  (let [page-id      (uuid/next)
        component-id (uuid/next)
        main-id      (uuid/next)
        component    {:id component-id
                      :name "Orphan"
                      :path ""
                      :main-instance-id main-id
                      :main-instance-page page-id}
        result       (ctf/load-component-objects
                      (file-data page-id component)
                      component)
        objects      (:objects result)]

    (t/is (not (contains? objects nil))
          "missing main must not create a nil key in :objects")
    (t/is (= {} objects)
          "missing main yields an empty snapshot, not a placeholder shape")))

(t/deftest load-component-objects-keeps-present-main
  (let [page-id      (uuid/next)
        component-id (uuid/next)
        main-id      (uuid/next)
        main         (cts/setup-shape {:id main-id
                                       :type :frame
                                       :name "Main"
                                       :shapes []})
        component    {:id component-id
                      :name "Present"
                      :path ""
                      :main-instance-id main-id
                      :main-instance-page page-id}
        file-data    {:pages-index {page-id {:id page-id
                                             :name "Page"
                                             :objects {uuid/zero (root [main-id])
                                                       main-id main}}}
                      :components {component-id component}}
        result       (ctf/load-component-objects file-data component)
        objects      (:objects result)]

    (t/is (contains? objects main-id)
          "present main is stored under its uuid")
    (t/is (not (contains? objects nil))
          "present main never introduces a nil key")
    (t/is (= main-id (get-in objects [main-id :id])))))

(t/deftest load-component-objects-treats-empty-objects-as-already-loaded
  ;; A missing-main soft-delete stores `:objects {}`. Render/library paths
  ;; call load-component-objects again on that component. The guard must
  ;; treat `{}` as already loaded — otherwise it reloads from the page
  ;; (and re-warns). Put the main on the page now so a mistaken re-entry
  ;; would populate objects and fail this assertion.
  (let [page-id      (uuid/next)
        component-id (uuid/next)
        main-id      (uuid/next)
        main         (cts/setup-shape {:id main-id
                                       :type :frame
                                       :name "Main"
                                       :shapes []})
        component    {:id component-id
                      :name "AlreadySnapshotted"
                      :path ""
                      :main-instance-id main-id
                      :main-instance-page page-id
                      :objects {}}
        file-data    {:pages-index {page-id {:id page-id
                                             :name "Page"
                                             :objects {uuid/zero (root [main-id])
                                                       main-id main}}}
                      :components {component-id component}}
        result       (ctf/load-component-objects file-data component)]

    (t/is (= {} (:objects result))
          "empty :objects means already loaded; do not reload from the page")))
