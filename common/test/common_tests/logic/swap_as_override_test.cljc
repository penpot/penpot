;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.swap-as-override-test
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

(defn- setup []
  (-> (thf/sample-file :file1)

      (tho/add-simple-component :component-1 :frame-component-1 :child-component-1 :child-params {:name "child-component-1" :type :rect :fills (ths/sample-fills-color :fill-color "#111111")})
      (tho/add-simple-component :component-2 :frame-component-2 :child-component-2 :child-params {:name "child-component-2" :type :rect :fills (ths/sample-fills-color :fill-color "#222222")})
      (tho/add-simple-component :component-3 :frame-component-3 :child-component-3 :child-params {:name "child-component-3" :type :rect :fills (ths/sample-fills-color :fill-color "#333333")})

      (tho/add-frame :frame-icon-and-text)
      (thc/instantiate-component :component-1 :copy-component-1 :parent-label :frame-icon-and-text :children-labels [:component-1-icon-and-text])
      (ths/add-sample-shape :text
                            {:type :text
                             :name "icon+text"
                             :parent-label :frame-icon-and-text})
      (thc/make-component :icon-and-text :frame-icon-and-text)

      (tho/add-frame :frame-panel)
      (thc/instantiate-component :icon-and-text :copy-icon-and-text :parent-label :frame-panel :children-labels [:icon-and-text-panel])
      (thc/make-component :panel :frame-panel)

      (thc/instantiate-component :panel :copy-panel :children-labels [:copy-icon-and-text-panel])))

(defn- propagate-all-component-changes [file]
  (-> file
      (tho/propagate-component-changes :icon-and-text)
      (tho/propagate-component-changes :panel)))

(defn- fill-colors [file]
  [(tho/bottom-fill-color file :frame-icon-and-text)
   (tho/bottom-fill-color file :frame-panel)
   (tho/bottom-fill-color file :copy-panel)])

(defn- validate [file validator]
  (validator file)
  file)

;; Related .penpot file: common/test/cases/swap-as-override.penpot
(t/deftest swap-main-then-copy
  (-> (setup)
      ;; Swap icon in icon+text main. Check that it propagates to copies.
      (tho/swap-component-in-shape :copy-component-1 :component-2 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#222222" "#222222" "#222222"])))

      ;; Change color of icon in icon+text main. Check that it propagates to copies.
      (tho/update-bottom-color :frame-icon-and-text "#333333" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#333333" "#333333" "#333333"])))

      ;; Swap icon inside panel main. Check it propagates to panel copy.
      (tho/swap-component-in-first-child :copy-icon-and-text :component-1 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#333333" "#111111" "#111111"])))

      ;; Change color of icon in icon+text. Check that it does not propagate. 
      (tho/update-bottom-color :frame-icon-and-text "#444444" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#444444" "#111111" "#111111"])))

      ;; Change color of icon in panel main. Check that it propagates.
      (tho/update-bottom-color :frame-panel "#555555" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#444444" "#555555" "#555555"])))))

(t/deftest swap-copy-then-main
  (-> (setup)
      ;; Swap icon inside panel main. Check that it propagates to panel copy.
      (tho/swap-component-in-first-child :copy-icon-and-text :component-2 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#111111" "#222222" "#222222"])))

      ;; Change color of icon in icon+text. Check that it does not propagate. 
      (tho/update-bottom-color :frame-icon-and-text "#333333" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#333333" "#222222" "#222222"])))

      ;;Change color of icon in panel main. Check that it propagates
      (tho/update-bottom-color :frame-panel "#444444" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#333333" "#444444" "#444444"])))

      ;; Swap icon in icon+text main. Check that it does not propagate.
      (tho/swap-component-in-shape :copy-component-1 :component-2 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#222222" "#444444" "#444444"])))

      ;; Change color of icon in icon+text. Check that it does not propagate.
      (tho/update-bottom-color :frame-icon-and-text "#555555" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#555555" "#444444" "#444444"])))))

(t/deftest swap-copy-then-2nd-copy
  (-> (setup)
      ;; Swap icon inside panel main. Check that it propagates to panel copy.
      (tho/swap-component-in-first-child :copy-icon-and-text :component-2 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#111111" "#222222" "#222222"])))

      ;; Swap icon inside panel copy.
      (tho/swap-component-in-first-child :copy-icon-and-text-panel :component-1 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#111111" "#222222" "#111111"])))

      ;; Change color of icon in icon+text. Check that it does not propagate.
      (tho/update-bottom-color :frame-icon-and-text "#333333" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#333333" "#222222" "#111111"])))

      ;; Change color of icon in panel main. Check that it does not propagate.
      (tho/update-bottom-color :frame-panel "#444444" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#333333" "#444444" "#111111"])))))

(t/deftest swap-2nd-copy-then-copy
  (-> (setup)
      ;; Swap icon inside panel copy
      (tho/swap-component-in-first-child :copy-icon-and-text-panel :component-2 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#111111" "#111111" "#222222"])))

      ;; Swap icon inside panel main. Check that it does not propagate.
      (tho/swap-component-in-first-child :copy-icon-and-text :component-3 :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#111111" "#333333" "#222222"])))

      ;; Change color of icon in icon+text. Check that it does not propagate. 
      (tho/update-bottom-color :frame-icon-and-text "#444444" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#444444" "#333333" "#222222"])))

      ;; Change color of icon in panel main. Check that it does not propagate.   
      (tho/update-bottom-color :frame-panel "#555555" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (fill-colors %) ["#444444" "#555555" "#222222"])))))
