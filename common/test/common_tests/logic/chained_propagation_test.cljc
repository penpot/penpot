;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.chained-propagation-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- first-fill-color [file tag]
  (-> (ths/get-shape file tag)
      (:fills)
      first
      :fill-color))

(defn- first-child-fill-color [file tag]
  (let [shape (ths/get-shape file tag)]
    (-> (ths/get-shape-by-id file (first (:shapes shape)))
        (:fills)
        first
        :fill-color)))

;; Related .penpot file: common/test/cases/chained-components-changes-propagation.penpot
(t/deftest test-propagation-with-anidated-components
  (letfn [(setup []
            (-> (thf/sample-file :file1)
                (tho/add-frame :frame-comp-1)
                (ths/add-sample-shape :rectangle :parent-label :frame-comp-1)
                (thc/make-component :comp-1 :frame-comp-1)

                (tho/add-frame :frame-comp-2)
                (thc/instantiate-component :comp-1 :copy-comp-1 :parent-label :frame-comp-2 :children-labels [:rect-comp-2])
                (thc/make-component :comp-2 :frame-comp-2)

                (tho/add-frame :frame-comp-3)
                (thc/instantiate-component :comp-2 :copy-comp-2  :parent-label :frame-comp-3 :children-labels [:comp-1-comp-2])
                (thc/make-component :comp-3 :frame-comp-3)))

          (step-update-color-comp-2 [file]
            (let [page    (thf/current-page file)

                  ;; Changes to update the color of the contained rectangle in component comp-2
                  changes-update-color-comp-1
                  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              (:shapes (ths/get-shape file :copy-comp-1))
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#FF0000")))
                                              (:objects page)
                                              {})

                  file' (thf/apply-changes file changes-update-color-comp-1)]

              (t/is (= (first-child-fill-color file' :comp-1-comp-2) "#B1B2B5"))
              file'))

          (step-propagate-comp-2 [file]
            (let [page    (thf/current-page file)
                  file-id (:id file)

                  ;; Changes to propagate the color changes of component comp-1
                  changes-sync-comp-1 (-> (pcb/empty-changes)
                                          (cll/generate-sync-file-changes
                                           nil
                                           :components
                                           file-id
                                           (:id (thc/get-component  file :comp-2))
                                           file-id
                                           {file-id file}
                                           file-id))

                  file' (thf/apply-changes file changes-sync-comp-1)]

              (t/is (= (first-fill-color file' :rect-comp-2) "#FF0000"))
              (t/is (= (first-child-fill-color file' :comp-1-comp-2) "#FF0000"))
              file'))

          (step-update-color-comp-3 [file]
            (let [page    (thf/current-page file)
                  page-id (:id page)
                  comp-1-comp-2 (ths/get-shape file :comp-1-comp-2)
                  rect-comp-3   (ths/get-shape-by-id file (first (:shapes comp-1-comp-2)))
                  ;; Changes to update the color of the contained rectangle in component comp-3
                  changes-update-color-comp-3
                  (cls/generate-update-shapes (pcb/empty-changes nil page-id)
                                              [(:id rect-comp-3)]
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#00FF00")))
                                              (:objects page)
                                              {})

                  file' (thf/apply-changes file changes-update-color-comp-3)]

              (t/is (= (first-child-fill-color file' :comp-1-comp-2) "#00FF00"))
              file'))

          (step-reset [file]
            (let [page          (thf/current-page file)
                  file-id       (:id file)
                  comp-1-comp-2 (ths/get-shape file :comp-1-comp-2)
                  ;; Changes to reset the changes on comp-1 inside comp-3
                  changes-reset  (cll/generate-reset-component (pcb/empty-changes)
                                                               file
                                                               {file-id file}
                                                               (ctn/make-container page :page)
                                                               (:id comp-1-comp-2)
                                                               true)
                  file' (thf/apply-changes file changes-reset)]

              (t/is (= (first-child-fill-color file' :comp-1-comp-2) "#FF0000"))
              file'))]

    (-> (setup)
        step-update-color-comp-2
        step-propagate-comp-2
        step-update-color-comp-3
        step-reset)))

(t/deftest test-propagation-with-deleted-component
  (letfn [(setup []
            (-> (thf/sample-file :file1)
                (tho/add-frame :frame-comp-4)
                (ths/add-sample-shape :rectangle :parent-label :frame-comp-4)
                (thc/make-component :comp-4 :frame-comp-4)

                (tho/add-frame :frame-comp-5)
                (thc/instantiate-component :comp-4 :copy-comp-4 :parent-label :frame-comp-5 :children-labels [:rect-comp-5])
                (thc/make-component :comp-5 :frame-comp-5)

                (tho/add-frame :frame-comp-6)
                (thc/instantiate-component :comp-5 :copy-comp-5  :parent-label :frame-comp-6 :children-labels [:comp-4-comp-5])
                (thc/make-component :comp-6 :frame-comp-6)))

          (step-delete-comp-5 [file]
            (let [page (thf/current-page file)
                  ;; Changes to delete comp-5
                  [_ changes-delete] (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                                                 file
                                                                 page
                                                                 (:objects page)
                                                                 #{(-> (ths/get-shape file :frame-comp-5)
                                                                       :id)}
                                                                 {:components-v2 true})

                  file' (thf/apply-changes file changes-delete)]
              (t/is (= (first-child-fill-color file' :comp-4-comp-5) "#B1B2B5"))
              file'))

          (step-update-color-comp-4 [file]
            (let [page          (thf/current-page file)
                  ;; Changes to update the color of the contained rectangle in component comp-4
                  changes-update-color-comp-4
                  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              [(-> (ths/get-shape file :rectangle)
                                                   :id)]
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#FF0000")))
                                              (:objects page)
                                              {})

                  file' (thf/apply-changes file changes-update-color-comp-4)]
              (t/is (= (first-fill-color file' :rectangle) "#FF0000"))
              file'))

          (step-propagate-comp-4 [file]
            (let [file-id (:id file)
                  ;; Changes to propagate the color changes of component comp-4
                  changes-sync-comp-4 (-> (pcb/empty-changes)
                                          (cll/generate-sync-file-changes
                                           nil
                                           :components
                                           file-id
                                           (:id (thc/get-component  file :comp-4))
                                           file-id
                                           {file-id file}
                                           file-id))

                  file' (thf/apply-changes file changes-sync-comp-4)]
              file'))

          (step-propagate-comp-5 [file]
            (let [file-id (:id file)
                  ;; Changes to propagate the color changes of component comp-5
                  changes-sync-comp-5 (-> (pcb/empty-changes)
                                          (cll/generate-sync-file-changes
                                           nil
                                           :components
                                           file-id
                                           (:id (thc/get-component  file :comp-5))
                                           file-id
                                           {file-id file}
                                           file-id))
                  file' (thf/apply-changes file changes-sync-comp-5)]
              (t/is (= (first-child-fill-color file' :comp-4-comp-5) "#FF0000"))
              file'))]

    (-> (setup)
        step-delete-comp-5
        step-update-color-comp-4
        step-propagate-comp-4
        step-propagate-comp-5)))
