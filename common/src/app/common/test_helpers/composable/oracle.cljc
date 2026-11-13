;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.oracle
  "Checks authored overrides against actual copies. Expected values come
   from the shadow and near main, never from the sync implementation."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.geometry :as geom]
   [app.common.test-helpers.composable.grid :as grid]
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

(defn check-requested-grid
  "The grid edit `action` gave `shape` the values it asked for. A direction
   change that moves no item of a copy that takes its cells from its main
   leaves the cells its main gives, which I4 checks against the main."
  [shape before action value]
  (let [main-cells? (and (= action :direction) (:shape-ref before)
                         (not (contains? (:touched before) :layout-grid-cells))
                         (= (grid/direction-cells before value) (grid/get-cells before)))
        expected (cond-> (grid/get-intended-values before action value)
                   main-cells? (dissoc :layout-grid-cells))
        actual   (cond-> {:layout-grid-cells (grid/get-cells shape)}
                   main-cells? (dissoc :layout-grid-cells)
                   (= action :direction) (assoc :layout-grid-dir (:layout-grid-dir shape)))]
    (when-not (= expected actual)
      (fail :I9 {:phase :requested-grid :expected expected :actual actual}))))

(defn check-requested-tracks
  "The track edit `action` gave `shape` the rows and columns it asked for.
   An edit that takes cells from children lets the grid grow default tracks
   after its last along its flow, columns for rows and rows for columns, to
   hold them again."
  [shape before action value]
  (let [expected (grid/intended-tracks before action value)
        actual   {:layout-grid-rows (vec (:layout-grid-rows shape))
                  :layout-grid-columns (vec (:layout-grid-columns shape))}
        [growing fixed] (if (= :column (:layout-grid-dir before))
                          [:layout-grid-rows :layout-grid-columns]
                          [:layout-grid-columns :layout-grid-rows])
        wanted   (get expected growing)
        tracks   (get actual growing)
        grown?   (and (= (get expected fixed) (get actual fixed))
                      (> (count tracks) (count wanted))
                      (= wanted (subvec tracks 0 (count wanted)))
                      (every? #(= {:type :flex :value 1} %) (subvec tracks (count wanted))))]
    (when-not (or (= expected actual) grown?)
      (fail :I9 {:phase :requested-tracks :action action :value value
                 :expected expected :actual actual}))))

(defn snapshot
  ([situation] (snapshot situation {}))
  ([situation opts]
   (into {} (map (fn [[id file]]
                   [id (thn/normalize-file file (merge {:position-data? false} opts))]))
         (assoc (tm/aux-files situation) (:id (tm/file situation)) (tm/file situation)))))

(defn history-snapshot
  "The `snapshot` undo and redo must give back: without deleted components,
   since undoing a component's creation leaves it deleted."
  [situation]
  (snapshot situation {:deleted-components? false}))

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
                                          (#{:fill :auto} (:layout-item-v-sizing shape))))
        ;; a layout resizes a turned child along its parent's axes, so both sizes;
        ;; a skewed transform is turned whichever rotation the shape stores
        {:keys [b c]} (or (:transform shape) (gmt/matrix))
        turned (and layout-child (or width-owned height-owned)
                    (or (not (mth/close? (mod (or (:rotation shape) 0) 360) 0))
                        (not (mth/close? (or b 0) 0 1e-6))
                        (not (mth/close? (or c 0) 0 1e-6))))
        ;; the shapes of a group or bool a layout sizes are sized with it
        parent (get objects (:parent-id shape))
        sized-by-parent (when (#{:group :bool} (:type parent))
                          (derived-attributes objects parent))
        width-owned (or width-owned turned (contains? sized-by-parent :width))
        height-owned (or height-owned turned (contains? sized-by-parent :height))]
    (cond-> #{}
      layout-child (into #{:x :y})
      width-owned (conj :width)
      height-owned (conj :height)
      (or layout-child width-owned height-owned) (into #{:points :selrect}))))

(defn- matrix-effect
  "Matrix coefficients have no pixel unit. Compare their effect over the
   shape's local bounds. Keep the inputs for paired derived-axis comparison."
  [shape matrix derived]
  (let [;; a bool or path has its size in its selrect only
        bounds {:width (or (:width shape) (get-in shape [:selrect :width]) 0)
                :height (or (:height shape) (get-in shape [:selrect :height]) 0)}
        half-width (/ (:width bounds) 2)
        half-height (/ (:height bounds) 2)]
    (with-meta
      (mapv #(gpt/transform % (or matrix (gmt/matrix)))
            [(gpt/point (- half-width) (- half-height))
             (gpt/point half-width (- half-height))
             (gpt/point half-width half-height)
             (gpt/point (- half-width) half-height)])
      {::matrix matrix ::bounds bounds ::derived derived})))

(declare get-derived-reference-origins)

(defn untracked-position?
  "Whether `shape` is hidden inside a layout: out of its flow and invisible,
   its position is nobody's to keep, and the layout places it once shown."
  [objects shape]
  (and (:hidden shape)
       (ctl/any-layout-immediate-child? objects shape)))

(defn- canonical-variants
  "`content` with the variant id of each numeric weight the one the notation
   gives its family, weight and style: weights are told apart by weight and
   style, and the renderer picks the variant closest to them."
  [content]
  (walk/postwalk
   (fn [node]
     (if-let [weight (and (map? node)
                          (contains? node :font-variant-id)
                          (d/parse-integer (str (:font-weight node))))]
       (assoc node :font-variant-id
              (thtx/variant-id (:font-family node) weight (= "italic" (:font-style node))))
       node))
   content))

(defn- without-unreferenced-deleted
  "The snapshot `files` without the deleted components no shape outside
   their own objects refers to, as an undone creation: only redo brings one
   back, so no sync reaches its stored copies."
  [files]
  (let [referenced (into #{}
                         (for [[_ file] files
                               [owner objects] (concat (map (fn [page] [nil (:objects page)])
                                                            (vals (get-in file [:data :pages-index])))
                                                       (keep (fn [[id component]]
                                                               (when (:objects component) [id (:objects component)]))
                                                             (get-in file [:data :components])))
                               shape (vals objects)
                               :let [id (:component-id shape)]
                               :when (and id (not= id owner))]
                           id))]
    (update-vals files
                 (fn [file]
                   (d/update-in-when file [:data :components]
                                     (fn [components]
                                       (into {} (remove (fn [[id component]]
                                                          (and (:deleted component) (not (contains? referenced id)))))
                                             components)))))))

(defn design-snapshot
  "Design inputs without derived placement, dimensions and bookkeeping.
   Geometry remains unrounded; equivalent? applies its tolerance directly.
   With `:unreferenced-deleted-components false`, the deleted components
   nothing refers to are left out."
  ([situation] (design-snapshot situation {}))
  ([situation {:keys [free-head-placement relative-free-head-placement unreferenced-deleted-components]
               :or {free-head-placement true unreferenced-deleted-components true}}]
   (let [origins (when (and free-head-placement relative-free-head-placement)
                   (get-derived-reference-origins situation))]
     (walk/postwalk
      (fn [value]
        (if (and (map? value) (contains? value :parent-id))
          (cond-> (-> value
                      (dissoc :remote-synced)
                      ;; with the transform, the flips only add whether it mirrors:
                      ;; one flip or the other is the same mirror turned half a turn
                      (assoc :flip-x (not= (boolean (:flip-x value)) (boolean (:flip-y value)))
                             :flip-y false)
                      (update :fixed-scroll boolean))
            (= :text (:type value)) (update :content (comp thtx/view canonical-variants))
            ;; path segments as plain data, so geometry tolerance reaches their points
            (and (= :path (:type value)) (some? (:content value))) (update :content #(into [] %))
            ;; a bool computes its segments from its children, which the design
            ;; holds, in an order that depends on how it was computed
            (= :bool (:type value)) (dissoc :content)
            ;; a group or bool takes its corners from its children, in any order
            (#{:group :bool} (:type value)) (update :points #(some-> % geom/ordered-corners vec))
            ;; the transform carries the turn, whichever rotation the shape
            ;; stores for it
            :always (dissoc :rotation))
          value))
      (walk/postwalk
       (fn [value]
         (if (and (map? value) (:objects value))
           (update value :objects
                   (fn [objects]
                     (into {} (map (fn [[id shape]]
                                     [id (-> (gsh/move shape
                                                       (gpt/negate
                                                        (or (get origins id)
                                                            ;; a group or bool takes its bounds from its
                                                            ;; children, so they measure from its board
                                                            (let [parent (get objects (:parent-id shape))
                                                                  parent (if (#{:group :bool} (:type parent))
                                                                           (get objects (:frame-id shape))
                                                                           parent)]
                                                              (gpt/point (or (:x parent) (get-in parent [:selrect :x]) 0)
                                                                         (or (:y parent) (get-in parent [:selrect :y]) 0))))))
                                             (as-> moved (let [derived (derived-attributes objects shape)]
                                                           (assoc moved
                                                                  :transform (matrix-effect shape (:transform moved) derived)
                                                                  ;; what the inverse undoes of the transform: its own effect magnifies a squashed axis
                                                                  :transform-inverse (matrix-effect shape (gmt/multiply (or (:transform-inverse moved) (gmt/matrix))
                                                                                                                        (or (:transform moved) (gmt/matrix)))
                                                                                                    derived))))
                                             ;; Measurement preserves the oriented first
                                             ;; corner, not the unrotated rectangle's x/y.
                                             (cond-> (and (= :text (:type shape))
                                                          (#{:auto-width :auto-height} (:grow-type shape)))
                                               (as-> moved (assoc moved :x (:x (first (:points moved)))
                                                                  :y (:y (first (:points moved))))))
                                             (as-> snapshot (apply dissoc snapshot
                                                                   (cond-> (derived-attributes objects shape)
                                                                     (or (untracked-position? objects shape)
                                                                         (and free-head-placement (:component-id shape) (:shape-ref shape)
                                                                              (= 1 (count (ctn/get-parent-copy-heads objects shape)))))
                                                                     (into #{:x :y :points :selrect}))))
                                             (update :constraints-h #(or % (gsh/default-constraints-h shape)))
                                             (update :constraints-v #(or % (gsh/default-constraints-v shape)))
                                             ;; a deleted component keeps its objects as stored, and sync
                                             ;; does not lay them out again: the geometry a layout gave a
                                             ;; shape, or one below it, is not part of its design
                                             (cond-> (and (:deleted value)
                                                          (some #(seq (derived-attributes objects (get objects %)))
                                                                (cons (:id shape) (cfh/get-parent-ids objects (:id shape)))))
                                               (dissoc :x :y :width :height :points :selrect :transform :transform-inverse)))])) objects)))
           value))
       (cond-> (snapshot situation)
         (not unreferenced-deleted-components) without-unreferenced-deleted))))))

(defn canonical
  "Design data with shape, component and grid-cell IDs named by tree path.
   Pure and frontend backends allocate different IDs for the same action.
   Missing references use retained source paths; live names take precedence."
  ([situation] (canonical situation {}))
  ([situation opts]
   (let [files (sort-by (comp str key) (snapshot situation))
         ids   (atom {#uuid "00000000-0000-0000-0000-000000000000" :root})]
     (letfn [(name-tree [names objects id path]
               (when-let [shape (get objects id)]
                 (swap! names assoc id path)
                 (doseq [[cell-id cell] (:layout-grid-cells shape)]
                   (swap! names assoc cell-id [path :cell (:row cell) (:column cell)]))
                 (doseq [[index child] (map-indexed vector (:shapes shape))]
                   (name-tree names objects child (conj path index)))))
             (name-file [names file path]
               (swap! names assoc (:id file) path)
               (doseq [[page-index page-id] (map-indexed vector (get-in file [:data :pages]))
                       :let [page (get-in file [:data :pages-index page-id])
                             path (conj path :page page-index)]]
                 (swap! names assoc page-id path)
                 (doseq [[index child] (map-indexed vector (get-in page [:objects #uuid "00000000-0000-0000-0000-000000000000" :shapes]))]
                   (name-tree names (:objects page) child (conj path index))))
               (doseq [[index [component-id component]]
                       (map-indexed vector (sort-by (comp str key) (get-in file [:data :components])))
                       :let [path (conj path :component index)]]
                 (swap! names assoc component-id path)
                 (when (:objects component)
                   (name-tree names (:objects component) (:main-instance-id component) (conj path :main)))))]
       (doseq [[file-index [_ file]] (map-indexed vector files)]
         (name-file ids file [:file file-index]))
       (when (:accepted-libraries situation)
         (doseq [[file-id file objects shape]
                 (sort-by #(pr-str (get @ids (:id (nth % 3))))
                          (for [[file-id file] files
                                objects (cf/object-trees file)
                                shape (vals objects)
                                :when (and (:shape-ref shape) (contains? @ids (:id shape)))]
                            [file-id file objects shape]))
                 :when (not (contains? @ids (:shape-ref shape)))]
           (let [libraries (cf/reference-libraries situation file-id (:id shape))
                 container (ctn/make-container {:objects objects} :page)]
             (when-let [reference (ctf/find-ref-shape file container libraries shape
                                                      :include-deleted? true :with-context? true)]
               (let [source (:file (meta reference))]
                 (when-let [source-path (get @ids (:id source))]
                   (let [names (atom {})]
                     (name-file names source source-path)
                     (when-let [path (get @names (:shape-ref shape))]
                       (swap! ids assoc (:shape-ref shape) [:retained-ref (get @ids (:id shape)) path])))))))))
       (walk/postwalk #(if (uuid? %) (get @ids % %) %) (design-snapshot situation opts))))))

(defn- paired-matrix-effects
  [shape other]
  (reduce
   (fn [shape attr]
     (let [{:keys [::matrix ::bounds ::derived]} (meta (get shape attr))
           other-bounds (::bounds (meta (get other attr)))]
       (if (or (nil? other-bounds) (not-any? derived [:width :height]) (= bounds other-bounds))
         shape
         (let [bounds (reduce (fn [bounds axis]
                                (cond-> bounds
                                  (contains? derived axis)
                                  (assoc axis (max (get bounds axis) (get other-bounds axis)))))
                              bounds [:width :height])]
           (assoc shape attr (matrix-effect bounds matrix derived))))))
   shape [:transform :transform-inverse]))

(defn equivalent?
  "Compare design data, allowing 0.01 only within shape geometry. Compare
   matrices over common derived dimensions, retaining each fixed dimension."
  [expected actual]
  (if (and (map? expected) (map? actual))
    (and (= (set (keys expected)) (set (keys actual)))
         (if (and (contains? expected :parent-id) (contains? actual :parent-id))
           (uc/same-state? (paired-matrix-effects expected actual)
                           (paired-matrix-effects actual expected) 0.01)
           (every? #(equivalent? (get expected %) (get actual %)) (keys expected))))
    (uc/same-state? expected actual 0.01)))

(defn- constraint-derived-attributes
  "The dimensions a constraint ties to the parent. Under a plain parent the
   parent's resize history is unknown, and such a resize is not an override."
  [shape]
  (cond-> #{}
    (#{:scale :leftright} (:constraints-h shape)) (conj :width)
    (#{:scale :topbottom} (:constraints-v shape)) (conj :height)))

(defn- with-derived-geometry
  "Derived dimensions and positions come from the shape. A placement centred
   on the copy (`centred?`) assumed the main's size, so a derived resize keeps
   the shape's first corner, as a layout resize does."
  [objects shape expected centred?]
  (let [shape   (geom/boxed shape)
        expected (geom/boxed expected)
        derived (cond-> (derived-attributes objects shape)
                  centred? (into (constraint-derived-attributes shape)))
        width (if (contains? derived :width) (:width shape) (:width expected))
        height (if (contains? derived :height) (:height shape) (:height expected))
        resized? (not (and (mth/close? width (:width expected) 0.01)
                           (mth/close? height (:height expected) 0.01)))
        expected (cond-> expected
                   resized? (gsh/transform-shape (ctm/change-size expected width height)))
        expected (cond-> expected
                   (and resized? centred?)
                   (gsh/move (gpt/subtract (first (:points shape)) (first (:points expected)))))]
    (if (every? derived [:x :y :width :height])
      ;; the layout gives the whole geometry, with the skew of a turned child
      (merge expected (select-keys shape [:x :y :width :height :points :selrect
                                          :transform :transform-inverse]))
      (gsh/move expected
                (gpt/point (if (contains? derived :x) (- (:x shape) (:x expected)) 0)
                           (if (contains? derived :y) (- (:y shape) (:y expected)) 0))))))

(declare same-turn?)

(defn- same-orientation?
  [shape other]
  (and (same-turn? (or (:rotation shape) 0) (or (:rotation other) 0))
       (= (boolean (:flip-x shape)) (boolean (:flip-x other)))
       (= (boolean (:flip-y shape)) (boolean (:flip-y other)))))

(defn- in-parent-orientation
  "`main` turned by its parent `main-parent`'s difference to `parent`,
   about its own centre, with the top-left corner of `shape`."
  [main main-parent parent shape]
  (let [relative (gmt/multiply (or (:transform parent) (gmt/matrix))
                               (or (:transform-inverse main-parent) (gmt/matrix)))
        flip     (fn [key]
                   (cond-> (boolean (get main key))
                     (not= (boolean (get main-parent key)) (boolean (get parent key))) not))
        turned   (cond-> main
                   (not (gmt/unit? relative))
                   (gsh/apply-transform (gmt/transform-in (gsh/shape->center main) relative)))]
    (-> turned
        (gsh/move (gpt/subtract (gpt/point (-> shape :selrect :x) (-> shape :selrect :y))
                                (gpt/point (-> turned :selrect :x) (-> turned :selrect :y))))
        (assoc :rotation (mod (+ (or (:rotation main) 0)
                                 (- (or (:rotation parent) 0) (or (:rotation main-parent) 0)))
                              360)
               :flip-x (flip :flip-x)
               :flip-y (flip :flip-y)))))

(defn expected-geometry
  ([file container libraries shape]
   (expected-geometry file container libraries shape (constantly libraries)))
  ([file container libraries shape libraries-for-shape]
   (let [objects (:objects container)]
     (letfn [(expected [shape visited]
               (when (contains? visited (:id shape))
                 (fail :I1 {:shape (:name shape) :reason :parent-cycle}))
               (if (or (contains? (:touched shape) :geometry-group)
                       ;; a constraint the copy authors applies to later resizes only
                       (and (contains? (:touched shape) :constraints-group)
                            (seq (constraint-derived-attributes shape))
                            (:shape-ref (get objects (:parent-id shape)))))
                 shape
                 (let [libraries (libraries-for-shape shape)
                       main (ctf/find-ref-shape file container libraries shape :include-deleted? true :with-context? true)
                       parent (get objects (:parent-id shape))
                       ;; A parent resize reaches the child through the child's own
                       ;; constraints once the copy authors them.
                       main-objects (cond-> (get-in (meta main) [:container :objects])
                                      (and main (contains? (:touched shape) :constraints-group))
                                      (update (:id main) merge
                                              (d/without-nils (select-keys shape [:constraints-h :constraints-v]))))
                       main-parent (get main-objects (:parent-id main))
                       ;; a group or bool takes its bounds and turn from its children,
                       ;; so they take their place from the board around it, or from
                       ;; the nearest group or bool on the way that authors its geometry
                       [parent main-parent]
                       (if (ctl/any-layout? (get objects (:frame-id shape)))
                         [parent main-parent]
                         (loop [parent parent main-parent main-parent]
                           (if (and (#{:group :bool} (:type parent))
                                    (#{:group :bool} (:type main-parent))
                                    (not (contains? (:touched parent) :geometry-group)))
                             (recur (get objects (:parent-id parent)) (get main-objects (:parent-id main-parent)))
                             [parent main-parent])))
                       ;; a reset refreshes its subtree from the loaded library while
                       ;; the parent keeps the version it accepted, which may lack a turn;
                       ;; a swapped parent holds the child in the main of its content
                       parent-main (when (and main-parent (:shape-ref parent)
                                              (not (contains? (:touched parent) :geometry-group)))
                                     (ctf/find-content-ref-shape file container (libraries-for-shape parent) parent
                                                                 :include-deleted? true))
                       pending-turn? (and (some? parent-main)
                                          (not (same-orientation? parent-main main-parent)))
                       centred? (and (some? main) (not (and (:shape-ref parent) main-parent)))
                       placed (cond
                                (nil? main) shape

                                ;; the head takes its main's size in the parent's
                                ;; orientation, from its own corner
                                pending-turn?
                                (in-parent-orientation main main-parent parent shape)

                                (and (:shape-ref parent) main-parent)
                                (geom/child-placement main main-parent (expected parent (conj visited (:id shape)))
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
                                 (ctf/find-content-ref-shape file container libraries shape :include-deleted? true))
                       placed (if content
                                ;; Content supplies size about the first corner,
                                ;; which a turned or flipped head keeps; an
                                ;; unturned one keeps its untransformed top-left.
                                (cond-> (gsh/transform-shape
                                         placed (ctm/change-size placed (:width content) (:height content)))
                                  (gmt/unit? (or (:transform placed) (gmt/matrix)))
                                  (gsh/absolute-move (gpt/point (get-in placed [:selrect :x])
                                                                (get-in placed [:selrect :y]))))
                                placed)]
                   (with-derived-geometry objects shape placed centred?))))]
       (expected shape #{})))))

(defn- get-derived-reference-origins
  "Backend parity permits source placement derived by reflow to differ.
   Untouched images compare offsets from their expected source frame,
   including hidden/absolute children of visible layout sources. Inherited
   placement can differ while a drifted image still fails.
   Strict same-run sync comparison never uses these origins."
  [situation]
  (into {}
        (for [[file-id file] (cf/all-files situation)
              page (vals (get-in file [:data :pages-index]))
              [id shape] (:objects page)
              :when (and (:shape-ref shape)
                         (not (ctk/touched-group? shape :geometry-group)))
              :let [libraries (cf/reference-libraries situation file-id id)
                    source (ctf/find-ref-shape file page libraries shape
                                               :include-deleted? true :with-context? true)
                    source-objects (get-in (meta source) [:container :objects])]
              :when (or (and (:component-id shape)
                             (> (count (ctn/get-parent-copy-heads (:objects page) shape)) 1)
                             (:component-id source) (:shape-ref source)
                             (= 1 (count (ctn/get-parent-copy-heads source-objects source))))
                        (and (not (contains? (derived-attributes (:objects page) shape) :x))
                             (contains? (derived-attributes source-objects source) :x)))
              :let [expected (expected-geometry file page libraries shape
                                                #(cf/reference-libraries situation file-id (:id %)))]]
          [id (gpt/point (:x expected) (:y expected))])))

(defn- same-turn?
  "Whether two rotations in degrees describe the same turn."
  [a b]
  (let [difference (mod (- a b) 360)]
    (or (< difference 0.01) (> difference 359.99))))

(defn- geometry-differences
  ([objects shape expected] (geometry-differences objects shape expected shape))
  ([objects shape expected ownership]
   (let [shape        (geom/boxed shape)
         expected     (geom/boxed expected)
         rotation     (or (:rotation shape) 0)
         exp-rotation (or (:rotation expected) 0)
         ;; Under one flip, either order of turn and flip stores the opposite turn.
         mirrored     (not= (boolean (:flip-x shape)) (boolean (:flip-y shape)))
         layout-child (and (ctl/any-layout-immediate-child? objects ownership)
                           (not (ctl/position-absolute? ownership)))
         auto-width   (and (= :text (:type ownership)) (= :auto-width (:grow-type ownership)))
         auto-height  (and (= :text (:type ownership)) (#{:auto-width :auto-height} (:grow-type ownership)))
         derived      (derived-attributes objects ownership)
         ;; a layout that sizes the group or bool around the shape places it too
         group-sized  (let [parent (get objects (:parent-id ownership))]
                        (and (#{:group :bool} (:type parent))
                             (some #{:width :height} (derived-attributes objects parent))))
         width-owned  (contains? derived :width)
         height-owned (contains? derived :height)
         ;; Both flags and a half turn decompose the transform neither does.
         same-corners? (gsh/close-attrs? :points (:points shape) (:points expected) 0.01)
         both-flipped (and (not= (boolean (:flip-x shape)) (boolean (:flip-x expected)))
                           (not= (boolean (:flip-y shape)) (boolean (:flip-y expected)))
                           (geom/decomposes? shape))
         ;; a group or bool takes its corners from its children: at its
         ;; expected corners, its turn and flips say nothing more; nor do those
         ;; of its children, whose orientation it stores unreliably
         in-group? (#{:group :bool} (:type (get objects (:parent-id ownership))))
         derived (and (or (#{:group :bool} (:type shape)) in-group?)
                      (gsh/close-attrs? :points (geom/ordered-corners (:points shape))
                                        (geom/ordered-corners (:points expected)) 0.01))]
     (cond-> #{}
       ;; Parents may store equivalent decompositions, so a rotation that
       ;; decomposes the shape's own transform is its expected turn too, as
       ;; is any rotation of a shape at its expected corners.
       (not (or derived
                (same-turn? rotation exp-rotation)
                (and mirrored (same-turn? rotation (- exp-rotation)))
                (geom/decomposes? shape)
                (gsh/close-attrs? :points (:points shape) (:points expected) 0.01))) (conj :rotation)
       ;; ordered corners that match fix the transform, so flags that decompose
       ;; it store the same transform otherwise
       (and (not both-flipped) (not derived) (not= (boolean (:flip-x shape)) (boolean (:flip-x expected)))
            (not (and same-corners? (geom/decomposes? shape)))) (conj :flip-x)
       (and (not both-flipped) (not derived) (not= (boolean (:flip-y shape)) (boolean (:flip-y expected)))
            (not (and same-corners? (geom/decomposes? shape)))) (conj :flip-y)
       (and (not width-owned) (not (mth/close? (:width shape) (:width expected) 0.01))) (conj :width)
       (and (not height-owned) (not (mth/close? (:height shape) (:height expected) 0.01))) (conj :height)
       (and (not layout-child) auto-height
            (not (gsh/close-attrs? :points [(first (:points shape))]
                                   [(first (:points expected))] 0.01))) (conj :position)
       (and (not layout-child) (not auto-width) (not auto-height) (not group-sized)
            ;; a bool computes its corners from its segments, in their order
            (not (if (or (= :bool (:type shape)) in-group?)
                   (gsh/close-attrs? :points (geom/ordered-corners (:points shape))
                                     (geom/ordered-corners (:points expected)) 0.01)
                   (gsh/close-attrs? :points (:points shape) (:points expected) 0.01)))) (conj :points)))))

(defn check-requested-geometry
  [objects actual expected]
  (when-let [attrs (seq (geometry-differences objects actual expected))]
    (fail :I9 {:phase :requested-geometry :attrs (set attrs)
               :shape (:name actual) :expected expected :actual actual})))

(defn- comparable-values
  [shape context group]
  (let [shape (if (= group :constraints-group)
                (cond-> (update shape :fixed-scroll boolean)
                  (nil? (:constraints-h shape)) (assoc :constraints-h (gsh/default-constraints-h context))
                  (nil? (:constraints-v shape)) (assoc :constraints-v (gsh/default-constraints-v context)))
                shape)]
    (shadow/values shape group)))

(defn- get-world-objects
  [situation]
  (into {} (mapcat cf/object-trees) (vals (cf/all-files situation))))

(defn check-requested-update-main
  "Keep authored intent after the shadow clears the source copy's groups.
   Only identities come from the result; expected values come from before."
  [before after model root-id]
  (let [source-objects (get-world-objects before)
        objects        (get-world-objects after)
        root           (get source-objects root-id)
        main-id        (:shape-ref root)
        subtree        (fn subtree [objects id]
                         (cons id (mapcat #(subtree objects %) (:shapes (get objects id)))))
        destination    (set (subtree objects main-id))
        mismatch       (fn [id group expected actual]
                         (fail :I9 {:phase :requested-update-main :shape-id id :group group
                                    :expected expected :actual actual}))]
    (doseq [id (subtree source-objects root-id)
            :let [source (get source-objects id)
                  kept   (get objects id)
                  target (get objects (if (= id root-id) main-id (:shape-ref kept)))]
            group (keys (get model id))]
      (when-not (and kept target (contains? destination (:id target)))
        (mismatch id group :destination-main (:id target)))
      (cond
        (= group :geometry-group)
        ;; The source copy stays in its own frame. Existing geometry ownership
        ;; and tolerance apply; I4 then checks its placement against the main.
        (let [source-parent (get source-objects (:parent-id source))
              kept-parent   (get objects (:parent-id kept))
              ;; a parent the push only moves, as a layout does a variant, carries the copy along
              source        (cond-> source
                              (and source-parent kept-parent
                                   (= (:id source-parent) (:id kept-parent))
                                   ;; a bool has no size but its selrect's
                                   (mth/close? (-> source-parent :selrect :width) (-> kept-parent :selrect :width) 0.01)
                                   (mth/close? (-> source-parent :selrect :height) (-> kept-parent :selrect :height) 0.01))
                              (gsh/move (gpt/subtract (first (:points kept-parent))
                                                      (first (:points source-parent)))))
              attrs         (geometry-differences source-objects kept source source)]
          (when (or (seq attrs)
                    (not= (or (:content-scale source) 1) (or (:content-scale kept) 1)))
            (mismatch id group (shadow/values source group) (shadow/values kept group))))

        (and (= group :name-group) (= id root-id) (:variant-id target))
        ;; a variant main keeps its set's name, and the copy root its own
        (when-not (= (:name source) (:name kept))
          (mismatch id group (:name source) (:name kept)))

        (= group :swap-group)
        (when-not (= (select-keys source [:component-id :component-file])
                     (select-keys target [:component-id :component-file]))
          (mismatch id group (select-keys source [:component-id :component-file])
                    (select-keys target [:component-id :component-file])))

        :else
        (let [item-group    (.startsWith (name group) "layout-item-")
              intrinsic     (and (:layout source)
                                 (#{:layout-item-h-sizing :layout-item-v-sizing} group)
                                 (#{:fix :auto} (get source group)))
              ;; only a layout parent in the main pushes layout child groups
              push-item     (or intrinsic
                                (and (not= id root-id) (:layout (get objects (:parent-id target)))))
              target        (if (and item-group (not push-item)) kept target)
              expected      (if (= group :layout-grid-cells)
                              (get (grid/remap-groups {group (shadow/values source group)}
                                                      (grid/get-child-map objects source target)) group)
                              (comparable-values source target group))
              actual        (comparable-values target target group)]
          (when-not (if (contains? shadow/text-groups group)
                      (shadow/same-value? target group expected)
                      (= expected actual))
            (mismatch id group expected actual)))))))

(defn- grid-cells
  [shape]
  (let [indices (zipmap (:shapes shape) (range))]
    (->> (vals (:layout-grid-cells shape))
         (map #(-> % (dissoc :id) (update :shapes (fn [ids] (mapv indices ids)))))
         (sort-by (juxt :row :column))
         vec)))

(defn- expected-grid-cells
  "Source cells with the copy's hidden/absolute children removed. A gap,
   manual cell or span ends the automatic prefix of the copy's own flow;
   visible children without a source cell take free cells in that order.
   No production assignment helper supplies these expectations."
  [objects shape main]
  (let [flow-order (if (= :column (:layout-grid-dir shape))
                     (juxt :column :row)
                     (juxt :row :column))
        participating (into #{}
                            (keep-indexed (fn [index id]
                                            (let [child (get objects id)]
                                              (when-not (or (:hidden child) (:layout-item-absolute child))
                                                index))))
                            (:shapes shape))
        end-auto-prefix
        (fn [cells]
          (second
           (reduce (fn [[ended result] cell]
                     (let [span (or (not= 1 (:row-span cell)) (not= 1 (:column-span cell)))
                           occupied (seq (:shapes cell))
                           manual (or (= :manual (:position cell)) span
                                      (and ended occupied (= :auto (:position cell))))]
                       [(or ended manual (not occupied))
                        (conj result (cond-> cell
                                       (and manual (= :auto (:position cell)))
                                       (assoc :position :manual)))]))
                   [false []] cells)))
        rows    (count (:layout-grid-rows shape))
        columns (count (:layout-grid-columns shape))
        ;; a copy with its own track lists keeps its main's cells within them
        fitted  (if (and (= rows (count (:layout-grid-rows main)))
                         (= columns (count (:layout-grid-columns main))))
                  (grid-cells main)
                  (grid/fit-cells (grid-cells main) rows columns (or (:layout-grid-dir shape) :row)))
        cells (->> fitted
                   (sort-by flow-order)
                   (mapv #(update % :shapes (fn [ids] (filterv participating ids))))
                   end-auto-prefix)
        assigned (into #{} (mapcat :shapes) cells)
        missing (remove assigned (sort participating))
        [cells _] (reduce (fn [[result pending] cell]
                            (if (and (seq pending) (empty? (:shapes cell)))
                              [(conj result (assoc cell :position :auto :shapes [(first pending)]))
                               (rest pending)]
                              [(conj result cell) pending]))
                          [[] missing] cells)]
    (->> cells end-auto-prefix (sort-by (juxt :row :column)) vec)))

(defn- loaded-shape?
  [libraries id]
  (some (fn [file] (some #(contains? (:objects %) id) (vals (get-in file [:data :pages-index]))))
        (vals libraries)))

(defn- check-structure
  "A child with no ref stands for a main child the loaded library has
   removed, or had removed when a detach left it (`unlinked`): detaching
   around a stale copy leaves it as a plain shape, which accepting the
   library replaces. A grid copy with its own cells holds the main's
   children in the flow of those cells."
  [objects shape main authored loaded unlinked]
  (let [refs (mapv (fn [id main-id]
                     (let [ref (get-in objects [id :shape-ref])]
                       (if (and (nil? ref) (some? main-id)
                                (or (not (loaded-shape? loaded main-id)) (contains? unlinked id)))
                         main-id
                         ref)))
                   (:shapes shape)
                   (concat (:shapes main) (repeat nil)))
        own-flow? (and (= :grid (:layout shape)) (contains? authored :layout-grid-cells))]
    (when-not (if own-flow?
                (and (= (frequencies (:shapes main)) (frequencies refs))
                     (= (vec (:shapes shape)) (grid/flow-shapes shape)))
                (= (vec (:shapes main)) refs))
      (fail :I4 {:shape (:name shape) :group :structure
                 :expected (:shapes main) :actual refs}))
    ;; Authored cells have already been checked against independent intent
    ;; by I9. Child structure still follows the main in every case.
    (when (and (= :grid (:layout main)) (not (contains? authored :layout-grid-cells)))
      (let [expected (expected-grid-cells objects shape main)
            actual (grid-cells shape)]
        (when-not (= expected actual)
          (fail :I4 {:shape (:name shape) :group :layout-grid-cells
                     :expected expected :actual actual}))))))

(defn- inherited-layout-item?
  "A root's placement in an unrelated flex/grid belongs to that placement.
   Layout child attrs inherit only through corresponding layout parents;
   intrinsic fix/auto sizing also belongs to the component itself."
  [file container libraries shape group]
  (let [source (ctf/find-ref-shape file container libraries shape :include-deleted? true :with-context? true)
        source-objects (get-in (meta source) [:container :objects])
        parent (get (:objects container) (:parent-id shape))
        source-parent (get source-objects (:parent-id source))]
    (or (ctk/swapped? shape)
        (and (#{:layout-item-h-sizing :layout-item-v-sizing} group)
             (#{:auto :fix} (get source group)))
        (and (:layout source-parent) (:layout parent)
             (= (:id source-parent) (:shape-ref parent))))))

(defn- mixed-typography-ownership?
  "A reference cannot describe styles split between the copy and main."
  [touched]
  (let [copy-reference? (contains? touched :text-content-typography)]
    (boolean
     (some #(not= copy-reference? (contains? touched %))
           [:text-content-family :text-content-weight :text-content-size
            :text-content-line-height :text-content-spacing :text-content-case]))))

(defn- check-file
  "I1–I4 and I9. Before settlement only authored groups/values are checked;
   pending main changes may legitimately leave copies stale."
  ([situation model] (check-file situation model true))
  ([situation model settled]
   (let [file (tm/file situation)
         libraries (cf/libraries situation)
         ;; the shape loop rebinds libraries to each copy's reference inputs
         loaded libraries]
     (when settled (thf/validate-file! file libraries))
     (doseq [page (vals (get-in file [:data :pages-index]))
             :let [container (ctn/make-container page :page)]
             shape (vals (:objects page))
             :let [libraries (cf/reference-libraries situation shape)
                   expected (if (:shape-ref shape) (get model (:id shape) {}) {})
                   touched (set (:touched shape))]]
       (when-not (= (set (keys expected)) touched)
         (fail (if (some #(not (contains? expected %)) touched) :I2 :I3)
               {:shape (:name shape) :expected (set (keys expected)) :actual touched}))
       (doseq [[group value] expected]
         (when-not (and (shadow/same-value? shape group value (:objects page))
                        (or (not= group :layout-grid-cells)
                            (grid/matches-context? shape (:layout-grid-cells value))))
           (fail :I9 {:shape (:name shape) :group group
                      :expected value :actual (shadow/values shape group)})))
       (when (and settled (:shape-ref shape))
         (when-let [main (ctf/find-content-ref-shape file container libraries shape :include-deleted? true)]
           (check-structure (:objects page) shape main expected loaded (n/unlinked-children situation))
           (doseq [group (keys shadow/attributes)
                   :when (and (not= group :geometry-group) (not (contains? touched group)))
                   :when (not (and (= group :name-group) (= :text (:type shape))
                                   (= (:name main) (txt/generate-shape-name (txt/content->text (:content main))))))
                   :when (or (not (.startsWith (name group) "layout-item-"))
                             (inherited-layout-item? file container libraries shape group))
                   ;; A copy root's name follows component renaming too.
                   :let [source (if (or (= group :constraints-group)
                                        (.startsWith (name group) "layout-item-"))
                                  (or (ctf/find-ref-shape file container libraries shape :include-deleted? true) main)
                                  main)
                         expected (comparable-values source shape group)
                         actual (comparable-values shape shape group)
                         ;; a grid grows default tracks after its last along its flow
                         ;; to hold children without a free slot
                         grown? (and (= group (if (= :column (:layout-grid-dir shape))
                                                :layout-grid-rows :layout-grid-columns))
                                     (let [wanted (vec (get expected group))
                                           tracks (vec (get actual group))]
                                       (and (> (count tracks) (count wanted))
                                            (= wanted (subvec tracks 0 (count wanted)))
                                            (every? #(= {:type :flex :value 1} %)
                                                    (subvec tracks (count wanted))))))]]
             (when-not (or (= expected actual) grown?)
               (fail :I4 {:shape (:name shape) :group group
                          :expected expected :actual actual})))
           (when (= :text (:type shape))
             (let [drop-reference? (mixed-typography-ownership? touched)]
               (when (and drop-reference?
                          (some #(or (:typography-ref-id %) (:typography-ref-file %))
                                (tree-seq map? :children (:content shape))))
                 (fail :I4 {:shape (:name shape) :group :text-content-typography
                            :unit :typography :expected nil}))
               (doseq [unit (thtx/override-units (:content main) (:content shape))
                       :when (not (and drop-reference? (= :typography unit)))
                       :let [group (some (fn [[group candidate]] (when (= unit candidate) group)) shadow/text-groups)]
                       :when (not (contains? touched group))]
                 (fail :I4 {:shape (:name shape) :group group :unit unit})))))
         ;; a group or bool takes its bounds from its children, which are checked
         (when-not (or (contains? touched :geometry-group) (#{:group :bool} (:type shape)))
           (let [expected (expected-geometry file container libraries shape
                                             #(cf/reference-libraries situation %))
                 attrs (cond-> (geometry-differences (:objects page) shape expected)
                         (untracked-position? (:objects page) shape) (disj :points :position))]
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
