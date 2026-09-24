;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files-migrations-0026-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; 0026-fix-svg-raw-shapes-uuids
;; Before the svg-raw schema declared :shapes as a vector of uuid, the
;; JSON decoder had no type information for those child ids and left
;; them as plain strings on any round trip, so they got persisted as
;; strings. Once the schema was tightened, such files fail schema
;; validation; this migration parses the strings back into uuids.

(defn- make-svg-raw-shape
  "Build a minimal svg-raw shape with the supplied :shapes vector.
  When `shapes` is nil the :shapes key is omitted, like a leaf svg-raw
  shape."
  [shape-id shapes]
  (cond-> {:id shape-id
           :type :svg-raw}
    (some? shapes)
    (assoc :shapes shapes)))

(defn- make-other-shape
  "Build a minimal non-svg-raw shape that must stay untouched."
  [shape-id shapes]
  {:id shape-id
   :type :group
   :shapes shapes})

(t/deftest migration-0026-converts-svg-raw-shapes-strings-to-uuids-in-pages
  (let [shape-id (uuid/next)
        child-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-svg-raw-shape
                               shape-id
                               [(str child-id)
                                "1c2986ce-4a0f-8001-8007-1fb8f3b5ab31"])}}}}
        data'    (cfm/migrate-data data "0026-fix-svg-raw-shapes-uuids")
        shape    (get-in data' [:pages-index page-id :objects shape-id])]

    (t/is (= 2 (count (:shapes shape))) "child ids preserved")
    (t/is (= child-id (first (:shapes shape))) "existing uuid string parsed to uuid")
    (t/is (= #uuid "1c2986ce-4a0f-8001-8007-1fb8f3b5ab31" (second (:shapes shape)))
          "foreign uuid string parsed to uuid")
    (t/is (every? uuid? (:shapes shape)) "all child ids are uuids")))

(t/deftest migration-0026-converts-svg-raw-shapes-strings-to-uuids-in-components
  (let [shape-id (uuid/next)
        child-id (uuid/next)
        component-id (uuid/next)
        data     {:components
                  {component-id
                   {:objects
                    {shape-id (make-svg-raw-shape
                               shape-id
                               [(str child-id)
                                "1c2986ce-4a0f-8001-8007-1fb92196e65f"])}}}}
        data'    (cfm/migrate-data data "0026-fix-svg-raw-shapes-uuids")
        shape    (get-in data' [:components component-id :objects shape-id])]

    (t/is (= 2 (count (:shapes shape))) "child ids preserved")
    (t/is (= child-id (first (:shapes shape))) "existing uuid string parsed to uuid")
    (t/is (= #uuid "1c2986ce-4a0f-8001-8007-1fb92196e65f" (second (:shapes shape)))
          "foreign uuid string parsed to uuid")
    (t/is (every? uuid? (:shapes shape)) "all child ids are uuids")))

(t/deftest migration-0026-leaves-uuids-and-other-shapes-untouched
  (let [svg-raw-id (uuid/next)
        child-id   (uuid/next)
        group-id   (uuid/next)
        leaf-id    (uuid/next)
        page-id    (uuid/next)
        data       {:pages-index
                    {page-id
                     {:objects
                      {svg-raw-id (make-svg-raw-shape svg-raw-id [child-id])
                       group-id   (make-other-shape group-id [(str child-id)])
                       leaf-id    (make-svg-raw-shape leaf-id nil)}}}}
        data'      (cfm/migrate-data data "0026-fix-svg-raw-shapes-uuids")
        objects    (get-in data' [:pages-index page-id :objects])]

    (t/is (= [child-id] (:shapes (get objects svg-raw-id)))
          "already-uuid svg-raw children untouched")
    (t/is (= [(str child-id)] (:shapes (get objects group-id)))
          "non-svg-raw shapes untouched")
    (t/is (nil? (:shapes (get objects leaf-id)))
          "svg-raw leaf without :shapes untouched")))

(t/deftest migration-0026-is-idempotent
  (let [shape-id (uuid/next)
        child-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-svg-raw-shape shape-id [(str child-id)])}}}}
        data'    (cfm/migrate-data data "0026-fix-svg-raw-shapes-uuids")
        data''   (cfm/migrate-data data' "0026-fix-svg-raw-shapes-uuids")]

    (t/is (= data' data'') "second run is a no-op")))