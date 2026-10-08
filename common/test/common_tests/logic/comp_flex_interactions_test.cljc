;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-flex-interactions-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file
  "Flex component and copy whose children navigate to different boards."
  []
  (-> (thf/sample-file :file1)
      (tho/add-simple-component-with-copy :component1
                                          :main-root
                                          :main-child
                                          :copy-root
                                          :main-root-params {:layout :flex
                                                             :layout-flex-dir :row}
                                          :copy-root-params {:children-labels [:copy-child]})
      (ths/add-sample-shape :main-popup :type :frame)
      (ths/add-sample-shape :copy-popup :type :frame)
      (ths/add-interaction :main-child :main-popup)
      (ths/add-interaction :copy-child :copy-popup)))

(defn- update-shape
  [file label update-fn]
  (let [page (thf/current-page file)]
    (thf/apply-changes
     file
     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                 #{(thi/id label)}
                                 update-fn
                                 (:objects page)
                                 {}))))

(defn- destinations
  [shape]
  (mapv :destination (:interactions shape)))

(t/deftest test-sync-flex-main-keeps-copy-interactions
  (let [;; ==== Setup
        file         (setup-file)
        margin       {:m1 5 :m2 5 :m3 5 :m4 5}

        ;; ==== Action
        updated-file (update-shape file :main-child
                                   #(assoc % :hidden true
                                           :layout-item-margin margin
                                           :layout-item-h-sizing :fill
                                           :layout-item-z-index 3))
        changes      (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))
        file'        (thf/apply-changes updated-file changes)

        ;; ==== Get
        copy-child'  (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (true? (:hidden copy-child')))
    (t/is (= margin (:layout-item-margin copy-child')))
    (t/is (= :fill (:layout-item-h-sizing copy-child')))
    (t/is (= 3 (:layout-item-z-index copy-child')))
    (t/is (= [(thi/id :copy-popup)] (destinations copy-child')))))

(t/deftest test-sync-flex-copy-to-main-keeps-main-interactions
  (let [;; ==== Setup
        file         (setup-file)

        ;; ==== Action
        updated-file (update-shape file :copy-child #(assoc % :hidden true))
        page         (thf/current-page updated-file)
        container    (ctn/make-container page :page)
        changes      (-> (pcb/empty-changes)
                         (pcb/with-container container)
                         (cll/generate-sync-shape-inverse (:data updated-file)
                                                          {(:id updated-file) updated-file}
                                                          container
                                                          (thi/id :copy-root)))
        file'        (thf/apply-changes updated-file changes)

        ;; ==== Get
        main-child'  (ths/get-shape file' :main-child)]

    ;; ==== Check
    (t/is (true? (:hidden main-child')))
    (t/is (= [(thi/id :main-popup)] (destinations main-child')))))

(defn- setup-library-and-file
  "Library flex component with a nested copy, and a file instance whose
  nested copy has an interaction."
  []
  (let [library (-> (thf/sample-file :library)
                    (tho/add-nested-component :component1
                                              :main1-root
                                              :main1-child
                                              :component2
                                              :main2-root
                                              :nested-head
                                              :main2-root-params {:layout :flex
                                                                  :layout-flex-dir :row}))
        file    (-> (thf/sample-file :file)
                    (ths/add-sample-shape :copy-popup :type :frame)
                    (thc/instantiate-component :component2
                                               :copy2-root
                                               :library library
                                               :children-labels [:copy-nested-head])
                    (ths/add-interaction :copy-nested-head :copy-popup))]
    [library file]))

(defn- sync-file-from-library
  [file library]
  (cll/generate-sync-file-changes (pcb/empty-changes)
                                  nil
                                  nil
                                  (:id file)
                                  nil
                                  (:id library)
                                  {(:id library) library
                                   (:id file) file}
                                  (:id file)))

(t/deftest test-sync-library-flex-keeps-nested-copy-interactions
  (let [;; ==== Setup
        [library file]    (setup-library-and-file)
        copy-x            (:x (tho/bottom-shape file :copy2-root))

        ;; ==== Action
        library'          (-> library
                              (update-shape :main1-child #(gsh/move % (gpt/point 1 0)))
                              (tho/propagate-component-changes :component1))
        file'             (thf/apply-changes file (sync-file-from-library file library'))

        ;; ==== Get
        copy-nested-head' (ths/get-shape file' :copy-nested-head)]

    ;; ==== Check
    (t/is (mth/close? (inc copy-x) (:x (tho/bottom-shape file' :copy2-root))))
    (t/is (= [(thi/id :copy-popup)] (destinations copy-nested-head')))))

(defn- setup-swapped-library-and-file
  []
  (let [[library _]    (setup-library-and-file)
        library        (-> library
                           (update-shape :nested-head #(assoc % :layout-item-absolute true))
                           (tho/add-simple-component :replacement :replacement-root :replacement-child))
        file           (-> (thf/sample-file :consumer)
                           (thc/instantiate-component :component2 :copy-root
                                                      :library library
                                                      :children-labels [:nested-copy]))
        page           (thf/current-page file)
        child          (ths/get-shape file :nested-copy)
        [swapped _ changes]
        (cll/generate-component-swap (pcb/empty-changes nil (:id page))
                                     (:objects page) child (:data library) page
                                     {(:id library) library (:id file) file}
                                     (thi/id :replacement) 0 nil
                                     (select-keys child ctk/swap-keep-attrs) false)
        file           (thf/apply-changes file changes)]
    (thi/set-id! :swapped-copy (:id swapped))
    [library file]))

(t/deftest test-library-sync-keeps-swapped-absolute-child
  (let [[library file] (setup-swapped-library-and-file)
        before         (ths/get-shape file :swapped-copy)
        library'       (update-shape library :main2-root #(assoc % :name "Updated main"))
        changes        (sync-file-from-library file library')
        file'          (thf/apply-changes file changes)
        after          (ths/get-shape file' :swapped-copy)]
    (t/is (true? (:layout-item-absolute before)))
    (t/is (nil? (ctf/get-ref-shape (:data library')
                                   (thc/get-component library' :component2) before)))
    (t/is (= (thi/id :replacement-root) (:shape-ref before)))
    (t/is (some? (ctk/get-swap-slot before)))
    (t/is (= "Updated main" (:name (ths/get-shape file' :copy-root))))
    (t/is (true? (:layout-item-absolute after)))
    (t/is (= (:shape-ref before) (:shape-ref after)))
    (t/is (= (thi/id :replacement) (:component-id after)))
    (t/is (= (:parent-id before) (:parent-id after)))
    (t/is (= (:shapes (ths/get-shape file :copy-root))
             (:shapes (ths/get-shape file' :copy-root))))
    (t/is (= (:touched before) (:touched after)))
    (t/is (= (ctk/get-swap-slot before) (ctk/get-swap-slot after)))
    (t/is (= (:data file) (:data (thf/apply-undo-changes file' changes))))
    (t/is (= (:data file')
             (:data (thf/apply-changes (thf/apply-undo-changes file' changes) changes))))))

(t/deftest test-flex-inverse-missing-copy-keeps-main-child-attrs
  (let [[library _]  (setup-swapped-library-and-file)
        file         (-> library
                         (thc/instantiate-component :component2 :local-copy
                                                    :children-labels [:local-nested])
                         (thc/component-swap :local-nested :replacement :local-swapped))
        page         (thf/current-page file)
        main         (ths/get-shape file :main2-root)
        copy         (ths/get-shape file :local-copy)
        child        (ths/get-shape file :nested-head)
        changes      (#'cll/update-flex-child-main-attrs
                      (pcb/empty-changes) main copy page page false)
        file'        (thf/apply-changes file changes)]
    (t/is (nil? (ctf/get-shape-in-copy page child copy)))
    (t/is (true? (:layout-item-absolute (ths/get-shape file' :nested-head))))
    (t/is (= child (ths/get-shape file' :nested-head)))))

(t/deftest test-flex-sync-propagates-matched-child-attrs-and-keeps-overrides
  (let [file         (setup-file)
        margin       {:m1 1 :m2 2 :m3 3 :m4 4}
        updated      (update-shape file :main-child
                                   #(assoc % :layout-item-margin margin
                                           :layout-item-h-sizing :fill
                                           :layout-item-z-index 3))
        updated      (update-shape updated :copy-child #(assoc % :layout-item-z-index 9))
        changes      (cll/generate-sync-file-changes (pcb/empty-changes) nil :components
                                                     (:id updated) (thi/id :component1)
                                                     (:id updated) {(:id updated) updated}
                                                     (:id updated))
        file'        (thf/apply-changes updated changes)
        child        (ths/get-shape file' :copy-child)
        undone       (thf/apply-undo-changes file' changes)
        redone       (thf/apply-changes undone changes)]
    (t/is (= margin (:layout-item-margin child)))
    (t/is (= :fill (:layout-item-h-sizing child)))
    (t/is (= 9 (:layout-item-z-index child)))
    (t/is (contains? (:touched child) :layout-item-z-index))
    (t/is (= (:data updated) (:data undone)))
    (t/is (= (:data file') (:data redone)))))

(t/deftest test-flex-inverse-propagates-matched-child-attrs
  (let [file         (setup-file)
        margin       {:m1 1 :m2 2 :m3 3 :m4 4}
        updated      (update-shape file :copy-child
                                   #(assoc % :layout-item-margin margin
                                           :layout-item-h-sizing :fill
                                           :layout-item-z-index 3))
        page         (thf/current-page updated)
        container    (ctn/make-container page :page)
        changes      (-> (pcb/empty-changes)
                         (pcb/with-container container)
                         (cll/generate-sync-shape-inverse (:data updated)
                                                          {(:id updated) updated}
                                                          container
                                                          (thi/id :copy-root)))
        file'        (thf/apply-changes updated changes)
        child        (ths/get-shape file' :main-child)]
    (t/is (= margin (:layout-item-margin child)))
    (t/is (= :fill (:layout-item-h-sizing child)))
    (t/is (= 3 (:layout-item-z-index child)))))
