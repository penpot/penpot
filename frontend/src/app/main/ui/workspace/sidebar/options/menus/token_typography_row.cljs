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
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.shared.token-option :as to]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))


(mf/defc token-typography-row*
  [{:keys [token-name active-tokens detach-token] :rest props}]
  (let [element-ref (mf/use-ref nil)
        id (mf/use-id)

        token (->> (:typography active-tokens)
                   (d/seek #(= (:name %) token-name)))

        display-name (or (:name token) token-name)

        resolved-value (:resolved-value token)
        on-detach
        (mf/use-fn
         (mf/deps display-name)
         (fn []
           (detach-token display-name)))

        all-tokens-map (mf/deref refs/workspace-all-tokens-map)

        token-exists? (contains? all-tokens-map token-name)
        has-errors (and token-exists?
                        (some? (:errors token)))

        not-active (and
                    token-exists?
                    (or (nil? token)
                        (empty? (:typography active-tokens))))

        broken-state (or (not token-exists?)
                         has-errors
                         not-active)
        tooltip-content (cond
                          not-active
                          (tr "ds.inputs.token-field.no-active-token-option" token-name)

                          (not token-exists?)
                          (tr "options.deleted-token-with-name" token-name)

                          has-errors
                          (tr "workspace.tokens.ref-not-valid")

                          :else
                          (mf/html [:> to/resolved-value-tooltip* {:token-name token-name
                                                                   :resolved-value resolved-value}]))]

    [:div {:class (stl/css-case :token-typography-row true
                                :token-typography-row-with-errors broken-state)}
     (when broken-state
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