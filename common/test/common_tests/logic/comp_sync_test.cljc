;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-sync-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-sync-when-changing-attribute
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1
                                                       :main-root
                                                       :main-child
                                                       :copy-root
                                                       :main-child-params {:fills (ths/sample-fills-color
                                                                                   :fill-color "#abcdef")}
                                                       :copy-root-params {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                                 (:objects page)
                                                 {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-sync-when-changing-attribute-from-library
  (let [;; ==== Setup
        library   (-> (thf/sample-file :file1)
                      (tho/add-simple-component :component1
                                                :main-root
                                                :main-child
                                                :copy-root
                                                :main-child-params {:fills (ths/sample-fills-color
                                                                            :fill-color "#abcdef")}))

        file       (-> (thf/sample-file :file)
                       (thc/instantiate-component :component1 :copy-root
                                                  :library library
                                                  :children-labels [:copy-child]))

        page       (thf/current-page library)
        main-child (ths/get-shape library :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                                 (:objects page)
                                                 {})

        updated-library (thf/apply-changes library changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id file)
                                                     (thi/id :component1)
                                                     (:id updated-library)
                                                     {(:id updated-library) updated-library
                                                      (:id file) file}
                                                     (:id file))

        file'     (thf/apply-changes file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-sync-when-changing-attribute-preserve-touched
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1
                                                       :main-root
                                                       :main-child
                                                       :copy-root
                                                       :main-child-params {:fills (ths/sample-fills-color
                                                                                   :fill-color "#abcdef")}
                                                       :copy-root-params {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes1     (-> (pcb/empty-changes nil (:id page))
                         (cls/generate-update-shapes
                          #{(:id copy-child)}
                          (fn [shape]
                            (assoc shape :fills (ths/sample-fills-color :fill-color "#aaaaaa")))
                          (:objects page)
                          {})
                         (cls/generate-update-shapes
                          #{(:id main-child)}
                          (fn [shape]
                            (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                          (:objects page)
                          {}))

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#aaaaaa"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:fill-group}))))

(t/deftest test-sync-when-adding-shape
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component-with-copy :component1
                                                      :main-root
                                                      :main-child
                                                      :copy-root
                                                      :copy-root-params {:children-labels [:copy-child]})
                  (ths/add-sample-shape :free-shape))

        page      (thf/current-page file)

        ;; ==== Action
        changes1 (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (thi/id :main-root)       ; parent-id
                                        0                         ; to-index
                                        #{(thi/id :free-shape)})   ; ids

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        main-free-shape'   (ths/get-shape file' :free-shape)
        copy-root'         (ths/get-shape file' :copy-root)
        copy-new-child-id' (d/seek #(not= % (thi/id :copy-child)) (:shapes copy-root'))
        copy-new-child'    (ths/get-shape-by-id file' copy-new-child-id')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-new-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-new-child') nil))
    (t/is (ctst/parent-of? copy-root' copy-new-child'))
    (t/is (ctk/is-main-of? main-free-shape' copy-new-child'))))

