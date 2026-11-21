;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.tokens-propagation
  "Changes that set the shapes to the resolved values of the design tokens
  applied to them.

  Pure: the tokens come already resolved and the fonts come as data, so
  every place that shows a file can recompute its shapes the same way.
  Geometry is computed with the CLJS modifiers."
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.fills :as types.fills]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.attrs :refer [editable-attrs]]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.shape.token :as ctst]
   [app.common.types.stroke :as cts]
   [app.common.types.text :as txt]
   [app.common.types.token :as ctt]
   [app.common.types.token-values :as cttv]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;; --- Values

(defn value->shadows
  "Shadows of a shadow token `value`. Each shadow keeps the id of the one in
  its position in `current`, so the same value always gives the same
  shadows."
  [value current]
  (into []
        (map-indexed
         (fn [index {:keys [offset-x offset-y blur spread color inset]}]
           {:id (or (:id (get current index)) (uuid/next))
            :hidden false
            :offset-x offset-x
            :offset-y offset-y
            :blur (cond-> blur (number? blur) (max 0))
            :color (cc/parse-css-color color)
            :spread spread
            :style (if (true? inset) :inner-shadow :drop-shadow)}))
        value))

(defn page-background
  "Background color of `page` from the color token linked to it, or nil when
  it has none or it doesn't resolve to a color."
  [page resolved-tokens]
  (some-> (get resolved-tokens (:background-token page))
          :resolved-value
          (cc/parse-css-color)
          :color))

;; --- Attributes

