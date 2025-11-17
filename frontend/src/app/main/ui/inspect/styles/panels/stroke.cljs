;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.stroke
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as ctc]
   [app.config :as cfg]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [app.util.color :as uc]
   [rumext.v2 :as mf]))

(def ^:private properties [:border-color :border-style :border-width])

(def ^:private shape-prop->stroke-prop
  {:border-style :stroke-style
   :border-width :stroke-width
   :border-color :stroke-color})

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (let [border-prop (get shape-prop->stroke-prop property)]
    (if border-prop
      (get shape-tokens border-prop)
      (get shape-tokens property))))

(defn- get-resolved-token
  "Get the resolved token for a specific property in a shape."
  [property shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens property)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

;; Current token implementation on strokes only supports one token per shape and has to be the first stroke
;; This must be improved in the future
(defn- is-first-element?
  [idx]
  (= 0 idx))

(defn- has-color-token?
  "Returns true if the resolved token matches the color and is the first stroke (idx = 0)."
  [resolved-token stroke-type idx]
  (and (= (:resolved-value resolved-token) (:color stroke-type))
       (is-first-element? idx)))

(defn- generate-stroke-shorthand
  [shapes color-space]
  (when (= (count shapes) 1)
    (let [shape (first shapes)]
      (reduce
       (fn [acc stroke]
         (let [stroke-type (ctc/stroke->color stroke)
               stroke-width (:stroke-width stroke)
               stroke-style (:stroke-style stroke)
               color-value (:color stroke-type)
               formatted-color-value (uc/color->format->background stroke-type (keyword color-space))
               color-gradient (:gradient stroke-type)
               gradient-data  {:type (get-in stroke-type [:gradient :type])
                               :stops (get-in stroke-type [:gradient :stops])}
               color-image (:image stroke-type)
               value (cond
                       color-value (dm/str "border: " stroke-width "px " (d/name stroke-style) " " formatted-color-value ";")
                       color-gradient (dm/str "border-image: " (uc/gradient->css gradient-data) " 100 / " stroke-width "px;")
                       color-image (dm/str "border-image: url(" (cfg/resolve-file-media color-image) ") 100 / " stroke-width "px;")
                       :else "")]
           (if (empty? acc)
             value
             (str acc " " value))))
       ""
       (:strokes shape)))))

(mf/defc stroke-panel*
  [{:keys [shapes objects resolved-tokens color-space on-stroke-shorthand]}]
  (let [shorthand* (mf/use-state #(generate-stroke-shorthand shapes color-space))
        shorthand (deref shorthand*)]
    (mf/use-effect
     (mf/deps shorthand on-stroke-shorthand shapes)
     (fn []
       (reset! shorthand* (generate-stroke-shorthand shapes color-space))
       (on-stroke-shorthand {:panel :stroke
                             :property shorthand})))
    [:div {:class (stl/css :stroke-panel)}
     (for [shape shapes]
       [:div {:key (:id shape) :class (stl/css :stroke-shape)}
        (for [[idx stroke] (map-indexed vector (:strokes shape))]
          (for [property properties]
            (let [value (css/get-css-value objects stroke property)
                  stroke-type (ctc/stroke->color stroke)
                  property-name (cmm/get-css-rule-humanized property)
                  property-value (css/get-css-property objects stroke property)
                  resolved-token (when (is-first-element? idx) (get-resolved-token property shape resolved-tokens))
                  has-color-token (has-color-token? resolved-token stroke-type idx)]
              (if (= property :border-color)
                [:> color-properties-row* {:key (str idx property)
                                           :term property-name
                                           :color stroke-type
                                           :token (when has-color-token resolved-token)
                                           :format color-space
                                           :copiable true}]
                [:> properties-row* {:key  (str idx property)
                                     :term (d/name property-name)
                                     :detail (dm/str value)
                                     :token resolved-token
                                     :property property-value
                                     :copiable true}]))))])]))
