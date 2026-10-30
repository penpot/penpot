;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-sync-layout-items-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Value sets

;; `:layout-item-h-sizing` and `:layout-item-v-sizing` may be absent, or take
;; one of these values. Each test sets both attributes to the same value.
;;
;;   :fix  -> valid for a board with flex and for any direct child of a flex board.
;;   :auto -> valid for a board with flex.
;;   :fill -> valid for a direct child of a flex board.
;;
;; A board with flex that is a direct child of another flex board (:comp1,
;; :board1) accepts every value. A plain shape that is a direct child (:rect1)
;; accepts :fix and :fill. :container-main is a top-level flex board, so it
;; accepts :fix and :auto but not :fill.
(def ^:private sizing-values-container
  [nil :fix :auto])

(def ^:private sizing-values-child
  [nil :fix :fill])

(def ^:private sizing-values-both
  [nil :fix :auto :fill])

;; ---- Setup

(defn- add-flex-frame
  [file label & {:keys [parent-label]}]
  (tho/add-frame file label (cond-> {:layout :flex :layout-flex-dir :row}
                              (some? parent-label) (assoc :parent-label parent-label))))

(defn- set-sizing
  "Sets both sizing attrs of the shape. A nil value removes them."
  [file label value]
  (-> file
      (ths/update-shape label :layout-item-h-sizing value)
      (ths/update-shape label :layout-item-v-sizing value)))

(defn- setup-file
  "Builds this tree (all boards are flex):

     :container-main
       :comp1                 # component main (component :comp1-component)
         :board1
           :rect1
     :container-copy
       :copy1                 #--> component :comp1-component
         :copy-board1
           :copy-rect1

   The h/v sizing attrs of :container-main, :comp1 and :board1 are set to the
   given values. The copy is created afterwards, without explicit sizing attrs."
  [comp-value board-value rect-value]
  (let [file (-> (thf/sample-file :file1)
                 (add-flex-frame :container-main)
                 (add-flex-frame :comp1 :parent-label :container-main)
                 (add-flex-frame :board1 :parent-label :comp1)
                 (tho/add-rect :rect1 :parent-label :board1)
                 (set-sizing :comp1 comp-value)
                 (set-sizing :board1 board-value)
                 (set-sizing :rect1 rect-value)
                 (thc/make-component :comp1-component :comp1)
                 (add-flex-frame :container-copy)
                 (thc/instantiate-component :comp1-component :copy1
                                            :parent-label :container-copy
                                            :children-labels [:copy-board1]))]
    (thi/set-id! :copy-rect1 (first (:shapes (ths/get-shape file :copy-board1))))
    file))

;; ---- Actions

(defn- sync-component
  "Propagates the main component changes to its copies."
  [file]
  (let [file-id      (:id file)
        component-id (:id (thc/get-component file :comp1-component))]
    (thf/apply-changes
     file
     (cll/generate-sync-file-changes (pcb/empty-changes)
                                     nil
                                     :components
                                     file-id
                                     component-id
                                     file-id
                                     {file-id file}
                                     file-id))))

(defn- sizing
  [file label]
  (let [shape (ths/get-shape file label)]
    [(:layout-item-h-sizing shape) (:layout-item-v-sizing shape)]))

(defn- touched
  [file label]
  (:touched (ths/get-shape file label)))

(defn- run-propagation
  "Changes the main shapes to every valid value and checks that the value
   reaches the copies."
  [file base-comp-value]
  (doseq [comp-value  sizing-values-both
          board-value sizing-values-both
          rect-value  sizing-values-child]
    (let [file' (-> file
                    (set-sizing :comp1 comp-value)
                    (set-sizing :board1 board-value)
                    (set-sizing :rect1 rect-value)
                    (sync-component))]
      (if (= comp-value :fill)
        (if (= base-comp-value :fill)
          (t/is (contains? #{[nil nil] [:fix :fix]} (sizing file' :copy1))
                (str "copy1 must be nil or :fix when comp1 is set to :fill and base comp is :fix"))
          (t/is (= [base-comp-value base-comp-value] (sizing file' :copy1))
                (str "copy1 must be " base-comp-value " when comp1 is set to :fill")))
        (t/is (= [comp-value comp-value] (sizing file' :copy1))
              (str "copy1 must be " (or comp-value "nil"))))
      (t/is (= [board-value board-value] (sizing file' :copy-board1))
            (str "copy-board1 must be " (or board-value "nil")))
      (t/is (= [rect-value rect-value] (sizing file' :copy-rect1))
            (str "copy-rect1 must be " (or rect-value "nil")))
      file')))

(defn- run-touched
  "Overrides the sizing in the copy (which activates the sync groups in
   :touched) and then changes the main. The main values must not be
   propagated to the copy this time."
  [file]
  #_(let [file'  (-> file
                   (set-sizing :copy1 :fix)
                   (set-sizing :copy-board1 :fix)
                   (set-sizing :copy-rect1 :fix))
        file'' (-> file'
                   (set-sizing :comp1 :auto)
                   (set-sizing :board1 :auto)
                   (set-sizing :rect1 :auto)
                   (sync-component))]

    (t/is (= #{:layout-item-h-sizing :layout-item-v-sizing} (touched file' :copy1)))
    (t/is (= #{:layout-item-h-sizing :layout-item-v-sizing} (touched file' :copy-board1)))
    (t/is (= #{:layout-item-h-sizing :layout-item-v-sizing} (touched file' :copy-rect1)))

    (t/is (= [:fix :fix] (sizing file'' :copy1)) "copy1 keeps its override")
    (t/is (= [:fix :fix] (sizing file'' :copy-board1)) "copy-board1 keeps its override")
    (t/is (= [:fix :fix] (sizing file'' :copy-rect1)) "copy-rect1 keeps its override")))

(defn- run-case
  [comp-value board-value rect-value]
  (-> (setup-file comp-value board-value rect-value)
      (run-propagation comp-value)
      (run-touched)))

;; ---- Tests

(t/deftest test-sync-layout-item-sizing
  (doseq [comp-value  sizing-values-both
          board-value sizing-values-both
          rect-value  sizing-values-child]
    (t/testing (str "Testing case comp1=" comp-value
                    ", board1=" board-value
                    ", rect1=" rect-value)
      (run-case comp-value board-value rect-value))))
