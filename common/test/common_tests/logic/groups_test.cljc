;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.groups-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.bool :as clb]
   [app.common.logic.groups :as clg]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- two-rects
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-shape :rect-a)
      (ths/add-sample-shape :rect-b)))

(defn- objects
  [file]
  (:objects (thf/current-page file)))

(defn- group
  [file labels]
  (let [page    (thf/current-page file)
        shapes  (clg/shapes-for-group-or-mask (:objects page) (map thi/id labels))
        [group changes] (clg/prepare-create-group (pcb/empty-changes nil) (uuid/next) (:objects page)
                                                  (:id page) shapes "Group" false)]
    [(:id group) (thf/apply-changes file changes)]))

(t/deftest grouping-puts-the-shapes-in-a-new-group
  (let [[id file] (group (two-rects) [:rect-a :rect-b])
        group     (get (objects file) id)]
    (t/is (= :group (:type group)))
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (set (:shapes group))))
    (t/is (every? #(= id (:parent-id (get (objects file) %))) (:shapes group)))))

(t/deftest ungrouping-gives-the-children-back-to-the-parent
  (let [[id file] (group (two-rects) [:rect-a :rect-b])
        page      (thf/current-page file)
        ids       (clg/ungroup-ids (:objects page) [id])
        file      (thf/apply-changes file (clg/generate-ungroup-changes nil (:id page) (:objects page) ids))]
    (t/is (nil? (get (objects file) id)))
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (clg/ungrouped-children (:objects page) ids)))
    (t/is (= uuid/zero (:parent-id (get (objects file) (thi/id :rect-a)))))))

(t/deftest ungrouping-leaves-copy-heads-whole
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component :main-root :main-child :copy-root))]
    (t/is (empty? (clg/ungroup-ids (objects file) [(thi/id :copy-root)])))))

(t/deftest masking-a-lone-group-masks-it-and-unmasking-takes-the-mask-off
  (let [[id file] (group (two-rects) [:rect-a :rect-b])
        page      (thf/current-page file)
        shapes    (clg/shapes-for-group-or-mask (:objects page) [id])
        [mask changes] (clg/generate-mask-changes nil (uuid/next) (:id page) (:objects page) shapes)
        masked    (thf/apply-changes file changes)
        page      (thf/current-page masked)
        unmasked  (thf/apply-changes masked (clg/generate-unmask-changes nil (:id page) (:objects page) [id]))]
    (t/is (= id (:id mask)))
    (t/is (true? (:masked-group (get (objects masked) id))))
    (t/is (nil? (:masked-group (get (objects unmasked) id))))))

(t/deftest a-bool-combines-shapes-but-not-boards
  (let [file (-> (two-rects)
                 (ths/add-sample-shape :board :type :frame))
        page (thf/current-page file)
        ids  (map thi/id [:rect-a :rect-b :board])
        [shape changes] (clb/generate-create-bool nil (:id page) (:objects page) ids :union nil)
        file (thf/apply-changes file changes)]
    (t/is (= :bool (:type (get (objects file) (:id shape)))))
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (set (:shapes (get (objects file) (:id shape))))))
    (t/is (nil? (clb/generate-create-bool nil (:id page) (:objects page) [(thi/id :board)] :union nil)))))

