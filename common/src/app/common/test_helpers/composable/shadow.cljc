;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.shadow
  "Authored overrides, kept independently of production touched writes.
   Each shape id maps groups to the attribute values the user authored."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.geometry :as geom]
   [app.common.test-helpers.composable.grid :as grid]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

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

;; the text groups of the styles a typography sets
(def ^:private typography-groups
  #{:text-content-family :text-content-weight :text-content-size
    :text-content-line-height :text-content-spacing :text-content-case})

(defn values
  [shape group]
  (cond
    (= group :layout-grid-cells) {:layout-grid-cells (grid/get-cells shape)}
    (contains? text-groups group)
    {:content (:content shape)}
    :else (into {} (map #(vector % (get shape %))) (get attributes group))))

(defn author-geometry
  "The geometry `shape` authors, with the `parent` it is placed in, which
   later resizes of that parent move and size it through. With `sized?`,
   only its size is authored: its place in a plain parent is not an
   override."
  ([shape parent] (author-geometry shape parent false))
  ([shape parent sized?]
   (cond-> (values shape :geometry-group)
     parent (with-meta {::position {:shape shape :parent parent :sized? sized?}}))))

(defn anchor-position
  "The authored geometry `value` placed from where `shape` sits now in
   `parent`: a constraint change moves nothing and applies only to the
   resizes after it."
  [value shape parent]
  (cond-> value
    (::position (meta value))
    (vary-meta update ::position assoc :shape shape :parent parent)))

(defn- authored-placement
  "Where the shape `expected` records it authored sits in `parent`, resized
   through the constraints of `current` when given (they apply to the
   resizes after them), else those it had."
  ([expected parent] (authored-placement expected parent nil))
  ([expected parent current]
   (when-let [{authored :shape origin :parent} (::position (meta expected))]
     (let [authored (cond-> authored
                      current (merge (select-keys current [:constraints-h :constraints-v])))]
       ;; Placement of this child does not depend on its siblings' identities.
       ;; Keep the expected tree self-contained across frontend ID allocation.
       (geom/child-placement authored origin parent
                             {(:id authored) (assoc authored :parent-id (:id origin) :shapes [])
                              (:id origin) (assoc origin :shapes [(:id authored)])})))))

(defn- layout-sized
  "The sizes a layout gives `shape`: its fill or auto axes, and both once it
   is turned, since the layout resizes it along its parent's axes."
  [shape objects]
  (let [child? (and (ctl/any-layout-immediate-child? objects shape)
                    (not (ctl/position-absolute? shape)))
        owned? #(and (or child? (ctl/any-layout? shape)) (#{:fill :auto} %))
        sized (cond-> #{}
                (owned? (:layout-item-h-sizing shape)) (conj :width)
                (owned? (:layout-item-v-sizing shape)) (conj :height))]
    (if (and child? (seq sized) (not (mth/close? (mod (or (:rotation shape) 0) 360) 0)))
      #{:width :height}
      sized)))

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
         (let [sized (if objects (layout-sized shape objects) #{})
               placed (when (and objects (not (:sized? (::position (meta expected)))))
                        (authored-placement expected (get objects (:parent-id shape)) shape))
               ;; an unskewed transform carries the turn and the mirror: an
               ;; expected rotation and flips match when they decompose it,
               ;; whichever rotation and flips the shape stores for it
               oriented (when (and (not placed) (:transform shape) (geom/rigid? shape)
                                   (number? (:rotation expected))
                                   (contains? expected :flip-x) (contains? expected :flip-y))
                          (geom/decomposes? (merge shape (select-keys expected [:rotation :flip-x :flip-y]))))
               ;; flipping both axes is a half turn, so a shape storing both
               ;; flips the other way stores the same transform turned by 180
               expected (if (and (number? (:rotation expected))
                                 (contains? expected :flip-x) (contains? expected :flip-y)
                                 (not= (boolean (:flip-x expected)) (boolean (:flip-x shape)))
                                 (not= (boolean (:flip-y expected)) (boolean (:flip-y shape))))
                          (-> expected
                              (update :flip-x not)
                              (update :flip-y not)
                              (update :rotation + 180))
                          expected)]
           (and (if placed
                  (gsh/close-attrs? :points (:points shape) (:points placed) 0.01)
                  true)
                (if (some? oriented) oriented true)
                (every? (fn [[key value]]
                          (let [actual (get shape key)]
                            (cond
                              (contains? sized key) true
                              (and (some? oriented) (#{:rotation :flip-x :flip-y} key)) true
                              ;; ordered corners fix the transform, whichever
                              ;; rotation the shape stores for it
                              (and (= :rotation key) placed) true
                              ;; a recorded place sizes it through its constraints as its parent resizes
                              (and (#{:width :height} key) placed (number? actual))
                              (mth/close? (get placed key) actual 0.01)
                              (#{:flip-x :flip-y} key) (= (boolean value) (boolean actual))
                              ;; under one flip, either order of turn and flip stores the opposite turn
                              (and (= :rotation key) (number? value) (number? actual)
                                   (not= (boolean (:flip-x shape)) (boolean (:flip-y shape))))
                              (or (mth/close? (mod value 360) (mod actual 360) 1e-10)
                                  (mth/close? (mod (- value) 360) (mod actual 360) 1e-10))
                              ;; Sizes are pixels, like the oracle's other geometry checks.
                              (and (#{:width :height} key) (number? value) (number? actual)) (mth/close? value actual 0.01)
                              (and (number? value) (number? actual)) (mth/close? value actual 1e-10)
                              :else (= value actual)))) expected)))
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
  "`values` turned and flipped from the frame of `source` to that of
   `target`; `sign` (`geom/turn-sign`) tells which way the rotation stores it."
  ([values source target] (in-orientation values source target 1))
  ([values source target sign]
   (let [flipped (reduce (fn [values key]
                           (cond-> values
                             (not= (boolean (get source key)) (boolean (get target key)))
                             (update key not)))
                         values
                         [:flip-x :flip-y])]
     (assoc flipped :rotation (geom/reoriented-rotation values sign source target flipped)))))

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
                    ;; the detach keeps the text the copy shows, which may
                    ;; have dropped a typography of a skipped link
                    (reduce (fn [groups group]
                              (cond-> groups
                                (and (contains? text-groups group)
                                     (not (contains? (get model (:id original)) group)))
                                (assoc group {:content (:content (get after (:id original)))})))
                            groups (keys groups))
                    (recur source
                           (merge (if swapped?
                                    (cond-> (select-keys inherited
                                                         (filter #(or (#{:geometry-group :constraints-group} %)
                                                                      (.startsWith (name %) "layout-item-"))
                                                                 (keys inherited)))
                                      (:geometry-group inherited)
                                      (update :geometry-group own-size original))
                                    (cond-> inherited
                                      (and (:geometry-group inherited) (constraint-sized? original))
                                      (update :geometry-group own-size original))) groups)
                           (conj seen ref)))))))
          ;; a copy shape that keeps its size through a constraint it authors
          ;; shows that size, not its link's
          (constraint-sized? [shape]
            (let [groups (get model (:id shape))]
              (and (contains? groups :constraints-group)
                   (not (contains? groups :geometry-group))
                   (or (#{:scale :leftright} (:constraints-h shape))
                       (#{:scale :topbottom} (:constraints-v shape))))))
          ;; a swapped head takes a link's placement with the size of its own
          ;; content, about the placement's first corner
          (own-size [value head]
            (let [{authored :shape} (::position (meta value))
                  sized (when authored
                          (gsh/transform-shape authored (ctm/change-size authored (:width head) (:height head))))]
              (cond-> (assoc value :width (:width head) :height (:height head))
                sized (vary-meta assoc-in [::position :shape] sized))))
          ;; a nested copy that its new main would place elsewhere keeps its
          ;; place as its own geometry, and one under a plain parent keeps an
          ;; orientation or size its main lacks; a swapped head takes its new place
          (kept-in-place [groups shape]
            (let [parent      (get after (:parent-id shape))
                  main        (get after (:shape-ref shape))
                  main-parent (get after (:parent-id main))
                  objects     (cond-> after
                                (and main (contains? groups :constraints-group))
                                (update (:id main) merge
                                        (into {} (filter val) (select-keys shape [:constraints-h :constraints-v]))))]
              (cond
                (or (contains? groups :geometry-group) (contains? groups :swap-group) (nil? main))
                groups

                (nil? (:shape-ref parent))
                (kept-orientation groups shape)

                (or (nil? main-parent)
                    (gsh/close-attrs? :points (:points shape)
                                      (:points (geom/child-placement main main-parent parent objects)) 0.01))
                groups

                ;; the kept place sits in the copy parent, whose later resizes
                ;; move and size it through its constraints unless a layout places it
                :else
                (assoc groups :geometry-group
                       (author-geometry shape (when-not (and (ctl/any-layout-immediate-child? after shape)
                                                             (not (ctl/position-absolute? shape)))
                                                parent))))))
          ;; under a plain parent a copy is placed freely, and only its
          ;; orientation and size follow its main, turned like that parent
          (kept-orientation [groups shape]
            (let [main (get after (:shape-ref shape))
                  parent (get after (:parent-id shape))
                  ;; a main at the root of its page has an unturned parent
                  main-parent (get after (:parent-id main) {})
                  expected (cond-> (values main :geometry-group)
                             parent (in-orientation main-parent parent))]
              (cond-> groups
                (and main
                     (nil? (:shape-ref parent))
                     (not (contains? groups :geometry-group))
                     (not (same-value? shape :geometry-group expected)))
                (assoc :geometry-group (author-geometry shape nil)))))
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
                               (-> (reduce (fn [groups group]
                                             (if (= (values (normalized old) group)
                                                    (values (normalized main) group))
                                               groups
                                               (assoc groups group (values old group))))
                                           (dissoc (get model id) :swap-group)
                                           (filter #(or (= :constraints-group %)
                                                        (.startsWith (name %) "layout-item-"))
                                                   (keys attributes)))
                                   (kept-orientation shape)))
                             :else (kept-in-place (inherit (get before id)) shape)))
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
                  root-parent (get before (:parent-id root))
                  main-parent (get before (:parent-id main-root) {})
                  ;; the way the rotation of `shape` stores its turn
                  sign (geom/turn-sign shape)
                  ;; the turn the copy root inherits places it and is not pushed:
                  ;; all of a top root's that authors no geometry, else its parent's
                  inherits-all? (and (:component-root root) (not (get-in model [root-id :geometry-group])))
                  geometry (cond-> (values shape :geometry-group)
                             inherits-all?
                             (in-orientation root main-root sign)

                             (and (not inherits-all?) root-parent)
                             (in-orientation root-parent main-parent sign))
                  replaced (when (and (contains? groups :swap-group)
                                      (not= (:shape-ref shape) (:shape-ref updated)))
                             (get before (:shape-ref shape)))
                  ;; a head pushed in place of a copy child takes the placement that child authored
                  placement (when (:shape-ref (get before (:parent-id replaced)))
                              (filter #(or (#{:geometry-group :constraints-group} %)
                                           (.startsWith (name %) "layout-item-"))
                                      (keys (get model (:id replaced)))))
                  expected (when pushed-swap?
                             (in-orientation (values content-main :geometry-group)
                                             (get before (:parent-id content-main))
                                             (get after (:parent-id ref))))
                  groups (cond-> groups
                           ;; a pushed swap authors geometry in the frame of its main
                           (and pushed-swap?
                                (or (contains? groups :geometry-group)
                                    (not (same-value? geometry :geometry-group
                                                      (select-keys expected [:rotation :flip-x :flip-y])))))
                           (assoc :geometry-group geometry)

                           (and pushed-swap?
                                (not (same-value? shape :constraints-group (values content-main :constraints-group))))
                           (assoc :constraints-group (values shape :constraints-group))

                           (seq placement)
                           (merge (into {} (map (fn [group]
                                                  [group (if (= :geometry-group group) geometry (values shape group))]))
                                        placement))

                           ;; a main shape the push keeps turns with its main afterwards;
                           ;; its pushed geometry leaves out the turn its copy parent shows
                           ;; against its main parent (the root's, against its main). A
                           ;; main shape the push adds takes its parent's turn after the push
                           (and (:geometry-group groups) (not pushed-swap?))
                           (update :geometry-group
                                   (fn [geometry]
                                     (if (= (:id shape) root-id)
                                       (in-orientation geometry root main-root)
                                       (in-orientation geometry (get before (:parent-id shape))
                                                       (if (contains? before (:id ref))
                                                         (get before (:parent-id ref))
                                                         (get after (:parent-id ref))))))))
                  ;; a variant main keeps its set's name, and the copy root its own
                  kept-name (when (and (= id root-id) (:variant-id ref)) (:name-group groups))
                  groups (cond-> groups kept-name (dissoc :name-group))
                  ;; only a layout parent pushes layout child groups; the copy keeps the others
                  kept-items (when (and (not= id root-id) (not (ctl/any-layout? (get after (:parent-id ref)))))
                               (into {} (filter #(.startsWith (name (key %)) "layout-item-")) groups))
                  groups (apply dissoc groups (keys kept-items))
                  result (cond-> (dissoc result id)
                           (and (contains? groups :swap-group)
                                (not= (:component-id updated) (:component-id ref)))
                           (assoc id {:swap-group {}})

                           (seq kept-items)
                           (update id merge kept-items)
                           kept-name
                           (assoc-in [id :name-group] kept-name)
                           (and (:shape-ref ref) (seq groups))
                           (update (:id ref)
                                   (fn [main-groups]
                                     (let [merged (merge main-groups
                                                         (cond-> (grid/remap-groups groups (grid/get-child-map after shape ref))
                                                           ;; a clone of the copy shape keeps the swap it authored against the same main
                                                           (and (= (:component-id ref)
                                                                   (:component-id (get after (:shape-ref ref))))
                                                                (not= (:shape-ref ref) (:shape-ref shape)))
                                                           (dissoc :swap-group)))]
                                       (cond-> merged
                                         ;; a pushed style of a typography drops the typography of the main
                                         (and (contains? merged :text-content-typography)
                                              (not (contains? groups :text-content-typography))
                                              (some #(contains? groups %) typography-groups))
                                         (assoc :text-content-typography {:content (:content shape)}))))))]
              (reduce #(push %1 %2 content?) result (:shapes shape))))]
    (push model root-id false)))

(defn layout-released
  "A layout writes the sizes it gives a shape into it, so a shape it stops
  sizing keeps them: each recorded geometry takes the dimensions its layout
  sized `before` and no longer sizes `after`."
  [model before after]
  (reduce-kv
   (fn [model id groups]
     (let [shape-before (get before id)
           shape-after  (get after id)
           released     (when (and shape-before shape-after (contains? groups :geometry-group))
                          (remove (layout-sized shape-after after) (layout-sized shape-before before)))]
       (reduce #(assoc-in %1 [id :geometry-group %2] (get shape-after %2)) model released)))
   model
   model))

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
           (let [geometry (:geometry-group groups)
                 ;; the way the authored rotation, not the one `old` stores, decomposes its transform
                 sign (geom/turn-sign (merge old (select-keys geometry [:rotation :flip-x :flip-y])))]
             (update-in result [id :geometry-group] in-orientation parent new-parent sign)))
         result)))
   model model))

(defn sync-scaled-heads
  "A copy parent that a sync resizes sizes its untouched swapped heads
  through their constraints. A head it gives another size authors that
  size, placed in the parent."
  [model before after]
  (let [sized-alike? #(and (mth/close? (:width %1) (:width %2))
                           (mth/close? (:height %1) (:height %2)))]
    (reduce-kv
     (fn [result id groups]
       (let [old        (get before id)
             shape      (get after id)
             old-parent (get before (:parent-id old))
             parent     (get after (:parent-id shape))]
         (if (and (contains? groups :swap-group)
                  (not (contains? groups :geometry-group))
                  old shape old-parent parent
                  (= (:parent-id old) (:parent-id shape))
                  (:shape-ref parent)
                  (not (sized-alike? old-parent parent))
                  (not (sized-alike? old shape)))
           (assoc-in result [id :geometry-group] (author-geometry shape parent))
           result)))
     model model)))

(defn settle
  "A detached position turns its copies into ordinary shapes. Their former
   component choice stops applying; other authored groups stay in place.
   Once synced (`synced?`, or the copy itself has no grid left), cells carry
   only between grids: a copy whose main has none, and that follows its
   main's layout, drops its own."
  ([model objects] (settle model objects {} false))
  ([model objects references] (settle model objects references true))
  ([model objects references synced?]
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
            ;; a copy already synced to a gridless main has no grid left either
            gridless? (and source (not= :grid (:layout source))
                           (or synced? (not= :grid (:layout shape)))
                           (not (contains? groups :layout-container)))
            groups (cond-> groups
                     ordinary? (dissoc :swap-group)
                     gridless? (dissoc :layout-grid-cells)
                     (and (:layout-grid-cells groups) (not gridless?))
                     (update-in [:layout-grid-cells :layout-grid-cells] grid/settle-cells shape objects context source))]
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

(defn- mirrored?
  "Whether `values` hold exactly one flip."
  [values]
  (not= (boolean (:flip-x values)) (boolean (:flip-y values))))

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
        ;; the shapes below an authored swap, which belong to the swapped component
        swapped-below (into #{}
                            (comp (filter #(and (not= root-id (:id %))
                                                (contains? (get model (:id %)) :swap-group)))
                                  (mapcat #(rest (subtree-shapes before (:id %))))
                                  (map :id))
                            (subtree-shapes before root-id))
        old-copies (into {} (keep (fn [shape]
                                    (when-not (contains? swapped-below (:id shape))
                                      (when-let [[main groups] (source before old-paths old-main-id shape)]
                                        [(get old-paths (:id main)) [shape main groups]]))))
                         (subtree-shapes before root-id))
        ;; the rotation a clean copy of the new main takes in the root's parent
        placed-rotation (let [main (get after new-main-id)]
                          (mod (+ (or (:rotation main) 0)
                                  (- (or (:rotation (get after (:parent-id (get after root-id)))) 0)
                                     (or (:rotation (get after (:parent-id main))) 0)))
                               360))
        ;; the rotation a clean copy of the old main takes in the root's parent
        old-placed-rotation (let [main (get before old-main-id)]
                              (mod (+ (or (:rotation main) 0)
                                      (- (or (:rotation (get before (:parent-id (get before root-id)))) 0)
                                         (or (:rotation (get before (:parent-id main))) 0)))
                                   360))
        ;; the flips a clean copy of `main`, in `main-parent`, takes in `parent`
        placed-flips (fn [main main-parent parent]
                       (into {} (map (fn [key]
                                       [key (when (odd? (count (filter key [main main-parent parent]))) true)]))
                             [:flip-x :flip-y]))
        ;; the turn and flips a head in a copy takes from its position, the main
        ;; shape it refers to; nil for a head outside a copy
        position-orientation (let [root (get after root-id)
                                   parent (get after (:parent-id root))
                                   position (when (:shape-ref parent) (get after (:shape-ref root)))
                                   position-parent (get after (:parent-id position))]
                               (when position
                                 (merge {:rotation (mod (+ (or (:rotation position) 0)
                                                           (- (or (:rotation parent) 0)
                                                              (or (:rotation position-parent) 0)))
                                                        360)}
                                        (placed-flips position position-parent parent))))
        cleared (clear-subtree model before root-id)
        result
        (if-not keep?
          cleared
          (reduce
           (fn [result shape]
             (if (and (not= root-id (:id shape)) (contains? before (:id shape)))
               ;; A switch keeps an authored swapped subtree with its ids; the
               ;; swap ends where the new position holds the same component.
               (let [position (get after (:shape-ref shape))
                     same?    (and position
                                   (= (:component-id position) (:component-id shape))
                                   (= (:component-file position) (:component-file shape)))
                     groups   (cond-> (get model (:id shape)) same? (dissoc :swap-group))]
                 (cond-> result (seq groups) (assoc (:id shape) groups)))
               (if-let [[main _] (source after new-paths new-main-id shape)]
                 (let [path (get new-paths (:id main))
                       [old old-main groups] (get old-copies path)
                       old-main (or old-main (get old-by-path path))
                       old-parent (get before (:parent-id old))
                       new-parent (get after (:parent-id shape))
                       ;; the old copy shape as its constraints take it into the new parent
                       old-resized (if (and old old-parent new-parent)
                                     (geom/follow-parent-size old old-parent new-parent)
                                     old)
                       text-differences (when (= :text (:type main))
                                          (thtx/override-units (:content old-main) (:content main)))
                       carried-text-units (into #{}
                                                (keep (fn [[group unit]]
                                                        (when (and (contains? groups group)
                                                                   (not (contains? text-differences unit)))
                                                          unit)))
                                                text-groups)
                       typography-units #{:family :weight :size :line-height :spacing :case}
                       old-reference? (some #(or (:typography-ref-id %) (:typography-ref-file %))
                                            (tree-seq map? :children (:content old)))
                       ;; a typography the copy applied carries only with all its styles
                       mixed-typography? (and old-reference?
                                              (some #(not= (contains? carried-text-units :typography)
                                                           (contains? carried-text-units %))
                                                    typography-units))
                       ;; and then owns all of them, equal ones too
                       owned-typography? (and old-reference? (not mixed-typography?)
                                              (contains? carried-text-units :typography)
                                              (not (same-value? main :text-content-typography
                                                                {:content (:content old)})))]
                   (reduce-kv
                    (fn [result group _]
                      (cond
                        (= group :layout-grid-cells)
                        ;; cell overrides carry whole, and only between identical grids
                        (let [path-cells (fn [grid paths]
                                           (into #{} (map #(update % :shapes (fn [ids] (mapv paths ids))))
                                                 (grid/get-cells grid)))
                              identical? (and old old-main
                                              (= (:layout-grid-rows old-main) (:layout-grid-rows main))
                                              (= (:layout-grid-columns old-main) (:layout-grid-columns main))
                                              (= (path-cells old-main old-paths) (path-cells main new-paths)))
                              ;; each old copy child, by its path, to the new copy child
                              children (into {}
                                             (keep (fn [child-id]
                                                     (when-let [[child-main _] (source after new-paths new-main-id
                                                                                       (get after child-id))]
                                                       (when-let [[old-child] (get old-copies (get new-paths (:id child-main)))]
                                                         [(:id old-child) child-id]))))
                                             (:shapes shape))]
                          (cond-> result
                            (and identical?
                                 ;; cells the new copy shows anyway are no override
                                 (not= (path-cells old (into {} (map (fn [[path [old-shape]]] [(:id old-shape) path]))
                                                             old-copies))
                                       (path-cells main new-paths)))
                            (assoc (:id shape) (merge (get result (:id shape))
                                                      (grid/remap-groups {group (values old group)} children)))))

                        (contains? text-groups group)
                        (let [unit (get text-groups group)]
                          (cond-> result
                            (and (not (and (= unit :typography) mixed-typography?))
                                 (not (contains? text-differences unit))
                                 (or (and owned-typography? (contains? typography-units unit))
                                     (not (same-value? main group {:content (:content old)}))))
                            (-> (assoc-in [(:id shape) group] {:content (:content old)})
                                (assoc-in [(:id shape) :content-group] {}))))

                        :else
                        (let [attrs (get attributes group)
                              same? (fn [a b]
                                      (cond
                                        (not= group :geometry-group) (= a b)
                                        (and (number? a) (number? b)) (mth/close? a b)
                                        (or (boolean? a) (boolean? b)) (= (boolean a) (boolean b))
                                        :else (= a b)))
                              root? (= root-id (:id shape))
                              ;; the new main as the new copy shows it: a child turns
                              ;; with the orientation its new parent shows
                              placed (cond-> (values main group)
                                       (and root? (= :geometry-group group))
                                       (merge {:rotation placed-rotation}
                                              (placed-flips main (get after (:parent-id main)) new-parent))
                                       (and (not root?) (= :geometry-group group) new-parent)
                                       (in-orientation (get after (:parent-id main)) new-parent))
                              ;; the old main as the old copy showed it, in the
                              ;; same frame as `placed`
                              old-placed (cond-> old-main
                                           (and root? (= :geometry-group group))
                                           (merge {:rotation old-placed-rotation}
                                                  (placed-flips old-main (get before (:parent-id old-main)) old-parent))
                                           (and (not root?) (= :geometry-group group) old-main old-parent)
                                           (in-orientation (get before (:parent-id old-main)) old-parent))
                              old (if (= :geometry-group group) old-resized old)
                              orientation #{:rotation :flip-x :flip-y}
                              ;; the head's turn and flips carry as one: only where the
                              ;; mains share all of them
                              agrees? (fn [attr]
                                        (cond
                                          (and root? (contains? orientation attr))
                                          (every? #(same? (get old-placed %) (get placed %)) orientation)

                                          ;; a mirror stores its turn either way: the turns of two
                                          ;; mirrored mains agree where they point their axes alike
                                          (and (= attr :rotation) old-main old-parent new-parent)
                                          (if (and (mirrored? old-placed) (mirrored? placed))
                                            (geom/same-placed-axes? old-main (get before (:parent-id old-main)) old-parent
                                                                    main (get after (:parent-id main)) new-parent)
                                            (same? (get old-placed attr) (get placed attr)))

                                          :else
                                          (same? (get old-placed attr) (get placed attr))))
                              ;; a mirror stores its turn either way: the turns of two
                              ;; mirrors differ where they point their axes apart
                              differs? (fn [attr]
                                         (if (and (= attr :rotation) (not root?) old-parent new-parent
                                                  (mirrored? old) (mirrored? placed))
                                           (not (geom/same-placed-axes? old old-parent new-parent
                                                                        main (get after (:parent-id main)) new-parent))
                                           (not (same? (get old attr) (get placed attr)))))
                              carried (filter (fn [attr]
                                                (and (attribute-applicable? main attr)
                                                     (agrees? attr)
                                                     (differs? attr))) attrs)]
                          (cond-> result
                            (seq carried)
                            (assoc-in [(:id shape) group]
                                      (merge placed
                                             (when (and root? (= :geometry-group group)) position-orientation)
                                             ;; an attribute the copy leaves unset carries as unset
                                             (into {} (map #(vector % (get old %))) carried)))))))
                    result (cond->> (dissoc groups :swap-group)
                             ;; the head carries only its own layout item overrides
                             (= root-id (:id shape))
                             (into {} (remove #(.startsWith (name (key %)) "layout-item-"))))))
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
                                  (not= (boolean (:flip-y old-root)) (flip :flip-y)))
        ;; a switch keeps the old head's layout item values, and their overrides
        layout-items (when keep?
                       (into {} (filter #(.startsWith (name (key %)) "layout-item-")) (get model root-id)))
        ;; a swap in a copy keeps an authored place its position does not give,
        ;; unless a layout places the head
        kept-place? (and (:shape-ref parent) position
                         (not (and (ctl/any-layout-immediate-child? after root)
                                   (not (ctl/position-absolute? root))))
                         (contains? (get model root-id) :geometry-group)
                         (not (gpt/close? (first (:points root))
                                          (first (:points (geom/child-placement position (get after (:parent-id position))
                                                                                parent after))))))]
    (cond-> result
      retained-orientation?
      (assoc-in [root-id :geometry-group]
                (merge (or (get-in result [root-id :geometry-group]) (values main :geometry-group))
                       (select-keys old-root [:rotation :flip-x :flip-y])))

      kept-place?
      (assoc-in [root-id :geometry-group] (author-geometry root parent))

      (seq layout-items)
      (update root-id merge layout-items)

      (and (:shape-ref parent) (not= (:component-id root) (:component-id position)))
      (assoc-in [root-id :swap-group] {}))))
