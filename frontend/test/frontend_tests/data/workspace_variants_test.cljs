;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-variants-test
  (:require
   [app.common.path-names :as cpn]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.variant :as ctv]
   [app.main.data.workspace.variants :as dwv]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(defn- two-property-file-on-other-page
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02)
      (cthf/add-sample-page :page2)))

(defn- run
  [file events done assert-fn]
  (ths/run-store
   (ths/setup-store file)
   done
   events
   (fn [state]
     (cthf/validate-file! (ths/get-file-from-state state))
     (assert-fn state))))

(defn- variant-name
  [file label]
  (:variant-name (cths/get-shape file label :page-label :page1)))

(t/deftest update-property-value-from-another-page
  (t/async
    done
    (let [file         (two-property-file-on-other-page)
          component-id (:id (cthc/get-component file :c01))
          other-id     (cthi/id :page2)]
      (run file
           [(dwv/update-property-value component-id 0 "active")
            (dwv/update-property-value component-id 1 "crítica")]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   props (:variant-properties (cthc/get-component file :c01))]
               (t/is (= other-id (:current-page-id state)))
               (t/is (= "crítica" (-> props second :value)))
               (t/is (= "active, crítica" (variant-name file :m01)))
               (t/is (= (ctv/properties-to-name props) (variant-name file :m01)))))))))

(t/deftest update-property-value-on-main-page
  (t/async
    done
    (let [file         (-> (two-property-file-on-other-page)
                           (cthf/switch-to-page :page1))
          component-id (:id (cthc/get-component file :c01))]
      (run file
           [(dwv/update-property-value component-id 1 "crítica")]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   props (:variant-properties (cthc/get-component file :c01))]
               (t/is (= (cthi/id :page1) (:current-page-id state)))
               (t/is (= "crítica" (-> props second :value)))
               (t/is (= (ctv/properties-to-name props) (variant-name file :m01)))))))))

(t/deftest add-remove-reorder-from-another-page
  (t/async
    done
    (let [file       (two-property-file-on-other-page)
          variant-id (cthi/id :v01)]
      (run file
           [(dwv/add-new-property variant-id {:fill-values? true})
            (dwv/reorder-variant-poperties variant-id 0 3)
            (dwv/remove-property variant-id 0)]
           done
           (fn [state]
             (let [file   (ths/get-file-from-state state)
                   comp1  (cthc/get-component file :c01)
                   comp2  (cthc/get-component file :c02)
                   names1 (mapv :name (:variant-properties comp1))
                   names2 (mapv :name (:variant-properties comp2))]
               (t/is (= names1 names2))
               (t/is (= (ctv/properties-to-name (:variant-properties comp1))
                        (variant-name file :m01)))
               (t/is (= (ctv/properties-to-name (:variant-properties comp2))
                        (variant-name file :m02)))))))))

(t/deftest formula-rename-from-another-page
  (t/async
    done
    (let [file     (two-property-file-on-other-page)
          comp     (cthc/get-component file :c01)
          previous (:variant-properties comp)
          updated  [{:name "Property 1" :value "active"}
                    {:name "Property 2" :value "crítica"}]]
      (run file
           [(dwv/update-properties-names-and-values (:id comp) (:variant-id comp) previous updated)
            (dwv/remove-empty-properties (:variant-id comp))]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   props (:variant-properties (cthc/get-component file :c01))]
               (t/is (= "active" (-> props first :value)))
               (t/is (= "crítica" (-> props second :value)))
               (t/is (= (ctv/properties-to-name props) (variant-name file :m01)))))))))

(t/deftest update-property-name-from-another-page
  (t/async
    done
    (let [file       (two-property-file-on-other-page)
          variant-id (cthi/id :v01)]
      (run file
           [(dwv/update-property-name variant-id 0 "Status" {:trigger "test"})]
           done
           (fn [state]
             (let [file  (ths/get-file-from-state state)
                   names (mapv :name (:variant-properties (cthc/get-component file :c01)))]
               (t/is (= names (mapv :name (:variant-properties (cthc/get-component file :c02)))))
               (t/is (= "Status" (first names)))))))))

(t/deftest rename-variant-from-another-page
  (t/async
    done
    (let [file       (two-property-file-on-other-page)
          variant-id (cthi/id :v01)
          other-id   (cthi/id :page2)
          clean-name (cpn/clean-path "Renamed set")]
      (run file
           [(dwv/rename-variant variant-id "Renamed set")]
           done
           (fn [state]
             (let [file (ths/get-file-from-state state)
                   container (cths/get-shape file :v01 :page-label :page1)
                   main1     (cths/get-shape file :m01 :page-label :page1)
                   main2     (cths/get-shape file :m02 :page-label :page1)
                   comp1     (cthc/get-component file :c01)
                   comp2     (cthc/get-component file :c02)]
               (t/is (= other-id (:current-page-id state)))
               (t/is (= clean-name (:name container)))
               (t/is (= clean-name (:name main1)))
               (t/is (= clean-name (:name main2)))
               (t/is (= clean-name (cpn/merge-path-item (:path comp1) (:name comp1))))
               (t/is (= clean-name (cpn/merge-path-item (:path comp2) (:name comp2))))))))))
