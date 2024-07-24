;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.core
  (:require
   [app.common.data :as d :refer [ordered-map]]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.token :as ctt]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; Helpers ---------------------------------------------------------------------

(defn resolve-token-value [{:keys [value resolved-value] :as token}]
  (or
   resolved-value
   (d/parse-double value)))

(defn maybe-resolve-token-value [{:keys [value] :as token}]
  (when value (resolve-token-value token)))

(defn group-tokens-by-type
  "Groups tokens by their `:type` property."
  [tokens]
  (->> (vals tokens)
       (group-by :type)))

(defn tokens-name-map->select-options [{:keys [shape tokens attributes selected-attributes]}]
  (->> (wtt/token-names-map tokens)
       (map (fn [[_k {:keys [name] :as item}]]
              (cond-> (assoc item :label name)
                (wtt/token-applied? item shape (or selected-attributes attributes)) (assoc :selected? true))))))

;; Shape Update Functions ------------------------------------------------------

(defn update-shape-radius-single-corner [value shape-ids attributes]
  (dch/update-shapes shape-ids
                     (fn [shape]
                       (when (ctsr/has-radius? shape)
                         (cond-> shape
                           (:rx shape) (ctsr/switch-to-radius-4)
                           :always (ctsr/set-radius-4 (first attributes) value))))
                     {:reg-objects? true
                      :attrs [:rx :ry :r1 :r2 :r3 :r4]}))

(defn update-shape-radius-all [value shape-ids]
  (dch/update-shapes shape-ids
                     (fn [shape]
                       (when (ctsr/has-radius? shape)
                         (ctsr/set-radius-1 shape value)))
                     {:reg-objects? true
                      :attrs ctt/border-radius-keys}))

(defn update-shape-dimensions [value shape-ids]
  (ptk/reify ::update-shape-dimensions
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (dwt/update-dimensions shape-ids :width value)
       (dwt/update-dimensions shape-ids :height value)))))

(defn update-opacity [value shape-ids]
  (dch/update-shapes shape-ids #(assoc % :opacity value)))

(defn update-stroke-width
  [value shape-ids]
  (dch/update-shapes shape-ids (fn [shape]
                                 (when (seq (:strokes shape))
                                   (assoc-in shape [:strokes 0 :stroke-width] value)))))

(defn update-rotation [value shape-ids]
  (ptk/reify ::update-shape-dimensions
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (udw/trigger-bounding-box-cloaking shape-ids)
       (udw/increase-rotation shape-ids value)))))

(defn update-layout-spacing-column [value shape-ids]
  (ptk/reify ::update-layout-spacing-column
    ptk/WatchEvent
    (watch [_ state _]
      (rx/concat
       (for [shape-id shape-ids]
         (let [shape (dt/get-shape-from-state shape-id state)
               layout-direction (:layout-flex-dir shape)
               layout-update (if (or (= layout-direction :row-reverse) (= layout-direction :row))
                               {:layout-gap {:column-gap value}}
                               {:layout-gap {:row-gap value}})]
           (dwsl/update-layout [shape-id] layout-update)))))))

;; Events ----------------------------------------------------------------------

(defn apply-token
  "Apply `attributes` that match `token` for `shape-ids`.

  Optionally remove attributes from `attributes-to-remove`,
  this is useful for applying a single attribute from an attributes set
  while removing other applied tokens from this set."
  [{:keys [attributes attributes-to-remove token shape-ids on-update-shape] :as _props}]
  (ptk/reify ::apply-token
    ptk/WatchEvent
    (watch [_ state _]
      (->> (rx/from (sd/resolve-tokens+ (get-in state [:workspace-data :tokens])))
           (rx/mapcat
            (fn [sd-tokens]
              (let [undo-id (js/Symbol)
                    resolved-value (-> (get sd-tokens (:id token))
                                       (resolve-token-value))
                    tokenized-attributes (wtt/attributes-map attributes (:id token))]
                (rx/of
                 (dwu/start-undo-transaction undo-id)
                 (dch/update-shapes shape-ids (fn [shape]
                                                (cond-> shape
                                                  attributes-to-remove (update :applied-tokens #(apply (partial dissoc %) attributes-to-remove))
                                                  :always (update :applied-tokens merge tokenized-attributes))))
                 (when on-update-shape
                   (on-update-shape resolved-value shape-ids attributes))
                 (dwu/commit-undo-transaction undo-id)))))))))

(defn unapply-token
  "Removes `attributes` that match `token` for `shape-ids`.

  Doesn't update shape attributes."
  [{:keys [attributes token shape-ids] :as _props}]
  (ptk/reify ::unapply-token
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (let [remove-token #(when % (wtt/remove-attributes-for-token-id attributes (:id token) %))]
         (dch/update-shapes
          shape-ids
          (fn [shape]
            (update shape :applied-tokens remove-token))))))))

(defn toggle-token
  [{:keys [token-type-props token shapes] :as _props}]
  (ptk/reify ::on-toggle-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [attributes on-update-shape]} token-type-props
            unapply-tokens? (wtt/shapes-token-applied? token shapes (:attributes token-type-props))
            shape-ids (map :id shapes)]
        (if unapply-tokens?
          (rx/of
           (unapply-token {:attributes attributes
                           :token token
                           :shape-ids shape-ids}))
          (rx/of
           (apply-token {:attributes attributes
                         :token token
                         :shape-ids shape-ids
                         :on-update-shape on-update-shape})))))))

