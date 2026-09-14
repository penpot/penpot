;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0025-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; 0025-repair-empty-text-content
;; Text shapes whose :content is a root with an empty/missing :children
;; vector used to slip past the schema (children was optional). With the
;; schema tightening those shapes must be repaired on next load.
(defn- make-text-shape-with-content
  "Build a text shape with arbitrary content structure"
  [shape-id content]
  (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
      (assoc :content content)))

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
        ;; A text shape with no :content at all. Should be repaired with default content.
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (map? content) "content is now a map")
    (t/is (= "root" (:type content)) "content has root type")
    (t/is (vector? (:children content)) "children is a vector")
    (t/is (pos? (count (:children content))) "has at least one paragraph-set")))

(t/deftest migration-0025-repair-empty-text-content-idempotent
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        ;; A healthy text shape with a proper paragraph-set/paragraph/
        ;; span tree. The migration must leave it untouched.
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "root"
                                                   :vertical-align "top"
                                                   :children [{:type "paragraph-set"
                                                               :children [{:type "paragraph"
                                                                           :children [{:text "hello"}]}]}]}))}}}}
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

(t/deftest migration-0025-repair-empty-text-content-level2
  ;; Level 2: paragraph-set with empty/missing children
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "root"
                                                   :vertical-align "top"
                                                   :children [{:type "paragraph-set"
                                                               :children []}]}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "paragraph-set" (get-in content [:children 0 :type])) "paragraph-set preserved")
    (t/is (pos? (count (get-in content [:children 0 :children])))
          "paragraph-set now has at least one paragraph")
    (t/is (= "paragraph" (get-in content [:children 0 :children 0 :type]))
          "seeded child is a paragraph")
    (t/is (= "top" (:vertical-align content))
          "preserves pre-existing :vertical-align")))

(t/deftest migration-0025-repair-empty-text-content-level3
  ;; Level 3: paragraph with empty/missing children
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "root"
                                                   :vertical-align "top"
                                                   :children [{:type "paragraph-set"
                                                               :children [{:type "paragraph"
                                                                           :children []}]}]}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "paragraph" (get-in content [:children 0 :children 0 :type])) "paragraph preserved")
    (t/is (pos? (count (get-in content [:children 0 :children 0 :children])))
          "paragraph now has at least one span")
    (t/is (= "" (get-in content [:children 0 :children 0 :children 0 :text]))
          "seeded span has empty text")
    (t/is (= "top" (:vertical-align content))
          "preserves pre-existing :vertical-align")))

(t/deftest migration-0025-repair-empty-text-content-mixed-levels
  ;; Valid level 1, but broken at levels 2 and 3 in different paragraph-sets
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "root"
                                                   :vertical-align "top"
                                                   :children [{:type "paragraph-set"
                                                               :children []}
                                                              {:type "paragraph-set"
                                                               :children [{:type "paragraph"
                                                                           :children []}]}]}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= 2 (count (:children content))) "both paragraph-sets preserved")
    ;; First paragraph-set had empty children (level 2 broken)
    (t/is (pos? (count (get-in content [:children 0 :children])))
          "first paragraph-set now has paragraphs")
    ;; Second paragraph-set had a paragraph with empty children (level 3 broken)
    (t/is (pos? (count (get-in content [:children 1 :children 0 :children])))
          "second paragraph's paragraph now has spans")))

;; ============================================================================
;; Category A: Shape-level guards (fix-shape)
;; ============================================================================

(t/deftest migration-0025-non-text-shape-untouched
  ;; A: Non-text shape should not be processed
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (cts/setup-shape {:id shape-id :type :rect :x 0 :y 0})}}}}
        original (get-in data [:pages-index page-id :objects shape-id])
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape'   (get-in data' [:pages-index page-id :objects shape-id])]

    (t/is (= original shape') "non-text shape is unchanged")))

(t/deftest migration-0025-text-shape-non-map-content-repaired
  ;; A: Text shape with non-map content should be repaired
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content "not a map"))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (map? content) "content is now a map")
    (t/is (= "root" (:type content)) "content has root type")
    (t/is (vector? (:children content)) "children is a vector")
    (t/is (pos? (count (:children content))) "has at least one paragraph-set")))

