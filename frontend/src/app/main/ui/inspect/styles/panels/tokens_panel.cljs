(ns app.main.ui.inspect.styles.panels.tokens-panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.inspect.styles.properties-row :refer [properties-row*]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc tokens-panel*
  [{:keys [theme-paths set-names]}]
  [:div {:class (stl/css :tokens-panel)}
   (when (seq theme-paths)
     (let [theme-list (str/join ", " theme-paths)]
       [:> properties-row* {:class (stl/css :token-theme)
                            :term (tr "inspect.tabs.styles.panel.tokens.active-themes")
                            :detail theme-list}]))
   (when (seq set-names)
     (let [sets-list (str/join ", " set-names)]
       [:> properties-row* {:class (stl/css :token-theme)
                            :term (tr "inspect.tabs.styles.panel.tokens.active-sets")
                            :detail sets-list}]))])
