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
                (ths/add-sample-shape :rectangle :parent-label :frame-comp-1 :fills (ths/sample-fills-color :fill-color "#2653d8"))
                (thc/make-component :comp-1 :frame-comp-1)

                (tho/add-frame :frame-comp-2)
                (thc/instantiate-component :comp-1 :copy-comp-1 :parent-label :frame-comp-2 :children-labels [:rect-comp-2])
                (thc/make-component :comp-2 :frame-comp-2)

                (tho/add-frame :frame-comp-3)
                (thc/instantiate-component :comp-2 :copy-comp-2  :parent-label :frame-comp-3 :children-labels [:comp-1-comp-2])
                (thc/make-component :comp-3 :frame-comp-3)))

          (propagate-all-component-changes [file]
            (-> file
                (tho/propagate-component-changes :comp-1)
                (tho/propagate-component-changes :comp-2)))

          (fill-colors [file]
            [(tho/bottom-fill-color file :frame-comp-1)
             (tho/bottom-fill-color file :frame-comp-2)
             (tho/bottom-fill-color file :frame-comp-3)])

          (validate [file validator]
            (validator file)
            file)]

    (-> (setup)
        ;; Change the color of Comp1 inside Comp2 to red. It will propagate to Comp1 inside Comp3
        (tho/update-bottom-color :frame-comp-2 "#FF0000" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#2653d8" "#FF0000" "#FF0000"])))

        ;; Change the color of Comp1 inside Comp3 to green.
        (tho/update-bottom-color :frame-comp-3 "#00FF00" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#2653d8" "#FF0000" "#00FF00"])))

        ;; Select Comp1 inside Comp3, and do a ‘Reset override’ 
        ;; Desired result: Comp1 inside Comp3 change its color to red, like Comp1 inside Comp2.
        (tho/reset-overrides-in-first-child :copy-comp-2)
        (validate #(t/is (= (fill-colors %) ["#2653d8" "#FF0000" "#FF0000"]))))))

(t/deftest test-propagation-with-deleted-component
  (letfn [(setup []
            (-> (thf/sample-file :file1)
                (tho/add-frame :frame-comp-4)
                (ths/add-sample-shape :rectangle :parent-label :frame-comp-4 :fills (ths/sample-fills-color :fill-color "#b1b2b5"))
                (thc/make-component :comp-4 :frame-comp-4)

                (tho/add-frame :frame-comp-5)
                (thc/instantiate-component :comp-4 :copy-comp-4 :parent-label :frame-comp-5 :children-labels [:rect-comp-5])
                (thc/make-component :comp-5 :frame-comp-5)

                (tho/add-frame :frame-comp-6)
                (thc/instantiate-component :comp-5 :copy-comp-5  :parent-label :frame-comp-6 :children-labels [:comp-4-comp-5])
                (thc/make-component :comp-6 :frame-comp-6)))

          (propagate-all-component-changes [file]
            (-> file
                (tho/propagate-component-changes :comp-4)
                (tho/propagate-component-changes :comp-5)))

          (fill-colors [file]
            [(tho/bottom-fill-color file :frame-comp-4)
             (tho/bottom-fill-color file :frame-comp-5)
             (tho/bottom-fill-color file :frame-comp-6)])

          (validate [file validator]
            (validator file)
            file)]

    (-> (setup)
        ;; Delete Comp5.
        (tho/delete-shape :frame-comp-5)
        (validate #(t/is (= (fill-colors %) ["#b1b2b5" nil "#b1b2b5"])))

        ;; Change the color of Comp4
        ;; Desired result: Comp6 change color
        (tho/update-bottom-color :frame-comp-4 "#FF0000" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#FF0000" nil "#FF0000"]))))))
