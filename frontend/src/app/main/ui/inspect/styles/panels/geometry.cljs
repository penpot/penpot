;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.geometry
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def ^:private properties
  [:width
   :height
   :left
   :top
   :border-start-start-radius
   :border-start-end-radius
   :border-end-start-radius
   :border-end-end-radius
   :transform])

(def ^:private shape-prop->border-radius-prop
  {:border-start-start-radius :r1
   :border-start-end-radius :r2
   :border-end-start-radius :r3
   :border-end-end-radius :r4})

(defn- has-border-radius?
  "Returns true if the shape has any non-zero border radius values."
  [shape]
  (let [radius-keys [:r1 :r2 :r3 :r4]]
    (some (fn [key]
            (and (contains? shape key)
                 (not= 0 (get shape key))))
          radius-keys)))

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (let [border-prop (get shape-prop->border-radius-prop property)]
    (if border-prop
      (get shape-tokens border-prop)
      (get shape-tokens property))))

(defn- get-resolved-token
  [property shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens property)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

(defn- generate-geometry-shorthand
  [shapes objects]
  (when (and (= (count shapes) 1) (has-border-radius? (first shapes)))
    (css/get-css-property objects (first shapes) :border-radius)))

(mf/defc geometry-panel*
  [{:keys [shapes objects resolved-tokens on-geometry-shorthand]}]
  (let [shorthand* (mf/use-state #(generate-geometry-shorthand shapes objects))
        shorthand (deref shorthand*)]
    (mf/use-effect
     (mf/deps shorthand on-geometry-shorthand shapes objects)
     (fn []
       (reset! shorthand* (generate-geometry-shorthand shapes objects))
       (on-geometry-shorthand {:panel :geometry
                               :property shorthand})))
    [:div {:class (stl/css :geometry-panel)}
     (for [shape shapes]
       [:div {:key (:id shape) :class (stl/css :geometry-shape)}

        (for [property properties]
          (when-let [value (css/get-css-value objects shape property)]
            (let [property-name (cmm/get-css-rule-humanized property)
                  resolved-token (get-resolved-token property shape resolved-tokens)
                  property-value (if (not resolved-token) (css/get-css-property objects shape property) "")]
              [:> properties-row* {:key (dm/str "geometry-property-" property)
                                   :term property-name
                                   :detail value
                                   :token resolved-token
                                   :property property-value
                                   :copiable true}])))])]))
