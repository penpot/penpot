(ns app.main.ui.inspect.styles
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.main.ui.inspect.styles.style-box :refer [style-box*]]
   [rumext.v2 :as mf]))


(def type->options
  {:multiple [:fill :stroke :text :shadow :blur :layout-element]
   :frame    [:visibility :geometry :fill :stroke :shadow :blur :layout :layout-element]
   :group    [:visibility :geometry :svg :layout-element]
   :rect     [:visibility :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :circle   [:visibility :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :path     [:visibility :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :text     [:visibility :geometry :text :shadow :blur :stroke :layout-element]
   :variant  [:variant :geometry :fill :stroke :shadow :blur :layout :layout-element]})

(defn- get-shape-type
  [shapes first-shape first-component]
  (cond
    (and (= (count shapes) 1)
         (or (ctc/is-variant-container? first-shape)
             (ctc/is-variant? first-component)))
    :variant

    (= (count shapes) 1)
    (:type first-shape)

    :else
    :multiple))

(mf/defc styles-tab*
  [{:keys [color-space shapes libraries file-id]}]
  (let [data               (dm/get-in libraries [file-id :data])
        first-shape        (first shapes)
        first-component    (ctkl/get-component data (:component-id first-shape))
        type               (get-shape-type shapes first-shape first-component)
        options            (type->options type)]

    [:div {:class (stl/css :element-options)}
     (for [[idx option] (map-indexed vector options)]
       [:> style-box* {:key idx :attribute option} color-space])]))


;; WIP
;; Panel list as stylebox children
#_(case option
    :geometry         [:> geometry-panel {}]
    :layout           [:> layout-panel {}]
    :layout-element   [:> layout-element-panel {}]
    :fill             [:> fill-panel {:color-space color-space}]
    :stroke           [:> stroke-panel {:color-space color-space}]
    :text             [:> text-panel {:color-space color-space}]
    :shadow           [:> shadow-panel {}]
    :blur             [:> blur-panel {}]
    :svg              [:> svg-panel {}]
    :variant          [:> variant-panel* {}]
    :visibility       [:> visibility-panel* {}])
