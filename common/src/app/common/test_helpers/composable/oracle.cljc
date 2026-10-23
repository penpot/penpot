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
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.texts :as thtx]
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

(defn design-snapshot
  "Sync equivalence ignores remote-sync bookkeeping, text run splitting and
   geometry noise below 0.01 px. Undo snapshots deliberately keep them."
  [situation]
  (walk/postwalk
   (fn [value]
     (if (and (map? value) (contains? value :parent-id))
       (let [round-geometry (fn [value]
                              (walk/postwalk #(if (number? %) (/ (mth/round (* % 100)) 100) %) value))]
         (cond-> (reduce (fn [shape key]
                           (if (contains? shape key) (update shape key round-geometry) shape))
                         (dissoc value :remote-synced)
                         [:x :y :width :height :points :selrect :transform :transform-inverse :rotation])
           (= :text (:type value)) (update :content thtx/view)))
       value))
   (walk/postwalk
    (fn [value]
      (if (and (map? value) (:objects value))
        (update value :objects
                (fn [objects]
                  (into {} (map (fn [[id shape]]
                                   [id (apply dissoc shape (derived-attributes objects shape))])) objects)))
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
        turn (gmt/multiply (or (:transform parent) (gmt/matrix))
                           (or (:transform-inverse main-parent) (gmt/matrix)))
        placed (if (gmt/unit? turn) main
                   (gsh/apply-transform main (gmt/transform-in center turn)))]
    (gsh/move placed (gpt/subtract (gsh/shape->center parent) center))))

(defn expected-geometry
  [file container libraries shape]
  (let [objects (:objects container)]
    (letfn [(expected [shape visited]
              (when (contains? visited (:id shape))
                (fail :I1 {:shape (:name shape) :reason :parent-cycle}))
              (if (contains? (:touched shape) :geometry-group)
                shape
                (if-let [main (ctf/find-ref-shape file container libraries shape :with-context? true)]
                  (let [parent (get objects (:parent-id shape))
                        main-objects (get-in (meta main) [:container :objects])
                        main-parent (get main-objects (:parent-id main))]
                    (if (and (:shape-ref parent) main-parent)
                      (child-placement main main-parent (expected parent (conj visited (:id shape)))
                                       main-objects)
                      ;; Root placement is authored independently of overrides.
                      (gsh/move main (gpt/subtract (gpt/point (:x shape) (:y shape))
                                                   (gpt/point (:x main) (:y main))))))
                  shape)))]
      (expected shape #{}))))

(defn- geometry-differences
  [objects shape expected]
  (let [layout-child (and (ctl/any-layout-immediate-child? objects shape)
                          (not (ctl/position-absolute? shape)))
        auto-width (and (= :text (:type shape)) (= :auto-width (:grow-type shape)))
        auto-height (and (= :text (:type shape)) (#{:auto-width :auto-height} (:grow-type shape)))
        width-owned (or auto-width (and layout-child (#{:fill :auto} (:layout-item-h-sizing shape))))
        height-owned (or auto-height (and layout-child (#{:fill :auto} (:layout-item-v-sizing shape))))]
    (cond-> #{}
      (and (not width-owned) (not (mth/close? (:width shape) (:width expected) 0.01))) (conj :width)
      (and (not height-owned) (not (mth/close? (:height shape) (:height expected) 0.01))) (conj :height)
      (and (not layout-child) (not auto-width) (not auto-height)
           (not (gsh/close-attrs? :points (:points shape) (:points expected) 0.01))) (conj :points))))

(defn check
  "I1–I4 and I9. Before settlement only authored groups/values are checked;
   pending main changes may legitimately leave copies stale."
  ([situation model] (check situation model true))
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
           (doseq [group (keys shadow/attributes)
                   :when (and (not= group :geometry-group) (not (contains? touched group)))
                   :when (not (and (= group :name-group) (= :text (:type shape))
                                   (= (:name main) (txt/generate-shape-name (txt/content->text (:content main))))))
                   ;; A copy root's name follows component renaming too.
                   :let [expected (shadow/values main group)
                         actual (shadow/values shape group)]]
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
