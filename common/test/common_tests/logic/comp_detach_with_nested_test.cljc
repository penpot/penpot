;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.comp-detach-with-nested-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; Related .penpot file: common/test/cases/detach-with-nested.penpot
(defn- setup-file
  []
  ;; {:r-ellipse} [:name Ellipse, :type :frame]                         # [Component :c-ellipse]
  ;;     :ellipse [:name Ellipse, :type :circle] 
  ;; {:r-rectangle} [:name Rectangle, :type :frame]                     # [Component :c-rectangle]
  ;;     :rectangle [:name rectangle, :type :rect] 
  ;; {:board-with-ellipse} [:name Board with ellipse, :type :frame]     # [Component :c-board-with-ellipse]
  ;;     :nested-h-ellipse [:name Ellipse, :type :frame]                @--> :r-ellipse
  ;;         :nested-ellipse [:name Ellipse, :type :circle]             ---> :ellipse
  ;; {:board-with-rectangle} [:name Board with rectangle, :type :frame] # [Component :c-board-with-rectangle]
  ;;     :nested-h-rectangle [:name Rectangle, :type :frame]            @--> :r-rectangle
  ;;         :nested-rectangle [:name rectangle, :type :rect]           ---> :rectangle
  ;; {:big-board} [:name Big Board, :type :frame]                       # [Component :c-big-board]
  ;;     :h-board-with-ellipse [:name Board with ellipse, :type :frame] @--> :board-with-ellipse
  ;;         :nested2-h-ellipse [:name Ellipse, :type :frame]           @--> :nested-h-ellipse
  ;;             :nested2-ellipse [:name Ellipse, :type :circle]        ---> :nested-ellipse
  (-> (thf/sample-file :file1)

      (tho/add-simple-component :c-ellipse :r-ellipse :ellipse
                                :root-params {:name "Ellipse"}
                                :child-params {:name "Ellipse" :type :circle})

      (tho/add-simple-component :c-rectangle :r-rectangle :rectangle
                                :root-params {:name "Rectangle"}
                                :child-params {:name "rectangle" :type :rect})

      (tho/add-frame :board-with-ellipse :name "Board with ellipse")
      (thc/instantiate-component :c-ellipse :nested-h-ellipse :parent-label :board-with-ellipse
                                 :children-labels [:nested-ellipse])
      (thc/make-component :c-board-with-ellipse :board-with-ellipse)

      (tho/add-frame :board-with-rectangle :name "Board with rectangle")
      (thc/instantiate-component :c-rectangle :nested-h-rectangle :parent-label :board-with-rectangle
                                 :children-labels [:nested-rectangle])
      (thc/make-component :c-board-with-rectangle :board-with-rectangle)

      (tho/add-frame :big-board :name "Big Board")
      (thc/instantiate-component :c-board-with-ellipse
                                 :h-board-with-ellipse
                                 :parent-label :big-board
                                 :children-labels [:nested2-h-ellipse :nested2-ellipse])
      (thc/make-component :c-big-board :big-board)))

