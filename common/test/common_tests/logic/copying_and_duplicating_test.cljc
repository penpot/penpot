;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.copying-and-duplicating-test
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
      (tho/add-simple-component :simple-1 :frame-simple-1 :rect-simple-1
                                :child-params {:type :rect :fills (ths/sample-fills-color :fill-color "#2152e5") :name "rect-simple-1"})

      (tho/add-frame :frame-composed-1 :name "frame-composed-1")
      (thc/instantiate-component :simple-1 :copy-simple-1 :parent-label :frame-composed-1 :children-labels [:composed-1-simple-1])
      (ths/add-sample-shape :rect-composed-1 :parent-label :frame-composed-1 :fills (ths/sample-fills-color :fill-color "#B1B2B5"))
      (thc/make-component :composed-1 :frame-composed-1)

      (tho/add-frame :frame-composed-2 :name "frame-composed-2")
      (thc/instantiate-component :composed-1 :copy-composed-1-composed-2 :parent-label :frame-composed-2 :children-labels [:composed-1-composed-2])
      (thc/make-component :composed-2 :frame-composed-2)

      (thc/instantiate-component :composed-2 :copy-composed-2)

      (tho/add-frame :frame-composed-3 :name "frame-composed-3")
      (tho/add-group :group-3 :parent-label :frame-composed-3)
      (thc/instantiate-component :composed-2 :copy-composed-1-composed-3 :parent-label :group-3 :children-labels [:composed-1-composed-2])
      (ths/add-sample-shape :circle-composed-3 :parent-label :group-3 :fills (ths/sample-fills-color :fill-color "#B1B2B5"))
      (thc/make-component :composed-3 :frame-composed-3)

      (thc/instantiate-component :composed-3 :copy-composed-3 :children-labels [:composed-2-composed-3])))

(defn- propagate-all-component-changes [file]
  (-> file
      (tho/propagate-component-changes :simple-1)
      (tho/propagate-component-changes :composed-1)
      (tho/propagate-component-changes :composed-2)
      (tho/propagate-component-changes :composed-3)))

(defn- count-shapes [file name color]
  (let [page (thf/current-page file)]
    (->> (vals (:objects page))
         (filter #(and
                   (= (:name %) name)
                   (-> (ths/get-shape-by-id file (:id %))
                       :fills
                       first
                       :fill-color
                       (= color))))
         (count))))

(defn- validate [file validator]
  (validator file)
  file)

;; Related .penpot file: common/test/cases/copying-and-duplicating.penpot
(t/deftest main-and-first-level-copy
  (-> (setup)
      ;; For each main and first level copy:
      ;; - Duplicate it two times.
      (tho/duplicate-shape :frame-simple-1)
      (tho/duplicate-shape :frame-simple-1)
      (tho/duplicate-shape :frame-composed-1)
      (tho/duplicate-shape :frame-composed-1)
      (tho/duplicate-shape :frame-composed-2)
      (tho/duplicate-shape :frame-composed-2)
      (tho/duplicate-shape :frame-composed-3)
      (tho/duplicate-shape :frame-composed-3)
      (tho/duplicate-shape :copy-composed-2)
      (tho/duplicate-shape :copy-composed-2)
      (tho/duplicate-shape :copy-composed-3)
      (tho/duplicate-shape :copy-composed-3)

      ;; - Change color of Simple1 and check propagation to all copies.
      (tho/update-bottom-color :frame-simple-1 "#111111" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#111111") 18)))
      ;; - Change color of the nearest main and check propagation to duplicated.
      (tho/update-bottom-color :frame-composed-1 "#222222" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#222222") 15)))
      (tho/update-bottom-color :frame-composed-2 "#333333" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#333333") 12)))
      (tho/update-bottom-color :frame-composed-3 "#444444" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#444444") 6)))))

(t/deftest copy-nested-in-main
  (-> (setup)
      ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
      ;; - Duplicate it two times, keeping the duplicated inside the same main.    
      (tho/duplicate-shape :copy-simple-1)
      (tho/duplicate-shape :copy-simple-1)
      (tho/duplicate-shape :group-3)
      (tho/duplicate-shape :group-3)

      ;; - Change color of Simple1 and check propagation to all copies.
      (tho/update-bottom-color :frame-simple-1 "#111111" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#111111") 28)))

      ;; - Change color of the nearest main and check propagation to duplicated.
      (tho/update-bottom-color :frame-composed-1 "#222222" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#222222") 9)))

      ;; - Change color of the copy you duplicated from, and check that it's NOT PROPAGATED.
      (tho/update-bottom-color :group-3 "#333333" :propagate-fn propagate-all-component-changes)
      (validate #(t/is (= (count-shapes % "rect-simple-1" "#333333") 2)))))
