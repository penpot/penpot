;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.application
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cft]
   [app.common.text :as txt]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as wdc]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

(declare token-properties)

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
        (when-let [tokens (some-> (dsh/lookup-file-data state)
                                  (get :tokens-lib)
                                  (ctob/get-tokens-in-active-sets))]
          (->> (sd/resolve-tokens tokens)
               (rx/mapcat
                (fn [resolved-tokens]
                  (let [undo-id (js/Symbol)
                        objects (dsh/lookup-page-objects state)

                        shape-ids (or (->> (select-keys objects shape-ids)
                                           (filter (fn [[_ shape]] (not= (:type shape) :group)))
                                           (keys))
                                      [])

                        resolved-value (get-in resolved-tokens [(cft/token-identifier token) :resolved-value])
                        tokenized-attributes (cft/attributes-map attributes token)]
                    (rx/of
                     (st/emit! (ptk/event ::ev/event {::ev/name "apply-tokens"}))
                     (dwu/start-undo-transaction undo-id)
                     (dwsh/update-shapes shape-ids (fn [shape]
                                                     (cond-> shape
                                                       attributes-to-remove
                                                       (update :applied-tokens #(apply (partial dissoc %) attributes-to-remove))
                                                       :always
                                                       (update :applied-tokens merge tokenized-attributes))))
                     (when on-update-shape
                       (on-update-shape resolved-value shape-ids attributes))
                     (dwu/commit-undo-transaction undo-id)))))))))))

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
           (apply-token {:attributes attributes
                         :token token
                         :shape-ids shape-ids
                         :on-update-shape on-update-shape})))))))

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
                           (when (seq (:strokes shape))
                             (assoc-in shape [:strokes 0 :stroke-width] value)))
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
          (when (:width attributes) (dwt/update-dimensions shape-ids :width value {:ignore-touched true :page-id page-id}))
          (when (:height attributes) (dwt/update-dimensions shape-ids :height value {:ignore-touched true :page-id page-id}))))))))

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
                (rx/map #(dwt/update-position % (zipmap attributes (repeat value))
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

(defn update-line-height
  ([value shape-ids attributes] (update-line-height value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is
                                         ; needed to have the same
                                         ; arity that other update
                                         ; functions
   (let [update-node? (fn [node]
                        (or (txt/is-text-node? node)
                            (txt/is-paragraph-node? node)))]
     (when (number? value)
       (dwsh/update-shapes shape-ids
                           #(txt/update-text-content % update-node? d/txt-merge {:line-height value})
                           {:ignore-touched true
                            :page-id page-id})))))

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

(defn token-attributes [token-type]
  (dm/get-in token-properties [token-type :attributes]))
