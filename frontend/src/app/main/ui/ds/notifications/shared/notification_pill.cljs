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
    :warning i/msg-neutral
    :error i/delete-text
    :success i/status-tick
    i/info))

(def ^:private schema:notification-pill
  [:map
   [:level [:enum :info :warning :error :success]]
   [:type  [:enum :toast :context]]])

(mf/defc notification-pill*
  {::mf/props :obj
   ::mf/schema schema:notification-pill}
  [{:keys [level type children]}]
  (let [class (stl/css-case :notification-pill true
                            :type-toast (= type :toast)
                            :type-context (= type :context)
                            :level-warning  (= level :warning)
                            :level-error    (= level :error)
                            :level-success  (= level :success)
                            :level-info     (= level :info))
        icon-id (icons-by-level level)]
    [:div {:class class}
     [:> i/icon* {:icon-id icon-id :class (stl/css :icon)}]
     children]))