(t/deftest test-advance-when-not-swapped
  (let [;; ==== Setup
        file (-> (setup-file)
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested-h-ellipse
                                                              :copy-nested-ellipse]))

        page (thf/current-page file)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-h-board-with-ellipse (ths/get-shape file' :copy-h-board-with-ellipse)
        copy-nested-h-ellipse     (ths/get-shape file' :copy-nested-h-ellipse)
        copy-nested-ellipse       (ths/get-shape file' :copy-nested-ellipse)]

    ;; ==== Check

    ;; In the normal case, children's ref (that pointed to the near main inside big-board)
    ;; are advanced to point to the new near main inside board-with-ellipse.
    (t/is (ctk/instance-root? copy-h-board-with-ellipse))
    (t/is (= (:shape-ref copy-h-board-with-ellipse) (thi/id :board-with-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-h-board-with-ellipse)))

    (t/is (ctk/instance-head? copy-nested-h-ellipse))
    (t/is (= (:shape-ref copy-nested-h-ellipse) (thi/id :nested-h-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-h-ellipse)))

    (t/is (not (ctk/instance-head? copy-nested-ellipse)))
    (t/is (= (:shape-ref copy-nested-ellipse) (thi/id :nested-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-ellipse)))))

(t/deftest test-advance-in-library
  (let [;; ==== Setup
        library (setup-file)
        file    (-> (thf/sample-file :file2)
                    (thc/instantiate-component :c-big-board
                                               :copy-big-board
                                               :library library
                                               :children-labels [:copy-h-board-with-ellipse
                                                                 :copy-nested-h-ellipse
                                                                 :copy-nested-ellipse]))
        page (thf/current-page file)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file
                                               (:id library) library}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-h-board-with-ellipse (ths/get-shape file' :copy-h-board-with-ellipse)
        copy-nested-h-ellipse     (ths/get-shape file' :copy-nested-h-ellipse)
        copy-nested-ellipse       (ths/get-shape file' :copy-nested-ellipse)]

    ;; ==== Check

    ;; It should the same as above, but in an external library.
    (thf/dump-file file)
    (t/is (ctk/instance-root? copy-h-board-with-ellipse))
    (t/is (= (:shape-ref copy-h-board-with-ellipse) (thi/id :board-with-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-h-board-with-ellipse)))

    (t/is (ctk/instance-head? copy-nested-h-ellipse))
    (t/is (= (:shape-ref copy-nested-h-ellipse) (thi/id :nested-h-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-h-ellipse)))

    (t/is (not (ctk/instance-head? copy-nested-ellipse)))
    (t/is (= (:shape-ref copy-nested-ellipse) (thi/id :nested-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-ellipse)))))

(t/deftest test-advance-in-broken-library
  (let [;; ==== Setup
        library (setup-file)
        file    (-> (thf/sample-file :file2)
                    (thc/instantiate-component :c-big-board
                                               :copy-big-board
                                               :library library
                                               :children-labels [:copy-h-board-with-ellipse
                                                                 :copy-nested-h-ellipse
                                                                 :copy-nested-ellipse]))
        page (thf/current-page file)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-h-board-with-ellipse (ths/get-shape file' :copy-h-board-with-ellipse)
        copy-nested-h-ellipse     (ths/get-shape file' :copy-nested-h-ellipse)
        copy-nested-ellipse       (ths/get-shape file' :copy-nested-ellipse)]

    ;; ==== Check

    ;; If the main component cannot be found, because it's in a library that is
    ;; not available, the nested copies should be detached too.
    (t/is (not (ctk/in-component-copy? copy-h-board-with-ellipse)))
    (t/is (not (ctk/in-component-copy? copy-nested-h-ellipse)))
    (t/is (not (ctk/in-component-copy? copy-nested-ellipse)))))

(t/deftest test-dont-advance-when-swapped-copy
  (let [;; ==== Setup
        file (-> (setup-file)
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested-h-ellipse
                                                              :copy-nested-ellipse])
                 (thc/component-swap :copy-h-board-with-ellipse
                                     :c-board-with-rectangle
                                     :copy-h-board-with-rectangle
                                     :children-labels [:copy-nested-h-rectangle
                                                       :copy-nested-rectangle]))

        page (thf/current-page file)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-h-board-with-rectangle (ths/get-shape file' :copy-h-board-with-rectangle)
        copy-nested-h-rectangle     (ths/get-shape file' :copy-nested-h-rectangle)
        copy-nested-rectangle       (ths/get-shape file' :copy-nested-rectangle)]

    ;; ==== Check

    ;; If the nested copy was swapped, there is no need to advance shape-refs,
    ;; as they already pointing to the near main inside board-with-rectangle.
    (t/is (ctk/instance-root? copy-h-board-with-rectangle))
    (t/is (= (:shape-ref copy-h-board-with-rectangle) (thi/id :board-with-rectangle)))
    (t/is (nil? (ctk/get-swap-slot copy-h-board-with-rectangle)))

    (t/is (ctk/instance-head? copy-nested-h-rectangle))
    (t/is (= (:shape-ref copy-nested-h-rectangle) (thi/id :nested-h-rectangle)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-h-rectangle)))

    (t/is (not (ctk/instance-head? copy-nested-rectangle)))
    (t/is (= (:shape-ref copy-nested-rectangle) (thi/id :nested-rectangle)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-rectangle)))))

(t/deftest test-propagate-slot-when-swapped-main
  (let [;; ==== Setup
        file (-> (setup-file)
                 (thc/component-swap :nested2-h-ellipse
                                     :c-rectangle
                                     :nested2-h-rectangle
                                     :children-labels [:nested2-rectangle])
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested-h-rectangle
                                                              :copy-nested-rectangle]))

        page (thf/current-page file)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-h-board-with-ellipse (ths/get-shape file' :copy-h-board-with-ellipse)
        copy-nested-h-rectangle   (ths/get-shape file' :copy-nested-h-rectangle)
        copy-nested-rectangle     (ths/get-shape file' :copy-nested-rectangle)]

    ;; ==== Check

    ;; This one is advanced normally, as it has not been swapped.
    (t/is (ctk/instance-root? copy-h-board-with-ellipse))
    (t/is (= (:shape-ref copy-h-board-with-ellipse) (thi/id :board-with-ellipse)))
    (t/is (nil? (ctk/get-swap-slot copy-h-board-with-ellipse)))

    ;; If the nested copy has been swapped in the main, it does advance,
    ;; but the swap slot of the near main is propagated to the copy.
    (t/is (ctk/instance-head? copy-nested-h-rectangle))
    (t/is (= (:shape-ref copy-nested-h-rectangle) (thi/id :r-rectangle)))
    (t/is (= (ctk/get-swap-slot copy-nested-h-rectangle) (thi/id :nested-h-ellipse)))

    (t/is (not (ctk/instance-head? copy-nested-rectangle)))
    (t/is (= (:shape-ref copy-nested-rectangle) (thi/id :rectangle)))
    (t/is (nil? (ctk/get-swap-slot copy-nested-rectangle)))))

(t/deftest test-propagate-touched
  (let [;; ==== Setup
        file (-> (setup-file)
                 (ths/update-shape :nested2-ellipse :fills (ths/sample-fills-color :fill-color "#fabada"))
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested2-h-ellipse
                                                              :copy-nested2-ellipse]))

        page                 (thf/current-page file)
        nested2-ellipse      (ths/get-shape file :nested2-ellipse)
        copy-nested2-ellipse (ths/get-shape file :copy-nested2-ellipse)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        nested2-ellipse'      (ths/get-shape file' :nested2-ellipse)
        copy-nested2-ellipse' (ths/get-shape file' :copy-nested2-ellipse)
        fills'                (:fills copy-nested2-ellipse')
        fill'                 (first fills')]

    ;; ==== Check

    ;; The touched group must be propagated to the copy, because now this copy
    ;; has the original ellipse component as near main, but its attributes have
    ;; been inherited from the ellipse inside big-board.
    (t/is (= (:touched nested2-ellipse) #{:fill-group}))
    (t/is (= (:touched copy-nested2-ellipse) nil))
    (t/is (= (:touched nested2-ellipse') #{:fill-group}))
    (t/is (= (:touched copy-nested2-ellipse') #{:fill-group}))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))))

(t/deftest test-merge-touched
  (let [;; ==== Setup
        file (-> (setup-file)
                 (ths/update-shape :nested2-ellipse :fills (ths/sample-fills-color :fill-color "#fabada"))
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested2-h-ellipse
                                                              :copy-nested2-ellipse])
                 (ths/update-shape :copy-nested2-ellipse :name "Modified name")
                 (ths/update-shape :copy-nested2-ellipse :fills (ths/sample-fills-color :fill-color "#abcdef")))

        page                 (thf/current-page file)
        nested2-ellipse      (ths/get-shape file :nested2-ellipse)
        copy-nested2-ellipse (ths/get-shape file :copy-nested2-ellipse)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        nested2-ellipse'      (ths/get-shape file' :nested2-ellipse)
        copy-nested2-ellipse' (ths/get-shape file' :copy-nested2-ellipse)
        fills'                (:fills copy-nested2-ellipse')
        fill'                 (first fills')]

    ;; ==== Check

    ;; If the copy have been already touched, merge the groups and preserve the modifications.
    (t/is (= (:touched nested2-ellipse) #{:fill-group}))
    (t/is (= (:touched copy-nested2-ellipse) #{:name-group :fill-group}))
    (t/is (= (:touched nested2-ellipse') #{:fill-group}))
    (t/is (= (:touched copy-nested2-ellipse') #{:name-group :fill-group}))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#abcdef"))
    (t/is (= (:fill-opacity fill') 1))))

(t/deftest test-dont-propagete-touched-when-swapped-copy
  (let [;; ==== Setup
        file (-> (setup-file)
                 (ths/update-shape :nested-rectangle :fills (ths/sample-fills-color :fill-color "#fabada"))
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested2-h-ellipse
                                                              :copy-nested2-ellipse])
                 (thc/component-swap :copy-h-board-with-ellipse
                                     :c-board-with-rectangle
                                     :copy-h-board-with-rectangle
                                     :children-labels [:copy-nested2-h-rectangle
                                                       :copy-nested2-rectangle]))

        page (thf/current-page file)
        nested2-rectangle      (ths/get-shape file :nested2-rectangle)
        copy-nested2-rectangle (ths/get-shape file :copy-nested2-rectangle)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        nested2-rectangle'      (ths/get-shape file' :nested2-rectangle)
        copy-nested2-rectangle' (ths/get-shape file' :copy-nested2-rectangle)
        fills'                  (:fills copy-nested2-rectangle')
        fill'                   (first fills')]

    ;; ==== Check

    ;; If the copy has been swapped, there is nothing to propagate since it's already
    ;; pointing to the swapped near main.
    (t/is (= (:touched nested2-rectangle) nil))
    (t/is (= (:touched copy-nested2-rectangle) nil))
    (t/is (= (:touched nested2-rectangle') nil))
    (t/is (= (:touched copy-nested2-rectangle') nil))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))))

(t/deftest test-propagate-touched-when-swapped-main
  (let [;; ==== Setup
        file (-> (setup-file)
                 (thc/component-swap :nested2-h-ellipse
                                     :c-rectangle
                                     :nested2-h-rectangle
                                     :children-labels [:nested2-rectangle])
                 (ths/update-shape :nested2-rectangle :fills (ths/sample-fills-color :fill-color "#fabada"))
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested2-h-rectangle
                                                              :copy-nested2-rectangle]))

        page (thf/current-page file)
        nested2-rectangle      (ths/get-shape file :nested2-rectangle)
        copy-nested2-rectangle (ths/get-shape file :copy-nested2-rectangle)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :copy-big-board))
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        nested2-rectangle'      (ths/get-shape file' :nested2-rectangle)
        copy-nested2-rectangle' (ths/get-shape file' :copy-nested2-rectangle)
        fills'                  (:fills copy-nested2-rectangle')
        fill'                   (first fills')]

    ;; ==== Check

    ;; If the main has been swapped, there is no difference. It propagates the same as
    ;; if it were the original component.
    (t/is (= (:touched nested2-rectangle) #{:fill-group}))
    (t/is (= (:touched copy-nested2-rectangle) nil))
    (t/is (= (:touched nested2-rectangle') #{:fill-group}))
    (t/is (= (:touched copy-nested2-rectangle') #{:fill-group}))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))))

(t/deftest test-detach-copy-in-main
  (let [;; ==== Setup
        file (-> (setup-file)
                 (thc/instantiate-component :c-big-board
                                            :copy-big-board
                                            :children-labels [:copy-h-board-with-ellipse
                                                              :copy-nested-h-ellipse
                                                              :copy-nested-ellipse]))

        page (thf/current-page file)

        ;; ==== Action
        changes (cll/generate-detach-instance (-> (pcb/empty-changes nil)
                                                  (pcb/with-page page)
                                                  (pcb/with-objects (:objects page)))
                                              page
                                              {(:id file) file}
                                              (thi/id :nested-h-ellipse))
        file'   (-> (thf/apply-changes file changes)
                    (tho/propagate-component-changes :c-board-with-ellipse)
                    (tho/propagate-component-changes :c-big-board))

        ;; ==== Get
        nested2-h-ellipse (ths/get-shape file' :nested-h-ellipse)
        copy-nested2-h-ellipse (ths/get-shape file' :copy-nested-h-ellipse)]

    ;; ==== Check

    ;; When the nested copy inside the main is detached, their copies are unheaded.
    (t/is (not (ctk/subcopy-head? nested2-h-ellipse)))
    (t/is (not (ctk/subcopy-head? copy-nested2-h-ellipse)))))