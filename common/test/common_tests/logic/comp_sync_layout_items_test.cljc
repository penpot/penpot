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

;; Cases for the touched tests: [copy-value main-before main-after].
;; `main-before` differs from `copy-value`, so setting the copy activates the
;; sync group in :touched. `main-after` is a value the root copy would receive
;; if it were not touched.
(def ^:private root-touched-cases
  [[nil    :fill :auto]
   [:fix   :fill :auto]
   [:auto  :fill :fix]
   [:fill  :auto :fix]])

(def ^:private child-touched-cases
  [[nil    :fill :fill]
   [:fix   :fill :fill]
   [:fill  nil   :fix]])

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

(defn- set-sizing-untouched
  "Sets both sizing attrs of the shape without marking it as touched."
  [file label value]
  (tho/change-shape
   file label
   (fn [shape]
     (cond-> shape
       (some? value) (assoc :layout-item-h-sizing value :layout-item-v-sizing value)
       (nil? value)  (dissoc :layout-item-h-sizing :layout-item-v-sizing)))
   :ignore-touched true))

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

(defn- normalize-sizing
  "`:fix` is the default sizing, so it is equivalent to the attribute being
   absent. Both forms are accepted in the checks."
  [value]
  (if (= value :fix) nil value))

(defn- check-sizing
  [file label expected]
  (t/is (= (mapv normalize-sizing [expected expected])
           (mapv normalize-sizing (sizing file label)))
        (str label " must be " (pr-str expected))))

(defn- expected-root-copy-sizing
  "Sizing the copy of the component root is expected to have after a sync.
   The root copy only receives `:auto` and `:fix`. `:fill` is never propagated
   from the root, and neither is the absence of the attribute, so in those
   cases the copy keeps the value it had before the propagation."
  [comp-value previous]
  (case comp-value
    :auto :auto
    :fix  :fix
    previous))

(defn- run-propagation
  "Changes the main shapes to every valid value and checks that the value
   reaches the copies."
  [file]
  (let [initial (first (sizing file :copy1))]
    (doseq [comp-value  sizing-values-both
            board-value sizing-values-both
            rect-value  sizing-values-child]
      (let [file' (-> file
                      (set-sizing :comp1 comp-value)
                      (set-sizing :board1 board-value)
                      (set-sizing :rect1 rect-value)
                      (sync-component))]
        (check-sizing file' :copy1 (expected-root-copy-sizing comp-value initial))
        (check-sizing file' :copy-board1 board-value)
        (check-sizing file' :copy-rect1 rect-value)))
    file))

(defn- run-touched
  "Overrides the sizing in the copy with every valid value (which activates the
   sync groups in :touched) and then changes the main. The main values must not
   be propagated to the copy this time."
  [file]
  (doseq [[label main-label cases]
          [[:copy1       :comp1  root-touched-cases]
           [:copy-board1 :board1 root-touched-cases]
           [:copy-rect1  :rect1  child-touched-cases]]
          [value before after] cases]
    (let [file'  (-> file
                     ;; Align the copy with the main, then override it with a
                     ;; different value so the override activates :touched.
                     (set-sizing main-label before)
                     (set-sizing-untouched label before)
                     (set-sizing label value))
          _      (t/is (not= (normalize-sizing value)
                             (normalize-sizing (first (sizing file' main-label))))
                       (str label " value must differ from " main-label))
          _      (t/is (= #{:layout-item-h-sizing :layout-item-v-sizing} (touched file' label))
                       (str label " must be touched after setting " (pr-str value)))
          ;; Changing the main must not reach the copy.
          file'' (-> file'
                     (set-sizing main-label after)
                     (sync-component))]
      (check-sizing file'' label value))))

(defn- run-case
  [comp-value board-value rect-value]
  (-> (setup-file comp-value board-value rect-value)
      (run-propagation)
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