(defn on-apply-token [{:keys [token token-type-props selected-shapes] :as _props}]
  (let [{:keys [attributes on-apply on-update-shape]
         :or {on-apply dt/update-token-from-attributes}} token-type-props
        shape-ids (->> selected-shapes
                       (eduction
                        (remove #(wtt/shapes-token-applied? token % attributes))
                        (map :id)))]

    (p/let [sd-tokens (sd/resolve-workspace-tokens+ {:debug? true})]
      (let [resolved-token (get sd-tokens (:id token))
            resolved-token-value (resolve-token-value resolved-token)]
        (doseq [shape selected-shapes]
          (st/emit! (on-apply {:token-id (:id token)
                               :shape-id (:id shape)
                               :attributes attributes}))
          (on-update-shape resolved-token-value shape-ids attributes))))))

;; JSON export functions -------------------------------------------------------

(defn encode-tokens
  [data]
  (-> data
      (clj->js)
      (js/JSON.stringify nil 2)))

(defn export-tokens-file [tokens-json]
  (let [file-name "tokens.json"
        file-content (encode-tokens tokens-json)
        blob (wapi/create-blob (clj->js file-content) "application/json")]
    (dom/trigger-download file-name blob)))

(defn transform-tokens-into-json-format [tokens]
  (let [global (reduce
                (fn [acc [_ {:keys [name value type]}]]
                  (assoc acc name {:$value value
                                   :$type (str/camel type)}))
                (sorted-map) tokens)]
    {:global global}))

(defn download-tokens-as-json []
  (let [all-tokens (deref refs/workspace-tokens)
        transformed-tokens-json (transform-tokens-into-json-format all-tokens)]
    (export-tokens-file transformed-tokens-json)))

;; Token types -----------------------------------------------------------------

(def token-types
  (ordered-map
   [:border-radius
    {:title "Border Radius"
     :attributes ctt/border-radius-keys
     :on-update-shape update-shape-radius-all
     :modal {:key :tokens/border-radius
             :fields [{:label "Border Radius"
                       :key :border-radius}]}}]
   [:stroke-width
    {:title "Stroke Width"
     :attributes ctt/stroke-width-keys
     :on-update-shape update-stroke-width
     :modal {:key :tokens/stroke-width
             :fields [{:label "Stroke Width"
                       :key :stroke-width}]}}]

   [:sizing
    {:title "Sizing"
     :attributes #{:width :height}
     :on-update-shape update-shape-dimensions
     :modal {:key :tokens/sizing
             :fields [{:label "Sizing"
                       :key :sizing}]}}]
   [:dimensions
    {:title "Dimensions"
     :attributes #{:width :height}
     :on-update-shape update-shape-dimensions
     :modal {:key :tokens/dimensions
             :fields [{:label "Dimensions"
                       :key :dimensions}]}}]

   [:opacity
    {:title "Opacity"
     :attributes ctt/opacity-keys
     :on-update-shape update-opacity
     :modal {:key :tokens/opacity
             :fields [{:label "Opacity"
                       :key :opacity}]}}]

   [:rotation
    {:title "Rotation"
     :attributes ctt/rotation-keys
     :on-update-shape update-rotation
     :modal {:key :tokens/rotation
             :fields [{:label "Rotation"
                       :key :rotation}]}}]
   [:spacing
    {:title "Spacing"
     :attributes ctt/spacing-keys
     :on-update-shape update-layout-spacing-column
     :modal {:key :tokens/spacing
             :fields [{:label "Spacing"
                       :key :spacing}]}}]
   (comment
     [:boolean
      {:title "Boolean"
       :modal {:key :tokens/boolean
               :fields [{:label "Boolean"}]}}]

     [:box-shadow
      {:title "Box Shadow"
       :modal {:key :tokens/box-shadow
               :fields [{:label "Box shadows"
                         :key :box-shadow
                         :type :box-shadow}]}}]

     [:numeric
      {:title "Numeric"
       :modal {:key :tokens/numeric
               :fields [{:label "Numeric"
                         :key :numeric}]}}]

     [:other
      {:title "Other"
       :modal {:key :tokens/other
               :fields [{:label "Other"
                         :key :other}]}}]
     [:string
      {:title "String"
       :modal {:key :tokens/string
               :fields [{:label "String"
                         :key :string}]}}]
     [:typography
      {:title "Typography"
       :modal {:key :tokens/typography
               :fields [{:label "Font" :key :font-family}
                        {:label "Weight" :key :weight}
                        {:label "Font Size" :key :font-size}
                        {:label "Line Height" :key :line-height}
                        {:label "Letter Spacing" :key :letter-spacing}
                        {:label "Paragraph Spacing" :key :paragraph-spacing}
                        {:label "Paragraph Indent" :key :paragraph-indent}
                        {:label "Text Decoration" :key :text-decoration}
                        {:label "Text Case" :key :text-case}]}}])))

(defn token-attributes [token-type]
  (get-in token-types [token-type :attributes]))