(t/deftest test-sync-when-deleting-shape
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :copy-root-params {:children-labels [:copy-child]}))

        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action

        ;; IMPORTANT: as modifying copies structure is now forbidden, this action will not
        ;; delete the child shape, but hide it (thus setting the visibility group).
        [_all-parents changes1]
        (cls/generate-delete-shapes (pcb/empty-changes)
                                    file
                                    page
                                    (:objects page)
                                    #{(:id main-child)}
                                    {:components-v2 true})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (nil? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (empty? (:shapes copy-root')))))

(t/deftest test-sync-when-moving-shape
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-component-with-many-children-and-copy :component1
                                                                :main-root
                                                                [:main-child1 :main-child2 :main-child3]
                                                                :copy-root
                                                                :copy-root-params {:children-labels [:copy-child1
                                                                                                     :copy-child2
                                                                                                     :copy-child3]})
                 (ths/add-sample-shape :free-shape))

        page (thf/current-page file)
        main-child1 (ths/get-shape file :main-child1)

        ;; ==== Action
        changes1     (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                (pcb/with-page-id (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (thi/id :main-root)         ; parent-id
                                            2                           ; to-index
                                            #{(:id main-child1)})       ; ids


        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'        (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'   (ths/get-shape file' :copy-root)
        copy-child1' (ths/get-shape file' :copy-child1)
        copy-child2' (ths/get-shape file' :copy-child2)
        copy-child3' (ths/get-shape file' :copy-child3)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child1'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child1') nil))
    (t/is (= (:touched copy-child2') nil))
    (t/is (= (:touched copy-child3') nil))
    (t/is (= (second (:shapes copy-root')) (:id copy-child1')))
    (t/is (= (first (:shapes copy-root')) (:id copy-child2')))
    (t/is (= (nth (:shapes copy-root') 2) (:id copy-child3')))))

(t/deftest test-sync-when-changing-upper
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-nested-component-with-copy :component1
                                                       :main1-root
                                                       :main1-child
                                                       :component2
                                                       :main2-root
                                                       :main2-nested-head
                                                       :copy2-root
                                                       :main2-root-params {:fills (ths/sample-fills-color
                                                                                   :fill-color "#abcdef")}))
        page       (thf/current-page file)
        main2-root (ths/get-shape file :main2-root)

        ;; ==== Action
        changes1  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id main2-root)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component2)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'        (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy2-root' (ths/get-shape file' :copy2-root)
        fills'      (:fills copy2-root')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))))

(t/deftest test-sync-when-changing-lower-near
  (let [;; ==== Setup
        file          (-> (thf/sample-file :file1)
                          (tho/add-nested-component-with-copy :component1
                                                              :main1-root
                                                              :main1-child
                                                              :component2
                                                              :main2-root
                                                              :main2-nested-head
                                                              :copy2-root
                                                              :copy2-root-params {:children-labels [:copy2-child]}))
        page              (thf/current-page file)
        main2-nested-head (ths/get-shape file :main2-nested-head)

        ;; ==== Action
        changes1  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id main2-nested-head)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component2)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'        (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy2-root'  (ths/get-shape file' :copy2-root)
        copy2-child' (ths/get-shape file' :copy2-child)
        fills'       (:fills copy2-child')
        fill'        (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (some? copy2-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))
    (t/is (= (:touched copy2-child') nil))))

(t/deftest test-sync-when-changing-lower-remote
  (let [;; ==== Setup
        file          (-> (thf/sample-file :file1)
                          (tho/add-nested-component-with-copy :component1
                                                              :main1-root
                                                              :main1-child
                                                              :component2
                                                              :main2-root
                                                              :main2-nested-head
                                                              :copy2-root
                                                              :copy2-root-params {:children-labels [:copy2-child]}))
        page              (thf/current-page file)
        main1-root (ths/get-shape file :main1-root)

        ;; ==== Action
        changes1  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id main1-root)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        synced-file (thf/apply-changes updated-file changes2)

        changes3     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id synced-file)
                                                     (thi/id :component2)
                                                     (:id synced-file)
                                                     {(:id synced-file) synced-file}
                                                     (:id synced-file))

        file'        (thf/apply-changes synced-file changes3)

        ;; ==== Get
        copy2-root'  (ths/get-shape file' :copy2-root)
        copy2-child' (ths/get-shape file' :copy2-child)
        fills'       (:fills copy2-child')
        fill'        (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (some? copy2-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))
    (t/is (= (:touched copy2-child') nil))))

(t/deftest test-no-sync-changes-when-only-position-changes
  ;; Regression: the library sync dialog was shown even when a library component
  ;; was only moved (x/y changed). Position changes are normalised by
  ;; reposition-shape during sync and never propagate to copies, so
  ;; generate-sync-file-changes must return empty :redo-changes in this case.
  (let [;; ==== Setup
        ;; Use integer width/height so that floating-point arithmetic of
        ;; move(+delta) followed by reposition(-delta) cancels out exactly.
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :main-root-params {:width 100 :height 100}
                                                           :main-child-params {:width 50 :height 50}))
        page       (thf/current-page file)
        main-root  (ths/get-shape file :main-root)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action
        ;; Move the entire main component (root + child) by a non-zero integer delta.
        ;; This is a position-only change: no fills, strokes or other
        ;; attributes are modified.
        delta      (gpt/point 100 150)
        changes1   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id main-root) (:id main-child)}
                                               (fn [shape] (gsh/move shape delta))
                                               (:objects page)
                                               {})

        updated-file (thf/apply-changes file changes1)

        ;; Run the full sync to check whether any real redo-changes are produced.
        ;; The fixed frontend code filters out libraries whose sync produces no
        ;; :redo-changes before showing the "library updated" notification.
        sync-changes (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))]

    ;; ==== Check
    ;; A position-only change in the main component must not propagate to copies
    ;; and therefore must produce no redo-changes.
    (t/is (empty? (:redo-changes sync-changes)))))

