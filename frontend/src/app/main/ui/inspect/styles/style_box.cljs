(ns app.main.ui.inspect.styles.style-box
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- attribute->title
  [type]
  (case type
    :variant    (tr "inspect.tabs.styles.panel.variant")
    :token      (tr "inspect.tabs.styles.panel.token")
    :geometry   (tr "inspect.tabs.styles.panel.geometry")
    :fill       (tr "inspect.tabs.styles.panel.fill")
    :stroke     (tr "inspect.tabs.styles.panel.stroke")
    :text       (tr "inspect.tabs.styles.panel.text")
    :blur       (tr "inspect.tabs.styles.panel.blur")
    :shadow     (tr "inspect.tabs.styles.panel.shadow")
    :layout     (tr "inspect.tabs.styles.panel.layout")
    :layout-element (tr "inspect.tabs.styles.panel.layout-element")
    :visibility (tr "inspect.tabs.styles.panel.visibility")
    :svg        (tr "inspect.tabs.styles.panel.svg")
    nil))

(mf/defc style-box*
  [{:keys [attribute shorthand children]}]
  (let [expanded* (mf/use-state true)
        expanded (deref expanded*)

        title (attribute->title attribute)

        toggle-panel
        (mf/use-fn
         (mf/deps expanded)
         (fn []
           (reset! expanded* (not expanded))))

        copy-shorthand
        (mf/use-fn
         (fn []
           (js/navigator.clipboard.writeText (str "Style: " title))))]
    [:article {:class (stl/css :style-box)}
     [:header {:class (stl/css :disclosure-header)}
      [:button {:class (stl/css :disclosure-button)
                :on-click toggle-panel
                :title (tr "inspect.tabs.styles.panel.toggle-style" title)
                :aria-label (tr "inspect.tabs.styles.panel.toggle-style" title)}
       [:> icon* {:icon-id (if expanded "arrow-down" "arrow")
                  :class (stl/css :disclosure-icon)
                  :size "s"}]]
      [:span {:class (stl/css :panel-title)} title]
      (when shorthand
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "inspect.tabs.styles.panel.copy-style-shorthand")
                          :on-click copy-shorthand
                          :icon i/clipboard}])]
     (when expanded
       [:div {:class (stl/css :style-box-content) :inert true}
        [:div {:class (stl/css :style-box-description)} children]])]))
