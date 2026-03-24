;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.token-typography-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc resolved-value-tooltip*
  {::mf/private true}
  [{:keys [token-name resolved-value]}]
  [:*
   [:span (dm/str (tr "workspace.tokens.token-name") ": ")]
   [:span {:class (stl/css :token-name-tooltip)} token-name]
   [:div
    [:span (tr "inspect.tabs.styles.token-resolved-value")]
    [:ul
     (for [[k v] resolved-value]
       [:li {:key (d/name k)}
        [:span {:class (stl/css :resolved-key)} (str "- " (d/name k) ": ")]
        [:span {:class (stl/css :resolved-value)}
         (if (sequential? v)
           (str/join ", " (map #(dm/str "\"" % "\"") v))
           (dm/str v))]])]]])

(mf/defc token-typography-row*
  [{:keys [token-name active-tokens detach-token] :rest props}]
  (let [ element-ref (mf/use-ref nil)
        id (mf/use-id)

        token (->> (:typography active-tokens)
                   (d/seek #(= (:name %) token-name)))

        has-errors (some? (:errors token))
        display-name (or (:name token) token-name)

        resolved-value (:resolved-value token)
        not-active (or (nil? token)
                       (empty? (:typography active-tokens)))
        on-detach
        (mf/use-fn
         (mf/deps display-name)
         (fn []
           (detach-token display-name)))

        tooltip-content (cond
                          not-active
                          (tr "not-active-token.no-name")
                          has-errors
                          (tr "options.deleted-token")
                          :else
                           (mf/html [:> resolved-value-tooltip* {:token-name token-name
                                                                 :resolved-value resolved-value}]))]

    [:div {:class (stl/css-case :token-typography-row true
                                :token-typography-row-with-errors has-errors
                                :token-typography-row-not-active not-active)}
     (when (or has-errors not-active)
       [:div {:class (stl/css :error-dot)}])
     [:> icon* {:icon-id i/text-typography
                :class (stl/css :icon)}]
     [:> tooltip* {:content tooltip-content
                   :trigger-ref element-ref
                   :class (stl/css :token-tooltip)
                   :id id}

      [:span {:aria-labelledby (dm/str id)
              :class (stl/css :token-name)
              :ref element-ref}
       display-name]]

     [:> icon-button* {:variant "action"
                       :aria-label (tr "token-actions.detach-token")
                       :tooltip-class (stl/css :detach-button)
                       :tooltip-placement "top-left"
                       :on-click on-detach
                       :icon i/detach}]]))