(t/deftest migration-0025-text-shape-wrong-root-type-repaired
  ;; A: Text shape with content :type not "root" should be repaired, preserving root-level attrs
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "paragraph"
                                                   :vertical-align "center"
                                                   :children []}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "root" (:type content)) "type is now root")
    (t/is (= "center" (:vertical-align content)) "root-level attrs preserved")
    (t/is (vector? (:children content)) "children is a vector")
    (t/is (pos? (count (:children content))) "has at least one paragraph-set")))

(t/deftest migration-0025-text-shape-nil-content
  ;; I: Text shape with :content nil should be repaired with default content
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content nil))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (map? content) "content is now a map")
    (t/is (= "root" (:type content)) "content has root type")
    (t/is (vector? (:children content)) "children is a vector")
    (t/is (pos? (count (:children content))) "has at least one paragraph-set")))

(t/deftest migration-0025-text-shape-empty-map-content
  ;; I: Text shape with :content {} (empty map) should be repaired with default content
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "root" (:type content)) "type is now root")
    (t/is (vector? (:children content)) "children is a vector")
    (t/is (pos? (count (:children content))) "has at least one paragraph-set")))

(t/deftest migration-0025-text-shape-wrong-type-with-root-attrs
  ;; I: Text shape with wrong type but valid root-level attrs should preserve attrs
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "paragraph"
                                                   :vertical-align "bottom"
                                                   :children []}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "root" (:type content)) "type is now root")
    (t/is (= "bottom" (:vertical-align content)) "root-level attrs preserved")
    (t/is (vector? (:children content)) "children is a vector")
    (t/is (pos? (count (:children content))) "has at least one paragraph-set")))

(t/deftest migration-0025-text-shape-partial-salvage-paragraphs-under-root
  ;; K: Root has children but they're paragraphs (not paragraph-sets) - should preserve level 1 attrs
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (-> (cts/setup-shape {:id shape-id :type :text :x 0 :y 0 :grow-type :auto-width})
                                  (assoc :content {:type "root"
                                                   :vertical-align "top"
                                                   :children [{:type "paragraph"
                                                               :children [{:text "hello"}]}]}))}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "root" (:type content)) "root type preserved")
    (t/is (= "top" (:vertical-align content)) "root-level attrs preserved")
    (t/is (= 1 (count (:children content))) "has one paragraph-set")
    (t/is (= "paragraph-set" (get-in content [:children 0 :type])) "child is paragraph-set")
    (t/is (pos? (count (get-in content [:children 0 :children]))) "paragraph-set has paragraphs")))

;; ============================================================================
;; Category B: Level 1 (root) variants
;; ============================================================================

(t/deftest migration-0025-root-non-vector-children-map
  ;; B: Root with non-vector children (map) - GAP: should repair
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :vertical-align "top"
                                :children {:invalid "map"}})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "root" (:type content)) "root type preserved")
    (t/is (vector? (:children content)) "children is now a vector")
    (t/is (= "top" (:vertical-align content)) "preserves vertical-align")))

(t/deftest migration-0025-root-non-vector-children-string
  ;; B: Root with non-vector children (string) - GAP: should repair
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :vertical-align "center"
                                :children "not a vector"})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (:children content)) "children is now a vector")
    (t/is (= "center" (:vertical-align content)) "preserves vertical-align")))

;; ============================================================================
;; Category C: Level 2 (paragraph-set) variants
;; ============================================================================

(t/deftest migration-0025-paragraph-set-nil-children
  ;; C: Paragraph-set with nil children key
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (get-in content [:children 0 :children])) "children is a vector")
    (t/is (pos? (count (get-in content [:children 0 :children]))) "has at least one paragraph")))

(t/deftest migration-0025-paragraph-set-non-vector-children-map
  ;; C: Paragraph-set with non-vector children (map)
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children {:invalid "map"}}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (get-in content [:children 0 :children])) "children is now a vector")
    (t/is (= "paragraph" (get-in content [:children 0 :children 0 :type])) "seeded with default paragraph")))

(t/deftest migration-0025-paragraph-set-non-vector-children-string
  ;; C: Paragraph-set with non-vector children (string)
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children "not a vector"}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (get-in content [:children 0 :children])) "children is now a vector")))

(t/deftest migration-0025-paragraph-set-item-not-map
  ;; C: Paragraph-set with non-map item in children vector
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children ["not a map"]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "paragraph" (get-in content [:children 0 :children 0 :type])) "non-map item replaced with default paragraph")))

