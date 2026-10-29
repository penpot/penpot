;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-sync-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes :as ch]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest grid-growth-fills-inherited-tracks-before-assigning-copy-children
  (doseq [direction [:row :column]
          turn-copy? [false true]]
    (let [initial   (cg/world [1 0 {:layout :grid :layout-children true :grid-square true}])
          main      (n/main-head-of "random-0")
          copy      (n/nth-copy-head-of "random-0" 0)
          other     (n/nth-copy-head-of "random-0" 1)
          snapshot  #(uc/snapshot (tm/file %))
          ops       (concat (when (= direction :column) [(n/edit-grid main :direction :column)])
                            [(n/edit-grid copy :cell {:row 1 :column 1 :props {:align-self :end}})]
                            (when turn-copy? [(n/edit-grid copy :direction (if (= direction :row) :column :row))])
                            [(n/add-child main :extra-a :width 20 :height 20)
                             (n/add-child main :extra-b :width 20 :height 20)])
          grown     (reduce (fn [before op]
                              (let [after  (-> before (r/step op) r/settle)
                                    undone (r/step after (n/undo))
                                    redone (r/step undone (n/redo))]
                                (t/is (< (r/undo-depth before) (r/undo-depth after)))
                                (t/is (uc/same-state? (snapshot before) (snapshot undone)))
                                (t/is (uc/same-state? (snapshot after) (snapshot redone)))
                                after)) initial ops)
          shapes    (mapv #(tm/shape-by-id grown (% grown)) [main copy other])
          dims      (if (= direction :row) [2 3] [3 2])
          coords    (set (for [row (range 1 (inc (first dims)))
                               column (range 1 (inc (second dims)))] [row column]))
          objects   (:objects (thf/current-page (tm/file grown)))
          main-ids  (set (:shapes (first shapes)))
          component (:component-id (first shapes))
          synced    (r/settle grown #{component})]
      (doseq [shape shapes]
        (t/is (= dims (mapv #(count (get shape %)) [:layout-grid-rows :layout-grid-columns])))
        (t/is (= coords (set (map (juxt :row :column) (vals (:layout-grid-cells shape))))))
        (t/is (= main-ids (set (map #(or (:shape-ref (get objects %)) %)
                                    (mapcat :shapes (vals (:layout-grid-cells shape)))))))
        (t/is (= (frequencies main-ids)
                 (frequencies (map #(or (:shape-ref (get objects %)) %)
                                   (mapcat :shapes (vals (:layout-grid-cells shape))))))))
      (t/is (= :end (:align-self (first (filter #(= [1 1] [(:row %) (:column %)])
                                                (vals (:layout-grid-cells (second shapes))))))))
      (t/is (= (cond-> #{:layout-grid-cells} turn-copy? (conj :layout-grid-dir))
               (:touched (second shapes))))
      (t/is (empty? (:touched (nth shapes 2))))
      (t/is (uc/same-state? (snapshot grown) (snapshot synced))))))

(t/deftest sync-keeps-a-real-subpixel-position-change
  (let [initial (setup/simple-component-with-labeled-copy)
        original (tm/resolve-shape initial :copy-child)
        file (tm/file initial)
        page (thf/current-page file)
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page)) [(thi/id :main-child)]
                 #(gsh/move % (gpt/point 0.000001 0)) (:objects page) {})
        source (thf/apply-changes file changes)
        changed (tm/with-file initial (:file (r/sync-cascade source {} (keys (get-in file [:data :components])))))
        actual (tm/resolve-shape changed :copy-child)
        expected (gsh/move original (gpt/point 0.000001 0))]
    (t/is (not= (:points original) (:points actual)))
    (t/is (gsh/close-attrs? :points (:points expected) (:points actual) 1e-10))))

(t/deftest grid-sync-keeps-new-main-cells
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-grid-frame :main-root :width 100 :height 100)
                 (tho/add-rect :main-child :parent-label :main-root)
                 (tho/place-in-grid-columns :main-root 1 {1 :main-child})
                 (thc/make-component :component1 :main-root)
                 (thc/instantiate-component :component1 :copy-root))
        initial (tm/make-situation file {})
        added (-> initial
                  (r/step (n/add-child :main-root :new-child :width 20 :height 20))
                  r/settle)
        cells (fn [s label copy?]
                (let [objects (:objects (thf/current-page (tm/file s)))
                      root (tm/resolve-shape s label)]
                  (into {} (map (fn [[id cell]]
                                  [id (cond-> cell copy?
                                              (update :shapes #(mapv (fn [id] (:shape-ref (get objects id))) %)))]))
                        (:layout-grid-cells root))))]
    (doseq [s [added]]
      (t/is (= (cells s :main-root false) (cells s :copy-root true)))
      (t/is (nil? (:touched (tm/resolve-shape s :copy-root)))))
    (let [undone (r/step added (n/undo))
          redone (r/step undone (n/redo))]
      (t/is (= (thn/normalize-file (tm/file initial)) (thn/normalize-file (tm/file undone)))
            (pr-str (thn/diff (thn/normalize-file (tm/file initial)) (thn/normalize-file (tm/file undone)))))
      (t/is (= (thn/normalize-file (tm/file added)) (thn/normalize-file (tm/file redone)))
            (pr-str (thn/diff (thn/normalize-file (tm/file added)) (thn/normalize-file (tm/file redone))))))))

(t/deftest grid-sync-keeps-authored-placement-until-reset
  (let [initial (setup/grid-component-with-copy)
        moved (-> initial (r/step (n/move-to-cell :grid-copy :copy-child1 1 3)) r/settle)
        edited (-> moved (r/step (n/change-property :grid-root :opacity 0.4)) r/settle)
        reset (-> edited (r/step (n/reset :grid-copy)) r/settle)
        cells #(get (tm/resolve-shape % :grid-copy) :layout-grid-cells)
        copy-membership (fn [s]
                          (let [root (tm/resolve-shape s :grid-copy)
                                objects (:objects (thf/current-page (tm/file s)))]
                            (update-vals (:layout-grid-cells root)
                                         #(update % :shapes (fn [ids] (mapv (fn [id] (:shape-ref (get objects id))) ids))))))]
    (t/is (contains? (:touched (tm/resolve-shape moved :grid-copy)) :layout-grid-cells))
    (t/is (= (cells moved) (cells edited)))
    (t/is (= (:layout-grid-cells (tm/resolve-shape reset :grid-root))
             (copy-membership reset)))
    (t/is (nil? (:touched (tm/resolve-shape reset :grid-copy))))))

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

(defn- reorder-main-and-sync
  "A main with six children, and its copy; the main moves the children
   `ids` to `to-index`, then syncs. Returns `[file-before file-after
   sync-changes]`."
  [labels to-index]
  (let [main-labels [:m1 :m2 :m3 :m4 :m5 :m6]
        file        (-> (thf/sample-file :file1)
                        (tho/add-component-with-many-children-and-copy
                         :component1 :main-root main-labels :copy-root
                         :copy-root-params {:children-labels [:c1 :c2 :c3 :c4 :c5 :c6]}))
        page        (thf/current-page file)
        relocate    (cls/generate-relocate (-> (pcb/empty-changes nil)
                                               (pcb/with-page-id (:id page))
                                               (pcb/with-objects (:objects page)))
                                           (thi/id :main-root)
                                           to-index
                                           (into #{} (map thi/id) labels))
        moved       (thf/apply-changes file relocate)
        sync        (cll/generate-sync-file-changes (pcb/empty-changes)
                                                    nil
                                                    :components
                                                    (:id moved)
                                                    (thi/id :component1)
                                                    (:id moved)
                                                    {(:id moved) moved}
                                                    (:id moved))]
    [moved (thf/apply-changes moved sync) sync]))

(defn- copy-order
  [file]
  (mapv #(:shape-ref (ths/get-shape-by-id file %)) (:shapes (ths/get-shape file :copy-root))))

(defn- moves
  [changes]
  (count (filter #(= :mov-objects (:type %)) (:redo-changes changes))))

(t/deftest test-sync-of-a-reorder-moves-each-displaced-child-once
  (doseq [[labels to-index displaced] [[[:m1] 6 1]   ; first to the end
                                       [[:m6] 0 1]   ; last to the start
                                       [[:m2] 5 1]   ; one to a later place
                                       [[:m5] 1 1]   ; one to an earlier place
                                       [[:m1 :m2] 6 2]]]
    (let [[moved synced sync] (reorder-main-and-sync labels to-index)
          main-order          (:shapes (ths/get-shape moved :main-root))]
      (t/testing (str labels " to " to-index)
        (t/is (= main-order (copy-order synced)))
        (t/is (= displaced (moves sync)))
        (t/testing "undo puts the copy's children back"
          (t/is (= (copy-order moved) (copy-order (thf/apply-undo-changes synced sync)))))))))

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

(t/deftest test-update-main-from-a-copy-with-reordered-children
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-component-with-many-children-and-copy :component1
                                                                   :main-root
                                                                   [:main-child1 :main-child2]
                                                                   :copy-root
                                                                   :copy-root-params {:children-labels [:copy-child1 :copy-child2]}))
        page    (thf/current-page file)

        ;; The UI never reorders the children of a copy, so the order is
        ;; set directly: the copy holds them in the reverse order of the main.
        file    (update-in file [:data :pages-index (:id page) :objects (thi/id :copy-root) :shapes]
                           (comp vec reverse))

        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id :copy-child2)}
                                            #(assoc % :hidden true)
                                            (:objects page)
                                            {})
        file    (thf/apply-changes file changes)

        ;; ==== Action
        page      (thf/current-page file)
        container (ctn/make-container page :page)
        changes   (-> (pcb/empty-changes)
                      (pcb/with-container container)
                      (cll/generate-sync-shape-inverse {(:id file) file}
                                                       container
                                                       (thi/id :copy-root)))
        file'     (thf/apply-changes file changes)

        ;; ==== Get
        main-root'   (ths/get-shape file' :main-root)
        main-child2' (ths/get-shape file' :main-child2)]

    ;; ==== Check
    (t/is (true? (:hidden main-child2')))
    (t/is (= [(thi/id :main-child2) (thi/id :main-child1)] (:shapes main-root')))))

(t/deftest test-update-main-of-a-swap-into-a-main-on-another-page
  ;; component2's main, on its own page, holds a copy of component1 inside a
  ;; group; its copy, on another page, swaps that copy for a bigger component3
  ;; and pushes it to the main
  (let [file      (-> (thf/sample-file :file1 :page-label :main-page)
                      (tho/add-simple-component :c1 :main1 :main1-child
                                                :root-params {:width 10 :height 10}
                                                :child-params {:width 10 :height 10})
                      (tho/add-simple-component :c3 :main3 :main3-child
                                                :root-params {:width 80 :height 80}
                                                :child-params {:width 80 :height 80})
                      (tho/add-frame :main2 {:width 200 :height 200})
                      (tho/add-group :group :parent-label :main2)
                      (thc/instantiate-component :c1 :nested :parent-label :group)
                      (tho/fit-group :group)
                      (thc/make-component :c2 :main2)
                      (thf/add-sample-page :copy-page)
                      (thc/instantiate-component :c2 :copy2 :children-labels [:copy-group :copy-nested])
                      (tho/swap-component-in-shape :copy-nested :c3 {:page-label :copy-page
                                                                     :new-shape-label :swapped}))
        page      (thf/current-page file)
        container (ctn/make-container page :page)
        changes   (-> (pcb/empty-changes)
                      (pcb/with-container container)
                      (cll/generate-sync-shape-inverse {(:id file) file} container (thi/id :copy2)))
        file'     (thf/apply-changes file changes)
        group'    (ths/get-shape file' :group :page-label :main-page)
        pushed    (ths/get-shape-by-id file' (first (:shapes group')) :page-label :main-page)]
    (t/is (= (thi/id :c3) (:component-id pushed)))
    (t/testing "the group takes the size of the pushed copy"
      (t/is (== 80 (-> group' :selrect :width) (-> group' :selrect :height))))
    (t/testing "undo gives the main back as it was"
      (let [file'' (thf/apply-undo-changes file' changes)]
        (t/is (= (:shapes (ths/get-shape file :group :page-label :main-page))
                 (:shapes (ths/get-shape file'' :group :page-label :main-page))))
        (t/is (= (count (:objects (thf/get-page file :main-page)))
                 (count (:objects (thf/get-page file'' :main-page)))))))))

(t/deftest test-an-assign-on-a-main-changes-its-component
  (let [file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root))
        page   (thf/current-page file)
        change (fn [operation]
                 {:type       :mod-obj
                  :id         (thi/id :main-child)
                  :page-id    (:id page)
                  :operations [operation]})]
    (t/is (= #{(thi/id :component1)}
             (ch/components-changed (:data file) (change {:type :set :attr :opacity :val 0.5}))))
    (t/is (= #{(thi/id :component1)}
             (ch/components-changed (:data file) (change {:type :assign :value {:opacity 0.5}}))))
    (t/is (nil? (ch/components-changed (:data file) (change {:type :assign :value {:interactions []}}))))))

(t/deftest restoring-a-variant-without-its-container-syncs-the-new-main-name
  (let [initial  (tm/apply-to (n/make-variant-container "restore" [["b" "#ffffff"]])
                              (setup/empty-situation))
        initial  (tm/with-file initial
                   (thc/instantiate-component (tm/file initial) :sync-restore-v0-component :copy))
        deletion (n/delete-component :sync-restore-v0-root)
        deleted  (-> initial (r/step deletion) r/settle)
        restored (-> deleted (r/step (n/restore-component deletion)) r/settle)
        main     (tm/resolve-shape restored :sync-restore-v0-root)
        copy     (tm/resolve-shape restored :copy)]
    (t/is (nil? (ths/get-shape (tm/file deleted) :sync-restore-vcontainer)))
    (t/is (= "Board / b" (:name main)))
    (t/is (= (:name main) (:name copy)))
    (t/is (empty? (:touched copy)))
    (t/is (= (thn/normalize-file (tm/file deleted))
             (thn/normalize-file (tm/file (r/step restored (n/undo))))))
    (t/is (= (thn/normalize-file (tm/file restored))
             (thn/normalize-file (tm/file (-> restored (r/step (n/undo)) (r/step (n/redo)))))))))

(t/deftest test-sync-of-a-flip
  ;; the main flips its child as the editor does: a transform that scales
  ;; it by -1, which toggles :flip-x
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                                        :copy-root-params {:children-labels [:copy-child]}))
        page    (thf/current-page file)
        flip    (fn [shape]
                  (gsh/transform-shape shape (ctm/resize-modifiers (gpt/point -1 1) (gsh/shape->center shape))))
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id :main-child)}
                                            flip
                                            (:objects page)
                                            {})
        flipped (thf/apply-changes file changes)
        sync    (cll/generate-sync-file-changes (pcb/empty-changes)
                                                nil
                                                :components
                                                (:id flipped)
                                                (thi/id :component1)
                                                (:id flipped)
                                                {(:id flipped) flipped}
                                                (:id flipped))
        file'   (thf/apply-changes flipped sync)]
    (t/is (true? (:flip-x (ths/get-shape flipped :main-child))))
    (t/is (true? (:flip-x (ths/get-shape file' :copy-child))))
    (t/is (= (:transform (ths/get-shape file' :main-child))
             (:transform (ths/get-shape file' :copy-child))))
    (t/is (nil? (:touched (ths/get-shape file' :copy-child))))))

(t/deftest test-sync-of-a-grid-child-margin
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-grid-frame :main-root)
                    (tho/add-rect :main-child {:parent-label :main-root})
                    (tho/place-in-grid-columns :main-root 1 {1 :main-child})
                    (thc/make-component :component1 :main-root)
                    (thc/instantiate-component :component1 :copy-root :children-labels [:copy-child]))
        page    (thf/current-page file)
        margin  {:m1 9 :m2 9 :m3 9 :m4 9}
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id :main-child)}
                                            #(assoc % :layout-item-margin margin :layout-item-margin-type :simple)
                                            (:objects page)
                                            {})
        edited  (thf/apply-changes file changes)
        sync    (cll/generate-sync-file-changes (pcb/empty-changes)
                                                nil
                                                :components
                                                (:id edited)
                                                (thi/id :component1)
                                                (:id edited)
                                                {(:id edited) edited}
                                                (:id edited))
        file'   (thf/apply-changes edited sync)]
    (t/is (= margin (:layout-item-margin (ths/get-shape file' :copy-child))))))

(t/deftest test-which-changes-of-a-main-mark-its-component-modified
  (let [file     (-> (thf/sample-file :file1)
                     (tho/add-simple-component :component1 :main-root :main-child)
                     (ths/update-shape :main-child :position-data [{:x 0 :y 0 :width 3 :height 4 :text "a"}])
                     (ctf/update-file-data #(assoc-in % [:components (thi/id :component1) :modified-at] :old)))
        page     (thf/current-page file)
        apply-op (fn [operations]
                   (-> (thf/apply-changes file {:redo-changes [{:type       :mod-obj
                                                                :id         (thi/id :main-child)
                                                                :page-id    (:id page)
                                                                :operations operations}]}
                                          :validate? false)
                       (thc/get-component :component1)
                       :modified-at))
        fills    {:type :set :attr :fills :val (ths/sample-fills-color :fill-color "#ff0000")}
        pos-data {:type :set :attr :position-data :val [{:x 1 :y 2 :width 3 :height 4 :text "a"}]}]
    (t/is (not= :old (apply-op [fills])))
    (t/testing "a re-measured text is not a change of the component"
      (t/is (= :old (apply-op [pos-data]))))
    (t/testing "any synced attribute of the change counts, not only the last"
      (t/is (not= :old (apply-op [fills pos-data]))))))

(t/deftest test-an-edit-of-a-nested-component-keeps-the-placement-of-its-copies
  ;; a copy of button dropped into the card main, which then sets it to
  ;; stick right (an override of the nested copy); an edit of the button
  ;; main keeps it, in the card main and in its copy
  (let [sync    (fn [f component]
                  (thf/apply-changes f (cll/generate-sync-file-changes (pcb/empty-changes) nil :components
                                                                       (:id f) (thi/id component) (:id f)
                                                                       {(:id f) f} (:id f))))
        file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :button :button-main :button-rect)
                    (tho/add-frame :card-main {:width 300 :height 300})
                    (thc/make-component :card :card-main)
                    (thc/instantiate-component :card :card-copy)
                    (thc/instantiate-component :button :nested-button))
        page    (thf/current-page file)
        file    (-> file
                    (thf/apply-changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                                  (pcb/with-page-id (:id page))
                                                                  (pcb/with-objects (:objects page)))
                                                              (thi/id :card-main) 0 #{(thi/id :nested-button)}))
                    (ths/update-shape :nested-button :constraints-h :right)
                    (sync :card))
        page    (thf/current-page file)
        edit    (cls/generate-update-shapes (pcb/empty-changes nil (:id page)) #{(thi/id :button-rect)}
                                            #(assoc % :opacity 0.5) (:objects page) {})
        file'   (-> (thf/apply-changes file edit) (sync :button) (sync :card))
        copy    #(ths/get-shape-by-id % (first (:shapes (ths/get-shape % :card-copy))))
        placing (juxt :constraints-h :constraints-v)]
    (t/is (= [:right :top] (placing (copy file))))
    (t/is (= [:right :top] (placing (ths/get-shape file' :nested-button))))
    (t/is (= [:right :top] (placing (copy file'))))
    (t/is (= 0.5 (:opacity (ths/get-shape-by-id file' (first (:shapes (copy file')))))))))

(t/deftest test-update-main-of-a-library-copy-files-the-copy-changes-as-local
  ;; a copy of a library component swaps its nested copy and pushes it to
  ;; the library main: the copy's own changes stay in the file
  (let [library (-> (thf/sample-file :library :is-shared true :page-label :lib-page)
                    (tho/add-nested-component :c1 :main1 :main1-child :c2 :main2 :nested-head)
                    (tho/add-simple-component :c3 :main3 :main3-child))
        file    (-> (thf/sample-file :file1 :page-label :page1)
                    (thc/instantiate-component :c2 :copy2 :library library
                                               :children-labels [:copy-nested]))
        file    (tho/swap-component-in-shape file :copy-nested :c3 {:library library
                                                                    :new-shape-label :swapped})
        page    (thf/current-page file)
        cont    (ctn/make-container page :page)
        changes (-> (pcb/empty-changes)
                    (pcb/with-container cont)
                    (cll/generate-sync-shape-inverse {(:id file) file (:id library) library}
                                                     cont (thi/id :copy2)))
        [local _] (cll/split-local-changes changes)
        objects (:objects page)
        in-copy (into #{} (cfh/get-children-ids-with-self objects (thi/id :copy2)))]
    (t/is (contains? (into #{} (map :id) (:redo-changes local)) (thi/id :swapped)))
    (t/testing "every change of a shape of the copy is local"
      (t/is (every? #(not (contains? in-copy (:id %)))
                    (:redo-changes (second (cll/split-local-changes changes))))))))

