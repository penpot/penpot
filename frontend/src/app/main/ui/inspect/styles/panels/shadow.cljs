;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [app.util.code-gen.style-css-formats :as scf]
   [rumext.v2 :as mf]))

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (get shape-tokens property))

(defn- get-resolved-token
  [property shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens property)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

(defn- generate-shadow-shorthand
  [shapes]
  (when (= (count shapes) 1)
    (let [shorthand-property (dm/str "box-shadow: ")
          shorthand-value (reduce
                           (fn [acc shadow]
                             (let [value (scf/format-shadow->css shadow {})]
                               (if (empty? acc)
                                 value
                                 (dm/str acc ", " value))))
                           ""
                           (:shadow (first shapes)))]
      (dm/str shorthand-property shorthand-value ";"))))

(mf/defc shadow-panel*
  [{:keys [shapes resolved-tokens color-space on-shadow-shorthand]}]
  (let [shorthand* (mf/use-state #(generate-shadow-shorthand shapes))
        shorthand (deref shorthand*)]
    (mf/use-effect
     (mf/deps shorthand on-shadow-shorthand shapes)
     (fn []
       (reset! shorthand* (generate-shadow-shorthand shapes))
       (on-shadow-shorthand {:panel :shadow
                             :property shorthand})))
    [:div {:class (stl/css :shadow-panel)}
     (for [shape shapes]
       (let [composite-shadow-token (get-resolved-token :shadow shape resolved-tokens)]
         (for [[idx shadow] (map-indexed vector (:shadow shape))]
           [:div {:key (dm/str idx) :class (stl/css :shadow-shape)}
            (when composite-shadow-token
              [:> properties-row* {:term "Shadow"
                                   :detail (:name composite-shadow-token)
                                   :token composite-shadow-token
                                   :property (:name composite-shadow-token)
                                   :copiable true}])
            [:> color-properties-row* {:term "Shadow Color"
                                       :color (:color shadow)
                                       :format color-space
                                       :copiable true}]

            (let [value (dm/str (:offset-x shadow) "px" " " (:offset-y shadow) "px" " " (:blur shadow) "px" " " (:spread shadow) "px")
                  property-name (cmm/get-css-rule-humanized (:style shadow))
                  property-value (css/shadow->css shadow)]

              [:> properties-row* {:term property-name
                                   :detail (dm/str value)
                                   :property property-value
                                   :copiable true}])])))]))