(t/deftest migration-0025-paragraph-set-mixed-valid-nil-non-map
  ;; C: Paragraph-set with mix of valid paragraphs, nil, and non-map items
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children [{:text "ok"}]}
                                                       nil
                                                       "not-a-map"
                                                       {:type "paragraph"
                                                        :children [{:text "also ok"}]}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        paragraphs (get-in shape [:content :children 0 :children])]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= 4 (count paragraphs)) "all items preserved as paragraphs")
    (t/is (= "paragraph" (:type (nth paragraphs 0))) "valid paragraph preserved")
    (t/is (= "ok" (:text (get-in (nth paragraphs 0) [:children 0]))) "valid span text preserved")
    (t/is (= "paragraph" (:type (nth paragraphs 1))) "nil replaced with default paragraph")
    (t/is (= "paragraph" (:type (nth paragraphs 2))) "non-map replaced with default paragraph")
    (t/is (= "paragraph" (:type (nth paragraphs 3))) "valid paragraph preserved")
    (t/is (= "also ok" (:text (get-in (nth paragraphs 3) [:children 0]))) "valid span text preserved")))

(t/deftest migration-0025-paragraph-set-wrong-type
  ;; C: Paragraph-set with wrong :type
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph"
                                            :children []}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "paragraph-set" (get-in content [:children 0 :type])) "wrong type replaced with default paragraph-set")))

;; ============================================================================
;; Category D: Level 3 (paragraph) variants
;; ============================================================================

(t/deftest migration-0025-paragraph-nil-children
  ;; D: Paragraph with nil children key
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (get-in content [:children 0 :children 0 :children])) "paragraph children is a vector")
    (t/is (pos? (count (get-in content [:children 0 :children 0 :children]))) "has at least one span")))

(t/deftest migration-0025-paragraph-non-vector-children-map
  ;; D: Paragraph with non-vector children (map) - GAP: should repair
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children {:invalid "map"}}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (get-in content [:children 0 :children 0 :children])) "paragraph children is now a vector")
    (t/is (= "" (get-in content [:children 0 :children 0 :children 0 :text])) "seeded with default span")))

(t/deftest migration-0025-paragraph-non-vector-children-string
  ;; D: Paragraph with non-vector children (string) - GAP: should repair
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children "not a vector"}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (vector? (get-in content [:children 0 :children 0 :children])) "paragraph children is now a vector")))

(t/deftest migration-0025-paragraph-item-not-map
  ;; D: Paragraph with non-map item in children vector
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children ["not a map"]}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "" (get-in content [:children 0 :children 0 :children 0 :text])) "non-map item replaced with default span")))

(t/deftest migration-0025-paragraph-wrong-type
  ;; D: Paragraph with wrong :type
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "span"
                                                        :text "hello"}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "paragraph" (get-in content [:children 0 :children 0 :type])) "wrong type replaced with default paragraph")))

(t/deftest migration-0025-paragraph-valid-spans-preserved
  ;; D: Paragraph with valid spans should be preserved
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children [{:text "hello"}
                                                                   {:text "world"}]}]}]})}}}}
        original-spans (get-in data [:pages-index page-id :objects shape-id :content :children 0 :children 0 :children])
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= 2 (count (get-in content [:children 0 :children 0 :children]))) "both spans preserved")
    (t/is (= original-spans (get-in content [:children 0 :children 0 :children])) "spans unchanged")))

;; ============================================================================
;; Category E: Preservation tests
;; ============================================================================

(t/deftest migration-0025-root-attrs-preserved-level2-repair
  ;; E: Root-level attrs preserved when level 2 repaired
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :vertical-align "bottom"
                                :children [{:type "paragraph-set"
                                            :children []}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "bottom" (:vertical-align content)) "root attrs preserved during level 2 repair")))

(t/deftest migration-0025-paragraph-set-attrs-preserved
  ;; E: Paragraph-set attrs preserved when repaired at level 2
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :custom-attr "preserve-me"
                                            :children []}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "preserve-me" (get-in content [:children 0 :custom-attr])) "paragraph-set attrs preserved")))

(t/deftest migration-0025-paragraph-attrs-preserved
  ;; E: Paragraph attrs preserved when repaired at level 3
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :text-align "center"
                                                        :children []}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= "center" (get-in content [:children 0 :children 0 :text-align])) "paragraph attrs preserved")))

