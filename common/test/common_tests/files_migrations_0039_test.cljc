;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0039-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.path-names :as cpn]
   [app.common.test-helpers.components :as thk]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.file :as ctf]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ======================================================================
;; 0039-align-variant-component-names
;; The variant board name and the layer name of each main are already
;; normalized, but the library entry of a member could still carry an
;; older path and name. The migration sets the `:path` and `:name` of
;; every member component from the board name.
;; ======================================================================

(def ^:private migration-id
  "0039-align-variant-component-names")

(def ^:private text-migration-id
  "0038-sanitize-variant-property-text")

(defn- named-board
  [board-name]
  (-> (thf/sample-file :file1)
      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
      (ths/update-shape :variant1 :name board-name)
      (ths/update-shape :root1 :name board-name)
      (ths/update-shape :root2 :name board-name)))

(t/deftest migration-0039-aligns-component-path-with-the-board
  (let [file   (-> (named-board "Group / Button")
                   (thk/update-component :component1 {:path "UI" :name "Button"})
                   (thk/update-component :component2 {:path "UI" :name "Button"})
                   (thk/instantiate-component :component2 :copy2)
                   (ths/update-shape :copy2 :name "Loose copy"))
        before {:root1 (ths/get-shape file :root1)
                :props1 (:variant-properties (thk/get-component file :component1))
                :label1 (:variant-name (ths/get-shape file :root1))
                :copy (ths/get-shape file :copy2)}
        data'  (cfm/migrate-data (ctf/file-data file) migration-id)
        file'  (assoc file :data data')
        comp1' (thk/get-component file' :component1)
        comp2' (thk/get-component file' :component2)
        root1' (ths/get-shape file' :root1)
        copy'  (ths/get-shape file' :copy2)]

    (t/is (= "Group" (:path comp1') (:path comp2')))
    (t/is (= "Button" (:name comp1') (:name comp2')))
    (t/is (= "Group / Button" (cpn/merge-path-item (:path comp1') (:name comp1'))))
    (t/is (= (:name (:root1 before)) (:name root1')))
    (t/is (= (:props1 before) (:variant-properties comp1')))
    (t/is (= (:label1 before) (:variant-name root1')))
    (t/is (= (:name (:copy before)) (:name copy')))
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0039-keeps-a-board-named-empty
  ;; 0033 stores a blank board name as "(empty)"; this migration copies
  ;; that name as it is.
  (let [file  (-> (named-board "(empty)")
                  (thk/update-component :component1 {:path "Old" :name "Thing"})
                  (thk/update-component :component2 {:path "Old" :name "Thing"}))
        data' (cfm/migrate-data (ctf/file-data file) migration-id)
        file' (assoc file :data data')
        comp' (thk/get-component file' :component1)]

    (t/is (= "" (:path comp')))
    (t/is (= "(empty)" (:name comp')))
    (t/is (= "(empty)" (cpn/merge-path-item (:path comp') (:name comp'))))
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0039-leaves-a-matching-component-unchanged
  (let [file  (-> (named-board "Group / Button")
                  (thk/update-component :component1 {:path "Group" :name "Button"})
                  (thk/update-component :component2 {:path "Group" :name "Button"}))
        data  (ctf/file-data file)
        data' (cfm/migrate-data data migration-id)]

    (t/is (= data data'))
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0039-skips-a-component-with-no-board
  (let [orphan-id (uuid/next)
        file      (update (thf/sample-file :file1) :data
                          (fn [data]
                            (assoc-in data [:components orphan-id]
                                      {:id orphan-id
                                       :name "Name"
                                       :path "Old"
                                       :variant-id (uuid/next)})))
        data      (ctf/file-data file)
        data'     (cfm/migrate-data data migration-id)]

    (t/is (= data data'))))

(t/deftest migration-0038-then-0039-cleans-text-and-the-library-name
  (let [file  (-> (named-board "Group / Button")
                  (thk/update-component :component2 {:path "UI"
                                                     :name "Button"
                                                     :variant-properties [{:name "Size=Big" :value "red"}]})
                  (thk/update-component :component1 {:path "UI"
                                                     :name "Button"
                                                     :variant-properties [{:name "Size=Big" :value "blue"}]}))
        data' (-> (ctf/file-data file)
                  (cfm/migrate-data text-migration-id)
                  (cfm/migrate-data migration-id))
        file' (assoc file :data data')
        comp2 (thk/get-component file' :component2)
        comp1 (thk/get-component file' :component1)
        root2 (ths/get-shape file' :root2)
        root1 (ths/get-shape file' :root1)]

    (t/is (= "SizeBig" (-> comp2 :variant-properties first :name)))
    (t/is (= "red" (-> comp2 :variant-properties first :value)))
    (t/is (= "blue" (-> comp1 :variant-properties first :value)))
    (t/is (= (ctv/properties-to-name (:variant-properties comp2)) (:variant-name root2)))
    (t/is (= (ctv/properties-to-name (:variant-properties comp1)) (:variant-name root1)))
    (t/is (= "Group" (:path comp2) (:path comp1)))
    (t/is (= "Button" (:name comp2) (:name comp1)))
    (t/is (= data' (-> data'
                       (cfm/migrate-data text-migration-id)
                       (cfm/migrate-data migration-id))))))