(def ^:private padding-attrs #{:p1 :p2 :p3 :p4})

(def ^:private size-attrs #{:width :height})

(def ^:private layout-attrs
  "Attributes that change how a layout places its children."
  ctt/spacing-gap-padding-keys)

(def ^:private layout-item-attrs
  "Attributes that change how a shape is placed by its parent layout."
  (set/union ctt/spacing-margin-keys ctt/sizing-layout-item-keys))

(def text-layout-attrs
  "Text token attributes that change the size of the texts."
  #{:font-size :font-family :font-weight :letter-spacing :line-height :typography})

(def ^:private stored-attr-paths
  "Path where a shape keeps each attribute that a token sets with its value
  as it is."
  (merge
   (into {}
         (map (fn [attr] [attr [attr]]))
         (concat ctt/border-radius-keys
                 ctt/opacity-keys
                 ctt/sizing-keys))
   (into {}
         (map (fn [attr] [attr [:layout-padding attr]]))
         padding-attrs)
   (into {}
         (map (fn [attr] [attr [:layout-item-margin attr]]))
         ctt/spacing-margin-keys)
   (into {}
         (map (fn [attr] [attr [:layout-gap attr]]))
         ctt/spacing-gap-keys)))

(def ^:private non-negative-attrs
  "Attributes whose token value is set as zero when it is negative."
  (set/union ctt/border-radius-keys
             padding-attrs
             ctt/spacing-gap-keys
             ctt/sizing-layout-item-keys))

(def ^:private text-attr-values
  "How the text nodes keep the value of each text attribute that a token
  sets."
  {:font-size str
   :letter-spacing str
   :line-height identity})

(defn- text-node?
  [node]
  (or (txt/is-text-node? node)
      (txt/is-paragraph-node? node)))

(defn- text-holds-value?
  "True when every text and paragraph node of `shape` keeps `value` on
  `attr`, with no typography that a token update would remove."
  [shape attr value]
  (let [node-value (get text-attr-values attr)
        nodes      (txt/node-seq text-node? (:content shape))]
    (and (seq nodes)
         (every? #(and (= (get % attr) (node-value value))
                       (nil? (:typography-ref-id %))
                       (nil? (:typography-ref-file %)))
                 nodes))))

(defn- holds-value?
  "True when `shape` already holds `value`, the resolved value of the token
  applied to `attr`, so applying the token would not change it."
  [shape attr value]
  (when (number? value)
    (if-let [path (get stored-attr-paths attr)]
      (= (get-in shape path)
         (if (contains? non-negative-attrs attr)
           (max 0 value)
           value))
      (and (contains? text-attr-values attr)
           (cfh/text-shape? shape)
           (text-holds-value? shape attr value)))))

(defn- token-values
  "Resolved values of the tokens applied to `attrs` of `shape`, by
  attribute. Leaves out the attributes whose token doesn't resolve."
  [shape attrs resolved-tokens]
  (let [applied (:applied-tokens shape)]
    (reduce (fn [values attr]
              (let [value (get-in resolved-tokens [(get applied attr) :resolved-value])]
                (if (nil? value)
                  values
                  (assoc values attr value))))
            {}
            attrs)))

(defn- layout-sized?
  "True when a layout sets the size of `shape` on the axis of `attr`: the
  shape hugs its content or fills its parent layout."
  [objects shape attr]
  (let [width? (= attr :width)]
    (or (and (ctl/any-layout? shape)
             (if width? (ctl/auto-width? shape) (ctl/auto-height? shape)))
        (and (ctl/any-layout-immediate-child? objects shape)
             (if width? (ctl/fill-width? shape) (ctl/fill-height? shape))))))

(defn- geometry-held?
  "True when `shape` of `objects` already has the size, rotation or
  position of `value` on `attr`, up to the float precision. A size that a
  layout sets is held: the layout keeps it whatever the token says."
  [objects shape attr value]
  (when (number? value)
    (cond
      (contains? size-attrs attr)
      (or (layout-sized? objects shape attr)
          (some-> (get shape attr) (mth/close? value)))

      (contains? ctt/rotation-keys attr)
      (mth/close? (d/nilv (:rotation shape) 0) value)

      (contains? ctt/axis-keys attr)
      (let [bbox  (-> shape :points grc/points->rect)
            frame (cfh/get-parent-frame objects shape)]
        (mth/close? (get bbox attr) (+ value (d/nilv (get frame attr) 0)))))))

(defn- values-to-set
  "The values of `values` that `shape` of `objects` doesn't hold yet."
  [objects shape values]
  (into {}
        (remove (fn [[attr value]]
                  (or (holds-value? shape attr value)
                      (geometry-held? objects shape attr value))))
        values))



;; --- Texts

(defn- merge-text-attrs
  [node attrs]
  (-> node
      (d/txt-merge attrs)
      (cty/remove-typography-from-node)))

(defn- find-font-family
  "Case insensitive lookup of a font of `fonts` by family."
  [fonts family]
  (let [family (str/lower family)]
    (d/seek #(= family (str/lower (:family %))) (vals fonts))))

(defn- font-family-attrs
  [value fonts]
  (let [family (-> (if (string? value) value (first value))
                   (some-> (str/replace #"^[\"']+|[\"']+$" "")))
        font   (some->> family (find-font-family fonts))]
    (if font
      {:font-id (:id font)
       :font-family (:family font)}
      {:font-id (str uuid/zero)
       :font-family family})))

(defn- with-closest-variant
  "`txt-attrs` with the variant of the font `font-id` closest to the weight
  and style of `target`."
  [txt-attrs target font-id fonts on-mismatch]
  (let [font    (get fonts font-id)
        variant (when font
                  (cty/closest-variant (:variants font []) (:weight target) (:style target)))]
    (when (and (fn? on-mismatch)
               (some? variant)
               (or (not= (:font-weight target) (:weight variant))
                   (and (some? (:font-style target))
                        (not= (:font-style target) (:style variant)))))
      (on-mismatch))
    (cond-> txt-attrs
      (some? (:id variant)) (assoc :font-variant-id (:id variant)))))

(defn- font-family-fn
  [value shape {:keys [fonts on-variant-mismatch]}]
  (let [txt-attrs  (font-family-attrs value fonts)
        not-found? (= (:font-id txt-attrs) (str uuid/zero))
        weight?    (ctst/font-weight-applied? shape)]
    (fn [node]
      (let [font-id   (if not-found? (:font-id node) (:font-id txt-attrs))
            txt-attrs (cond-> txt-attrs
                        weight? (with-closest-variant node font-id fonts on-variant-mismatch))]
        (merge-text-attrs node txt-attrs)))))

(defn- font-weight-fn
  [value {:keys [fonts on-variant-mismatch]}]
  (when-let [variant (ctt/valid-font-weight-variant value)]
    (let [variant (assoc variant
                         :font-weight (:weight variant)
                         :font-style (:style variant))]
      (fn [node]
        (merge-text-attrs node (with-closest-variant variant variant (:font-id node) fonts on-variant-mismatch))))))

(defn- text-attrs-fn
  [attrs]
  (fn [node] (merge-text-attrs node attrs)))

(defn- atomic-text-fn
  "Function that sets on a text node the value of the token applied to
  `attr`, or nil when `value` isn't valid for it."
  [attr value shape opts]
  (case attr
    :font-size       (when (number? value) (text-attrs-fn {:font-size (str value)}))
    :letter-spacing  (when (number? value) (text-attrs-fn {:letter-spacing (str value)}))
    :line-height     (when (number? value) (text-attrs-fn {:line-height value}))
    :text-case       (when (string? value) (text-attrs-fn {:text-transform value}))
    :text-decoration (when (ctt/valid-text-decoration value)
                       (text-attrs-fn {:text-decoration (if (= value "strike-through")
                                                          "line-through"
                                                          value)}))
    :font-family     (font-family-fn value shape opts)
    :font-weight     (font-weight-fn value opts)
    nil))

(def ^:private typography-order
  [:font-size :font-family :font-weight :letter-spacing :text-case :text-decoration :line-height])

(defn- typography-fns
  [value shape opts]
  (when (map? value)
    (into []
          (keep (fn [attr]
                  (let [value (get value attr)]
                    (when (some? value)
                      (atomic-text-fn attr value shape opts)))))
          typography-order)))

(defn- text-node-fns
  "Functions to apply to every text and paragraph node of `shape` for the
  text tokens in `values`: the typography first, then the atomic ones in
  typography order."
  [shape values opts]
  (concat
   (when-let [value (get values :typography)]
     (typography-fns value shape opts))
   (keep (fn [attr]
           (when-let [value (get values attr)]
             (atomic-text-fn attr value shape opts)))
         typography-order)))

(defn- color->fill
  [{:keys [color opacity]}]
  (-> {:fill-color color :fill-opacity opacity}
      (d/without-nils)
      (types.fills/check-fill)))

;; --- Shape attributes

(defn- set-stroke-side-width
  [shape attr value]
  (let [strokes (:strokes shape)
        stroke  (cts/materialize-stroke-side-widths (first strokes) #{attr} value)]
    (if (seq strokes)
      (assoc shape :strokes (into [stroke] (rest strokes)))
      (assoc shape :strokes [stroke]))))

(defn- set-attr
  "Set on `shape` the `value` of the token applied to `attr`, when it is
  valid for it. Text and geometry attributes are set elsewhere."
  [shape attr value layout-child?]
  (cond
    (contains? ctt/border-radius-keys attr)
    (cond-> shape
      (number? value) (ctsr/set-radius-for-corners #{attr} (max 0 value)))

    (= attr :fill)
    (if-let [color (when-not (cfh/text-shape? shape) (cc/parse-css-color value))]
      (update shape :fills types.fills/assoc 0 (color->fill color))
      shape)

    (= attr :stroke-color)
    (if-let [color (cc/parse-css-color value)]
      (cts/update-shape-stroke-color shape 0 color)
      shape)

    (contains? ctt/stroke-width-keys attr)
    (cond-> shape
      (number? value) (set-stroke-side-width attr (max 0 value)))

    (contains? ctt/opacity-keys attr)
    (cond-> shape
      (and (number? value) (<= 0 value 1)) (assoc :opacity value))

    (contains? ctt/shadow-keys attr)
    (cond-> shape
      (sequential? value) (assoc :shadow (value->shadows value (:shadow shape))))

    ;; A padding keeps its four sides, the missing ones at 0
    (contains? padding-attrs attr)
    (cond-> shape
      (and (number? value) (ctl/any-layout? shape))
      (update :layout-padding #(-> (merge {:p1 0 :p2 0 :p3 0 :p4 0} (d/without-nils %))
                                   (assoc attr (max 0 value)))))

    (contains? ctt/spacing-gap-keys attr)
    (cond-> shape
      (and (number? value) (ctl/any-layout? shape))
      (assoc-in [:layout-gap attr] (max 0 value)))

    (contains? ctt/spacing-margin-keys attr)
    (cond-> shape
      (and (number? value) layout-child?)
      (assoc-in [:layout-item-margin attr] value))

    (contains? ctt/sizing-layout-item-keys attr)
    (cond-> shape
      (number? value) (assoc attr (max 0 value)))

    :else
    shape))

(defn- set-first-fill
  "`fills` with `fill` as the first one."
  [fills fill]
  (if (seq fills)
    (assoc (vec fills) 0 fill)
    [fill]))

(defn- set-text-values
  [shape values {:keys [reset-position-data?] :as opts}]
  (let [fill-color (some-> (get values :fill) (cc/parse-css-color))
        fill       (some-> fill-color (color->fill))
        text-fns   (text-node-fns shape values opts)
        node-fns   (cond->> text-fns
                     (some? fill)
                     (cons #(update (txt/migrate-node %) :fills types.fills/assoc 0 fill)))]
    (if (empty? node-fns)
      shape
      (let [update-node (apply comp (reverse node-fns))
            shape'      (cond-> shape
                          (some? fill-color) (dissoc :fills)
                          :always (update :content #(txt/transform-nodes text-node? update-node %)))]
        (cond
          (or (not reset-position-data?)
              (= (:content shape) (:content shape')))
          shape'

          ;; A new fill doesn't move the text: its fragments take the fill
          (and (empty? text-fns) (seq (:position-data shape')))
          (update shape' :position-data
                  (fn [position-data]
                    (mapv #(update % :fills set-first-fill fill) position-data)))

          :else
          (dissoc shape' :position-data))))))

(defn- set-values
  [shape values layout-child? opts]
  (as-> shape $
    (reduce-kv (fn [shape attr value] (set-attr shape attr value layout-child?)) $ values)
    (cond-> $
      (cfh/text-shape? $) (set-text-values values opts))))

(defn- update-shapes
  "Update the shapes `ids` with `update-fn`, as a derived change that keeps
  their touched flags and token values, and assign again the cells of the
  grids among them."
  [changes ids update-fn attrs]
  (let [objects  (pcb/lookup-objects changes)
        grid-ids (filter #(ctl/grid-layout? objects %) ids)]
    (cond-> (pcb/update-shapes changes ids update-fn {:attrs attrs :ignore-touched true :derived? true})
      (seq grid-ids)
      (-> (pcb/update-shapes grid-ids ctl/assign-cell-positions {:with-objects? true})
          (pcb/reorder-grid-children ids)))))

(defn- reg-objects-attr?
  "Attributes that change the content of the booleans and groups that hold
  the shape."
  [attr]
  (or (contains? ctt/border-radius-keys attr)
      (contains? ctt/stroke-width-keys attr)
      (contains? ctt/shadow-keys attr)))

(def ^:private geometry-attrs
  (set/union size-attrs ctt/rotation-keys ctt/axis-keys))

(defn- set-shape-values
  "Set the values that don't change the geometry, and record in the shapes
  all the `values` they are set to, in a single change for each shape."
  [changes updates values opts]
  (let [objects (pcb/lookup-objects changes)
        updates (into {}
                      (keep (fn [[id values]]
                              (let [values (apply dissoc values geometry-attrs)]
                                (when (seq values) [id values]))))
                      updates)
        ids     (vec (keys values))
        child?  (into #{}
                      (filter #(ctl/any-layout-immediate-child? objects (get objects %)))
                      (keys updates))
        reg-ids (into []
                      (comp (filter #(some reg-objects-attr? (keys (get updates %)))))
                      (keys updates))

        update-shape
        (fn [{:keys [id] :as shape}]
          (let [shape-updates (get updates id)]
            (-> shape
                (cond-> (some? shape-updates)
                  (set-values shape-updates (contains? child? id) opts))
                (cttv/set-values (get values id)))))]
    (if (empty? ids)
      changes
      (-> changes
          (update-shapes ids update-shape nil)
          (cond-> (seq reg-ids) (pcb/resize-parents reg-ids))))))

(defn- fix-layout-items-sizing
  "Keep the sizing of the shapes with new layout item limits, of their
  children and of their parents consistent with their layouts."
  [changes updates]
  (let [groups (->> updates
                    (keep (fn [[id values]]
                            (let [limits (into {}
                                               (keep (fn [[attr value]]
                                                       (when (number? value) [attr (max 0 value)])))
                                               (select-keys values ctt/sizing-layout-item-keys))]
                              (when (seq limits)
                                [id limits]))))
                    (group-by second))]
    (reduce
     (fn [changes [props entries]]
       (let [objects      (pcb/lookup-objects changes)
             ids          (map first entries)
             children-ids (mapcat #(cfh/get-children-ids objects %) ids)
             parent-ids   (distinct (keep #(cfh/get-parent-id objects %) ids))]
         (-> changes
             (update-shapes children-ids (partial ctl/fix-child-sizing objects props) nil)
             (pcb/update-shapes parent-ids
                                (fn [parent objects]
                                  (-> parent
                                      (ctl/fix-parent-sizing objects (set ids) props)
                                      (cond-> (ctl/grid-layout? parent)
                                        (ctl/assign-cells objects))))
                                {:with-objects? true :ignore-touched true :derived? true}))))
     changes
     groups)))

;; --- Geometry

(defn- move-frame-guides
  "Move the guides of the page in `changes` with the frames they belong to."
  [changes objects object-modifiers]
  (let [page (::pcb/page (meta changes))]
    (reduce
     (fn [changes {:keys [frame-id axis] :as guide}]
       (let [modifiers (dm/get-in object-modifiers [frame-id :modifiers])
             frame     (get objects frame-id)]
         (if (and (some? modifiers) (some? frame))
           (let [frame' (gsh/transform-shape frame modifiers)
                 moved  (gpt/to-vec (gpt/point (:x frame) (:y frame))
                                    (gpt/point (:x frame') (:y frame')))]
             (pcb/set-guide changes (:id guide) (update guide :position + (get moved axis))))
           changes)))
     changes
     (some-> page :guides vals))))

(defn- apply-modif-tree
  "Apply `modif-tree`, with its layouts and constraints, to the shapes."
  [changes modif-tree params {:keys [reset-position-data?]}]
  (if (empty? modif-tree)
    changes
    (let [objects          (pcb/lookup-objects changes)
          object-modifiers (gm/set-objects-modifiers modif-tree objects params)
          ids              (into [] (remove #(= % uuid/zero)) (keys object-modifiers))
          moved-bool-ids   (ctm/moved-bool-ids objects object-modifiers)

          update-shape
          (fn [shape]
            (let [modifiers (dm/get-in object-modifiers [(:id shape) :modifiers])]
              (-> shape
                  (gsh/transform-shape modifiers)
                  (cond-> (and reset-position-data?
                               (cfh/text-shape? shape)
                               (some? modifiers)
                               (not (ctm/only-move? modifiers)))
                    (dissoc :position-data)))))]
      (-> changes
          (update-shapes ids update-shape ctm/transform-attrs)
          (pcb/resize-parents ids moved-bool-ids)
          (move-frame-guides objects object-modifiers)))))

(defn rotation-modif-tree
  "Modifiers that rotate the shapes with new rotations in `updates`."
  [objects updates]
  (into {}
        (keep (fn [[id values]]
                (let [angle (get values :rotation)
                      shape (get objects id)]
                  (when (and (number? angle)
                             (some? shape)
                             (not (:blocked shape))
                             (contains? (get editable-attrs (:type shape)) :rotation))
                    [id {:modifiers (ctm/rotation-modifiers
                                     shape
                                     (gsh/shape->center shape)
                                     (- angle (d/nilv (:rotation shape) 0)))}]))))
        updates))

(defn position-modif-tree
  "Modifiers that move the shapes with new positions in `updates`."
  [objects updates]
  (into {}
        (keep (fn [[id values]]
                (let [{:keys [x y]} (d/update-vals (select-keys values ctt/axis-keys)
                                                   #(when (number? %) %))
                      shape         (get objects id)]
                  (when (and (some? shape) (or (some? x) (some? y)))
                    (let [bbox    (-> shape :points grc/points->rect)
                          frame   (cfh/get-parent-frame objects shape)
                          current (gpt/point (:x bbox) (:y bbox))
                          target  (gpt/point (if (some? x) (+ x (:x frame)) (:x bbox))
                                             (if (some? y) (+ y (:y frame)) (:y bbox)))]
                      [id {:modifiers (ctm/move-modifiers (gpt/subtract target current))}])))))
        updates))

(defn- changed-layout-ids
  "Ids of the layouts that the new values in `updates` change: the shapes
  with new layout attributes, and the shapes with new layout item
  attributes together with their parents."
  [objects updates]
  (into #{}
        (mapcat (fn [[id values]]
                  (let [attrs (keys values)]
                    (concat (when (some layout-attrs attrs)
                              [id])
                            (when (some layout-item-attrs attrs)
                              (cons id (some-> (cfh/get-parent-id objects id) list)))))))
        updates))

(defn size-modif-tree
  "Modifiers that resize the shapes with new sizes and lay out again the
  layouts that change."
  [objects updates layout-ids]
  (let [sizes (into {}
                    (keep (fn [[id values]]
                            (let [size (into {}
                                             (filter (comp number? val))
                                             (select-keys values size-attrs))]
                              (when (and (seq size) (contains? objects id))
                                [id size]))))
                    updates)]
    (merge
     (into {}
           (keep (fn [id]
                   (when (and (not= id uuid/zero) (contains? objects id))
                     [id {:modifiers (ctm/reflow-modifiers)}])))
           layout-ids)
     (into {}
           (map (fn [[id size]]
                  [id {:modifiers (ctm/size-modifiers (get objects id) size)}]))
           sizes))))

;; --- Propagation

(defn generate-token-propagation
  "Add to `changes` the shape changes that set the shapes of `objects` to the
  resolved values of the tokens applied to them.

  - `resolved-tokens`: map of token name to token, with `:resolved-value`.
  - `targets`: map of shape id to the set of token attributes to set.
    Attributes whose token doesn't resolve are left as they are.

  `changes` needs a page id; with a page (`pcb/with-page`) the guides of
  the frames that move are moved too. The objects of the result
  (`pcb/lookup-objects`) are `objects` with the new values.

  Options:
  - `:fonts`: map of font id to font, to find the fonts of the font family
    and weight tokens.
  - `:reset-position-data?`: drop the position data of the texts whose
    layout changes, for pages that aren't rendered.
  - `:on-variant-mismatch`: called when a font weight token has no exact
    variant in a font.
  - `:geometry`: `:cljs` (default) computes rotations, positions, sizes and
    layouts here; with `:wasm` they are left to the caller, to apply with
    the renderer (`pending-geometry`).

  The touched flags of the shapes are kept, so component copies don't
  take the values as overrides. The values are recorded in the shapes
  (`:token-values`), also the ones they already held."
  [changes objects resolved-tokens targets opts]
  (let [values  (into {}
                      (keep (fn [[id attrs]]
                              (when-let [shape (get objects id)]
                                (let [values (token-values shape attrs resolved-tokens)]
                                  (when (seq values) [id values])))))
                      targets)
        updates (into {}
                      (keep (fn [[id values]]
                              (let [values (values-to-set objects (get objects id) values)]
                                (when (seq values) [id values]))))
                      values)]
    (if (empty? values)
      changes
      (let [layout-ids (changed-layout-ids objects updates)
            local      (-> (pcb/empty-changes)
                           (with-meta (select-keys (meta changes) [::pcb/page ::pcb/page-id]))
                           (pcb/with-objects objects)
                           (set-shape-values updates values opts)
                           (fix-layout-items-sizing updates))
            wasm?      (= :wasm (:geometry opts))
            local      (if wasm?
                         local
                         (-> local
                             (apply-modif-tree (rotation-modif-tree (pcb/lookup-objects local) updates)
                                               nil
                                               opts)
                             (as-> $ (apply-modif-tree $
                                                       (position-modif-tree (pcb/lookup-objects $) updates)
                                                       {:ignore-constraints false}
                                                       opts))
                             (as-> $ (apply-modif-tree $
                                                       (size-modif-tree (pcb/lookup-objects $) updates layout-ids)
                                                       {:changed-layout-ids layout-ids}
                                                       opts))))]
        ;; The objects of the result are the ones with every change applied
        (-> (pcb/concat-changes changes local)
            (vary-meta assoc
                       ::pcb/file-data (::pcb/file-data (meta local))
                       ::pcb/applied-changes-count (+ (count (:redo-changes changes))
                                                      (count (:redo-changes local))))
            (cond-> (and wasm?
                         (or (seq layout-ids)
                             (some #(some geometry-attrs (keys %)) (vals updates))))
              (vary-meta assoc ::geometry {:updates updates :layout-ids layout-ids})))))))

(defn target-weight
  "Relative cost of setting the token attributes `attrs` of `shape`: the
  texts measured again and the geometry cost more than the other values."
  [shape attrs]
  (cond
    (and (cfh/text-shape? shape) (some text-layout-attrs attrs))
    10

    (some #(or (contains? geometry-attrs %)
               (contains? layout-attrs %)
               (contains? layout-item-attrs %))
          attrs)
    3

    :else
    1))

(defn pending-geometry
  "With `:geometry :wasm`, the geometry left to apply after `changes`: the
  new values by shape and the layouts that change. Nil when there is none."
  [changes]
  (::geometry (meta changes)))

(defn text-ids-to-resize
  "Ids of the texts in `targets` whose size may change with the tokens."
  [objects targets]
  (into []
        (keep (fn [[id attrs]]
                (when (and (cfh/text-shape? (get objects id))
                           (some text-layout-attrs attrs))
                  id)))
        targets))

;; --- Readers

(defn resolved-values
  "Map of token name to resolved value, from `resolved-tokens`, to save in
  the file (`:tokens-resolved`)."
  [resolved-tokens]
  (into {}
        (keep (fn [[token-name token]]
                (let [value (:resolved-value token)]
                  (when (some? value) [token-name value]))))
        resolved-tokens))

(defn values->resolved-tokens
  "Resolved tokens from `values`, a map of token name to resolved value
  saved in a file."
  [values]
  (into {} (map (fn [[token-name value]] [token-name {:name token-name :resolved-value value}])) values))

(defn out-of-date-targets
  "Targets with the token attributes of the shapes of `objects` that are out
  of date with `resolved-tokens`; unassigned ones only with `unassigned?`."
  [objects resolved-tokens unassigned?]
  (into {}
        (keep (fn [[id shape]]
                (when (seq (:applied-tokens shape))
                  (let [attrs (cttv/out-of-date-attrs shape resolved-tokens {:unassigned? unassigned?})]
                    (when (seq attrs) [id attrs])))))
        objects))

(defn- top-shape-id
  "Id of the topmost shape of `objects` under the root that holds `id`; nil
  for the root."
  [objects id]
  (->> (cfh/get-parent-ids-seq-with-self objects id)
       (take-while #(and (not= % uuid/zero) (contains? objects %)))
       (last)))

(defn layout-scope
  "Ids of the shapes of `objects` whose token values may change the shapes
  `ids`: the topmost shapes under the root that hold them, and their
  children, since no layout goes beyond them. Nil when `ids` hold the
  root."
  [objects ids]
  (let [top-ids (map #(top-shape-id objects %) ids)]
    (when (every? some? top-ids)
      (into #{}
            (mapcat #(cfh/get-children-ids-with-self objects %))
            (distinct top-ids)))))

(defn- scoped-objects
  "`objects`, or only the ones of the `layout-scope` of `ids` when given."
  [objects ids]
  (if-let [scope (some->> (seq ids) (layout-scope objects))]
    (into {} (keep (fn [id] (when-let [shape (get objects id)] [id shape]))) scope)
    objects))

(defn page-out-of-date?
  "True when some shape of `page` holds another value than its tokens
  resolve to in `resolved-tokens`, only in the `layout-scope` of `ids` when
  given. Unassigned values count as up to date."
  [page resolved-tokens & {:keys [ids]}]
  (boolean
   (some (fn [[_ shape]]
           (and (seq (:applied-tokens shape))
                (seq (cttv/out-of-date-attrs shape resolved-tokens {:unassigned? false}))))
         (scoped-objects (:objects page) ids))))

(defn generate-page-correction
  "Changes that set the out of date shapes of `page` to `resolved-tokens`,
  for a reader: unassigned values count as up to date and texts drop their
  position data when their content changes. With `ids`, only the shapes of
  their `layout-scope` are set."
  [page resolved-tokens fonts & {:keys [ids]}]
  (let [objects (:objects page)]
    (-> (pcb/empty-changes nil (:id page))
        (pcb/with-page page)
        (generate-token-propagation objects
                                    resolved-tokens
                                    (out-of-date-targets (scoped-objects objects ids) resolved-tokens false)
                                    {:fonts fonts :reset-position-data? true}))))

(defn correct-objects
  "The objects of `page` with the out of date shapes set to
  `resolved-tokens`, for a reader; with `ids`, only the shapes that may
  change them (`layout-scope`)."
  [page resolved-tokens fonts & {:keys [ids]}]
  (let [changes (generate-page-correction page resolved-tokens fonts :ids ids)]
    (if (empty? (:redo-changes changes))
      (:objects page)
      (pcb/lookup-objects changes))))
