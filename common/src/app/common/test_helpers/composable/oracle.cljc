;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.oracle
  "Checks authored overrides against actual copies. Expected values come
   from the shadow and near main, never from the sync implementation."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [clojure.walk :as walk]))

(defn fail
  [invariant details]
  (throw (ex-info (str "Component invariant " (name invariant) " failed")
                  (assoc details :invariant invariant))))

(defn snapshot
  [situation]
  (into {} (map (fn [[id file]]
                  [id (thn/normalize-file file {:position-data? false})]))
        (assoc (tm/aux-files situation) (:id (tm/file situation)) (tm/file situation))))

(defn derived-attributes
  [objects shape]
  (let [layout-child (and (ctl/any-layout-immediate-child? objects shape)
                          (not (ctl/position-absolute? shape)))
        text (= :text (:type shape))
        auto-width (and text (= :auto-width (:grow-type shape)))
        auto-height (and text (#{:auto-width :auto-height} (:grow-type shape)))
        width-owned (or auto-width (and (or layout-child (ctl/any-layout? shape))
                                        (#{:fill :auto} (:layout-item-h-sizing shape))))
        height-owned (or auto-height (and (or layout-child (ctl/any-layout? shape))
                                          (#{:fill :auto} (:layout-item-v-sizing shape))))]
    (cond-> #{}
      layout-child (into #{:x :y})
      width-owned (conj :width)
      height-owned (conj :height)
      (or layout-child width-owned height-owned) (into #{:points :selrect}))))

(defn- matrix-effect
  "Matrix coefficients have no pixel unit. Compare their effect over the
   shape's local bounds, so the geometry tolerance has the same unit."
  [shape matrix]
  (let [half-width (/ (:width shape) 2)
        half-height (/ (:height shape) 2)]
    (mapv #(gpt/transform % (or matrix (gmt/matrix)))
          [(gpt/point (- half-width) (- half-height))
           (gpt/point half-width (- half-height))
           (gpt/point half-width half-height)
           (gpt/point (- half-width) half-height)])))

(defn design-snapshot
  "Design inputs without derived placement, dimensions and bookkeeping.
   Geometry remains unrounded; equivalent? applies its tolerance directly."
  [situation]
  (walk/postwalk
   (fn [value]
     (if (and (map? value) (contains? value :parent-id))
       (cond-> (-> value
                   (dissoc :remote-synced)
                   (update :flip-x boolean)
                   (update :flip-y boolean))
         (= :text (:type value)) (update :content thtx/view))
       value))
   (walk/postwalk
    (fn [value]
      (if (and (map? value) (:objects value))
        (update value :objects
                (fn [objects]
                  (into {} (map (fn [[id shape]]
                                  [id (-> (gsh/move shape
                                                    (gpt/point (- (get-in objects [(:parent-id shape) :x] 0))
                                                               (- (get-in objects [(:parent-id shape) :y] 0))))
                                          (as-> moved (assoc moved
                                                             :transform (matrix-effect shape (:transform moved))
                                                             :transform-inverse (matrix-effect shape (:transform-inverse moved))))
                                          (as-> shape (apply dissoc shape
                                                             (cond-> (derived-attributes objects shape)
                                                               (and (:component-id shape) (:shape-ref shape))
                                                               (into #{:x :y :points :selrect}))))
                                          (update :constraints-h #(or % (gsh/default-constraints-h shape)))
                                          (update :constraints-v #(or % (gsh/default-constraints-v shape))))])) objects)))
        value))
    (snapshot situation))))

(defn canonical
  "Design data with shape, component and grid-cell IDs named by tree path.
   Pure and frontend backends allocate different IDs for the same action."
  [situation]
  (let [files (sort-by (comp str key) (snapshot situation))
        ids (atom {#uuid "00000000-0000-0000-0000-000000000000" :root})]
    (letfn [(name-tree [objects id path]
              (when-let [shape (get objects id)]
                (swap! ids assoc id path)
                (doseq [[cell-id cell] (:layout-grid-cells shape)]
                  (swap! ids assoc cell-id [path :cell (:row cell) (:column cell)]))
                (doseq [[index child] (map-indexed vector (:shapes shape))]
                  (name-tree objects child (conj path index)))))]
      (doseq [[file-index [file-id file]] (map-indexed vector files)]
        (swap! ids assoc file-id [:file file-index])
        (doseq [[page-index page-id] (map-indexed vector (get-in file [:data :pages]))
                :let [page (get-in file [:data :pages-index page-id])]]
          (swap! ids assoc page-id [:file file-index :page page-index])
          (doseq [[index child] (map-indexed vector (get-in page [:objects #uuid "00000000-0000-0000-0000-000000000000" :shapes]))]
            (name-tree (:objects page) child [:file file-index :page page-index index])))
        (doseq [[index [component-id component]]
                (map-indexed vector (sort-by (comp str key) (get-in file [:data :components])))
                :let [path [:file file-index :component index]]]
          (swap! ids assoc component-id path)
          (when (:objects component)
            (name-tree (:objects component) (:main-instance-id component) (conj path :main)))))
      (walk/postwalk #(if (uuid? %) (get @ids % %) %) (design-snapshot situation)))))

(defn equivalent?
  "Compare canonical data, allowing 0.01 only within shape geometry."
  [expected actual]
  (uc/same-state? expected actual 0.01))

(defn- orientation-matrix
  "Resize uses constraints and dimensions. Parent placement supplies axis
   directions, without applying residual minimum-size scale a second time."
  [shape]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        horizontal (gpt/unit (gpt/point a b))
        vertical (gpt/unit (gpt/point c d))]
    (gmt/matrix (:x horizontal) (:y horizontal) (:x vertical) (:y vertical) 0 0)))

(defn- child-placement
  "Place a main child against its expected copy parent. Only lower-level
   constraint and matrix math is shared with production, not sync code."
  [main main-parent parent objects]
  (let [resized (if (and (mth/close? (:width main-parent) (:width parent) 0.01)
                         (mth/close? (:height main-parent) (:height parent) 0.01))
                  objects
                  (let [tree (gm/set-objects-modifiers
                              {(:id main-parent)
                               {:modifiers (ctm/change-size main-parent (:width parent) (:height parent))}}
                              objects)]
                    (reduce-kv (fn [objects id {:keys [modifiers]}]
                                 (update objects id gsh/transform-shape modifiers)) objects tree)))
        main (get resized (:id main))
        main-parent (get resized (:id main-parent))
        center (gsh/shape->center main-parent)
        turn (gmt/multiply (orientation-matrix parent)
                           (gmt/inverse (orientation-matrix main-parent)))
        placed (if (gmt/unit? turn) main
                   (gsh/apply-transform main (gmt/transform-in center turn)))
        ;; Flip flags follow the parent's composition history. A single
        ;; large turn can toggle both flags where several small turns do not.
        flipped? (fn [key]
                   (odd? (count (filter identity [(get main key) (get main-parent key)
                                                  (get parent key)]))))]
    (-> placed
        (gsh/move (gpt/subtract (gsh/shape->center parent) center))
        (assoc :rotation (mod (+ (or (:rotation main) 0)
                                 (- (or (:rotation parent) 0) (or (:rotation main-parent) 0))) 360)
               :flip-x (flipped? :flip-x)
               :flip-y (flipped? :flip-y)))))

(defn- with-derived-geometry
  [objects shape expected]
  (let [derived (derived-attributes objects shape)
        width (if (contains? derived :width) (:width shape) (:width expected))
        height (if (contains? derived :height) (:height shape) (:height expected))
        expected (if (and (mth/close? width (:width expected) 0.01)
                          (mth/close? height (:height expected) 0.01))
                   expected
                   (gsh/transform-shape expected (ctm/change-size expected width height)))]
    (gsh/move expected
              (gpt/point (if (contains? derived :x) (- (:x shape) (:x expected)) 0)
                         (if (contains? derived :y) (- (:y shape) (:y expected)) 0)))))

(defn expected-geometry
  [file container libraries shape]
  (let [objects (:objects container)]
    (letfn [(expected [shape visited]
              (when (contains? visited (:id shape))
                (fail :I1 {:shape (:name shape) :reason :parent-cycle}))
              (if (contains? (:touched shape) :geometry-group)
                shape
                (let [main (ctf/find-ref-shape file container libraries shape :with-context? true)
                      parent (get objects (:parent-id shape))
                      main-objects (get-in (meta main) [:container :objects])
                      main-parent (get main-objects (:parent-id main))
                      placed (cond
                               (nil? main) shape
                               (and (:shape-ref parent) main-parent)
                               (child-placement main main-parent (expected parent (conj visited (:id shape)))
                                                main-objects)
                               :else
                               ;; Ordinary parents left by detach still place their nested copies.
                               (let [turn (gmt/multiply (or (:transform shape) (gmt/matrix))
                                                        (or (:transform-inverse main) (gmt/matrix)))
                                     placed (if (gmt/unit? turn) main
                                                (gsh/apply-transform main (gmt/transform-in (gsh/shape->center main) turn)))]
                                 (-> placed
                                     (gsh/move (gpt/subtract (gsh/shape->center shape) (gsh/shape->center placed)))
                                     ;; This is the placement matrix just applied.
                                     ;; Re-decomposing a .01px frame can add scale
                                     ;; noise that grows in its larger children.
                                     (assoc :transform (:transform shape)
                                            :transform-inverse (:transform-inverse shape)
                                            :rotation (:rotation shape)
                                            :flip-x (:flip-x shape) :flip-y (:flip-y shape)))))
                      content (when (ctk/swapped? shape)
                                (ctf/find-content-ref-shape file container libraries shape))
                      placed (if content
                               ;; Content supplies size; the position source
                               ;; still owns the untransformed top-left anchor.
                               (-> placed
                                   (gsh/transform-shape
                                    (ctm/change-size placed (:width content) (:height content)))
                                   (gsh/absolute-move (gpt/point (get-in placed [:selrect :x])
                                                                 (get-in placed [:selrect :y]))))
                               placed)]
                  (with-derived-geometry objects shape placed))))]
      (expected shape #{}))))

(defn- geometry-differences
  [objects shape expected]
  (let [layout-child (and (ctl/any-layout-immediate-child? objects shape)
                          (not (ctl/position-absolute? shape)))
        auto-width (and (= :text (:type shape)) (= :auto-width (:grow-type shape)))
        auto-height (and (= :text (:type shape)) (#{:auto-width :auto-height} (:grow-type shape)))
        derived (derived-attributes objects shape)
        width-owned (contains? derived :width)
        height-owned (contains? derived :height)]
    (cond-> #{}
      (not (mth/close? (or (:rotation shape) 0) (or (:rotation expected) 0) 0.01)) (conj :rotation)
      (not= (boolean (:flip-x shape)) (boolean (:flip-x expected))) (conj :flip-x)
      (not= (boolean (:flip-y shape)) (boolean (:flip-y expected))) (conj :flip-y)
      (and (not width-owned) (not (mth/close? (:width shape) (:width expected) 0.01))) (conj :width)
      (and (not height-owned) (not (mth/close? (:height shape) (:height expected) 0.01))) (conj :height)
      (and (not layout-child) (not auto-width) (not auto-height)
           (not (gsh/close-attrs? :points (:points shape) (:points expected) 0.01))) (conj :points))))

(defn- comparable-values
  [shape context group]
  (let [shape (if (= group :constraints-group)
                (cond-> shape
                  (nil? (:constraints-h shape)) (assoc :constraints-h (gsh/default-constraints-h context))
                  (nil? (:constraints-v shape)) (assoc :constraints-v (gsh/default-constraints-v context)))
                shape)]
    (shadow/values shape group)))

(defn- grid-cells
  [shape]
  (let [indices (zipmap (:shapes shape) (range))]
    (->> (vals (:layout-grid-cells shape))
         (map #(-> % (dissoc :id) (update :shapes (fn [ids] (mapv indices ids)))))
         (sort-by (juxt :row :column))
         vec)))

(defn- check-structure
  [objects shape main]
  (let [refs (mapv #(get-in objects [% :shape-ref]) (:shapes shape))]
    (when-not (= (vec (:shapes main)) refs)
      (fail :I4 {:shape (:name shape) :group :structure
                 :expected (:shapes main) :actual refs}))
    (when (and (= :grid (:layout main)) (not= (grid-cells shape) (grid-cells main)))
      (fail :I4 {:shape (:name shape) :group :layout-grid-cells
                 :expected (grid-cells main) :actual (grid-cells shape)}))))

(defn- check-file
  "I1–I4 and I9. Before settlement only authored groups/values are checked;
   pending main changes may legitimately leave copies stale."
  ([situation model] (check-file situation model true))
  ([situation model settled]
   (let [file (tm/file situation)
         libraries (assoc (tm/aux-files situation) (:id file) file)]
     (when settled (thf/validate-file! file libraries))
     (doseq [page (vals (get-in file [:data :pages-index]))
             :let [container (ctn/make-container page :page)]
             shape (vals (:objects page))
             :when (:shape-ref shape)
             :let [expected (get model (:id shape) {})
                   touched (set (:touched shape))]]
       (when-not (= (set (keys expected)) touched)
         (fail (if (some #(not (contains? expected %)) touched) :I2 :I3)
               {:shape (:name shape) :expected (set (keys expected)) :actual touched}))
       (doseq [[group value] expected]
         (when-not (shadow/same-value? shape group value)
           (fail :I9 {:shape (:name shape) :group group
                      :expected value :actual (shadow/values shape group)})))
       (when settled
         (when-let [main (ctf/find-content-ref-shape file container libraries shape)]
           (check-structure (:objects page) shape main)
           (doseq [group (keys shadow/attributes)
                   :when (and (not= group :geometry-group) (not (contains? touched group)))
                   :when (not (and (= group :name-group) (= :text (:type shape))
                                   (= (:name main) (txt/generate-shape-name (txt/content->text (:content main))))))
                   ;; A copy root's name follows component renaming too.
                   :let [source (if (or (= group :constraints-group)
                                        (.startsWith (name group) "layout-item-"))
                                  (or (ctf/find-ref-shape file container libraries shape) main)
                                  main)
                         expected (comparable-values source shape group)
                         actual (comparable-values shape shape group)]]
             (when-not (= expected actual)
               (fail :I4 {:shape (:name shape) :group group
                          :expected expected :actual actual})))
           (when (= :text (:type shape))
             (doseq [unit (thtx/override-units (:content main) (:content shape))
                     :let [group (some (fn [[group candidate]] (when (= unit candidate) group)) shadow/text-groups)]
                     :when (not (contains? touched group))]
               (fail :I4 {:shape (:name shape) :group group :unit unit}))))
         (when-not (contains? touched :geometry-group)
           (let [expected (expected-geometry file container libraries shape)
                 attrs (geometry-differences (:objects page) shape expected)]
             (when (seq attrs)
               (fail :I4 {:shape (:name shape) :group :geometry-group :attrs attrs
                          :expected (select-keys expected [:width :height :points])
                          :actual (select-keys shape [:width :height :points])}))))))
     nil)))

(defn check
  "Check both the working file and every library in the generated world."
  ([situation model] (check situation model true))
  ([situation model settled]
   (let [files (cf/all-files situation)]
     (doseq [[id file] files]
       (check-file (-> situation (tm/with-file file) (tm/with-aux-files (dissoc files id)))
                   model settled)))
   nil))
