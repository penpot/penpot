;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.shadow
  "Authored overrides, kept independently of production touched writes.
   Each shape id maps groups to the attribute values the user authored."
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.geometry :as geom]
   [app.common.test-helpers.composable.grid :as grid]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.container :as ctn]))

;; This table is a specification, deliberately not ctk/sync-attrs.
(def groups
  {:fills :fill-group :opacity :layer-effects-group :name :name-group
   :strokes :stroke-group :hidden :visibility-group :blocked :modifiable-group
   :r1 :radius-group :r2 :radius-group :r3 :radius-group :r4 :radius-group
   :shadow :shadow-group :blur :blur-group :background-blur :blur-group
   :blend-mode :layer-effects-group :hide-fill-on-export :fill-group
   :show-content :show-content :fixed-scroll :constraints-group
   :constraints-h :constraints-group :constraints-v :constraints-group
   :layout-item-margin :layout-item-margin :layout-gap :layout-gap
   :layout-padding :layout-padding :layout-align-items :layout-align-items
   :layout-flex-dir :layout-flex-dir :layout-wrap-type :layout-wrap-type
   :layout-justify-content :layout-justify-content :layout-align-content :layout-align-content
   :layout-gap-type :layout-gap :layout-padding-type :layout-padding
   :layout-justify-items :layout-justify-items
   :layout-item-margin-type :layout-item-margin
   :layout-item-align-self :layout-item-align-self
   :layout-item-h-sizing :layout-item-h-sizing :layout-item-v-sizing :layout-item-v-sizing
   :layout-item-min-w :layout-item-min-w :layout-item-max-w :layout-item-max-w
   :layout-item-min-h :layout-item-min-h :layout-item-max-h :layout-item-max-h
   :layout-item-absolute :layout-item-absolute :layout-item-z-index :layout-item-z-index})

(defn attribute-applicable?
  "The clipping and background-export controls belong to frames."
  [shape attr]
  (case attr
    (:show-content :hide-fill-on-export) (= :frame (:type shape))
    true))

(def attributes
  {:fill-group [:fills :hide-fill-on-export]
   :layer-effects-group [:opacity :blend-mode]
   :name-group [:name]
   :stroke-group [:strokes]
   :radius-group [:r1 :r2 :r3 :r4]
   :visibility-group [:hidden]
   :modifiable-group [:blocked]
   :shadow-group [:shadow]
   :blur-group [:blur :background-blur]
   :mask-group [:masked-group]
   :exports-group [:exports]
   :grids-group [:grids]
   :show-content [:show-content]
   :content-group [:bool-type :bool-content]
   :geometry-group [:width :height :rotation :flip-x :flip-y :content-scale]
   :text-font-group [:grow-type :font-family :font-size :font-style :font-weight]
   :text-display-group [:letter-spacing :line-height :text-align]
   :constraints-group [:constraints-h :constraints-v :fixed-scroll]
   :layout-container [:layout]
   :layout-grid-dir [:layout-grid-dir]
   :layout-grid-rows [:layout-grid-rows]
   :layout-grid-columns [:layout-grid-columns]
   :layout-align-content [:layout-align-content]
   :layout-justify-items [:layout-justify-items]
   :layout-wrap-type [:layout-wrap-type]
   :layout-gap [:layout-gap :layout-gap-type]
   :layout-padding [:layout-padding :layout-padding-type]
   :layout-flex-dir [:layout-flex-dir]
   :layout-align-items [:layout-align-items]
   :layout-justify-content [:layout-justify-content]
   :layout-item-margin [:layout-item-margin :layout-item-margin-type]
   :layout-item-max-h [:layout-item-max-h]
   :layout-item-min-h [:layout-item-min-h]
   :layout-item-max-w [:layout-item-max-w]
   :layout-item-min-w [:layout-item-min-w]
   :layout-item-h-sizing [:layout-item-h-sizing]
   :layout-item-v-sizing [:layout-item-v-sizing]
   :layout-item-absolute [:layout-item-absolute]
   :layout-item-z-index [:layout-item-z-index]
   :layout-item-align-self [:layout-item-align-self]})

