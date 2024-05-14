;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.duplicated-pages-test
  (:require
   [app.common.files.changes :as ch]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.pprint :as pp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as  ctf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; Related .penpot file: common/test/cases/duplicated-pages.penpot
(t/deftest test-propagation-with-anidated-components
  (letfn [(setup []
            (-> (thf/sample-file :file1 :page-label :page-1)
                (tho/add-frame :frame-ellipse-1 :fills [])
                (ths/add-sample-shape :ellipse-shape-1 :parent-label :frame-ellipse-1 :fills (ths/sample-fills-color :fill-color "#204fdc"))
                (thc/make-component :ellipse-1 :frame-ellipse-1)

                (tho/add-frame :frame-ellipse-2 :fills [])
                (ths/add-sample-shape :ellipse-shape-2 :parent-label :frame-ellipse-2 :fills (ths/sample-fills-color :fill-color "#dc3020"))
                (thc/make-component :ellipse-2 :frame-ellipse-2)

                (tho/add-frame :frame-ellipse-3 :fills [])
                (ths/add-sample-shape :ellipse-shape-3 :parent-label :frame-ellipse-3 :fills (ths/sample-fills-color :fill-color "#d8dc20"))
                (thc/make-component :ellipse-3 :frame-ellipse-3)

                (tho/add-frame :frame-board-1 :fills (ths/sample-fills-color :fill-color "#FFFFFF"))
                (thc/instantiate-component :ellipse-1 :copy-ellipse-1 :parent-label :frame-board-1 :children-labels [:ellipse-shape-1-board-1])
                (thc/make-component :board-1 :frame-board-1)

                (thf/add-sample-page :page-2)
                (tho/add-frame :frame-board-2 :fills (ths/sample-fills-color :fill-color "#FFFFFF"))
                (thc/instantiate-component :board-1 :copy-board-1 :parent-label :frame-board-2 :children-labels [:board-1-board-2])
                (thc/make-component :board-2 :frame-board-2)

                (thf/add-sample-page :page-3)
                (tho/add-frame :frame-board-3 :fills (ths/sample-fills-color :fill-color "#FFFFFF"))
                (thc/instantiate-component :board-2 :copy-board-2 :parent-label :frame-board-3 :children-labels [:board-2-board-3])
                (thc/make-component :board-3 :frame-board-3)))

          (propagate-all-component-changes [file]
            (-> file
                (tho/propagate-component-changes :ellipse-1)
                (tho/propagate-component-changes :ellipse-2)
                (tho/propagate-component-changes :ellipse-3)
                (tho/propagate-component-changes :board-1)
                (tho/propagate-component-changes :board-2)))

          (reset-all-overrides [file]
            (-> file
                (tho/reset-overrides-in-first-child :frame-board-1 :page-label :page-1)
                (tho/reset-overrides-in-first-child :copy-board-1 :page-label :page-2)
                (propagate-all-component-changes)))

          (fill-colors [file]
            [(tho/bottom-fill-color file :frame-ellipse-1 :page-label :page-1)
             (tho/bottom-fill-color file :frame-ellipse-2 :page-label :page-1)
             (tho/bottom-fill-color file :frame-ellipse-3 :page-label :page-1)
             (tho/bottom-fill-color file :frame-board-1 :page-label :page-1)
             (tho/bottom-fill-color file :frame-board-2 :page-label :page-2)
             (tho/bottom-fill-color file :frame-board-3 :page-label :page-3)
             (->
              (ths/get-shape file :frame-board-1 :page-label :page-1)
              :fills
              first
              :fill-color)
             (->
              (ths/get-shape file :copy-board-1 :page-label :page-2)
              :fills
              first
              :fill-color)])

          (validate [file validator]
            (validator file)
            file)]

    (-> (setup)
        ;; Swap the copy inside main of Board1 to Ellipse2, and see that it propagates to copies in other pages.
        (tho/swap-component-in-shape :copy-ellipse-1 :ellipse-2 :page-label :page-1 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#dc3020" "#d8dc20" "#dc3020" "#dc3020" "#dc3020" "#FFFFFF" "#FFFFFF"])))

        ;; Change color of Ellipse2 main, and see that it propagates to all copies.
        (tho/update-bottom-color :frame-ellipse-2 "#abcdef" :page-label :page-1 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#abcdef" "#d8dc20" "#abcdef" "#abcdef" "#abcdef" "#FFFFFF" "#FFFFFF"])))

        ;;Change color of copies of Ellipse2 and see that the override works.
        (tho/update-bottom-color :frame-board-1 "#efaade" :page-label :page-1 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#abcdef" "#d8dc20" "#efaade" "#efaade" "#efaade" "#FFFFFF" "#FFFFFF"])))
        (tho/update-bottom-color :copy-board-1 "#aaefcb" :page-label :page-2 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#abcdef" "#d8dc20" "#efaade" "#aaefcb" "#aaefcb" "#FFFFFF" "#FFFFFF"])))

        ;; Reset all overrides.
        (reset-all-overrides)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#abcdef" "#d8dc20" "#abcdef" "#abcdef" "#abcdef" "#FFFFFF" "#FFFFFF"])))

        ;; Swap the copy of Ellipse2 inside copies of Board1 to Ellipse 3. Then make
        ;; changes in Board1 main and see that they are not propagated.
        (tho/swap-component-in-first-child :copy-board-1 :ellipse-3 :page-label :page-2 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#abcdef" "#d8dc20" "#abcdef" "#d8dc20" "#d8dc20" "#FFFFFF" "#FFFFFF"])))
        (tho/update-color :frame-board-1 "#fabada" :page-label :page-1 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#204fdc" "#abcdef" "#d8dc20" "#abcdef" "#d8dc20" "#d8dc20" "#fabada" "#fabada"]))))))
