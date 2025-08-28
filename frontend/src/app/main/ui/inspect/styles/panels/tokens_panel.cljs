(ns app.main.ui.inspect.styles.panels.tokens-panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc tokens-row*
  [{:keys [term detail copiable]}]
  (let [copiable? (or copiable false)
        detail? (not (or (nil? detail) (str/blank? detail)))
        detail (if detail? detail "-")
        copy-attr
        (mf/use-fn
         (fn []
           (wapi/write-to-clipboard (str term ": " detail))))]
    [:dl {:class (stl/css :attribute-row)}
     [:dt {:class (stl/css :attribute-term)} term]
     [:dd {:class (stl/css :attribute-detail)}
      (if (and copiable? detail?)
        [:button {:class (stl/css :attribute-detail-copiable)
                  :on-click copy-attr} detail]
        detail)]]))

(mf/defc tokens-panel*
  [{:keys [theme-paths set-names]}]
  [:div {:class (stl/css :tokens-panel)}
   (when (seq theme-paths)
     (for [theme theme-paths]
       [:> tokens-row* {:key theme
                        :class (stl/css :token-theme)
                        :term (tr "inspect.tabs.styles.panel.tokens.active-themes")
                        :detail theme}]))
   (when (seq set-names)
     (let [sets-list (str/join ", " set-names)]
       [:> tokens-row* {:class (stl/css :token-theme)
                        :term (tr "inspect.tabs.styles.panel.tokens.active-sets")
                        :detail sets-list}]))])
