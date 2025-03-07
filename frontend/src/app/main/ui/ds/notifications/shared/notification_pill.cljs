;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.notifications.shared.notification-pill
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i]
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
   [:is-html {:optional true} :boolean]])

(mf/defc notification-pill*
  {::mf/props :obj
   ::mf/schema schema:notification-pill}
  [{:keys [level type is-html appearance children]}]
  (let [class (stl/css-case :notification-pill true
                            :appearance-neutral (= appearance :neutral)
                            :appearance-ghost (= appearance :ghost)
                            :type-toast (= type :toast)
                            :type-context (= type :context)
                            :level-default  (= level :default)
                            :level-warning  (= level :warning)
                            :level-error    (= level :error)
                            :level-success  (= level :success)
                            :level-info     (= level :info))
        is-html (or is-html false)
        icon-id (icons-by-level level)]
    [:div {:class class}
     [:> i/icon* {:icon-id icon-id :class (stl/css :icon)}]
      ;; The content can arrive in markdown format, in these cases
   ;;  we will use the prop is-html to true to indicate it and
   ;; that the html injection is performed and the necessary css classes are applied.
     (if is-html
       [:div {:class (stl/css :context-text)
              :dangerouslySetInnerHTML #js {:__html children}}]
       children)]))
