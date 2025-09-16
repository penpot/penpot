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
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
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

(mf/defc fill-panel*
  [{:keys [shapes _objects resolved-tokens color-space]}]
  [:div {:class (stl/css :fill-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class "fill-shape"}
      (for [fill (:fills shape [])]
        (let [property :background
              color-type (types.fills/fill->color fill) ;; can be :color, :gradient or :image
              property-name (cmm/get-css-rule-humanized property)
              resolved-token (get-resolved-token shape resolved-tokens)]
          (if (:color color-type)
            [:> color-properties-row* {:key (dm/str "fill-property-" (:id shape) (:color color-type))
                                       :term property-name
                                       :color color-type
                                       :token resolved-token
                                       :format color-space
                                       :copiable true}]
            (if (or (:gradient color-type) (:image color-type))
              [:> color-properties-row* {:term property-name
                                         :color color-type
                                         :copiable true}]
              [:span "background-image"]))))])])
