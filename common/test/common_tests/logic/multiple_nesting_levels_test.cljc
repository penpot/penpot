;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.multiple-nesting-levels-test
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

;; Related .penpot file: common/test/cases/multiple-testing-levels.penpot
(t/deftest test-multiple-nesting-levels
  (letfn [(setup []
            (-> (thf/sample-file :file1)

                (tho/add-frame :frame-simple-1)
                (ths/add-sample-shape :rectangle :parent-label :frame-simple-1 :fills (ths/sample-fills-color :fill-color "#2152e5"))
                (thc/make-component :simple-1 :frame-simple-1)

                (tho/add-frame :frame-simple-2)
                (ths/add-sample-shape :circle :parent-label :frame-simple-2 :fills (ths/sample-fills-color :fill-color "#e56d21"))
                (thc/make-component :simple-2 :frame-simple-2)

                (tho/add-frame :frame-composed-1)
                (thc/instantiate-component :simple-1 :copy-simple-1 :parent-label :frame-composed-1 :children-labels [:simple-1-composed-1])
                (thc/make-component :composed-1 :frame-composed-1)

                (tho/add-frame :frame-composed-2)
                (thc/instantiate-component :composed-1 :copy-frame-composed-1 :parent-label :frame-composed-2 :children-labels [:composed-1-composed-2])
                (thc/make-component :composed-2 :frame-composed-2)

                (thc/instantiate-component :composed-2 :copy-frame-composed-2 :children-labels [:composed-1-composed-2-copy])))

          (propagate-all-component-changes [file]
            (-> file
                (tho/propagate-component-changes :simple-1)
                (tho/propagate-component-changes :simple-2)
                (tho/propagate-component-changes :composed-1)
                (tho/propagate-component-changes :composed-2)))

          (reset-all-overrides [file]
            (-> file
                (tho/reset-overrides (ths/get-shape file :copy-simple-1))
                (tho/reset-overrides (ths/get-shape file :copy-frame-composed-1))
                (tho/reset-overrides (ths/get-shape file :composed-1-composed-2-copy))
                (propagate-all-component-changes)))

          (fill-colors [file]
            [(tho/bottom-fill-color file :frame-simple-1)
             (tho/bottom-fill-color file :frame-simple-2)
             (tho/bottom-fill-color file :frame-composed-1)
             (tho/bottom-fill-color file :frame-composed-2)
             (tho/bottom-fill-color file :copy-frame-composed-2)])

          (validate [file validator]
            (validator file)
            file)]
    (-> (setup)
        ;; Change color of Simple1 and see that it's propagated to all copies.
        (tho/update-bottom-color :frame-simple-1 "#e521a8" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#e521a8" "#e56d21" "#e521a8" "#e521a8" "#e521a8"])))

        ;; Override color in copy inside Composed1, Composed2 and the copy
        ;; of Composed2 and see in all cases that a change in the main is overriden.
        (tho/update-bottom-color :simple-1-composed-1 "#21e59e" :propagate-fn propagate-all-component-changes)
        (tho/update-bottom-color :composed-1-composed-2 "#2186e5" :propagate-fn propagate-all-component-changes)
        (tho/update-bottom-color :composed-1-composed-2-copy "#e5a221" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#e521a8" "#e56d21" "#21e59e" "#2186e5" "#e5a221"])))
        (tho/update-bottom-color :frame-simple-1 "#b2e521" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#b2e521" "#e56d21" "#21e59e" "#2186e5" "#e5a221"])))

        ;; Reset all overrides and check again the propagation from mains.
        (reset-all-overrides)
        (tho/update-bottom-color :frame-simple-1 "#21aae5" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#21aae5" "#e56d21" "#21aae5" "#21aae5" "#21aae5"])))

        ;; Swap in Composed1 to Simple2 and see that it propagates ok.
        (tho/swap-component-in-shape :copy-simple-1 :simple-2 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#21aae5" "#e56d21" "#e56d21" "#e56d21" "#e56d21"])))

        ;; Change color of Simple 2 and see that it propagates ok.
        (tho/update-bottom-color :frame-simple-2 "#c321e5" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#21aae5" "#c321e5" "#c321e5" "#c321e5" "#c321e5"])))

        ;; Swap Simple 2 copy in Composed2. Check propagations.
        (tho/swap-component-in-first-child :copy-frame-composed-1 :simple-1 :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#21aae5" "#c321e5" "#c321e5" "#21aae5" "#21aae5"])))

        ;; Change color of Simple 1 and check propagation.
        (tho/update-bottom-color :frame-simple-1 "#e521a8" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#e521a8" "#c321e5" "#c321e5" "#e521a8" "#e521a8"])))

        ;; Reset overrides in Composed2 main, and swap Simple 2 copy in
        ;; Composed2 copy. Change color of Simple 2 and check propatagion.
        (tho/reset-overrides-in-first-child :copy-frame-composed-1 :propagate-fn propagate-all-component-changes)
        (tho/swap-component-in-first-child :composed-1-composed-2-copy :simple-1 :propagate-fn propagate-all-component-changes)
        (tho/update-bottom-color :frame-simple-2 "#21e55d" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#e521a8" "#21e55d" "#21e55d" "#21e55d" "#e521a8"])))

        ;; Swap all of three (Composed 1, Composed2 and copy of Composed2)
        ;; and check propagations from Simple mains. 
        (tho/swap-component-in-first-child :frame-composed-1 :simple-1 :propagate-fn propagate-all-component-changes)
        (tho/swap-component-in-first-child :copy-frame-composed-1 :simple-2 :propagate-fn propagate-all-component-changes)
        (tho/swap-component-in-first-child :composed-1-composed-2-copy :simple-2 :propagate-fn propagate-all-component-changes)
        (tho/update-bottom-color :frame-simple-1 "#111111" :propagate-fn propagate-all-component-changes)
        (tho/update-bottom-color :frame-simple-2 "#222222" :propagate-fn propagate-all-component-changes)
        (validate #(t/is (= (fill-colors %) ["#111111" "#222222" "#111111" "#222222" "#222222"]))))))