;; These fields need geometry, content or cell-reference comparisons instead
;; of raw equality. Keep the inventory explicit so new production attrs cannot
;; silently fall outside the oracle's specification.
(def specialized-attributes
  {:geometry-group [:type :selrect :points :locked :proportion :proportion-lock
                    :x :y :transform :transform-inverse :content]
   :content-group [:content :position-data]
   :layout-grid-cells [:layout-grid-cells]})

(def text-groups
  {:text-content-text :letters :text-content-family :family :text-content-weight :weight
   :text-content-size :size :text-content-line-height :line-height :text-content-spacing :spacing
   :text-content-decoration :decoration :text-content-case :case :text-content-fills :fills
   :text-content-typography :typography :text-content-align :align :text-content-direction :direction
   :text-content-valign :valign})

(defn values
  [shape group]
  (cond
    (= group :layout-grid-cells) {:layout-grid-cells (grid/get-cells shape)}
    (contains? text-groups group)
    {:content (:content shape)}
    :else (into {} (map #(vector % (get shape %))) (get attributes group))))

(defn author-geometry
  [shape parent]
  (cond-> (values shape :geometry-group)
    parent (with-meta {::position {:shape shape :parent parent}})))

(defn- authored-placement
  [expected parent]
  (when-let [{authored :shape origin :parent} (::position (meta expected))]
    ;; Placement of this child does not depend on its siblings' identities.
    ;; Keep the expected tree self-contained across frontend ID allocation.
    (geom/child-placement authored origin parent
                          {(:id authored) (assoc authored :parent-id (:id origin) :shapes [])
                           (:id origin) (assoc origin :shapes [(:id authored)])})))

(defn same-value?
  ([shape group expected] (same-value? shape group expected nil))
  ([shape group expected objects]
   (if (and (= :text (:type shape)) (= :content-group group))
     ;; Text values are checked per style/letter unit. The parent group is
     ;; only a marker; boolean content uses the ordinary attribute table.
     true
     (if-let [unit (get text-groups group)]
       (not (contains? (thtx/override-units (:content expected) (:content shape)) unit))
       (if (= group :geometry-group)
         (and (if-let [placed (when objects
                                (authored-placement expected (get objects (:parent-id shape))))]
                (gsh/close-attrs? :points (:points shape) (:points placed) 0.01)
                true)
              (every? (fn [[key value]]
                        (let [actual (get shape key)]
                          (cond
                            (#{:flip-x :flip-y} key) (= (boolean value) (boolean actual))
                            (and (number? value) (number? actual)) (mth/close? value actual 1e-10)
                            :else (= value actual)))) expected))
         (= expected (values shape group)))))))

(defn edit-text
  ([model before content] (edit-text model before content false))
  ([model before content typography]
   (if (:shape-ref before)
     (let [units (cond-> (thtx/override-units (:content before) content)
                   typography (into #{:family :weight :size :line-height :spacing :case :typography}))]
       (reduce (fn [model [group unit]]
                 (cond-> model
                   (contains? units unit)
                   (assoc-in [(:id before) group] {:content content})))
               (cond-> model (seq units) (assoc-in [(:id before) :content-group] {}))
               text-groups))
     model)))

(defn duplicate
  [model before source-id after copy-id]
  (letfn [(copy-groups [result source-id copy-id]
            (let [groups (grid/remap-groups (get model source-id)
                                            (zipmap (:shapes (get before source-id)) (:shapes (get after copy-id))))
                  result (cond-> result (seq groups) (assoc copy-id groups))]
              (reduce (fn [result [source child]] (copy-groups result source child)) result
                      (map vector (:shapes (get before source-id)) (:shapes (get after copy-id))))))]
    (copy-groups model source-id copy-id)))

(defn edit
  [model before property value]
  (let [after (n/set-property before property value)
        group (get groups property)]
    (if (and (:shape-ref before) (not= (get before property) (get after property)))
      (assoc-in model [(:id before) group] (values after group))
      model)))

(defn clear-subtree
  [model objects root-id]
  (letfn [(clear [model id]
            (reduce clear (dissoc model id) (:shapes (get objects id))))]
    (clear model root-id)))

(defn- in-orientation
  [values source target]
  (reduce (fn [values key]
            (cond-> values
              (not= (boolean (get source key)) (boolean (get target key)))
              (update key not)))
          (update values :rotation
                  #(mod (+ (or % 0) (- (or (:rotation target) 0) (or (:rotation source) 0))) 360))
          [:flip-x :flip-y]))

(defn detach
  "A detached outer head loses its groups. Surviving nested copies inherit
   the authored groups of the near-main links the detach skips."
  [model before after root-id]
  (letfn [(inherit [shape]
            (let [original shape
                  new-ref (:shape-ref (get after (:id shape)))
                  swapped? (contains? (get model (:id shape)) :swap-group)]
              (loop [shape shape groups (get model (:id shape)) seen #{}]
                (let [ref (:shape-ref shape)
                      source (get before ref)
                      inherited (grid/remap-groups (get model ref) (grid/get-child-map before source original))
                      inherited (cond-> inherited
                                  (:geometry-group inherited)
                                  (update :geometry-group in-orientation source original))]
                  (if (or (nil? ref) (contains? seen ref) (= ref new-ref))
                    groups
                    (recur source
                           (merge (if swapped?
                                    (select-keys inherited
                                                 (filter #(or (#{:geometry-group :constraints-group} %)
                                                              (.startsWith (name %) "layout-item-"))
                                                         (keys inherited)))
                                    inherited) groups)
                           (conj seen ref)))))))
          (visit [result id]
            (let [shape (get after id)
                  groups (when (:shape-ref shape)
                           (cond
                             (= (:shape-ref shape) (:shape-ref (get before id)))
                             (get model id)
                             (and (contains? (get model id) :swap-group)
                                  (= 1 (count (ctn/get-parent-copy-heads after shape)))
                                  (= (:component-id shape)
                                     (:component-id (get after (:shape-ref shape)))))
                             ;; The old position no longer supplies placement.
                             ;; Predict retained ownership from the before-state.
                             (let [old (get before id)
                                   main (get before (:shape-ref shape))
                                   normalized (fn [source]
                                                (-> source
                                                    (update :constraints-h #(or % (gsh/default-constraints-h old)))
                                                    (update :constraints-v #(or % (gsh/default-constraints-v old)))
                                                    (update :fixed-scroll boolean)))]
                               (reduce (fn [groups group]
                                         (if (= (values (normalized old) group)
                                                (values (normalized main) group))
                                           groups
                                           (assoc groups group (values old group))))
                                       (dissoc (get model id) :swap-group)
                                       (filter #(or (= :constraints-group %)
                                                    (.startsWith (name %) "layout-item-"))
                                               (keys attributes))))
                             :else (inherit (get before id))))
                  result (cond-> (dissoc result id) (seq groups) (assoc id groups))]
              (reduce visit result (:shapes (get before id)))))]
    (visit model root-id)))

(defn update-main
  "Pushing overrides into a nested copy in a main makes them authored at
   that level. Ordinary main shapes do not have touched groups."
  [model before after root-id]
  (letfn [(content-overrides [shape reference]
            (when reference
              (let [content-groups (remove #(or (#{:geometry-group :constraints-group} %)
                                                (.startsWith (name %) "layout-item-"))
                                           (keys attributes))
                    groups (into {}
                                 (keep (fn [group]
                                         (let [expected (values reference group)]
                                           (when-not (same-value? shape group expected)
                                             [group (values shape group)]))))
                                 content-groups)
                    units (when (= :text (:type shape))
                            (thtx/override-units (:content reference) (:content shape)))]
                (reduce (fn [groups [group unit]]
                          (cond-> groups
                            (contains? units unit)
                            (assoc group {:content (:content shape)})))
                        (cond-> groups (seq units) (assoc :content-group {}))
                        text-groups))))
          (push [result id content?]
            (let [shape (get before id)
                  updated (get after id)
                  ref (get after (:shape-ref updated))
                  groups (get model id)
                  content-main (get before (:shape-ref ref))
                  pushed-swap? (and content-main (contains? groups :swap-group)
                                    (= (:component-id ref) (:component-id content-main)))
                  content? (or content? pushed-swap?)
                  groups (merge (when (and content?
                                           (= (:component-id ref) (:component-id content-main)))
                                  (content-overrides shape content-main))
                                groups)
                  root (get before root-id)
                  main-root (get before (:shape-ref root))
                  geometry (cond-> (values shape :geometry-group)
                             (get-in model [(:id main-root) :geometry-group])
                             (in-orientation root main-root))
                  expected (when pushed-swap?
                             (in-orientation (values content-main :geometry-group)
                                             (get before (:parent-id content-main))
                                             (get after (:parent-id ref))))
                  groups (cond-> groups
                           (and pushed-swap?
                                (not (same-value? geometry :geometry-group
                                                  (select-keys expected [:rotation :flip-x :flip-y]))))
                           (assoc :geometry-group geometry))
                  result (cond-> (dissoc result id)
                           (and (contains? groups :swap-group)
                                (not= (:component-id updated) (:component-id ref)))
                           (assoc id {:swap-group {}})
                           (and (:shape-ref ref) (seq groups))
                           (update (:id ref) merge
                                   (cond-> (grid/remap-groups groups (grid/get-child-map after shape ref))
                                     (= (:component-id ref)
                                        (:component-id (get after (:shape-ref ref))))
                                     (dissoc :swap-group))))]
              (reduce #(push %1 %2 content?) result (:shapes shape))))]
    (push model root-id false)))

(defn parent-orientation
  "Authored child geometry moves with its parent's orientation. Compare the
   parent frames, without reading the child's resulting geometry."
  [model before after]
  (reduce-kv
   (fn [result id groups]
     (let [old (get before id)
           shape (get after id)
           parent (get before (:parent-id old))
           new-parent (get after (:parent-id shape))]
       (if (and (:geometry-group groups) old shape
                (= (:parent-id old) (:parent-id shape)) parent new-parent)
         (if-let [expected (authored-placement (:geometry-group groups) new-parent)]
           (assoc-in result [id :geometry-group]
                     (with-meta (values expected :geometry-group) (meta (:geometry-group groups))))
           (update-in result [id :geometry-group] in-orientation parent new-parent))
         result)))
   model model))

(defn settle
  "A detached position turns its copies into ordinary shapes. Their former
   component choice stops applying; other authored groups stay in place."
  ([model objects] (settle model objects {}))
  ([model objects references]
   (reduce-kv
    (fn [result id groups]
      (let [shape (get objects id)
            position (get objects (:shape-ref shape))
            ordinary? (and shape position
                           (nil? (:component-id shape))
                           (nil? (:component-id position)))
            source (get references id)
            context (when source
                      (grid/get-context (merge source (:layout-grid-rows groups)
                                               (:layout-grid-columns groups) (:layout-grid-dir groups))))
            groups (cond-> groups
                     ordinary? (dissoc :swap-group)
                     (:layout-grid-cells groups)
                     (update-in [:layout-grid-cells :layout-grid-cells] grid/settle-cells shape objects context))]
        (if (seq groups) (assoc result id groups) (dissoc result id))))
    model model)))

(defn- subtree-shapes
  [objects id]
  (cons (get objects id) (mapcat #(subtree-shapes objects %) (:shapes (get objects id)))))

(defn- variant-paths
  [objects root-id]
  (letfn [(path [shape]
            (when-not (= root-id (:id shape))
              (conj (vec (path (get objects (:parent-id shape))))
                    [(:name shape) (case (:type shape)
                                     (:frame :group) :container
                                     (:rect :circle :bool :path) :shape
                                     (:type shape))])))]
    (first
     (reduce (fn [[result counts] shape]
               (let [p (path shape)
                     ordinal (get counts p 0)]
                 [(assoc result (:id shape) [p ordinal]) (update counts p (fnil inc 0))]))
             [{} {}] (reverse (subtree-shapes objects root-id))))))

(defn swap
  "Predict switch carry from authored groups and the two mains. Matching
   uses main names and ancestor kinds; copy renames never change a match."
  [model before after root-id old-main-id new-main-id keep?]
  (let [old-paths (variant-paths before old-main-id)
        new-paths (variant-paths after new-main-id)
        old-by-path (into {} (map (fn [[id path]] [path (get before id)])) old-paths)
        source (fn [objects paths main-id original]
                 (let [mains (into {} (keep (fn [shape]
                                              (when (:main-instance shape)
                                                [[(:component-file shape) (:component-id shape)] shape])))
                                   (vals objects))]
                   (or
                    (loop [shape original groups {} seen #{}]
                      (cond
                        (contains? paths (:id shape)) [shape groups]
                        (or (nil? shape) (contains? seen (:id shape))) nil
                        :else (recur (if (contains? (get model (:id shape)) :swap-group)
                                       (get mains [(:component-file shape) (:component-id shape)])
                                       (get objects (:shape-ref shape)))
                                     (merge (get model (:id shape)) groups)
                                     (conj seen (:id shape)))))
                    ;; A swapped root refers to its position, while its content
                    ;; and authored overrides belong to its own component.
                    (when (= root-id (:id original))
                      [(get objects main-id) (get model root-id)]))))
        old-copies (into {} (keep (fn [shape]
                                    (when-let [[main groups] (source before old-paths old-main-id shape)]
                                      [(get old-paths (:id main)) [shape main groups]])))
                         (subtree-shapes before root-id))
        cleared (clear-subtree model before root-id)
        result
        (if-not keep?
          cleared
          (reduce
           (fn [result shape]
             (if (and (not= root-id (:id shape)) (contains? before (:id shape)))
               ;; A switch keeps an authored swapped subtree with its ids.
               (cond-> result (seq (get model (:id shape)))
                       (assoc (:id shape) (get model (:id shape))))
               (if-let [[main _] (source after new-paths new-main-id shape)]
                 (let [path (get new-paths (:id main))
                       [old old-main groups] (get old-copies path)
                       old-main (or old-main (get old-by-path path))
                       text-differences (when (= :text (:type main))
                                          (thtx/override-units (:content old-main) (:content main)))
                       carried-text-units (into #{}
                                                (keep (fn [[group unit]]
                                                        (when (and (contains? groups group)
                                                                   (not (contains? text-differences unit)))
                                                          unit)))
                                                text-groups)
                       mixed-typography? (some #(not= (contains? carried-text-units :typography)
                                                      (contains? carried-text-units %))
                                               [:family :weight :size :line-height :spacing :case])]
                   (reduce-kv
                    (fn [result group _]
                      (if-let [unit (get text-groups group)]
                        (cond-> result
                          (and (not (and (= unit :typography) mixed-typography?))
                               (not (contains? text-differences unit))
                               (not (same-value? main group {:content (:content old)})))
                          (-> (assoc-in [(:id shape) group] {:content (:content old)})
                              (assoc-in [(:id shape) :content-group] {})))
                        (let [attrs (get attributes group)
                              carried (filter (fn [attr]
                                                (let [old-value (get old-main attr)
                                                      new-value (get main attr)
                                                      agree? (if (and (= group :geometry-group)
                                                                      (number? old-value) (number? new-value))
                                                               (mth/close? old-value new-value)
                                                               (= old-value new-value))]
                                                  (and (attribute-applicable? main attr)
                                                       agree? (not= (get old attr) new-value)))) attrs)]
                          (cond-> result
                            (seq carried)
                            (assoc-in [(:id shape) group]
                                      (merge (values main group) (select-keys old carried)))))))
                    result (dissoc groups :swap-group)))
                 result)))
           cleared (subtree-shapes after root-id)))
        root (get after root-id)
        old-root (get before root-id)
        parent (get after (:parent-id root))
        position (get after (:shape-ref root))
        main (get after new-main-id)
        main-parent (get after (:parent-id main))
        baseline (if (:shape-ref parent) position main)
        baseline-parent (if (:shape-ref parent)
                          (get after (:parent-id position)) main-parent)
        rotation (mod (+ (or (:rotation baseline) 0)
                         (- (or (:rotation parent) 0) (or (:rotation baseline-parent) 0))) 360)
        flip (fn [key]
               (odd? (count (filter identity [(get baseline key) (get parent key)
                                              (get baseline-parent key)]))))
        retained-orientation? (or (not (mth/close? (mod (or (:rotation old-root) 0) 360) rotation 0.01))
                                  (not= (boolean (:flip-x old-root)) (flip :flip-x))
                                  (not= (boolean (:flip-y old-root)) (flip :flip-y)))]
    (cond-> result
      retained-orientation?
      (assoc-in [root-id :geometry-group]
                (merge (or (get-in result [root-id :geometry-group]) (values main :geometry-group))
                       (select-keys old-root [:rotation :flip-x :flip-y])))
      (and (:shape-ref parent) (not= (:component-id root) (:component-id position)))
      (assoc-in [root-id :swap-group] {}))))
