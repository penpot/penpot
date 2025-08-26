(ns app.main.ui.inspect.styles
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.main.ui.inspect.styles.style-box :refer [style-box*]]
   [app.util.i18n :refer [tr]]
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
  (if (= (count shapes) 1)
    (if (or (ctc/is-variant-container? first-shape)
            (ctc/is-variant? first-component))
      :variant

      (:type first-shape))
    :multiple))

(mf/defc styles-tab*
  [{:keys [color-space shapes libraries file-id]}]
  (let [data               (dm/get-in libraries [file-id :data])
        first-shape        (first shapes)
        first-component    (mf/with-memo (ctkl/get-component data (:component-id first-shape)))
        type               (mf/with-memo (get-shape-type shapes first-shape first-component))
        has-tokens?        (:applied-tokens first-shape)
        options            (type->options type)]
    [:ol {:class (stl/css :styles-tab) :aria-label (tr "inspect.tabs.styles")}
     (when has-tokens?
       [:li {:key "token"}
        [:> style-box* {:attribute :token}
         [:p "Tokens Panel (WIP)"]]])
     (for [option options]
       [:li {:key (d/name option)}
        [:> style-box* {:attribute option} color-space]])]))


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
