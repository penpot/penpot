;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0027-test
  "Tests for the 0027 migration
  (see https://github.com/penpot/penpot/issues/12433).

  Production files fail schema validation with unconverted types
  (`:type \"circle\"` instead of `:circle`, ids as text, map keys as
  text/keyword instead of uuid) and with nil pages. The file holds
  two groups: `decode-coverage-*` are characterization tests pinning
  down what the schema `decode` (json-transformer) already fixes on
  its own and what still needs targeted repair clauses;
  `migration-0027-*` pin the migration behavior itself (converting
  unconverted types, dropping nil pages, leaving valid data
  untouched, idempotence and running through `migrate`). The first
  group asserts observed behavior, the second fixed behavior."
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.schema :as sm]
   [app.common.test-helpers.files :as thf]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- make-rect-shape
  [shape-id]
  (cts/setup-shape {:id shape-id
                    :name "rect"
                    :type :rect
                    :x 0 :y 0 :width 10 :height 10
                    :parent-id uuid/zero
                    :frame-id uuid/zero}))

(defn- make-root-shape
  [shape-id]
  (cts/setup-shape {:id uuid/zero
                    :name "root"
                    :type :frame
                    :parent-id uuid/zero
                    :frame-id uuid/zero
                    :shapes [shape-id]}))

(defn- make-data
  "Minimal file data with a single page holding a single rect shape."
  [page-id shape]
  (let [shape-id (:id shape)]
    {:pages [page-id]
     :pages-index
     {page-id {:id page-id
               :name "Page 1"
               :objects {uuid/zero (make-root-shape shape-id)
                         shape-id shape}}}}))

(defn- decode-data
  [data]
  (sm/decode ctf/schema:data data (sm/json-transformer)))

(t/deftest decode-coverage-string-shape-type
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (make-data page-id (assoc (make-rect-shape shape-id) :type "rect"))
        decoded  (decode-data data)]
    (t/is (= :rect (get-in decoded [:pages-index page-id :objects shape-id :type]))
          "decode converts string shape type to keyword")))