(defn- sync-file-changes
  [file]
  (cll/generate-sync-file-changes (pcb/empty-changes)
                                  nil
                                  :components
                                  (:id file)
                                  (thi/id :component1)
                                  (:id file)
                                  {(:id file) file}
                                  (:id file)))

(defn- changes-attrs
  "Set of attrs touched by the :mod-obj changes of a changes map."
  [changes]
  (->> (:redo-changes changes)
       (filter #(= :mod-obj (:type %)))
       (mapcat :operations)
       (map :attr)
       (into #{})))

(defn- file-with-path-component
  []
  (let [content [{:command :move-to :params {:x 10.3 :y 10.7}}
                 {:command :line-to :params {:x 24.3 :y 10.7}}
                 {:command :line-to :params {:x 17.3 :y 24.7}}
                 {:command :close-path}]]
    (-> (thf/sample-file :file1)
        (tho/add-frame :main-root {:width 107.3 :height 41.7})
        (ths/add-sample-shape :main-path :type :path :parent-label :main-root :content content)
        (thc/make-component :component1 :main-root)
        (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-path]}))))

(t/deftest test-no-sync-changes-when-position-changes-with-float-drift
  ;; Regression (#12719): geometry in production carries sub-pixel drift
  ;; and non-integer sizes, so move(+delta) followed by reposition(-delta)
  ;; does not cancel exactly. A position-only move of the main must still
  ;; produce no sync changes, and so no "library updated" notification.
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :main-root-params {:width 107.3 :height 41.7}
                                                           :main-child-params {:width 33.3 :height 17.7}
                                                           :copy-root-params {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        main-root  (ths/get-shape file :main-root)
        main-child (ths/get-shape file :main-child)
        copy-child (ths/get-shape file :copy-child)

        ;; Override on the copy, so it carries touched state
        changes0   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                               (:objects page)
                                               {})
        file       (thf/apply-changes file changes0)
        page       (thf/current-page file)

        ;; ==== Action
        ;; Move the whole main by 1 px plus sub-pixel noise
        delta      (gpt/point 1.0000123 0.0000071)
        changes1   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id main-root) (:id main-child)}
                                               (fn [shape] (gsh/move shape delta))
                                               (:objects page)
                                               {})

        updated-file (thf/apply-changes file changes1)

        sync-changes (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))]

    ;; ==== Check
    (t/is (empty? (:redo-changes sync-changes)))))

