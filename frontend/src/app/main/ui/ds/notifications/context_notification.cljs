;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.notifications.context-notification
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.notifications.shared.notification-pill :refer [notification-pill*]]
   [rumext.v2 :as mf]))

(def ^:private schema:context-notification
  [:map
   [:class {:optional true} :string]
   [:type  {:optional true} [:maybe [:enum :toast :context]]]
   [:appearance {:optional true} [:enum :neutral :ghost]]
   [:level {:optional true} [:maybe [:enum :default :info :warning :error :success]]]
   [:is-html {:optional true} :boolean]])

(mf/defc context-notification*
  "Persistent notifications, they do not disappear.
   These are contextual messages in specific areas of the tool, usually in modals and Dashboard area, and are mainly informative."
  {::mf/props :obj
   ::mf/schema schema:context-notification}
  [{:keys [class type appearance level is-html children] :rest props}]
  (let [class (dm/str class " " (stl/css-case :contextual-notification true
                                              :contain-html is-html
                                              :level-default  (= level :default)
                                              :level-warning  (= level :warning)
                                              :level-error    (= level :error)
                                              :level-success  (= level :success)
                                              :level-info     (= level :info)))
        level (if (string? level)
                (keyword level)
                (d/nilv level :default))
        type (if (string? type)
               (keyword type)
               (d/nilv type :context))
        appearance (if (string? appearance)
                     (keyword appearance)
                     (d/nilv appearance :neutral))
        is-html (or is-html false)
        props (mf/spread-props props {:class class
                                      :role "alert"
                                      :aria-live "polite"})]
    [:> "aside" props
     [:> notification-pill* {:level level
                             :type type
                             :is-html is-html
                             :appearance appearance} children]]))

