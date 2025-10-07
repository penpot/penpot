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
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def ^:private properties [:border-color :border-style :border-width])

(defn- stroke->color [shape]
  {:color (:stroke-color shape)
   :opacity (:stroke-opacity shape)
   :gradient (:stroke-color-gradient shape)
   :id (:stroke-color-ref-id shape)
   :file-id (:stroke-color-ref-file shape)
   :image (:stroke-image shape)})

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

;; Current token implementation on fills only supports one token per shape and has to be the first fill
;; This must be improved in the future
(defn- has-token?
  "Returns true if the resolved token matches the color and is the first fill (idx = 0)."
  [resolved-token stroke-type idx]
  (and (= (:resolved-value resolved-token) (:color stroke-type))
       (= 0 idx)))

(mf/defc stroke-panel*
  [{:keys [shapes objects resolved-tokens color-space]}]
  [:div {:class (stl/css :stroke-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class "stroke-shape"}
      (for [[idx stroke] (map-indexed vector (:strokes shape))]
        (for [property properties]
          (let [property property
                value (css/get-css-value objects stroke property)
                stroke-type (stroke->color stroke)
                property-name (cmm/get-css-rule-humanized property)
                property-value (css/get-css-property objects stroke property)
                resolved-token (get-resolved-token property shape resolved-tokens)
                has-token (has-token? resolved-token stroke-type idx)]
            (if (= property :border-color)
              [:> color-properties-row* {:key (str idx property)
                                         :term property-name
                                         :color stroke-type
                                         :token (when has-token resolved-token)
                                         :format color-space
                                         :copiable true}]
              [:> properties-row* {:key  (str idx property)
                                   :term (d/name property-name)
                                   :detail (dm/str value)
                                   :token (when has-token resolved-token)
                                   :property property-value
                                   :copiable true}]))))])])
