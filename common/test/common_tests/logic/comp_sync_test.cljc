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
   [app.common.files.validate :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.path-names :as cpn]
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
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest removed-main-grid-tracks-keep-overridden-copy-cells-in-bounds
  (doseq [axis [:row :column]
          span? [false true]]
    (let [initial       (cg/world [1 0 {:layout :grid :layout-children true :grid-square true}])
          main-id       ((n/main-head-of "random-0") initial)
          copy-id       ((n/nth-copy-head-of "random-0" 0) initial)
          other-id      ((n/nth-copy-head-of "random-0" 1) initial)
          index         (if span? 1 0)
          coord         (if (= (= axis :row) span?) [1 2] [2 1])
          original      (tm/shape-by-id initial copy-id)
          authored      (cond-> original
                          span? (ctl/resize-cell-area (first coord) (second coord)
                                                      (first coord) (second coord)
                                                      (if (= axis :row) 2 1) (if (= axis :column) 2 1)))
          cell-id       (:id (ctl/cell-by-row-column authored (first coord) (second coord)))
          authored      (update-in authored [:layout-grid-cells cell-id] assoc :align-self :end :justify-self :center)
          original-file (tm/file initial)
          original-page (thf/current-page original-file)
          override      (cls/generate-update-shapes
                         (pcb/empty-changes nil (:id original-page)) #{copy-id}
                         #(assoc % :layout-grid-cells (:layout-grid-cells authored))
                         (:objects original-page) {})
          file          (thf/apply-changes original-file override)
          page          (thf/current-page file)
          main          (get-in page [:objects main-id])
          copy          (get-in page [:objects copy-id])
          cell          (first (filter #(= coord [(:row %) (:column %)]) (vals (:layout-grid-cells copy))))
          child-id      (first (:shapes cell))
          ids           (set ((if (= axis :row) ctl/shapes-by-row ctl/shapes-by-column) main index))
          [_ deletion]  (cls/generate-delete-shapes (pcb/empty-changes) (:data file) page (:objects page) ids {})
          deleted       (thf/apply-changes file deletion)
          objects       (:objects (thf/current-page deleted))
          removal       (cls/generate-update-shapes
                         (pcb/empty-changes nil (:id page)) #{main-id}
                         (fn [shape objects]
                           ((if (= axis :row) ctl/remove-grid-row ctl/remove-grid-column) shape index objects))
                         objects {:with-objects? true})
          removed       (thf/apply-changes deleted removal)
          synced        (r/sync-cascade removed {} #{(:component-id main)})
          after         (:file synced)
          changes       (reduce pcb/concat-changes (pcb/concat-changes deletion removal) (:syncs synced))
          undone        (thf/apply-undo-changes after changes)
          redone        (thf/apply-changes undone changes)
          get-shape     #(ths/get-shape-by-id after %)
          retained      (first (filter #(some #{child-id} (:shapes %)) (vals (:layout-grid-cells (get-shape copy-id)))))
          tracks        (if (= axis :row) :layout-grid-rows :layout-grid-columns)]
      (t/is (some? child-id))
      (t/is (= 1 (count (get (get-shape main-id) tracks))))
      (t/is (= (get (get-shape main-id) tracks) (get (get-shape copy-id) tracks)))
      (doseq [id [main-id copy-id other-id]
              :let [shape (get-shape id)
                    rows (count (:layout-grid-rows shape))
                    cols (count (:layout-grid-columns shape))]]
        (t/is (every? #(and (<= 1 (:row %) (+ (:row %) (:row-span %) -1) rows)
                            (<= 1 (:column %) (+ (:column %) (:column-span %) -1) cols))
                      (vals (:layout-grid-cells shape)))
              (pr-str {:axis axis :id id :rows rows :columns cols})))
      (t/is (= :end (:align-self retained)))
      (t/is (= :center (:justify-self retained)))
      (t/is (= cell-id (:id retained)))
      (t/is (= (if span? coord [1 1]) [(:row retained) (:column retained)]))
      (t/is (= [1 1] [(:row-span retained) (:column-span retained)]))
      (t/is (= #{:layout-grid-cells} (:touched (get-shape copy-id))))
      (t/is (uc/same-state? (uc/snapshot file) (uc/snapshot undone)))
      (t/is (uc/same-state? (uc/snapshot after) (uc/snapshot redone)))
      (t/is (uc/same-state? (uc/snapshot after)
                            (uc/snapshot (:file (r/sync-cascade after {} #{(:component-id main)}))))))))

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
          ;; a sync of the copies lays them out again, as in the editor
          synced    (-> grown (r/settle #{component}) r/settle-layout)]
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
                 #(gsh/move % (gpt/point 0.01 0)) (:objects page) {})
        source (thf/apply-changes file changes)
        changed (tm/with-file initial (:file (r/sync-cascade source {} (keys (get-in file [:data :components])))))
        actual (tm/resolve-shape changed :copy-child)
        expected (gsh/move original (gpt/point 0.01 0))]
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
  ;; This group follows the main's bounds; its children keep their geometry.
  ;; The next case checks a group that keeps its own frame instead.
  (let [;; ==== Setup
        file         (-> (thf/sample-file :file1)
                         (add-group-component-with-copy)
                         (ths/update-shape :copy-child1 :touched #{:geometry-group})
                         (ths/update-shape :copy-child2 :touched #{:geometry-group})
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

(t/deftest test-sync-new-child-follows-an-overridden-group-frame
  (let [file         (-> (thf/sample-file :file1)
                         (add-group-component-with-copy)
                         (touch-copy-group-geometry)
                         (ths/add-sample-shape :free-shape :x 60 :y 60 :width 10 :height 10))
        page         (thf/current-page file)
        changes1     (cls/generate-relocate (-> (pcb/empty-changes nil (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (thi/id :main-group) 0 #{(thi/id :free-shape)})
        updated-file (-> (thf/apply-changes file changes1)
                         (update-fill :main-child1 "#fabada"))
        changes2     (sync-file-changes updated-file)
        file'        (thf/apply-changes updated-file changes2)
        file''       (thf/apply-undo-changes file' changes2)
        redone       (thf/apply-changes file'' changes2)
        copy-group   (ths/get-shape file :copy-group)
        copy-group'  (ths/get-shape file' :copy-group)
        added        (ths/get-shape-by-id file' (first (:shapes copy-group')))]
    ;; The main grew from 30x10 to 60x60. The copy keeps its authored frame;
    ;; its new child follows the resulting 1/2 and 1/6 size ratios.
    (t/is (= (geometry copy-group) (geometry copy-group')))
    (t/is (= #{:geometry-group} (:touched copy-group')))
    (t/is (= (thi/id :free-shape) (:shape-ref added)))
    (t/is (gsh/close-attrs? :points
                            [(gpt/point 135 (/ 355.0 3)) (gpt/point 140 (/ 355.0 3))
                             (gpt/point 140 120) (gpt/point 135 120)]
                            (:points added) 1e-10))
    (t/is (nil? (:touched added)))
    (doseq [label [:copy-child1 :copy-child2]]
      (t/is (= (geometry (ths/get-shape file label)) (geometry (ths/get-shape file' label))))
      (t/is (= #{:geometry-group} (:touched (ths/get-shape file' label)))))
    (t/is (uc/same-state? (uc/snapshot updated-file) (uc/snapshot file'')))
    (t/is (uc/same-state? (uc/snapshot file') (uc/snapshot redone)))))

(t/deftest test-sync-undo-group-after-remove-and-update
  (let [;; ==== Setup
        file         (-> (thf/sample-file :file1)
                         (add-group-component-with-copy)
                         (touch-copy-group-geometry))
        page         (thf/current-page file)
        copy-group   (ths/get-shape file :copy-group)

        ;; ==== Action
        ;; The removed shape precedes the updated sibling. Generate sync
        ;; from the intermediate main-only deletion, but validate and undo
        ;; the complete local edit and its propagation as one group.
        changes1     (-> (pcb/empty-changes nil (:id page))
                         (pcb/with-objects (:objects page))
                         (pcb/remove-objects #{(thi/id :main-child1)})
                         (pcb/update-shapes #{(thi/id :main-child2)}
                                            #(assoc % :fills (ths/sample-fills-color :fill-color "#fabada"))))
        updated-file (thf/apply-changes file changes1 :validate? false)
        changes2     (sync-file-changes updated-file)
        changes      (pcb/concat-changes changes1 changes2)
        file'        (thf/apply-changes file changes)
        file''       (thf/apply-undo-changes file' changes)
        redone       (thf/apply-changes file'' changes)

        ;; ==== Get
        copy-group'  (ths/get-shape file' :copy-group)
        copy-group'' (ths/get-shape file'' :copy-group)]

    ;; ==== Check
    (t/is (= 1 (count (:shapes copy-group'))))
    (t/is (not= (geometry copy-group) (geometry copy-group')))
    (t/is (= (:shapes copy-group) (:shapes copy-group'')))
    (t/is (= (geometry copy-group) (geometry copy-group'')))
    (t/is (= "#fabada" (get-in (ths/get-shape file' :main-child2) [:fills 0 :fill-color])))
    (t/is (= "#fabada" (get-in (ths/get-shape file' :copy-child2) [:fills 0 :fill-color])))
    (t/is (uc/same-state? (uc/snapshot file) (uc/snapshot file'')))
    (t/is (uc/same-state? (uc/snapshot file') (uc/snapshot redone)))))

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
                         (cll/generate-sync-shape-inverse {(:id updated-file) updated-file}
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

(defn- run-sync-inverse
  "Run the inverse sync on `shape-tag`, which has been renamed to an
   unnormalized name and holds a name override, as a main of an older file
   may, and return the resulting main root and component names, and the
   updated file."
  [shape-tag]
  (let [file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root)
                       (ths/update-shape shape-tag :touched #{:name-group})
                       (ths/update-shape shape-tag :name "Group/Subgroup"))
        page       (thf/current-page file)
        container  (ctn/make-container page :page)
        changes    (-> (pcb/empty-changes)
                       (pcb/with-container container)
                       (cll/generate-sync-shape-inverse {(:id file) file}
                                                        container
                                                        (thi/id shape-tag)))
        file'      (thf/apply-changes file changes)
        main-root' (ths/get-shape file' :main-root)
        component' (ctkl/get-component (:data file') (thi/id :component1))]
    [(:name main-root')
     (cpn/merge-path-item (:path component') (:name component'))
     file']))

(t/deftest test-sync-inverse-normalizes-copy-name
  (t/testing "syncing an unnormalized copy name keeps the component and its main root matching"
    (let [file       (-> (thf/sample-file :file1)
                         (tho/add-simple-component-with-copy :component1
                                                             :main-root
                                                             :main-child
                                                             :copy-root)
                         (tho/change-shape :copy-root #(update % :touched (fnil conj #{}) :name-group))
                         (ths/update-shape :copy-root :name "Group/Subgroup"))
          page       (thf/current-page file)
          container  (ctn/make-container page :page)
          changes    (-> (pcb/empty-changes)
                         (pcb/with-container container)
                         (cll/generate-sync-shape-inverse {(:id file) file}
                                                          container
                                                          (thi/id :copy-root)))
          file'      (thf/apply-changes file changes)
          main-root' (ths/get-shape file' :main-root)
          component' (ctkl/get-component (:data file') (thi/id :component1))]

      ;; ==== Check
      (t/is (= "Group / Subgroup" (:name main-root')))
      (t/is (= "Group / Subgroup" (cpn/merge-path-item (:path component') (:name component'))))
      (t/is (nil? (cfv/validate-file file' {}))))))

(t/deftest test-sync-inverse-normalizes-main-name
  (t/testing "syncing an unnormalized main name keeps the component and the main root matching"
    (let [[main-root-name component-name file'] (run-sync-inverse :main-root)]
      ;; ==== Check
      (t/is (= "Group / Subgroup" main-root-name))
      (t/is (= "Group / Subgroup" component-name))
      (t/is (nil? (cfv/validate-file file' {}))))))

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

(t/deftest a-copy-grid-with-its-own-cells-orders-its-children-by-their-flow
  (let [page-id (thi/new-id! :page)
        grid    {:id (thi/new-id! :grid) :type :frame :layout :grid :layout-grid-dir :row
                 :shape-ref (thi/new-id! :main-grid) :shapes [(thi/new-id! :a) (thi/new-id! :b)]
                 :layout-grid-cells
                 {(thi/new-id! :cell-a) {:id (thi/id :cell-a) :row 1 :column 1 :row-span 1 :column-span 1
                                         :position :auto :shapes [(thi/id :a)]}
                  (thi/new-id! :cell-b) {:id (thi/id :cell-b) :row 1 :column 2 :row-span 1 :column-span 1
                                         :position :auto :shapes [(thi/id :b)]}}}
        objects (fn [grid]
                  {(:id grid)    grid
                   (thi/id :a) {:id (thi/id :a) :type :rect :parent-id (:id grid) :shape-ref (thi/new-id! :main-a)}
                   (thi/id :b) {:id (thi/id :b) :type :rect :parent-id (:id grid) :shape-ref (thi/new-id! :main-b)}})
        order   (fn [grid]
                  (->> (-> (pcb/empty-changes nil page-id)
                           (pcb/with-objects (objects grid))
                           (pcb/reorder-grid-children [(:id grid)]))
                       :redo-changes
                       (some #(when (= :reorder-children (:type %)) (:shapes %)))))]
    (t/testing "a copy grid with its own cells follows their flow"
      (t/is (= [(thi/id :b) (thi/id :a)] (order (assoc grid :touched #{:layout-grid-cells})))))
    (t/testing "a copy grid that takes its main's cells keeps the order sync gives"
      (t/is (nil? (order grid))))))

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

