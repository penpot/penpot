(ns app.main.ui.workspace.tokens.token-pill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.utilities.token.token-status :refer [token-status-icon*]]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc token-pill
  {::mf/wrap-props false}
  [{:keys [on-click token theme-token full-applied on-context-menu half-applied]}]
  (let [{:keys [name value resolved-value errors]} token
        errors? (or (nil? theme-token) (and (seq errors) (seq (:errors theme-token))))

        color (when (seq (ctob/find-token-value-references value))
                (wtt/resolved-value-hex theme-token))

        color (or color (wtt/resolved-value-hex token))

        token-status-id (cond
                          half-applied
                          "token-status-partial"
                          full-applied
                          "token-status-full"
                          :else
                          "token-status-non-applied")]
    [:button {:class (stl/css-case :token-pill true
                                   :token-pill-applied (or half-applied full-applied)
                                   :token-pill-invalid errors?
                                   :token-pill-invalid-applied (and full-applied errors?))
              :type "button"
              :title (cond
                       errors? (sd/humanize-errors token)
                       :else (->> [(str "Token: " name)
                                   (tr "workspace.token.original-value" value)
                                   (tr "workspace.token.resolved-value" resolved-value)]
                                  (str/join "\n")))
              :on-click on-click
              :on-context-menu on-context-menu
              :disabled errors?}
     (cond
       color
       [:& color-bullet {:color color
                         :mini true}]
       errors?
       [:> icon*
        {:icon-id "broken-link"
         :class (stl/css :token-pill-icon)}]

       :else
       [:> token-status-icon*
        {:icon-id token-status-id
         :class (stl/css :token-pill-icon)}])
     name]))