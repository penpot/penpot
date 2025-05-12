;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.notifications.shared.notification-pill
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn icons-by-level
  [level]
  (case level
    :info i/info
    :default i/msg-neutral
    :warning i/msg-neutral
    :error i/delete-text
    :success i/status-tick
    i/info))

(def ^:private schema:notification-pill
  [:map
   [:level [:enum :default :info :warning :error :success]]
   [:type  [:enum :toast :context]]
   [:appearance {:optional true} [:enum :neutral :ghost]]
   [:is-html {:optional true} :boolean]
   [:show-detail {:optional true} [:maybe :boolean]]
   [:on-toggle-detail {:optional true} [:maybe fn?]]])

(mf/defc notification-pill*
  {::mf/props :obj
   ::mf/schema schema:notification-pill}
  [{:keys [level type is-html appearance detail children show-detail on-toggle-detail]}]
  (let [class (stl/css-case :appearance-neutral (= appearance :neutral)
                            :appearance-ghost (= appearance :ghost)
                            :with-detail detail
                            :type-toast (= type :toast)
                            :type-context (= type :context)
                            :level-default  (= level :default)
                            :level-warning  (= level :warning)
                            :level-error    (= level :error)
                            :level-success  (= level :success)
                            :level-info     (= level :info))
        is-html (or is-html false)
        icon-id (icons-by-level level)]
    [:div {:class (dm/str class " " (stl/css :notification-pill))}
     [:div {:class (stl/css :error-message)}
      [:> i/icon* {:icon-id icon-id :class (stl/css :icon)}]
      ;; The content can arrive in markdown format, in these cases
      ;;  we will use the prop is-html to true to indicate it and
      ;; that the html injection is performed and the necessary css classes are applied.
      (if is-html
        [:div {:class (stl/css :context-text)
               :dangerouslySetInnerHTML #js {:__html children}}]
        children)]

     (when detail
       [:div {:class (stl/css :error-detail)}
        [:div {:class (stl/css :error-detail-title)}
         [:> icon-button*
          {:icon (if show-detail "arrow-down" "arrow")
           :aria-label (tr "workspace.notification-pill.detail")
           :icon-class (stl/css :expand-icon)
           :variant "action"
           :on-click on-toggle-detail}]
         [:div {:on-click on-toggle-detail}
          (tr "workspace.notification-pill.detail")]]
        (when show-detail
          [:div {:class (stl/css :error-detail-content)} detail])])]))
