(ns app.main.ui.inspect.styles.panels.layout-element
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.properties-row :refer [properties-row*]]
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

(mf/defc layout-element-panel*
  [{:keys [shapes objects resolved-tokens layout-element-properties]}]
  (let [shapes (->> shapes (filter #(ctl/any-layout-immediate-child? objects %)))]
    [:div {:class (stl/css :layout-element-panel)}
     (for [shape shapes]
       [:div {:key (:id shape) :class "layout-element-shape"}
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
