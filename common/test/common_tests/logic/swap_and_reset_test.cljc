;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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

;; The nested component sits inside a group, not directly under the instance root.
;; Resetting overrides after a swap must undo the swap without error.
(t/deftest test-swap-and-reset-override-inside-group
  (letfn [(nested-in-copy [file]
            (->> (ths/get-shape file :copy-group)
                 :shapes
                 first
                 (ths/get-shape-by-id file)))]

    (let [;; ==== Setup
          file
          (-> (thf/sample-file :file1)

              (tho/add-frame :frame-rectangle)
              (ths/add-sample-shape :rectangle-shape :parent-label :frame-rectangle :type :rect)
              (thc/make-component :rectangle :frame-rectangle)

              (tho/add-frame :frame-circle)
              (ths/add-sample-shape :circle :parent-label :frame-circle :type :circle)
              (thc/make-component :circle :frame-circle)

              (tho/add-frame :frame-main)
              (tho/add-group :group-main :parent-label :frame-main)
              (thc/instantiate-component :rectangle :nested-rectangle
                                         :parent-label :group-main
                                         :children-labels [:nested-rectangle-shape])
              (thc/make-component :main :frame-main)

              (thc/instantiate-component :main :copy
                                         :children-labels [:copy-group
                                                           :copy-nested-rectangle
                                                           :copy-nested-rectangle-shape]))

          rectangle-id (:id (thc/get-component file :rectangle))
          circle-id    (:id (thc/get-component file :circle))
          main-nested-id (:id (ths/get-shape file :nested-rectangle))

          ;; ==== Action – swap nested copy inside the group, then reset overrides
          file-swapped
          (tho/swap-component-in-shape file :copy-nested-rectangle :circle
                                       :new-shape-label :copy-nested-swapped)

          swapped (ths/get-shape file-swapped :copy-nested-swapped)

          file'
          (tho/reset-overrides file-swapped swapped)

          restored (nested-in-copy file')]

      ;; ==== Check – after swap
      (t/is (= :circle (:type (tho/bottom-shape file-swapped :copy-nested-swapped))))
      (t/is (= circle-id (:component-id swapped)))
      (t/is (= main-nested-id (ctk/get-swap-slot swapped)))

      ;; ==== Check – after reset: back to rectangle, no swap slot, file still valid
      (t/is (some? restored))
      (t/is (= :rect (:type (tho/bottom-shape-by-id file' (:id restored)))))
      (t/is (= rectangle-id (:component-id restored)))
      (t/is (nil? (ctk/get-swap-slot restored))))))
