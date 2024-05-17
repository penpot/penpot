;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.swap-and-reset-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.file :as  ctf]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; Related .penpot file: common/test/cases/swap-and-reset.penpot
(t/deftest test-swap-and-reset-override
  (letfn [(setup []
            (-> (thf/sample-file :file1)

                (tho/add-frame :frame-rectangle)
                (ths/add-sample-shape :rectangle-shape :parent-label :frame-rectangle :type :rect)
                (thc/make-component :rectangle :frame-rectangle)

                (tho/add-frame :frame-circle)
                (ths/add-sample-shape :circle :parent-label :frame-circle :type :circle)
                (thc/make-component :circle :frame-circle)

                (tho/add-frame :frame-main)
                (thc/instantiate-component :rectangle :copy-rectangle :parent-label :frame-main :children-labels [:copy-rectangle-shape])
                (thc/make-component :main :frame-main)

                (thc/instantiate-component :main :copy :children-labels [:copy-copy-rectangle])))

          (copy-type [file]
            (:type (tho/bottom-shape file :copy)))

          (nested-component-id [file]
            (->>
             (ths/get-shape file :copy)
             :shapes
             first
             (ths/get-shape-by-id file)
             (:component-id)))

          (nested-swap-slot [file]
            (->>
             (ths/get-shape file :copy)
             :shapes
             first
             (ths/get-shape-by-id file)
             (ctk/get-swap-slot)))

          (circle-component-id [file]
            (:id (thc/get-component file :circle)))

          (rectangle-component-id [file]
            (:id (thc/get-component file :rectangle)))

          (copy-rectangle-id [file]
            (:id (ths/get-shape file :copy-rectangle)))

          (validate [file validator]
            (validator file)
            file)]

    (-> (setup)
        ;; Select the Rectangle inside Copy and swap it for an Ellipse
        (tho/swap-component-in-shape :copy-copy-rectangle :circle)
        (validate #(t/is (= (copy-type %) :circle)))
        (validate #(t/is (= (nested-component-id %) (circle-component-id %))))
        (validate #(t/is (= (copy-rectangle-id %) (nested-swap-slot %))))

        ;; Do a "Reset override" on the newly created Ellipse. It should swap for a Rectangle
        (tho/reset-overrides-in-first-child :copy)
        (validate #(t/is (= (copy-type %) :rect)))
        (validate #(t/is (= (nested-component-id %) (rectangle-component-id %))))
        (validate #(t/is (nil? (nested-swap-slot %)))))))
