;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.fills :as types.fills]
   [app.config :as cfg]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
   [app.util.color :as uc]
   [rumext.v2 :as mf]))

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (get shape-tokens property))

(defn- get-resolved-token
  "Get the resolved token for a specific property in a shape."
  [shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens :fill)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

;; Current token implementation on fills only supports one token per shape and has to be the first fill
;; This must be improved in the future
(defn- has-token?
  "Returns true if the resolved token matches the color and is the first fill (idx = 0)."
  [resolved-token color-type idx]
  (and (= (:resolved-value resolved-token) (:color color-type))
       (= 0 idx)))

(defn- generate-fill-shorthand
  [shape color-space]
  (reduce
   (fn [acc fill]
     (let [color (types.fills/fill->color fill)
           prefix (if (:color color) "background-color: " "background-image: ")
           value (cond
                   (or (:color color) (:gradient color)) (uc/color->format->background color (keyword color-space))
                   (:image color) (str "url('" (cfg/resolve-file-media (:image color)) "')")
                   :else "")
           full-value (str prefix value ";")]
       (if (empty? acc)
         full-value
         (str acc " " full-value))))
   ""
   (:fills shape)))

(mf/defc fill-panel*
  [{:keys [shapes resolved-tokens color-space on-fill-shorthand]}]
  (let [shorthand* (mf/use-state #(generate-fill-shorthand (first shapes) color-space))
        shorthand (deref shorthand*)]
    (mf/use-effect
     (mf/deps shorthand on-fill-shorthand shapes)
     (fn []
       (reset! shorthand* (generate-fill-shorthand (first shapes) color-space))
       (on-fill-shorthand {:panel :fill
                           :property shorthand})))
    [:div {:class (stl/css :fill-panel)}
     (for [shape shapes]
       [:div {:key (:id shape) :class (stl/css :fill-shape)}
        (for [[idx fill] (map-indexed vector (:fills shape))]
          (let [property :background
                color-type (types.fills/fill->color fill) ;; can be :color, :gradient or :image
                property-name (cmm/get-css-rule-humanized property)
                resolved-token (get-resolved-token shape resolved-tokens)
                has-token (has-token? resolved-token color-type idx)]
            (if (:color color-type)
              [:> color-properties-row* {:key idx
                                         :term property-name
                                         :color color-type
                                         :token (when has-token resolved-token)
                                         :format color-space
                                         :copiable true}]
              [:> color-properties-row* {:key idx
                                         :term property-name
                                         :color color-type
                                         :copiable true}])))])]))