(t/deftest test-sync-size-change-of-main-child
  ;; The drift tolerance must not swallow real size edits (>> 0.001).
  (let [;; ==== Setup
        file         (-> (thf/sample-file :file1)
                         (tho/add-simple-component-with-copy :component1
                                                             :main-root
                                                             :main-child
                                                             :copy-root
                                                             :main-root-params {:width 107.3 :height 41.7}
                                                             :main-child-params {:width 33.3 :height 17.7}
                                                             :copy-root-params {:children-labels [:copy-child]}))
        page         (thf/current-page file)

        ;; ==== Action
        updated-file (thf/apply-changes file
                                        (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                                    #{(thi/id :main-child)}
                                                                    #(gsh/transform-shape % (ctm/change-size % (+ (:width %) 1) nil))
                                                                    (:objects page)
                                                                    {}))
        attrs        (changes-attrs (sync-file-changes updated-file))]

    ;; ==== Check
    (t/is (contains? attrs :width))
    (t/is (contains? attrs :selrect))))

(t/deftest test-sync-text-position-data-change-with-same-xy
  ;; A change in a text run (here its fill) that keeps x/y must still be
  ;; synced: position-data is not a drifting geometry attr.
  (let [;; ==== Setup
        run         (fn [color]
                      [{:x 0 :y 0 :width 50 :height 20 :text "hello"
                        :fills [{:fill-color color :fill-opacity 1}]}])
        file        (-> (thf/sample-file :file1)
                        (tho/add-frame-with-text :main-root :main-child "hello")
                        (thc/make-component :component1 :main-root)
                        (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        set-pd      (fn [file color]
                      (let [page       (thf/current-page file)
                            main-child (ths/get-shape file :main-child)]
                        (thf/apply-changes
                         file
                         (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                     #{(:id main-child)}
                                                     #(assoc % :position-data (run color))
                                                     (:objects page)
                                                     {}))))

        file1       (set-pd file "#000000")
        file2       (thf/apply-changes file1 (sync-file-changes file1))
        file3       (set-pd file2 "#ff0000")

        ;; ==== Action
        sync-changes (sync-file-changes file3)]

    ;; ==== Check
    (t/is (contains? (changes-attrs sync-changes) :position-data))))

(t/deftest test-sync-small-opacity-change
  ;; The drift tolerance must not swallow real non-geometry changes.
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :copy-root-params {:children-labels [:copy-child]}))
        set-opacity (fn [file opacity]
                      (let [page       (thf/current-page file)
                            main-child (ths/get-shape file :main-child)]
                        (thf/apply-changes
                         file
                         (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                     #{(:id main-child)}
                                                     #(assoc % :opacity opacity)
                                                     (:objects page)
                                                     {}))))

        file1      (set-opacity file 1)
        file2      (thf/apply-changes file1 (sync-file-changes file1))
        file3      (set-opacity file2 0.9995)

        ;; ==== Action
        sync-changes (sync-file-changes file3)]

    ;; ==== Check
    (t/is (contains? (changes-attrs sync-changes) :opacity))))

(t/deftest test-no-sync-changes-when-path-moves-with-float-drift
  ;; Path content stores absolute coordinates, so it drifts like the other
  ;; geometry when the main is moved.
  (let [;; ==== Setup
        file       (file-with-path-component)
        page       (thf/current-page file)
        main-root  (ths/get-shape file :main-root)
        main-path  (ths/get-shape file :main-path)

        ;; ==== Action
        changes1   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id main-root) (:id main-path)}
                                               #(gsh/move % (gpt/point 1.0000123 0.0000071))
                                               (:objects page)
                                               {})
        updated-file (thf/apply-changes file changes1)]

    ;; ==== Check
    (t/is (empty? (:redo-changes (sync-file-changes updated-file))))))

(t/deftest test-sync-path-moved-inside-main
  ;; Moving only the path inside its main is a real change and must sync.
  (let [;; ==== Setup
        file       (file-with-path-component)
        page       (thf/current-page file)
        main-path  (ths/get-shape file :main-path)

        ;; ==== Action
        changes1   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id main-path)}
                                               #(gsh/move % (gpt/point 1 0))
                                               (:objects page)
                                               {})
        updated-file (thf/apply-changes file changes1)]

    ;; ==== Check
    (t/is (contains? (changes-attrs (sync-file-changes updated-file)) :content))))

