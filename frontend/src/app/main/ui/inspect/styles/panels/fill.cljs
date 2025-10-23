;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
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

(mf/defc fill-panel*
  [{:keys [shapes resolved-tokens color-space on-fill-shorthand]}]
  [:div {:class (stl/css :fill-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class "fill-shape"}
      (let [shorthand
            ;; A background-image shorthand for all fills in the shape
            (when (= (count shapes) 1)
              (reduce
               (fn [acc fill]
                 (let [color-type (types.fills/fill->color fill)
                       color-value (:color color-type)
                       color-gradient (:gradient color-type)
                       gradient-data  {:type color-type
                                       :stops (:stops color-gradient)}
                       color-image (:image color-type)
                       image-url (cfg/resolve-file-media color-image)
                       value (cond
                               (:color color-type) (dm/str color-value)
                               color-gradient (uc/gradient->css gradient-data)
                               color-image (str "url(\"" image-url "\")")
                               :else "")]
                   (if (empty? acc)
                     value
                     (str acc ", " value))))
               ""
               (:fills shape)))
            shorthand (when shorthand
                        (str "background-image: " shorthand ";"))]
        (mf/use-effect
         (fn []
           (when on-fill-shorthand
             (on-fill-shorthand {:panel :fill
                                 :property shorthand}))))
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
              (if (or (:gradient color-type) (:image color-type))
                [:> color-properties-row* {:key idx
                                           :term property-name
                                           :color color-type
                                           :copiable true}]
                [:span "background-image"])))))])])
