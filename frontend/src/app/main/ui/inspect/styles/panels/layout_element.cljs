;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.layout-element
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def ^:private shape-prop->margin-prop
  {:margin-block-start :m1
   :margin-inline-end :m2
   :margin-block-end :m3
   :margin-inline-start :m4
   :max-block-size :layout-item-max-h ;; :max-height
   :min-block-size :layout-item-min-h ;; :min-height
   :max-inline-size :layout-item-max-w ;; :max-width
   :min-inline-size :layout-item-min-w ;; :min-width
   })

(defn- has-margin?
  [shape]
  (let [margin (:layout-item-margin shape)
        margin-keys [:m1 :m2 :m3 :m4]]
    (some (fn [key]
            (and (contains? margin key)
                 (not= 0 (get margin key))))
          margin-keys)))

(defn- get-applied-margins-in-shape
  [shape-tokens property]
  (if-let [margin-prop (get shape-prop->margin-prop property)]
    (get shape-tokens margin-prop)
    (get shape-tokens property)))

(defn- get-resolved-tokens
  [property shape resolved-tokens]
  (when-let [shape-tokens (:applied-tokens shape)]
    (let [applied-tokens-in-shape (get-applied-margins-in-shape shape-tokens property)
          token (get resolved-tokens applied-tokens-in-shape)]
      token)))

(defn- generate-layout-element-shorthand
  [shapes objects]
  (let [shape (first shapes)
        shorthand-margin (when (and (= (count shapes) 1) (has-margin? shape))
                           (css/get-css-property objects shape :margin))
        shorthand-grow (when (and (= (count shapes) 1)
                                  (ctl/flex-layout-immediate-child? objects shape))
                         (if-let [flex-value (css/get-css-value objects shape :flex)]
                           flex-value
                           0))
        shorthand-basis 0 ;; Default-value. Currently not supported in the UI
        shorthand-shrink (when (and (= (count shapes) 1)
                                    (ctl/flex-layout-immediate-child? objects shape))
                           (if-let [flex-shrink-value (css/get-css-value objects shape :flex-shrink)]
                             flex-shrink-value
                             0))
        shorthand-flex (dm/str "flex: " shorthand-grow " " shorthand-basis " " shorthand-shrink ";")
        shorthand (dm/str  shorthand-margin " " shorthand-flex)]
    shorthand))

(mf/defc layout-element-panel*
  [{:keys [shapes objects resolved-tokens layout-element-properties on-layout-element-shorthand]}]
  (let [shapes (->> shapes (filter #(ctl/any-layout-immediate-child? objects %)))
        shorthand* (mf/use-state #(generate-layout-element-shorthand shapes objects))
        shorthand (deref shorthand*)]
    (mf/use-effect
     (mf/deps shorthand on-layout-element-shorthand shapes objects)
     (fn []
       (reset! shorthand* (generate-layout-element-shorthand shapes objects))
       (on-layout-element-shorthand {:panel :layout-element
                                     :property shorthand})))
    [:div {:class (stl/css :layout-element-panel)}
     (for [shape shapes]
       [:div {:key (:id shape) :class (stl/css :layout-element-shape)}
        (for [property layout-element-properties]
          (when-let [value (css/get-css-value objects shape property)]
            (let [property-name (cmm/get-css-rule-humanized property)
                  resolved-token (get-resolved-tokens property shape resolved-tokens)
                  property-value (if (not resolved-token) (css/get-css-property objects shape property) "")]
              [:> properties-row* {:key (dm/str "layout-element-property-" property)
                                   :term property-name
                                   :detail (str value)
                                   :token resolved-token
                                   :property property-value
                                   :copiable true}])))])]))
