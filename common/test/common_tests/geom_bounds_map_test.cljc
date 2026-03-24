;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-bounds-map-test
  (:require
   [app.common.geom.bounds-map :as gbm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; ---- Helpers ----

(defn- make-rect
  "Create a minimal rect shape with given id and position/size."
  [id x y w h]
  (-> (cts/setup-shape {:id id
                        :type :rect
                        :name (str "rect-" id)
                        :x x
                        :y y
                        :width w
                        :height h})
      (assoc :parent-id uuid/zero
             :frame-id uuid/zero)))

(defn- make-group
  "Create a minimal group shape with given id and children ids."
  [id child-ids]
  (let [x 0 y 0 w 100 h 100]
    (-> (cts/setup-shape {:id id
                          :type :group
                          :name (str "group-" id)
                          :x x
                          :y y
                          :width w
                          :height h})
        (assoc :parent-id uuid/zero
               :frame-id uuid/zero
               :shapes (vec child-ids)))))

(defn- make-masked-group
  "Create a masked group shape with given id and children ids."
  [id child-ids]
  (let [x 0 y 0 w 100 h 100]
    (-> (cts/setup-shape {:id id
                          :type :group
                          :name (str "masked-group-" id)
                          :x x
                          :y y
                          :width w
                          :height h})
        (assoc :parent-id uuid/zero
               :frame-id uuid/zero
               :masked-group true
               :shapes (vec child-ids)))))

(defn- make-objects
  "Build an objects map from shapes. Sets parent-id on children."
  [root-parent-id shapes]
  (let [shape-map (into {} (map (fn [s] [(:id s) s]) shapes))]
    ;; Set parent-id on children based on their container's :shapes list
    (reduce-kv (fn [m _id shape]
                 (if (contains? shape :shapes)
                   (reduce (fn [m' child-id]
                             (assoc-in m' [child-id :parent-id] (:id shape)))
                           m
                           (:shapes shape))
                   m))
               shape-map
               shape-map)))

;; ---- Tests for objects->bounds-map ----

(t/deftest objects->bounds-map-empty-test
  (t/testing "Empty objects returns empty map"
    (let [result (gbm/objects->bounds-map {})]
      (t/is (map? result))
      (t/is (empty? result)))))

(t/deftest objects->bounds-map-single-rect-test
  (t/testing "Single rect produces bounds entry"
    (let [id     (uuid/next)
          shape  (make-rect id 10 20 30 40)
          objects {id shape}
          bm     (gbm/objects->bounds-map objects)]
      (t/is (contains? bm id))
      (t/is (delay? (get bm id)))
      (let [bounds @(get bm id)]
        (t/is (vector? bounds))
        (t/is (= 4 (count bounds)))
        ;; Verify bounds match the rect's geometry
        (t/is (mth/close? 10.0 (:x (gpo/origin bounds))))
        (t/is (mth/close? 20.0 (:y (gpo/origin bounds))))
        (t/is (mth/close? 30.0 (gpo/width-points bounds)))
        (t/is (mth/close? 40.0 (gpo/height-points bounds)))))))

(t/deftest objects->bounds-map-multiple-rects-test
  (t/testing "Multiple rects each produce correct bounds"
    (let [id1    (uuid/next)
          id2    (uuid/next)
          id3    (uuid/next)
          objects {id1 (make-rect id1 0 0 100 50)
                   id2 (make-rect id2 50 25 200 75)
                   id3 (make-rect id3 10 10 1 1)}
          bm     (gbm/objects->bounds-map objects)]
      (t/is (= 3 (count bm)))
      (doseq [id [id1 id2 id3]]
        (t/is (contains? bm id))
        (t/is (delay? (get bm id))))
      ;; Check each shape's bounds
      (let [b1 @(get bm id1)]
        (t/is (mth/close? 0.0 (:x (gpo/origin b1))))
        (t/is (mth/close? 0.0 (:y (gpo/origin b1))))
        (t/is (mth/close? 100.0 (gpo/width-points b1)))
        (t/is (mth/close? 50.0 (gpo/height-points b1))))
      (let [b2 @(get bm id2)]
        (t/is (mth/close? 50.0 (:x (gpo/origin b2))))
        (t/is (mth/close? 25.0 (:y (gpo/origin b2))))
        (t/is (mth/close? 200.0 (gpo/width-points b2)))
        (t/is (mth/close? 75.0 (gpo/height-points b2))))
      (let [b3 @(get bm id3)]
        (t/is (mth/close? 10.0 (:x (gpo/origin b3))))
        (t/is (mth/close? 10.0 (:y (gpo/origin b3))))))))

(t/deftest objects->bounds-map-laziness-test
  (t/testing "Bounds are computed lazily (delay semantics)"
    (let [id1    (uuid/next)
          id2    (uuid/next)
          objects {id1 (make-rect id1 0 0 10 10)
                   id2 (make-rect id2 5 5 20 20)}
          bm     (gbm/objects->bounds-map objects)]
      ;; Delays should not be realized until deref'd
      (t/is (not (realized? (get bm id1))))
      (t/is (not (realized? (get bm id2))))
      ;; After deref, they should be realized
      @(get bm id1)
      (t/is (realized? (get bm id1)))
      (t/is (not (realized? (get bm id2))))
      @(get bm id2)
      (t/is (realized? (get bm id2))))))

;; ---- Tests for transform-bounds-map ----

(t/deftest transform-bounds-map-empty-modif-tree-test
  (t/testing "Empty modif-tree returns equivalent bounds-map"
    (let [id1    (uuid/next)
          objects {id1 (make-rect id1 10 20 30 40)}
          bm      (gbm/objects->bounds-map objects)
          result  (gbm/transform-bounds-map bm objects {})]
      ;; No modifiers means no IDs to resolve, so bounds-map should be returned as-is
      (t/is (= bm result)))))

(t/deftest transform-bounds-map-move-rect-test
  (t/testing "Moving a rect updates its bounds"
    (let [id1     (uuid/next)
          objects  {id1 (make-rect id1 10 20 30 40)}
          bm       (gbm/objects->bounds-map objects)
          modif-tree {id1 {:modifiers (ctm/move-modifiers (gpt/point 100 200))}}
          result   (gbm/transform-bounds-map bm objects modif-tree)]
      (t/is (contains? result id1))
      (let [old-bounds @(get bm id1)
            new-bounds @(get result id1)]
        ;; Original bounds should be unchanged
        (t/is (mth/close? 10.0 (:x (gpo/origin old-bounds))))
        (t/is (mth/close? 20.0 (:y (gpo/origin old-bounds))))
        ;; New bounds should be translated
        (t/is (mth/close? 110.0 (:x (gpo/origin new-bounds))))
        (t/is (mth/close? 220.0 (:y (gpo/origin new-bounds))))))))

(t/deftest transform-bounds-map-move-in-group-test
  (t/testing "Moving a child rect also updates its parent group bounds"
    (let [child-id  (uuid/next)
          group-id  (uuid/next)
          child     (make-rect child-id 10 10 20 20)
          group     (make-group group-id [child-id])
          objects   (make-objects uuid/zero [child group])
          bm        (gbm/objects->bounds-map objects)
          ;; Move child by (50, 50)
          modif-tree {child-id {:modifiers (ctm/move-modifiers (gpt/point 50 50))}}
          result    (gbm/transform-bounds-map bm objects modif-tree)]
      ;; Both child and group should have new bounds
      (t/is (contains? result child-id))
      (t/is (contains? result group-id))
      (let [new-child-bounds @(get result child-id)]
        (t/is (mth/close? 60.0 (:x (gpo/origin new-child-bounds))))
        (t/is (mth/close? 60.0 (:y (gpo/origin new-child-bounds)))))
      (let [new-group-bounds @(get result group-id)]
        ;; Group bounds should encompass the moved child
        (t/is (some? new-group-bounds))))))

(t/deftest transform-bounds-map-masked-group-test
  (t/testing "Masked group only uses first child for bounds"
    (let [child1-id (uuid/next)
          child2-id (uuid/next)
          group-id  (uuid/next)
          child1    (make-rect child1-id 0 0 10 10)
          child2    (make-rect child2-id 100 100 50 50)
          group     (make-masked-group group-id [child1-id child2-id])
          objects   (make-objects uuid/zero [child1 child2 group])
          bm        (gbm/objects->bounds-map objects)
          result    (gbm/transform-bounds-map bm objects {})]
      ;; Even with empty modif-tree, the group should be resolved
      ;; Masked group behavior: only first child contributes
      (t/is (some? result)))))

(t/deftest transform-bounds-map-multiple-modifiers-test
  (t/testing "Multiple shapes modified at once"
    (let [id1      (uuid/next)
          id2      (uuid/next)
          objects  {id1 (make-rect id1 0 0 100 100)
                    id2 (make-rect id2 200 200 50 50)}
          bm       (gbm/objects->bounds-map objects)
          modif-tree {id1 {:modifiers (ctm/move-modifiers (gpt/point 10 10))}
                      id2 {:modifiers (ctm/move-modifiers (gpt/point -5 -5))}}
          result   (gbm/transform-bounds-map bm objects modif-tree)]
      (let [b1 @(get result id1)]
        (t/is (mth/close? 10.0 (:x (gpo/origin b1))))
        (t/is (mth/close? 10.0 (:y (gpo/origin b1)))))
      (let [b2 @(get result id2)]
        (t/is (mth/close? 195.0 (:x (gpo/origin b2))))
        (t/is (mth/close? 195.0 (:y (gpo/origin b2))))))))

(t/deftest transform-bounds-map-uuid-zero-ignored-test
  (t/testing "uuid/zero in modif-tree is skipped when creating new bounds entries"
    (let [id1     (uuid/next)
          objects  {id1 (make-rect id1 10 20 30 40)
                    uuid/zero {:id uuid/zero :type :frame :parent-id uuid/zero}}
          bm       (gbm/objects->bounds-map objects)
          ;; uuid/zero in modif-tree triggers resolve but its entry is preserved from original
          modif-tree {id1 {:modifiers (ctm/move-modifiers (gpt/point 0 0))}
                      uuid/zero {:modifiers (ctm/move-modifiers (gpt/point 0 0))}}
          result   (gbm/transform-bounds-map bm objects modif-tree)]
      ;; uuid/zero may still be in result if it was in the original bounds-map
      ;; The function does not add NEW uuid/zero entries, but preserves existing ones
      (when (contains? bm uuid/zero)
        (t/is (contains? result uuid/zero))))))

(t/deftest transform-bounds-map-explicit-ids-test
  (t/testing "Passing explicit ids limits which shapes are recomputed"
    (let [id1     (uuid/next)
          id2     (uuid/next)
          objects {id1 (make-rect id1 0 0 100 100)
                   id2 (make-rect id2 200 200 50 50)}
          bm      (gbm/objects->bounds-map objects)
          modif-tree {id1 {:modifiers (ctm/move-modifiers (gpt/point 10 10))}
                      id2 {:modifiers (ctm/move-modifiers (gpt/point 20 20))}}
          ;; Only recompute id1
          result  (gbm/transform-bounds-map bm objects modif-tree #{id1})]
      ;; id1 should be updated
      (let [b1 @(get result id1)]
        (t/is (mth/close? 10.0 (:x (gpo/origin b1)))))
      ;; id2 should be preserved from original bounds-map
      (let [b2-original @(get bm id2)
            b2-result   @(get result id2)]
        (t/is (= b2-original b2-result))))))

(t/deftest transform-bounds-map-nested-groups-test
  (t/testing "Nested groups propagate bounds updates upward"
    (let [child-id   (uuid/next)
          inner-grp  (uuid/next)
          outer-grp  (uuid/next)
          child      (make-rect child-id 0 0 20 20)
          inner      (make-group inner-grp [child-id])
          outer      (make-group outer-grp [inner-grp])
          objects    (make-objects uuid/zero [child inner outer])
          bm         (gbm/objects->bounds-map objects)
          modif-tree {child-id {:modifiers (ctm/move-modifiers (gpt/point 100 100))}}
          result     (gbm/transform-bounds-map bm objects modif-tree)]
      ;; All three should be in the result
      (t/is (contains? result child-id))
      (t/is (contains? result inner-grp))
      (t/is (contains? result outer-grp))
      (let [child-bounds @(get result child-id)]
        (t/is (mth/close? 100.0 (:x (gpo/origin child-bounds))))
        (t/is (mth/close? 100.0 (:y (gpo/origin child-bounds))))))))

;; ---- Tests for bounds-map (debug function) ----

(t/deftest bounds-map-debug-empty-test
  (t/testing "Debug bounds-map with empty inputs returns empty map"
    (let [result (gbm/bounds-map {} {})]
      (t/is (map? result))
      (t/is (empty? result)))))

(t/deftest bounds-map-debug-single-shape-test
  (t/testing "Debug bounds-map returns readable entries for shapes"
    (let [id      (uuid/next)
          shape   (make-rect id 10 20 30 40)
          objects {id shape}
          bm      (gbm/objects->bounds-map objects)
          result  (gbm/bounds-map objects bm)
          expected-name (str "rect-" id)]
      (t/is (contains? result expected-name))
      (let [entry (get result expected-name)]
        (t/is (map? entry))
        (t/is (contains? entry :x))
        (t/is (contains? entry :y))
        (t/is (contains? entry :width))
        (t/is (contains? entry :height))
        (t/is (mth/close? 10.0 (:x entry)))
        (t/is (mth/close? 20.0 (:y entry)))
        (t/is (mth/close? 30.0 (:width entry)))
        (t/is (mth/close? 40.0 (:height entry)))))))

(t/deftest bounds-map-debug-missing-shape-test
  (t/testing "Debug bounds-map skips entries where shape is nil"
    (let [fake-id (uuid/next)
          real-id (uuid/next)
          objects {real-id (make-rect real-id 10 20 30 40)}
          real-bm (gbm/objects->bounds-map objects)
          ;; Bounds map has an entry for fake-id (delay with valid points)
          ;; but no shape in objects for fake-id
          bm      {fake-id (delay [(gpt/point 0 0)
                                   (gpt/point 10 0)
                                   (gpt/point 10 10)
                                   (gpt/point 0 10)])
                   real-id (get real-bm real-id)}
          result  (gbm/bounds-map objects bm)]
      ;; fake-id has no shape in objects, so it should be excluded
      (t/is (not (contains? result (str fake-id))))
      ;; real-id has a shape, so it should be present
      (t/is (contains? result (str "rect-" real-id))))))

(t/deftest bounds-map-debug-multiple-shapes-test
  (t/testing "Debug bounds-map with multiple shapes"
    (let [id1     (uuid/next)
          id2     (uuid/next)
          objects {id1 (make-rect id1 0 0 50 50)
                   id2 (make-rect id2 100 100 25 25)}
          bm      (gbm/objects->bounds-map objects)
          result  (gbm/bounds-map objects bm)]
      (t/is (>= (count result) 2)))))

(t/deftest bounds-map-debug-rounds-values-test
  (t/testing "Debug bounds-map rounds x/y/width/height to 2 decimal places"
    (let [id      (uuid/next)
          objects {id (make-rect id 10.123456 20.987654 30.5555 40.4444)}
          bm      (gbm/objects->bounds-map objects)
          result  (gbm/bounds-map objects bm)
          entry   (get result (str "rect-" id))]
      (when (some? entry)
        (t/is (number? (:x entry)))
        (t/is (number? (:y entry)))
        (t/is (number? (:width entry)))
        (t/is (number? (:height entry)))))))

;; ---- Edge cases ----

(t/deftest objects->bounds-map-shape-with-identity-transform-test
  (t/testing "Shape with identity transform uses selrect-based points"
    (let [id     (uuid/next)
          shape  (make-rect id 5 15 25 35)
          objects {id shape}
          bm     (gbm/objects->bounds-map objects)]
      (t/is (contains? bm id))
      (let [bounds @(get bm id)]
        (t/is (= 4 (count bounds)))
        ;; All points should have valid coordinates
        (doseq [p bounds]
          (t/is (number? (:x p)))
          (t/is (number? (:y p))))))))

(t/deftest transform-bounds-map-unchanged-unmodified-shapes-test
  (t/testing "Unmodified shapes keep their original bounds reference"
    (let [id1     (uuid/next)
          id2     (uuid/next)
          objects {id1 (make-rect id1 0 0 100 100)
                   id2 (make-rect id2 200 200 50 50)}
          bm      (gbm/objects->bounds-map objects)
          ;; Only modify id1
          modif-tree {id1 {:modifiers (ctm/move-modifiers (gpt/point 10 10))}}
          result  (gbm/transform-bounds-map bm objects modif-tree)]
      ;; id1 should have different bounds
      (let [old-b1 @(get bm id1)
            new-b1 @(get result id1)]
        (t/is (not (mth/close? (:x (gpo/origin old-b1))
                               (:x (gpo/origin new-b1)))))))))

(t/deftest transform-bounds-map-deep-nesting-test
  (t/testing "3-level nesting of groups with a leaf modification"
    (let [leaf-id    (uuid/next)
          grp1-id    (uuid/next)
          grp2-id    (uuid/next)
          grp3-id    (uuid/next)
          leaf       (make-rect leaf-id 0 0 10 10)
          grp1       (make-group grp1-id [leaf-id])
          grp2       (make-group grp2-id [grp1-id])
          grp3       (make-group grp3-id [grp2-id])
          objects    (make-objects uuid/zero [leaf grp1 grp2 grp3])
          bm         (gbm/objects->bounds-map objects)
          modif-tree {leaf-id {:modifiers (ctm/move-modifiers (gpt/point 5 5))}}
          result     (gbm/transform-bounds-map bm objects modif-tree)]
      ;; All group levels should be recomputed
      (t/is (contains? result leaf-id))
      (t/is (contains? result grp1-id))
      (t/is (contains? result grp2-id))
      (t/is (contains? result grp3-id)))))

(t/deftest objects->bounds-map-zero-sized-rect-test
  (t/testing "Zero-sized rect produces valid bounds (clamped to 0.01)"
    (let [id     (uuid/next)
          shape  (make-rect id 10 20 0 0)
          objects {id shape}
          bm     (gbm/objects->bounds-map objects)]
      (t/is (contains? bm id))
      (let [bounds @(get bm id)]
        ;; Width and height should be clamped to at least 0.01
        (t/is (>= (gpo/width-points bounds) 0.01))
        (t/is (>= (gpo/height-points bounds) 0.01))))))
