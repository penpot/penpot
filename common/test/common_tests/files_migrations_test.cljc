;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files-migrations-test
  (:require
   [app.common.data :as d]
   [app.common.files.migrations :as cfm]
   [app.common.pprint :as pp]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defmethod cfm/migrate-data "test/1" [data _] (update data :sum inc))
(defmethod cfm/migrate-data "test/2" [data _] (update data :sum inc))
(defmethod cfm/migrate-data "test/3" [data _] (update data :sum inc))

(t/deftest generic-migration-subsystem-1
  (let [migrations (into (d/ordered-set) ["test/1" "test/2" "test/3"])]
    (with-redefs [cfm/available-migrations migrations
                  ctf/check-file-data identity]
      (let [file  {:data {:sum 1}
                   :id 1
                   :migrations (d/ordered-set "test/1")}
            file' (cfm/migrate file nil)]
        (t/is (= cfm/available-migrations (:migrations file')))
        (t/is (= 3 (:sum (:data file'))))))))

(t/deftest migration-0024b-fix-stroke-cap-placement
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id {:id         shape-id
                               :type       :path
                               :stroke-cap-start "round"
                               :stroke-cap-end   "round"
                               :strokes    [{:stroke-color "#000000"
                                             :stroke-opacity 1
                                             :stroke-style :svg
                                             :stroke-width 2}
                                            {:stroke-color "#000000"
                                             :stroke-cap-start "round"
                                             :stroke-cap-end   "round"
                                             :stroke-opacity 1
                                             :stroke-style :svg
                                             :stroke-width 2}]}}}}}
        data'    (cfm/migrate-data data "0024b-fix-stroke-cap-placement")]

    (let [shape (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (nil? (:stroke-cap-start shape)) "top-level cap removed")
      (t/is (nil? (:stroke-cap-end shape)) "top-level cap removed")
      (t/is (= :round (get-in shape [:strokes 0 :stroke-cap-start])) "cap moved into stroke")
      (t/is (= :round (get-in shape [:strokes 0 :stroke-cap-end])) "cap moved into stroke")
      (t/is (= :round (get-in shape [:strokes 1 :stroke-cap-start])) "correct cap type")
      (t/is (= :round (get-in shape [:strokes 1 :stroke-cap-end])) "correct cap type"))))

(t/deftest migration-0024-fix-stroke-cap-no-strokes
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id {:id               shape-id
                               :type             :path
                               :stroke-cap-start :round
                               :stroke-cap-end   :round
                               :strokes          []}}}}}
        data'    (cfm/migrate-data data "0024b-fix-stroke-cap-placement")]

    (let [shape (get-in data' [:pages-index page-id :objects shape-id])]
      (t/is (nil? (:stroke-cap-start shape)) "top-level cap removed even with no strokes")
      (t/is (nil? (:stroke-cap-end shape)) "top-level cap removed even with no strokes"))))

;; 0025-repair-empty-text-content
;; Text shapes whose :content is a root with an empty/missing :children
;; vector used to slip past the schema (children was optional). With the
;; schema tightening those shapes must be repaired on next load.
(defn- make-broken-text-shape
  "Build a fully-initialised text shape with a broken :content and the
  supplied root-level attrs overlaid on it."
  [shape-id root-attrs]
  (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
      (assoc :content (merge {:type "root"}
                             (when (seq root-attrs) root-attrs)
                             {:children []}))))

(t/deftest migration-0025-repair-empty-text-content-empty-children
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-broken-text-shape shape-id {:vertical-align "top"})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "root" (:type content)) "root type preserved")
    (t/is (vector? (:children content)) "children is now a vector")
    (t/is (= 1 (count (:children content))) "exactly one paragraph-set seeded")
    (t/is (= "paragraph-set" (get-in content [:children 0 :type])))
    (t/is (pos? (count (get-in content [:children 0 :children])))
          "paragraph-set has at least one paragraph")
    (t/is (= "" (get-in content [:children 0 :children 0 :children 0 :text]))
          "seeded span has empty text")
    (t/is (= "top" (:vertical-align content))
          "preserves pre-existing :vertical-align")))

(t/deftest migration-0025-repair-empty-text-content-missing-children
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        ;; A text shape whose :content has no :children key at all.
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "root"
                                                   :vertical-align "center"}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (:children content)) "missing children becomes a vector")
    (t/is (pos? (count (:children content))) "missing children gets a paragraph-set")
    (t/is (= "center" (:vertical-align content))
          "preserves pre-existing :vertical-align")))

(t/deftest migration-0025-repair-empty-text-content-no-content
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        ;; A text shape with no :content at all. The migration is
        ;; intentionally a no-op for this case; the schema tightening
        ;; already allows :content to be missing entirely.
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])]

    (t/is (not (contains? shape :content))
          "migration does not invent :content for shapes that don't have one")
    (t/is (cts/valid-shape? shape))))

(t/deftest migration-0025-repair-empty-text-content-idempotent
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        ;; A healthy text shape with a proper paragraph-set/paragraph/
        ;; span tree. The migration must leave it untouched.
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})}}}}
        original (get-in data [:pages-index page-id :objects shape-id])
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape'   (get-in data' [:pages-index page-id :objects shape-id])]

    (t/is (cts/valid-shape? original) "baseline shape is valid")
    (t/is (= original shape') "healthy content is unchanged")))

(t/deftest migration-0025-repair-empty-text-content-component
  ;; The migration also walks :components, so a broken text inside a
  ;; component is also repaired.
  (let [shape-id (uuid/next)
        comp-id  (uuid/next)
        data     {:components
                  {comp-id
                   {:objects
                    {shape-id (make-broken-text-shape shape-id nil)}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:components comp-id :objects shape-id])]

    (t/is (cts/valid-shape? shape) "repaired component shape is valid")
    (t/is (pos? (count (get-in shape [:content :children])))
          "children vector is no longer empty")))
