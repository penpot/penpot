;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-flex-interactions-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file
  "A flex component with a copy, where the main child and the copy child
  have interactions pointing to different boards."
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
                                   #(assoc % :hidden true :layout-item-margin margin))
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

(t/deftest test-reset-flex-copy-keeps-copy-interactions
  (let [;; ==== Setup
        file         (setup-file)

        ;; ==== Action
        updated-file (update-shape file :copy-child
                                   #(assoc % :fills (ths/sample-fills-color :fill-color "#fabada")))
        page         (thf/current-page updated-file)
        container    (ctn/make-container page :page)
        changes      (cll/generate-reset-component (pcb/empty-changes)
                                                   updated-file
                                                   {(:id updated-file) updated-file}
                                                   container
                                                   (thi/id :copy-root))
        file'        (thf/apply-changes updated-file changes)

        ;; ==== Get
        copy-child'  (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (= (:fills (ths/get-shape file :main-child)) (:fills copy-child')))
    (t/is (= [(thi/id :copy-popup)] (destinations copy-child')))))
