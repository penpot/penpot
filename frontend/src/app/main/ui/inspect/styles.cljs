(ns app.main.ui.inspect.styles
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.tokens-lib :as ctob]
   [app.main.refs :as refs]
   [app.main.ui.inspect.styles.panels.tokens-panel :refer [tokens-panel*]]
   [app.main.ui.inspect.styles.panels.variants-panel :refer [variants-panel*]]
   [app.main.ui.inspect.styles.style-box :refer [style-box*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))


(def type->panel-group
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
  [{:keys [color-space shapes libraries objects file-id]}]
  (let [data               (dm/get-in libraries [file-id :data])
        first-shape        (first shapes)
        first-component    (ctkl/get-component data (:component-id first-shape))
        type               (get-shape-type shapes first-shape first-component)
        tokens-lib         (mf/deref refs/tokens-lib)
        active-themes      (mf/deref refs/workspace-active-theme-paths-no-hidden)
        active-sets
        (mf/with-memo [tokens-lib]
          (some-> tokens-lib (ctob/get-active-themes-set-names)))
        panels            (type->panel-group type)]
    [:ol {:class (stl/css :styles-tab) :aria-label (tr "labels.styles")}
     (when (or active-themes active-sets)
       [:li
        [:> style-box* {:panel :token}
         [:> tokens-panel* {:theme-paths active-themes :set-names active-sets}]]])
     (for [panel panels]
       [:li {:key (d/name panel)}
        [:> style-box* {:panel panel}
         (case panel
           :variant          [:> variants-panel* {:component first-component
                                                  :objects objects
                                                  :shape first-shape
                                                  :data data}]
           color-space)]])]))


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