(defn- file-with-bool-component
  []
  (let [content [{:command :move-to :params {:x 10.3 :y 10.7}}
                 {:command :line-to :params {:x 24.3 :y 10.7}}
                 {:command :line-to :params {:x 17.3 :y 24.7}}
                 {:command :close-path}]]
    (-> (thf/sample-file :file1)
        (tho/add-frame :main-root {:width 107.3 :height 41.7})
        (ths/add-sample-shape :main-bool :type :bool :bool-type :union :parent-label :main-root :content content)
        (ths/add-sample-shape :main-p1 :type :path :parent-label :main-bool :content content)
        (ths/add-sample-shape :main-p2 :type :path :parent-label :main-bool :content content)
        (thc/make-component :component1 :main-root)
        (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-bool :copy-p1 :copy-p2]}))))

(defn- move-shapes
  [file labels delta]
  (let [page (thf/current-page file)]
    (thf/apply-changes file
                       (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                   (into #{} (map #(thi/id %)) labels)
                                                   #(gsh/move % delta)
                                                   (:objects page)
                                                   {}))))

(t/deftest test-no-sync-changes-when-text-moves-with-float-drift
  ;; Text position-data moves with the shape and drifts like geometry, so
  ;; a position-only move of the main must produce no sync changes.
  (let [;; ==== Setup
        position-data [{:x 10.3 :y 10.7 :width 33.3 :height 17.7 :text "hello"
                        :fills [{:fill-color "#000000" :fill-opacity 1}]}]
        file          (-> (thf/sample-file :file1)
                          (tho/add-frame-with-text :main-root :main-child "hello")
                          (thc/make-component :component1 :main-root)
                          (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page          (thf/current-page file)
        file          (thf/apply-changes file
                                         (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                                     #{(thi/id :main-child)}
                                                                     #(assoc % :position-data position-data)
                                                                     (:objects page)
                                                                     {}))
        file          (thf/apply-changes file (sync-file-changes file))

        ;; ==== Action
        updated-file  (move-shapes file [:main-root :main-child] (gpt/point 1.0000123 0.0000071))
        sync-changes  (sync-file-changes updated-file)]

    ;; ==== Check
    (t/is (= #{} (changes-attrs sync-changes)))
    (t/is (empty? (:redo-changes sync-changes)))))

(t/deftest test-sync-text-moved-inside-main
  ;; Moving only the text inside its main is a real change beyond the
  ;; tolerance and must sync its position-data.
  (let [;; ==== Setup
        position-data [{:x 10.3 :y 10.7 :width 33.3 :height 17.7 :text "hello"
                        :fills [{:fill-color "#000000" :fill-opacity 1}]}]
        file          (-> (thf/sample-file :file1)
                          (tho/add-frame-with-text :main-root :main-child "hello")
                          (thc/make-component :component1 :main-root)
                          (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page          (thf/current-page file)
        file          (thf/apply-changes file
                                         (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                                     #{(thi/id :main-child)}
                                                                     #(assoc % :position-data position-data)
                                                                     (:objects page)
                                                                     {}))
        file          (thf/apply-changes file (sync-file-changes file))

        ;; ==== Action
        updated-file  (move-shapes file [:main-child] (gpt/point 5 0))]

    ;; ==== Check
    (t/is (contains? (changes-attrs (sync-file-changes updated-file)) :position-data))))

(t/deftest test-sync-text-position-data-entry-count-change
  ;; A text that gains a line keeps its first entry untouched but has one
  ;; more entry: a length mismatch is a real change and must sync.
  (let [;; ==== Setup
        entry         {:x 10.3 :y 10.7 :width 33.3 :height 17.7 :text "hello"
                       :fills [{:fill-color "#000000" :fill-opacity 1}]}
        set-pd        (fn [file position-data]
                        (let [page (thf/current-page file)]
                          (thf/apply-changes file
                                             (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                                         #{(thi/id :main-child)}
                                                                         #(assoc % :position-data position-data)
                                                                         (:objects page)
                                                                         {}))))
        file          (-> (thf/sample-file :file1)
                          (tho/add-frame-with-text :main-root :main-child "hello")
                          (thc/make-component :component1 :main-root)
                          (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        file          (set-pd file [entry])
        file          (thf/apply-changes file (sync-file-changes file))

        ;; ==== Action
        updated-file  (set-pd file [entry (assoc entry :y 28.7 :text "world")])]

    ;; ==== Check
    (t/is (contains? (changes-attrs (sync-file-changes updated-file)) :position-data))))

(t/deftest test-no-sync-changes-when-bool-moves-with-float-drift
  ;; Bool content stores absolute coordinates, so it drifts like path content.
  (let [;; ==== Action
        file         (file-with-bool-component)
        updated-file (move-shapes file
                                  [:main-root :main-bool :main-p1 :main-p2]
                                  (gpt/point 1.0000123 0.0000071))
        sync-changes (sync-file-changes updated-file)]

    ;; ==== Check
    (t/is (= #{} (changes-attrs sync-changes)))
    (t/is (empty? (:redo-changes sync-changes)))))

(t/deftest test-sync-bool-content-change-inside-main
  ;; Moving only the bool inside its main is a real change and must sync.
  (let [;; ==== Action
        file         (file-with-bool-component)
        updated-file (move-shapes file [:main-bool :main-p1 :main-p2] (gpt/point 1 0))]

    ;; ==== Check
    (t/is (contains? (changes-attrs (sync-file-changes updated-file)) :content))))

(t/deftest test-sync-many-updated-shapes
  ;; The undo changes of a sync used to grow one lazy level per updated
  ;; shape (#12576). Overflowing the stack needs thousands of shapes, which
  ;; is too slow here; test-update-attr-changes-many-calls covers that.
  (let [;; ==== Setup
        n            500
        child-labels (mapv #(keyword (str "main-child-" %)) (range n))
        file         (-> (thf/sample-file :file1)
                         (tho/add-component-with-many-children-and-copy
                          :component1
                          :main-root
                          child-labels
                          :copy-root
                          :main-child-params-list (repeat n {:fills (ths/sample-fills-color
                                                                     :fill-color "#abcdef")})))
        page         (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 (into #{} (map thi/id) child-labels)
                                                 (fn [shape]
                                                   (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                                 (:objects page)
                                                 {})
        updated-file (thf/apply-changes file changes1)
        changes2     (sync-file-changes updated-file)
        file'        (thf/apply-changes updated-file changes2)
        file''       (thf/apply-undo-changes file' changes2)

        ;; ==== Get
        copy-fill    (fn [file]
                       (let [copy-root (ths/get-shape file :copy-root)]
                         (into #{}
                               (map #(-> (ths/get-shape-by-id file %) :fills first :fill-color))
                               (:shapes copy-root))))]

    ;; ==== Check
    (t/is (= #{"#fabada"} (copy-fill file')))
    (t/is (= #{"#abcdef"} (copy-fill file'')))))

(t/deftest test-update-attr-changes-many-calls
  ;; Regression: each call nested the undo list in one more lazy concat, so
  ;; walking it after many calls overflowed the stack (#12576).
  (let [;; ==== Setup
        file        (-> (thf/sample-file :file1)
                        (tho/add-rect :rect1))
        page        (thf/current-page file)
        rect        (ths/get-shape file :rect1)
        roperations [{:type :set :attr :opacity :val 0.5 :ignore-touched true}]
        uoperations (list {:type :set :attr :opacity :val 1 :ignore-touched true})

        ;; ==== Action
        changes     (reduce (fn [changes _]
                              (#'cll/add-update-attr-changes changes rect page roperations uoperations))
                            (pcb/empty-changes)
                            (range 50000))
        undo        (:undo-changes changes)]

    ;; ==== Check
    (t/is (= 100000 (count undo)))
    ;; Undo restores the shape first and then recalculates its parents.
    (t/is (= [:mod-obj :reg-objects] (map :type (take 2 undo))))))

(defn- geometry
  [shape]
  ;; Compare as doubles: a recalculated selrect has doubles where the
  ;; original one may have longs.
  {:selrect (update-vals (select-keys (:selrect shape) [:x :y :width :height]) double)
   :points  (mapv #(update-vals (select-keys % [:x :y]) double) (:points shape))})

(defn- add-group-component-with-copy
  [file]
  ;; Generated shape tree:
  ;; :main-root [:name Frame1]                    # [Component :component1]
  ;;     :main-group [:name Group1]
  ;;         :main-child1 [:name Rect1]
  ;;         :main-child2 [:name Rect2]
  ;;
  ;; :copy-root [:name Frame1]                    #--> [Component :component1] :main-root
  ;;     :copy-group [:name Group1]               ---> :main-group
  ;;         :copy-child1 [:name Rect1]           ---> :main-child1
  ;;         :copy-child2 [:name Rect2]           ---> :main-child2
  (-> file
      (tho/add-frame :main-root :x 0 :y 0 :width 100 :height 100)
      (tho/add-group :main-group :parent-label :main-root :x 10 :y 10 :width 30 :height 10)
      (ths/add-sample-shape :main-child1 :parent-label :main-group :name "Rect1"
                            :x 10 :y 10 :width 10 :height 10
                            :fills (ths/sample-fills-color :fill-color "#abcdef"))
      (ths/add-sample-shape :main-child2 :parent-label :main-group :name "Rect2"
                            :x 30 :y 10 :width 10 :height 10
                            :fills (ths/sample-fills-color :fill-color "#abcdef"))
      (thc/make-component :component1 :main-root)
      (thc/instantiate-component :component1 :copy-root
                                 :children-labels [:copy-group :copy-child1 :copy-child2])))

(defn- touch-copy-group-geometry
  ;; With its geometry touched, the sync does not copy the group's own
  ;; geometry from the main, so only the parents recalculation decides it.
  [file]
  (reduce #(ths/update-shape %1 %2 :touched #{:geometry-group})
          file
          [:copy-group :copy-child1 :copy-child2]))

(defn- update-fill
  [file label color]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            #(assoc % :fills (ths/sample-fills-color :fill-color color))
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes)))

(t/deftest test-sync-undo-restores-group-geometry
  (let [;; ==== Setup
        file         (add-group-component-with-copy (thf/sample-file :file1))
        page         (thf/current-page file)
        copy-group   (ths/get-shape file :copy-group)

        ;; ==== Action
        changes1     (-> (pcb/empty-changes nil (:id page))
                         (pcb/with-objects (:objects page))
                         (cls/generate-update-shapes #{(thi/id :main-child2)}
                                                     #(gsh/absolute-move % (gpt/point 60 60))
                                                     (:objects page)
                                                     {})
                         (pcb/resize-parents [(thi/id :main-child2)]))
        updated-file (thf/apply-changes file changes1)
        changes2     (sync-file-changes updated-file)
        file'        (thf/apply-changes updated-file changes2)
        file''       (thf/apply-undo-changes file' changes2)

        ;; ==== Get
        copy-group'  (ths/get-shape file' :copy-group)
        copy-group'' (ths/get-shape file'' :copy-group)]

    ;; ==== Check
    (t/is (not= (geometry copy-group) (geometry copy-group')))
    (t/is (= (geometry copy-group) (geometry copy-group'')))))

(t/deftest test-sync-undo-group-after-add-and-update
  (let [;; ==== Setup
        file         (-> (thf/sample-file :file1)
                         (add-group-component-with-copy)
                         (touch-copy-group-geometry)
                         (ths/add-sample-shape :free-shape :x 60 :y 60 :width 10 :height 10))
        page         (thf/current-page file)
        copy-group   (ths/get-shape file :copy-group)

        ;; ==== Action
        ;; The new shape goes first in the group, so the sync handles it before
        ;; the updated sibling.
        changes1     (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                (pcb/with-page-id (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (thi/id :main-group)
                                            0
                                            #{(thi/id :free-shape)})
        updated-file (-> (thf/apply-changes file changes1)
                         (update-fill :main-child1 "#fabada"))
        changes2     (sync-file-changes updated-file)
        file'        (thf/apply-changes updated-file changes2)
        file''       (thf/apply-undo-changes file' changes2)

        ;; ==== Get
        copy-group'  (ths/get-shape file' :copy-group)
        copy-group'' (ths/get-shape file'' :copy-group)]

    ;; ==== Check
    (t/is (= 3 (count (:shapes copy-group'))))
    (t/is (not= (geometry copy-group) (geometry copy-group')))
    (t/is (= (:shapes copy-group) (:shapes copy-group'')))
    (t/is (= (geometry copy-group) (geometry copy-group'')))))

(t/deftest test-sync-undo-group-after-remove-and-update
  (let [;; ==== Setup
        file         (-> (thf/sample-file :file1)
                         (add-group-component-with-copy)
                         (touch-copy-group-geometry))
        page         (thf/current-page file)
        copy-group   (ths/get-shape file :copy-group)

        ;; ==== Action
        ;; The removed shape is the first one in the group, so the sync
        ;; handles it before the updated sibling. Remove it only from the
        ;; main, as a library update does, so that the sync removes it from
        ;; the copy.
        changes1     (-> (pcb/empty-changes nil (:id page))
                         (pcb/with-objects (:objects page))
                         (pcb/remove-objects #{(thi/id :main-child1)}))
        updated-file (-> (thf/apply-changes file changes1)
                         (update-fill :main-child2 "#fabada"))
        changes2     (sync-file-changes updated-file)
        file'        (thf/apply-changes updated-file changes2)
        file''       (thf/apply-undo-changes file' changes2)

        ;; ==== Get
        copy-group'  (ths/get-shape file' :copy-group)
        copy-group'' (ths/get-shape file'' :copy-group)]

    ;; ==== Check
    (t/is (= 1 (count (:shapes copy-group'))))
    (t/is (not= (geometry copy-group) (geometry copy-group')))
    (t/is (= (:shapes copy-group) (:shapes copy-group'')))
    (t/is (= (geometry copy-group) (geometry copy-group'')))))

(t/deftest test-sync-inverse-undo-group-after-add-and-update
  (let [;; ==== Setup
        ;; Penpot does not let users move shapes into a copy, but files can
        ;; still hold copies with shapes the main lacks, so build the data
        ;; directly. The new shape goes first in the copy group, so the
        ;; inverse sync handles it before the updated sibling.
        file         (-> (thf/sample-file :file1)
                         (add-group-component-with-copy)
                         (ths/add-sample-shape :free-shape :parent-label :copy-group
                                               :x 160 :y 160 :width 10 :height 10)
                         (ths/update-shape :copy-group :shapes [(thi/id :free-shape)
                                                                (thi/id :copy-child1)
                                                                (thi/id :copy-child2)]))
        main-group   (ths/get-shape file :main-group)

        ;; ==== Action
        updated-file (update-fill file :copy-child1 "#fabada")
        page         (thf/current-page updated-file)
        container    (ctn/make-container page :page)
        changes      (-> (pcb/empty-changes)
                         (pcb/with-container container)
                         (cll/generate-sync-shape-inverse (:data updated-file)
                                                          {(:id updated-file) updated-file}
                                                          container
                                                          (thi/id :copy-root)))
        file'        (thf/apply-changes updated-file changes)
        file''       (thf/apply-undo-changes file' changes)

        ;; ==== Get
        main-group'  (ths/get-shape file' :main-group)
        main-group'' (ths/get-shape file'' :main-group)]

    ;; ==== Check
    (t/is (= 3 (count (:shapes main-group'))))
    (t/is (not= (geometry main-group) (geometry main-group')))
    (t/is (= (:shapes main-group) (:shapes main-group'')))
    (t/is (= (geometry main-group) (geometry main-group'')))))
