;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0038-test
  (:require
   [app.common.files.migrations :as cfm]
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
;; 0038-sanitize-variant-property-text
;; Property names and values used to accept `,` and `=`, which the
;; formula editor treats as separators. Files saved before those were
;; stripped can also hold two identical names, two identical value
;; combinations, or a main whose `:variant-name` does not match its
;; properties. The migration rewrites the text in place and refreshes
;; the label.
;; ======================================================================

(def ^:private migration-id
  "0038-sanitize-variant-property-text")

(defn- migrate-text
  [file]
  (let [data' (cfm/migrate-data (ctf/file-data file) migration-id)]
    (assoc file :data data')))

(defn- variant-file
  []
  (-> (thf/sample-file :file1)
      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)))

(t/deftest migration-0038-strips-forbidden-characters
  ;; root2 is the first child of :shapes, so its names lead the set.
  (let [file   (-> (variant-file)
                   (thk/update-component :component2 {:variant-properties [{:name "Size=Big"
                                                                            :value "a=b,"
                                                                            :hidden true}]})
                   (thk/instantiate-component :component1 :copy2)
                   (ths/update-shape :copy2 :name "Loose copy"))
        copy   (ths/get-shape file :copy2)
        file'  (migrate-text file)
        comp2' (thk/get-component file' :component2)
        root2' (ths/get-shape file' :root2)
        comp1' (thk/get-component file' :component1)
        copy'  (ths/get-shape file' :copy2)
        data'  (ctf/file-data file')]

    (t/is (= "SizeBig" (-> comp2' :variant-properties first :name)))
    (t/is (= "ab" (-> comp2' :variant-properties first :value)))
    (t/is (true? (-> comp2' :variant-properties first :hidden)))
    (t/is (= "ab" (:variant-name root2')))
    (t/is (= "Value1" (->> (:variant-properties comp1')
                           (some #(when (= "Property 1" (:name %)) %))
                           :value)))
    (t/is (= copy copy') "copies are left untouched")
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0038-refreshes-a-stale-variant-name
  (let [props [{:name "State" :value "active"}
               {:name "Tone" :value "crítica"}]
        file  (-> (variant-file)
                  (thk/update-component :component2 {:variant-properties props})
                  (thk/update-component :component1 {:variant-properties [{:name "State" :value "active"}
                                                                          {:name "Tone" :value "hostil"}]})
                  (ths/update-shape :root2 :variant-name "active, hostil"))
        file' (migrate-text file)
        comp' (thk/get-component file' :component2)
        root' (ths/get-shape file' :root2)
        data' (ctf/file-data file')]

    (t/is (= props (:variant-properties comp')))
    (t/is (= "active, crítica" (:variant-name root')))
    (t/is (= (ctv/properties-to-name props) (:variant-name root')))
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0038-does-not-take-names-from-a-detached-component
  (let [deleted-id (uuid/next)
        main-id    (uuid/next)
        file       (variant-file)
        variant-id (thi/id :variant1)
        page-id    (:id (thf/current-page file))
        file       (update file :data
                           (fn [data]
                             (assoc-in data [:components deleted-id]
                                       {:id deleted-id
                                        :name "Gone"
                                        :path ""
                                        :deleted true
                                        :variant-id variant-id
                                        :main-instance-id main-id
                                        :main-instance-page page-id
                                        :variant-properties [{:name "Legacy=Name" :value "a=b"}]
                                        :objects {main-id {:id main-id
                                                           :type :frame
                                                           :name "Gone"
                                                           :variant-id variant-id
                                                           :variant-name "stale"}}})))
        file'      (migrate-text file)
        live-names (mapv :name (:variant-properties (thk/get-component file' :component2)))
        deleted    (get-in file' [:data :components deleted-id])
        data'      (ctf/file-data file')]

    (t/is (= ["Property 1"] live-names))
    (t/is (= "LegacyName" (-> deleted :variant-properties first :name)))
    (t/is (= "ab" (-> deleted :variant-properties first :value)))
    (t/is (= "ab" (get-in deleted [:objects main-id :variant-name])))
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0038-leaves-a-clean-set-unchanged
  (let [data  (ctf/file-data (variant-file))
        data' (cfm/migrate-data data migration-id)]

    (t/is (= data data'))
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0038-runs-through-file-migration
  (let [file  (-> (variant-file)
                  (thk/update-component :component2 {:variant-properties [{:name "Size=Big" :value "red"}]})
                  (assoc :migrations (disj cfm/available-migrations migration-id)))
        file' (cfm/migrate-file file {})]

    (t/is (contains? (:migrations file') migration-id))
    (t/is (= "SizeBig" (-> (thk/get-component file' :component2) :variant-properties first :name)))
    (t/is (some? (ctf/check-file-data (:data file'))))))
