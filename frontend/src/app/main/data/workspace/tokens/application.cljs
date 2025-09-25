;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.application
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.shape.token :as ctst]
   [app.common.types.stroke :as cts]
   [app.common.types.text :as txt]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as wdc]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwtr]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(declare token-properties)

;; Events to update the value of attributes with applied tokens ---------------------------------------------------------

;; (note that dwsh/update-shapes function returns an event)

(defn update-shape-radius-all
  ([value shape-ids attributes] (update-shape-radius-all value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (when (number? value)
     (dwsh/update-shapes shape-ids
                         (fn [shape]
                           (ctsr/set-radius-to-all-corners shape value))
                         {:reg-objects? true
                          :ignore-touched true
                          :page-id page-id
                          :attrs ctt/border-radius-keys}))))

(defn update-shape-radius-for-corners
  ([value shape-ids attributes] (update-shape-radius-for-corners value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (when (number? value)
     (dwsh/update-shapes shape-ids
                         (fn [shape]
                           (ctsr/set-radius-for-corners shape attributes value))
                         {:reg-objects? true
                          :ignore-touched true
                          :page-id page-id
                          :attrs ctt/border-radius-keys}))))

(defn update-opacity
  ([value shape-ids attributes] (update-opacity value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (when (<= 0 value 1)
     (dwsh/update-shapes shape-ids
                         #(assoc % :opacity value)
                         {:ignore-touched true
                          :page-id page-id}))))

;; FIXME: if attributes are not always present, maybe we have an
;; options here for pass optional value and preserve the correct and
;; uniform api across all functions (?)

(defn update-rotation
  ([value shape-ids attributes] (update-rotation value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (ptk/reify ::update-shape-rotation
     ptk/WatchEvent
     (watch [_ _ _]
       (when (number? value)
         (rx/of
          (udw/trigger-bounding-box-cloaking shape-ids)
          (udw/increase-rotation shape-ids value nil
                                 {:page-id page-id
                                  :ignore-touched true})))))))

(defn update-stroke-width
  ([value shape-ids attributes] (update-stroke-width value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (when (number? value)
     (dwsh/update-shapes shape-ids
                         (fn [shape]
                           (if (seq (:strokes shape))
                             (assoc-in shape [:strokes 0 :stroke-width] value)
                             (let [stroke (assoc cts/default-stroke :stroke-width value)]
                               (assoc shape :strokes [stroke]))))
                         {:reg-objects? true
                          :ignore-touched true
                          :page-id page-id
                          :attrs [:strokes]}))))

(defn update-color [f value shape-ids page-id]
  (when-let [tc (tinycolor/valid-color value)]
    (let [hex (tinycolor/->hex-string tc)
          opacity (tinycolor/alpha tc)]
      (f shape-ids {:color hex :opacity opacity} 0 {:ignore-touched true
                                                    :page-id page-id}))))

(defn- value->color
  "Transform a token color value into penpot color data structure"
  [color]
  (when-let [tc (tinycolor/valid-color color)]
    (let [hex (tinycolor/->hex-string tc)
          opacity (tinycolor/alpha tc)]
      {:color hex :opacity opacity})))

(defn update-fill
  ([value shape-ids attributes]
   (update-fill value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ;; The attributes param is needed to have the same arity that other update functions
   (ptk/reify ::update-fill
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [color (value->color value)]
         (let [update-fn #(wdc/assoc-shape-fill %1 0 %2)]
           (wdc/transform-fill state shape-ids color update-fn {:ignore-touched true
                                                                :page-id page-id})))))))

(defn update-stroke-color
  ([value shape-ids attributes]
   (update-stroke-color value shape-ids attributes nil))

   ;; The attributes param is needed to have the same arity that other update functions
  ([value shape-ids _attributes page-id]
   (when-let [color (value->color value)]
     (dwsh/update-shapes shape-ids
                         #(wdc/update-shape-stroke-color % 0 color)
                         {:page-id page-id
                          :ignore-touched true
                          :changed-sub-attr [:stroke-color]}))))

(defn update-fill-stroke
  ([value shape-ids attributes]
   (update-fill-stroke value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-fill-stroke
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of
        (when (:fill attributes) (update-fill value shape-ids attributes page-id))
        (when (:stroke-color attributes) (update-stroke-color value shape-ids attributes page-id)))))))

(defn update-shape-dimensions
  ([value shape-ids attributes] (update-shape-dimensions value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-shape-dimensions
     ptk/WatchEvent
     (watch [_ _ _]
       (when (number? value)
         (rx/of
          (when (:width attributes) (dwtr/update-dimensions shape-ids :width value {:ignore-touched true :page-id page-id}))
          (when (:height attributes) (dwtr/update-dimensions shape-ids :height value {:ignore-touched true :page-id page-id}))))))))

(defn- attributes->layout-gap [attributes value]
  (let [layout-gap (-> (set/intersection attributes #{:column-gap :row-gap})
                       (zipmap (repeat value)))]
    {:layout-gap layout-gap}))

(defn- shape-ids-with-layout [state page-id shape-ids]
  (->> (dsh/lookup-shapes state page-id shape-ids)
       (eduction
        (filter ctsl/any-layout?)
        (map :id))))

(defn- shape-ids-with-layout-parent [state page-id shape-ids]
  (let [objects (dsh/lookup-page-objects state)]
    (->> (dsh/lookup-shapes state page-id shape-ids)
         (eduction
          (filter #(ctsl/any-layout-immediate-child? objects %))
          (map :id)))))

(defn update-layout-item-margin
  ([value shape-ids attrs] (update-layout-item-margin value shape-ids attrs nil))
  ([value shape-ids attrs page-id]
   (ptk/reify ::update-layout-item-margin
     ptk/WatchEvent
     (watch [_ state _]
       (when (number? value)
         (let [ids-with-layout-parent (shape-ids-with-layout-parent state (or page-id (:current-page-id state)) shape-ids)]
           (rx/of
            (dwsl/update-layout ids-with-layout-parent
                                {:layout-item-margin (zipmap attrs (repeat value))}
                                {:ignore-touched true
                                 :page-id page-id}))))))))

(defn update-layout-padding
  ([value shape-ids attrs] (update-layout-padding value shape-ids attrs nil))
  ([value shape-ids attrs page-id]
   (ptk/reify ::update-layout-padding
     ptk/WatchEvent
     (watch [_ state _]
       (when (number? value)
         (let [ids-with-layout (shape-ids-with-layout state (or page-id (:current-page-id state)) shape-ids)]
           (rx/of
            (dwsl/update-layout ids-with-layout
                                {:layout-padding (zipmap attrs (repeat value))}
                                {:ignore-touched true
                                 :page-id page-id}))))))))

(defn update-layout-spacing
  ([value shape-ids attributes] (update-layout-spacing value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-layout-spacing
     ptk/WatchEvent
     (watch [_ state _]
       (when (number? value)
         (let [ids-with-layout (shape-ids-with-layout state (or page-id (:current-page-id state)) shape-ids)
               layout-attributes (attributes->layout-gap attributes value)]
           (rx/of
            (dwsl/update-layout ids-with-layout
                                layout-attributes
                                {:ignore-touched true
                                 :page-id page-id}))))))))

(defn update-shape-position
  ([value shape-ids attributes] (update-shape-position value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-shape-position
     ptk/WatchEvent
     (watch [_ state _]
       (when (number? value)
         (let [page-id (or page-id (get state :current-page-id))]
           (->> (rx/from shape-ids)
                (rx/map #(dwtr/update-position % (zipmap attributes (repeat value))
                                               {:ignore-touched true
                                                :page-id page-id})))))))))

(defn update-layout-sizing-limits
  ([value shape-ids attributes] (update-layout-sizing-limits value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-layout-sizing-limits
     ptk/WatchEvent
     (watch [_ _ _]
       (when (number? value)
         (let [props (-> {:layout-item-min-w value
                          :layout-item-min-h value
                          :layout-item-max-w value
                          :layout-item-max-h value}
                         (select-keys attributes))]
           (rx/of
            (dwsl/update-layout-child shape-ids props {:ignore-touched true
                                                       :page-id page-id}))))))))

(defn- generate-text-shape-update
  [txt-attrs shape-ids page-id]
  (let [update-node? (fn [node]
                       (or (txt/is-text-node? node)
                           (txt/is-paragraph-node? node)))
        update-fn (fn [node _]
                    (-> node
                        (d/txt-merge txt-attrs)
                        (cty/remove-typography-from-node)))]
    (dwsh/update-shapes shape-ids
                        #(txt/update-text-content % update-node? update-fn nil)
                        {:ignore-touched true
                         :page-id page-id})))

(defn update-line-height
  ([value shape-ids attributes] (update-line-height value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when (number? value)
     (generate-text-shape-update {:line-height value} shape-ids page-id))))

(defn update-letter-spacing
  ([value shape-ids attributes] (update-letter-spacing value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when (number? value)
     (generate-text-shape-update {:letter-spacing (str value)} shape-ids page-id))))

(defn warn-font-variant-not-found! []
  (st/emit!
   (ntf/show {:content (tr "workspace.tokens.font-variant-not-found")
              :type :toast
              :level :warning
              :timeout 7000})))

(defn- update-closest-font-variant-id-by-weight
  [txt-attrs target-variant font-id on-mismatch]
  (let [font (fonts/get-font-data font-id)
        variant (when font
                  (fonts/find-closest-variant font (:weight target-variant) (:style target-variant)))
        call-on-mismatch? (when (and (fn? on-mismatch) variant)
                            (or
                             (not= (:font-weight target-variant) (:weight variant))
                             (when (:font-style target-variant)
                               (not= (:font-style target-variant) (:style variant)))))]
    (when call-on-mismatch?
      (on-mismatch))
    (cond-> txt-attrs
      (:id variant) (assoc :font-variant-id (:id variant)))))

(defn- generate-font-family-text-shape-update
  [txt-attrs shape-ids page-id on-mismatch]
  (let [not-found-font (= (:font-id txt-attrs) (str uuid/zero))
        update-node? (fn [node]
                       (or (txt/is-text-node? node)
                           (txt/is-paragraph-node? node)))
        update-fn (fn [node find-closest-weight?]
                    (let [font-id (if not-found-font (:font-id node) (:font-id txt-attrs))
                          txt-attrs (cond-> txt-attrs
                                      find-closest-weight? (update-closest-font-variant-id-by-weight node font-id on-mismatch))]
                      (-> node
                          (d/txt-merge txt-attrs)
                          (cty/remove-typography-from-node))))]
    (dwsh/update-shapes shape-ids
                        (fn [shape]
                          (txt/update-text-content shape update-node? #(update-fn %1 (ctst/font-weight-applied? shape)) nil))
                        {:ignore-touched true
                         :page-id page-id})))

(defn- create-font-family-text-attrs
  [value]
  (let [font-family (-> (first value)
                        ;; Strip quotes around font-family like `"Inter"`
                        (str/trim #"[\"']"))
        font (some-> font-family
                     (fonts/find-font-family))]
    (if font
      {:font-id (:id font)
       :font-family (:family font)}
      {:font-id (str uuid/zero)
       :font-family font-family})))

(defn update-font-family
  ([value shape-ids attributes] (update-font-family value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when-let [text-attrs (create-font-family-text-attrs value)]
     (generate-font-family-text-shape-update text-attrs shape-ids page-id nil))))

(defn update-font-family-interactive
  ([value shape-ids attributes] (update-font-family-interactive value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when-let [text-attrs (create-font-family-text-attrs value)]
     (generate-font-family-text-shape-update text-attrs shape-ids page-id warn-font-variant-not-found!))))

(defn update-font-size
  ([value shape-ids attributes] (update-font-size value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when (number? value)
     (generate-text-shape-update {:font-size (str value)} shape-ids page-id))))

(defn update-text-case
  ([value shape-ids attributes] (update-text-case value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when (string? value)
     (generate-text-shape-update {:text-transform value} shape-ids page-id))))

(defn update-text-decoration
  ([value shape-ids attributes] (update-text-decoration value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when (ctt/valid-text-decoration value)
     (let [css-value (case value
                       "strike-through" "line-through"
                       value)]
       (generate-text-shape-update {:text-decoration css-value} shape-ids page-id)))))

(defn update-text-decoration-interactive
  ([value shape-ids attributes] (update-text-decoration-interactive value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (when (ctt/valid-text-decoration value)
     (st/emit! (ptk/data-event :expand-text-more-options))
     (update-text-decoration value shape-ids attributes page-id))))

(defn- generate-font-weight-text-shape-update
  [font-variant shape-ids page-id on-mismatch]
  (let [font-variant (assoc font-variant
                            :font-weight (:weight font-variant)
                            :font-style (:style font-variant))
        update-node? (fn [node]
                       (or (txt/is-text-node? node)
                           (txt/is-paragraph-node? node)))
        update-fn (fn [node _]
                    (let [txt-attrs (update-closest-font-variant-id-by-weight font-variant font-variant (:font-id node) on-mismatch)]
                      (-> node
                          (d/txt-merge txt-attrs)
                          (cty/remove-typography-from-node))))]
    (dwsh/update-shapes shape-ids
                        #(txt/update-text-content % update-node? update-fn nil)
                        {:ignore-touched true
                         :page-id page-id})))

(defn update-font-weight
  ([value shape-ids attributes] (update-font-weight value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when-let [font-variant (ctt/valid-font-weight-variant value)]
     (generate-font-weight-text-shape-update font-variant shape-ids page-id nil))))

(defn update-font-weight-interactive
  ([value shape-ids attributes] (update-font-weight-interactive value shape-ids attributes nil))
  ([value shape-ids _attributes page-id]
   (when-let [font-variant (ctt/valid-font-weight-variant value)]
     (generate-font-weight-text-shape-update font-variant shape-ids page-id warn-font-variant-not-found!))))

(defn- apply-functions-map
  "Apply map of functions `fs` to a map of values `vs` using `args`.
  The keys for both must match to be applied with an non-nil value in `vs`.
  Returns a vector of the resulting values.

  E.g.: `(apply-functions {:a + :b -} {:a 1 :b nil :c 10} [1 1]) => [3]`"
  [fs vs args]
  (map (fn [[k f]]
         (when-let [v (get vs k)]
           (apply f v args)))
       fs))

(defn update-typography
  ([value shape-ids attributes] (update-typography value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (when (map? value)
     (rx/merge
      (apply-functions-map
       {:font-size update-font-size
        :font-family update-font-family
        :font-weight update-font-weight
        :letter-spacing update-letter-spacing
        :text-case update-text-case
        :text-decoration update-text-decoration}
       value
       [shape-ids attributes page-id])))))

(defn update-typography-interactive
  ([value shape-ids attributes] (update-typography value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (when (map? value)
     (rx/merge
      (apply-functions-map
       {:font-size update-font-size
        :font-family update-font-family-interactive
        :font-weight update-font-weight-interactive
        :letter-spacing update-letter-spacing
        :text-case update-text-case
        :text-decoration update-text-decoration-interactive}
       value
       [shape-ids attributes page-id])))))

;; Events to apply / unapply tokens to shapes ------------------------------------------------------------

(defn apply-token
  "Apply `attributes` that match `token` for `shape-ids`.

  Optionally remove attributes from `attributes-to-remove`,
  this is useful for applying a single attribute from an attributes set
  while removing other applied tokens from this set."
  [{:keys [attributes attributes-to-remove token shape-ids on-update-shape]}]
  (ptk/reify ::apply-token
    ptk/WatchEvent
    (watch [_ state _]
      ;; We do not allow to apply tokens while text editor is open.
      (when (empty? (get state :workspace-editor-state))
        (let [attributes-to-remove
              ;; Remove atomic typography tokens when applying composite and vice-verca
              (cond
                (ctt/typography-token-keys (:type token)) (set/union attributes-to-remove ctt/typography-keys)
                (ctt/typography-keys (:type token)) (set/union attributes-to-remove ctt/typography-token-keys)
                :else attributes-to-remove)]
          (when-let [tokens (some-> (dsh/lookup-file-data state)
                                    (get :tokens-lib)
                                    (ctob/get-tokens-in-active-sets))]
            (->> (sd/resolve-tokens tokens)
                 (rx/mapcat
                  (fn [resolved-tokens]
                    (let [undo-id (js/Symbol)
                          objects (dsh/lookup-page-objects state)
                          selected-shapes (select-keys objects shape-ids)

                          shapes (->> selected-shapes
                                      (filter (fn [[_ shape]]
                                                (or
                                                 (and (ctsl/any-layout-immediate-child? objects shape)
                                                      (some ctt/spacing-margin-keys attributes))
                                                 (ctt/any-appliable-attr? attributes (:type shape) (:layout shape))))))
                          shape-ids (d/nilv (keys shapes)  [])
                          any-variant? (->> shapes vals (some ctk/is-variant?) boolean)

                          resolved-value (get-in resolved-tokens [(cft/token-identifier token) :resolved-value])
                          tokenized-attributes (cft/attributes-map attributes token)
                          type (:type token)]
                      (rx/concat
                       (rx/of
                        (st/emit! (ev/event {::ev/name "apply-tokens"
                                             :type type
                                             :applyed-to attributes
                                             :applied-to-variant any-variant?}))
                        (dwu/start-undo-transaction undo-id)
                        (dwsh/update-shapes shape-ids (fn [shape]
                                                        (cond-> shape
                                                          attributes-to-remove
                                                          (update :applied-tokens #(apply (partial dissoc %) attributes-to-remove))
                                                          :always
                                                          (update :applied-tokens merge tokenized-attributes)))))
                       (when on-update-shape
                         (let [res (on-update-shape resolved-value shape-ids attributes)]
                           ;; Composed updates return observables and need to be executed differently
                           (if (rx/observable? res)
                             res
                             (rx/of res))))
                       (rx/of (dwu/commit-undo-transaction undo-id)))))))))))))

(defn apply-spacing-token
  "Handles edge-case for spacing token when applying token via toggle button.
  Splits out `shape-ids` into seperate default actions:
  - Layouts take the `default` update function
  - Shapes inside layout will only take margin"
  [{:keys [token shapes]}]
  (ptk/reify ::apply-spacing-token
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)

            {:keys [attributes on-update-shape]}
            (get token-properties (:type token))

            {:keys [other frame-children]}
            (group-by #(if (ctsl/any-layout-immediate-child? objects %) :frame-children :other) shapes)]

        (rx/of
         (apply-token {:attributes attributes
                       :token token
                       :shape-ids (map :id other)
                       :on-update-shape on-update-shape})
         (apply-token {:attributes ctt/spacing-margin-keys
                       :token token
                       :shape-ids (map :id frame-children)
                       :on-update-shape update-layout-item-margin}))))))

(defn unapply-token
  "Removes `attributes` that match `token` for `shape-ids`.

  Doesn't update shape attributes."
  [{:keys [attributes token shape-ids] :as _props}]
  (ptk/reify ::unapply-token
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (let [remove-token #(when % (cft/remove-attributes-for-token attributes token %))]
         (dwsh/update-shapes
          shape-ids
          (fn [shape]
            (update shape :applied-tokens remove-token))))))))

(defn toggle-token
  [{:keys [token shapes]}]
  (ptk/reify ::on-toggle-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [attributes all-attributes on-update-shape]}
            (get token-properties (:type token))

            unapply-tokens?
            (cft/shapes-token-applied? token shapes (or all-attributes attributes))

            shape-ids (map :id shapes)]
        (if unapply-tokens?
          (rx/of
           (unapply-token {:attributes (or all-attributes attributes)
                           :token token
                           :shape-ids shape-ids}))
          (rx/of
           (case (:type token)
             :spacing
             (apply-spacing-token {:token token
                                   :shapes shapes})
             (apply-token {:attributes attributes
                           :token token
                           :shape-ids shape-ids
                           :on-update-shape on-update-shape}))))))))

;; Map token types to different properties used along the cokde ---------------------------------------------

;; FIXME: the values should be lazy evaluated, probably a function,
;; becasue on future we will need to translate that labels and that
;; can not be done statically

(def token-properties
  "A map of default properties by token type"
  (d/ordered-map
   :border-radius
   {:title "Border Radius"
    :attributes ctt/border-radius-keys
    :on-update-shape update-shape-radius-all
    :modal {:key :tokens/border-radius
            :fields [{:label "Border Radius"
                      :key :border-radius}]}}

   :color
   {:title "Color"
    :attributes #{:fill}
    :all-attributes ctt/color-keys
    :on-update-shape update-fill-stroke
    :modal {:key :tokens/color
            :fields [{:label "Color" :key :color}]}}

   :font-size
   {:title "Font Size"
    :attributes ctt/font-size-keys
    :on-update-shape update-font-size
    :modal {:key :tokens/font-size
            :fields [{:label "Font Size"
                      :key :font-size}]}}

   :letter-spacing
   {:title "Letter Spacing"
    :attributes ctt/letter-spacing-keys
    :on-update-shape update-letter-spacing
    :modal {:key :tokens/letter-spacing
            :fields [{:label "Letter Spacing"
                      :key :letter-spacing}]}}

   :font-family
   {:title "Font Family"
    :attributes ctt/font-family-keys
    :on-update-shape update-font-family-interactive
    :modal {:key :tokens/font-family
            :fields [{:label "Font Family"
                      :key :font-family}]}}

   :text-case
   {:title "Text Case"
    :attributes ctt/text-case-keys
    :on-update-shape update-text-case
    :modal {:key :tokens/text-case
            :fields [{:label "Text Case"
                      :key :text-case}]}}

   :font-weight
   {:title "Font Weight"
    :attributes ctt/font-weight-keys
    :on-update-shape update-font-weight-interactive
    :modal {:key :tokens/font-weight
            :fields [{:label "Font Weight"
                      :key :font-weight}]}}

   :typography
   {:title "Typography"
    :attributes ctt/typography-token-keys
    :on-update-shape update-typography
    :modal {:key :tokens/typography
            :fields [{:label "Typography"
                      :key :typography}]}}

   :text-decoration
   {:title "Text Decoration"
    :attributes ctt/text-decoration-keys
    :on-update-shape update-text-decoration-interactive
    :modal {:key :tokens/text-decoration
            :fields [{:label "Text Decoration"
                      :key :text-decoration}]}}

   :stroke-width
   {:title "Stroke Width"
    :attributes ctt/stroke-width-keys
    :on-update-shape update-stroke-width
    :modal {:key :tokens/stroke-width
            :fields [{:label "Stroke Width"
                      :key :stroke-width}]}}

   :sizing
   {:title "Sizing"
    :attributes #{:width :height}
    :all-attributes ctt/sizing-keys
    :on-update-shape update-shape-dimensions
    :modal {:key :tokens/sizing
            :fields [{:label "Sizing"
                      :key :sizing}]}}
   :dimensions
   {:title "Dimensions"
    :attributes #{:width :height}
    :all-attributes (set/union
                     ctt/spacing-keys
                     ctt/sizing-keys
                     ctt/border-radius-keys
                     ctt/axis-keys
                     ctt/stroke-width-keys)
    :on-update-shape update-shape-dimensions
    :modal {:key :tokens/dimensions
            :fields [{:label "Dimensions"
                      :key :dimensions}]}}

   :opacity
   {:title "Opacity"
    :attributes ctt/opacity-keys
    :on-update-shape update-opacity
    :modal {:key :tokens/opacity
            :fields [{:label "Opacity"
                      :key :opacity}]}}

   :number
   {:title "Number"
    :attributes ctt/rotation-keys
    :all-attributes ctt/number-keys
    :on-update-shape update-rotation
    :modal {:key :tokens/number
            :fields [{:label "Number"
                      :key :number}]}}

   :rotation
   {:title "Rotation"
    :attributes ctt/rotation-keys
    :on-update-shape update-rotation
    :modal {:key :tokens/rotation
            :fields [{:label "Rotation"
                      :key :rotation}]}}
   :spacing
   {:title "Spacing"
    :attributes #{:column-gap :row-gap}
    :all-attributes ctt/spacing-keys
    :on-update-shape update-layout-spacing
    :modal {:key :tokens/spacing
            :fields [{:label "Spacing"
                      :key :spacing}]}}))

(defn get-token-properties [token]
  (get token-properties (:type token)))