(t/deftest decode-coverage-string-uuid-fields
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        shape    (-> (make-rect-shape shape-id)
                     (assoc :id (str shape-id))
                     (assoc :parent-id (str uuid/zero))
                     (assoc :frame-id (str uuid/zero)))
        data     (make-data page-id shape)
        decoded  (decode-data data)
        shape'   (get-in decoded [:pages-index page-id :objects shape-id])]
    (t/is (uuid? (:id shape')) "decode converts string shape id to uuid")
    (t/is (uuid? (:parent-id shape')) "decode converts string parent id to uuid")
    (t/is (uuid? (:frame-id shape')) "decode converts string frame id to uuid")))

(t/deftest decode-coverage-string-page-and-map-keys
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (make-data page-id (make-rect-shape shape-id))
        data     (-> data
                     (update :pages (fn [pages] (mapv str pages)))
                     (update :pages-index
                             (fn [pages-index]
                               (into {}
                                     (map (fn [[pid page]]
                                            [(str pid)
                                             (update page :objects
                                                     (fn [objects]
                                                       (into {}
                                                             (map (fn [[id shape]]
                                                                    [(keyword (str id)) shape]))
                                                             objects)))]))
                                     pages-index))))
        decoded  (decode-data data)]
    (t/is (= [page-id] (:pages decoded)) "decode converts string page refs to uuid")
    (t/is (contains? (:pages-index decoded) page-id) "decode converts string page keys to uuid")
    (t/is (contains? (get-in decoded [:pages-index page-id :objects]) shape-id)
          "decode converts keyword object keys to uuid")))

(t/deftest decode-coverage-nil-pages
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (-> (make-data page-id (make-rect-shape shape-id))
                     (update :pages conj nil)
                     (assoc-in [:pages-index nil]
                               {:id nil :name "ghost" :objects {}}))
        decoded  (decode-data data)]
    (t/is (some nil? (:pages decoded)) "decode leaves nil page entries alone")
    (t/is (contains? (:pages-index decoded) nil) "decode leaves nil page keys alone")))

(t/deftest decode-coverage-healthy-file-untouched
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (make-data page-id (make-rect-shape shape-id))
        _        (ctf/check-file-data (assoc data :id (uuid/next)))
        decoded  (decode-data data)
        shape'   (get-in decoded [:pages-index page-id :objects shape-id])]
    ;; NOTE: no whole-map equality here: decode materializes domain
    ;; records (Shape, Point, ...), which is the normal in-memory
    ;; representation (setup-shape builds the same records).
    (t/is (= (:type (get-in data [:pages-index page-id :objects shape-id]))
             (:type shape'))
          "decode preserves shape type")
    (t/is (= #{page-id} (set (:pages decoded))) "decode preserves page refs")
    (t/is (contains? (:pages-index decoded) page-id) "decode preserves page keys")
    (t/is (some? (ctf/check-file-data (assoc decoded :id (uuid/next))))
          "decoded healthy data stays valid")))

(defn- valid-shape?
  [data page-id shape-id]
  (let [shape (get-in data [:pages-index page-id :objects shape-id])]
    (and (= :rect (:type shape))
         (uuid? (:id shape))
         (uuid? (:parent-id shape))
         (uuid? (:frame-id shape)))))

(t/deftest migration-0027-converts-unconverted-types-in-pages
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (-> (make-data page-id (make-rect-shape shape-id))
                     (thf/corrupt-shape-types)
                     (assoc :custom-attr "kept"))
        data'    (cfm/migrate-data data "0027-repair-unconverted-types-and-nil-pages")
        shape    (get-in data' [:pages-index page-id :objects shape-id])]
    (t/is (valid-shape? data' page-id shape-id) "types and ids converted back")
    (t/is (contains? (:pages-index data') page-id) "page key converted back")
    (t/is (= [page-id] (:pages data')) "page refs converted back")
    (t/is (= "kept" (:custom-attr data')) "unknown keys preserved")
    (t/is (some? (ctf/check-file-data (assoc data' :id (uuid/next))))
          "migrated data validates")))

(t/deftest migration-0027-converts-unconverted-types-in-components
  (let [page-id      (uuid/next)
        shape-id     (uuid/next)
        component-id (uuid/next)
        data         {:pages-index
                      {page-id {:id page-id
                                :name "Page 1"
                                :objects {uuid/zero (make-root-shape page-id)}}}
                      :components
                      {(str component-id) {:id (str component-id)
                                           :name "Component"
                                           :objects {shape-id (make-rect-shape shape-id)}}}}
        data         (-> data
                         (update-in [:components (str component-id) :objects]
                                    (fn [objects]
                                      (into {}
                                            (map (fn [[id shape]]
                                                   [(keyword (str id))
                                                    (-> shape
                                                        (assoc :type (name (:type shape)))
                                                        (assoc :id (str (:id shape))))]))
                                            objects))))
        data'        (cfm/migrate-data data "0027-repair-unconverted-types-and-nil-pages")
        shape        (get-in data' [:components component-id :objects shape-id])]
    (t/is (= :rect (:type shape)) "component shape type converted")
    (t/is (uuid? (:id shape)) "component shape id converted")
    (t/is (contains? (:components data') component-id) "component key converted")
    (t/is (= component-id (get-in data' [:components component-id :id]))
          "component id converted")
    (t/is (contains? (get-in data' [:components component-id :objects]) shape-id)
          "component object key converted")))

(t/deftest migration-0027-drops-nil-pages
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (-> (make-data page-id (make-rect-shape shape-id))
                     (update :pages conj nil)
                     (assoc-in [:pages-index nil]
                               {:id nil :name "ghost" :objects {}}))
        data'    (cfm/migrate-data data "0027-repair-unconverted-types-and-nil-pages")]
    (t/is (not-any? nil? (:pages data')) "nil page refs removed")
    (t/is (not (contains? (:pages-index data') nil)) "nil page key removed")
    (t/is (some? (ctf/check-file-data (assoc data' :id (uuid/next))))
          "migrated data validates")))

(t/deftest migration-0027-leaves-valid-data-untouched
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (assoc (make-data page-id (make-rect-shape shape-id))
                        :id (uuid/next))
        data'    (cfm/migrate-data data "0027-repair-unconverted-types-and-nil-pages")]
    (t/is (some? (ctf/check-file-data data')) "healthy data stays valid")))

(t/deftest migration-0027-is-idempotent
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        data     (-> (make-data page-id (make-rect-shape shape-id))
                     (thf/corrupt-shape-types))
        data'    (cfm/migrate-data data "0027-repair-unconverted-types-and-nil-pages")
        data''   (cfm/migrate-data data' "0027-repair-unconverted-types-and-nil-pages")]
    (t/is (= data' data'') "second run is a no-op")))

(t/deftest migration-0027-runs-through-migrate
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        shape-id (uuid/next)
        data     (-> (make-data page-id (make-rect-shape shape-id))
                     (thf/corrupt-shape-types))
        file     {:id file-id
                  :features #{}
                  :migrations (disj cfm/available-migrations
                                    "0027-repair-unconverted-types-and-nil-pages")
                  :data data}
        file'    (cfm/migrate file nil)]
    ;; migrate itself validates at the end, so returning is passing.
    (t/is (contains? (:migrations file') "0027-repair-unconverted-types-and-nil-pages")
          "migration marked as applied")
    (t/is (valid-shape? (:data file') page-id shape-id)
          "migrated file data has converted types")))