;; ============================================================================
;; Category F: Multi-item tests
;; ============================================================================

(t/deftest migration-0025-multiple-paragraphs-mixed-valid-broken
  ;; F: Multiple paragraphs within one paragraph-set, mix of valid and broken
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children [{:text "valid"}]}
                                                       {:type "paragraph"
                                                        :children []}
                                                       {:type "paragraph"
                                                        :children [{:text "also valid"}]}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= 3 (count (get-in content [:children 0 :children]))) "all three paragraphs preserved")
    (t/is (= "valid" (get-in content [:children 0 :children 0 :children 0 :text])) "first paragraph preserved")
    (t/is (= "" (get-in content [:children 0 :children 1 :children 0 :text])) "second paragraph repaired")
    (t/is (= "also valid" (get-in content [:children 0 :children 2 :children 0 :text])) "third paragraph preserved")))

(t/deftest migration-0025-multiple-spans-all-preserved
  ;; F: Multiple spans within one paragraph (all should be preserved)
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root"
                                :children [{:type "paragraph-set"
                                            :children [{:type "paragraph"
                                                        :children [{:text "span1"}
                                                                   {:text "span2"}
                                                                   {:text "span3"}]}]}]})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        shape    (get-in data' [:pages-index page-id :objects shape-id])
        content  (:content shape)]

    (t/is (cts/valid-shape? shape) "repaired shape is valid")
    (t/is (= 3 (count (get-in content [:children 0 :children 0 :children]))) "all spans preserved")
    (t/is (= "span1" (get-in content [:children 0 :children 0 :children 0 :text])))
    (t/is (= "span2" (get-in content [:children 0 :children 0 :children 1 :text])))
    (t/is (= "span3" (get-in content [:children 0 :children 0 :children 2 :text])))))

;; ============================================================================
;; Category G: Container coverage
;; ============================================================================

(t/deftest migration-0025-multiple-pages-broken-shapes
  ;; G: Multiple pages, each with broken shapes
  (let [shape-id-1 (uuid/next)
        shape-id-2 (uuid/next)
        page-id-1  (uuid/next)
        page-id-2  (uuid/next)
        data       {:pages-index
                    {page-id-1
                     {:objects
                      {shape-id-1 (make-text-shape-with-content
                                   shape-id-1
                                   {:type "root" :children []})}}
                     page-id-2
                     {:objects
                      {shape-id-2 (make-text-shape-with-content
                                   shape-id-2
                                   {:type "root" :children []})}}}}
        data'      (cfm/migrate-data data "0025-repair-empty-text-content")
        shape-1    (get-in data' [:pages-index page-id-1 :objects shape-id-1])
        shape-2    (get-in data' [:pages-index page-id-2 :objects shape-id-2])]

    (t/is (cts/valid-shape? shape-1) "first page shape is valid")
    (t/is (cts/valid-shape? shape-2) "second page shape is valid")
    (t/is (pos? (count (get-in shape-1 [:content :children]))) "first page shape repaired")
    (t/is (pos? (count (get-in shape-2 [:content :children]))) "second page shape repaired")))

(t/deftest migration-0025-container-without-objects
  ;; G: Container without :objects key should not crash
  (let [page-id (uuid/next)
        data    {:pages-index
                 {page-id {}}}
        data'   (cfm/migrate-data data "0025-repair-empty-text-content")]

    (t/is (= data data') "container without objects is unchanged")))

;; ============================================================================
;; Category H: Idempotency
;; ============================================================================

(t/deftest migration-0025-already-repaired-unchanged
  ;; H: Already-repaired content unchanged (run migration twice)
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-text-shape-with-content
                               shape-id
                               {:type "root" :children []})}}}}
        data'    (cfm/migrate-data data "0025-repair-empty-text-content")
        data''   (cfm/migrate-data data' "0025-repair-empty-text-content")
        shape'   (get-in data' [:pages-index page-id :objects shape-id])
        shape''  (get-in data'' [:pages-index page-id :objects shape-id])]

    (t/is (cts/valid-shape? shape') "first repair produces valid shape")
    (t/is (cts/valid-shape? shape'') "second repair produces valid shape")
    (t/is (= shape' shape'') "migration is idempotent")))