(t/deftest a-bool-in-a-grid-takes-the-cell-of-its-last-shape
  (let [file   (-> (thf/sample-file :file1)
                   (tho/add-frame :grid :layout :grid
                                  :layout-grid-rows [{:type :flex :value 1}]
                                  :layout-grid-columns [{:type :flex :value 1} {:type :flex :value 1}])
                   (ths/add-sample-shape :rect-a :parent-label :grid)
                   (ths/add-sample-shape :rect-b :parent-label :grid)
                   (ths/update-shape :grid :layout-grid-cells {})
                   (as-> file (let [page (thf/current-page file)]
                                (ths/update-shape file :grid :layout-grid-cells
                                                  (:layout-grid-cells
                                                   (ctl/assign-cells (get (:objects page) (thi/id :grid))
                                                                     (:objects page)))))))
        page   (thf/current-page file)
        ids    (map thi/id [:rect-a :rect-b])
        [shape changes] (clb/generate-create-bool nil (:id page) (:objects page) ids :union nil)
        file   (thf/apply-changes file changes)
        grid   (get (objects file) (thi/id :grid))]
    (t/is (some #(= [(:id shape)] (:shapes %)) (vals (:layout-grid-cells grid))))))

(t/deftest ungrouping-a-group-a-main-holds-leaves-no-copy-referring-to-it
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-frame :main-root)
                  (ths/add-sample-shape :rect :parent-label :main-root))
        page  (thf/current-page file)
        [group changes] (clg/prepare-create-group (pcb/empty-changes nil) (uuid/next) (:objects page) (:id page)
                                                  (clg/shapes-for-group-or-mask (:objects page) [(thi/id :rect)])
                                                  "Group" false)
        file  (-> (thf/apply-changes file changes)
                  (thc/make-component :component :main-root)
                  (thc/instantiate-component :component :copy-root))
        copy-group (first (:shapes (get (objects file) (thi/id :copy-root))))
        copy-rect  (first (:shapes (get (objects file) copy-group)))
        file  (-> file
                  (ths/update-shape-by-id copy-rect :opacity 0.5)
                  (ths/update-shape-by-id copy-rect :touched #{:layer-effects-group}))
        page  (thf/current-page file)
        ids   (clg/ungroup-ids (:objects page) [(:id group)])
        file  (thf/apply-changes file (clg/generate-ungroup-changes nil (:id page) (:objects page) ids
                                                                    (get-in file [:data :pages-index])))
        rect  (get (objects file) copy-rect)]
    (t/is (nil? (get (objects file) copy-group)))
    (t/is (= (thi/id :copy-root) (:parent-id rect)))
    (t/is (= 0.5 (:opacity rect)))
    (t/is (= #{:layer-effects-group} (:touched rect)))))

(t/deftest resizing-a-bool-resizes-the-shapes-it-combines
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-frame :board :width 200 :height 200)
                    (ths/add-sample-shape :rect :parent-label :board :width 50 :height 50
                                          :constraints-h :left :constraints-v :top))
        page    (thf/current-page file)
        [shape changes] (clb/generate-create-bool nil (:id page) (:objects page) [(thi/id :rect)] :union nil)
        objects (objects (thf/apply-changes file changes))
        bool    (get objects (:id shape))
        tree    (gm/set-objects-modifiers {(:id bool) {:modifiers (ctm/change-size bool 80 nil)}} objects)
        rect    (gsh/transform-shape (get objects (thi/id :rect)) (get-in tree [(thi/id :rect) :modifiers]))]
    (t/is (mth/close? 80 (-> rect :selrect :width)))))

(t/deftest a-bool-made-in-a-group-gives-the-group-its-bounds
  (let [file    (-> (thf/sample-file :file1)
                    (ths/add-sample-shape :badge :width 12 :height 12)
                    (ths/add-sample-shape :rect :x 10 :y 10 :width 20 :height 20))
        [id file] (group file [:badge :rect])
        page    (thf/current-page file)
        [shape changes] (clb/generate-create-bool nil (:id page) (:objects page)
                                                  [(thi/id :badge) (thi/id :rect)] :difference nil)
        objects (objects (thf/apply-changes file changes))]
    (t/is (= (-> (get objects (:id shape)) :selrect ((juxt :x :y :width :height)))
             (-> (get objects id) :selrect ((juxt :x :y :width :height)))))))

(t/deftest ungrouping-a-group-in-a-bool-combines-its-shapes-again
  (let [file    (-> (thf/sample-file :file1)
                    (ths/add-sample-shape :rect-a :width 50 :height 50)
                    (ths/add-sample-shape :rect-b :width 70 :height 25))
        page    (thf/current-page file)
        ;; the bool its shapes make without the group
        [direct changes] (clb/generate-create-bool nil (:id page) (:objects page)
                                                   [(thi/id :rect-a) (thi/id :rect-b)] :difference nil)
        expected (:content (get (objects (thf/apply-changes file changes)) (:id direct)))
        [id file] (group file [:rect-a :rect-b])
        page    (thf/current-page file)
        [shape changes] (clb/generate-create-bool nil (:id page) (:objects page) [id] :difference nil)
        file    (thf/apply-changes file changes)
        page    (thf/current-page file)
        file    (thf/apply-changes file (clg/generate-ungroup-changes nil (:id page) (:objects page) [id]))
        bool    (get (objects file) (:id shape))]
    (t/is (= #{(thi/id :rect-a) (thi/id :rect-b)} (set (:shapes bool))))
    (t/is (= (str expected) (str (:content bool))))))
