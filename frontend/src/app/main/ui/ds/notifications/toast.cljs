;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.notifications.toast
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.notifications.shared.notification-pill :refer [notification-pill*]]
   [rumext.v2 :as mf]))

(def ^:private schema:toast
  [:map
   [:class {:optional true} :string]
   [:type  {:optional true} [:maybe [:enum :toast :context]]]
   [:level {:optional true} [:maybe [:enum :default :info :warning :error :success]]]
   [:appearance {:optional true} [:enum :neutral :ghost]]
   [:is-html {:optional true} :boolean]
   [:on-close {:optional true} fn?]])

(mf/defc toast*
  {::mf/props :obj
   ::mf/schema schema:toast}
  [{:keys [class level appearance type is-html children on-close] :rest props}]
  (let [class (dm/str class " " (stl/css :toast))
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
                             :appearance appearance} children]
      ;; TODO: this should be a buttom from the DS, but this variant is not designed yet.
      ;; https://tree.taiga.io/project/penpot/task/8492
     [:> "button" {:on-click on-close
                   :aria-label "Close"
                   :class (stl/css-case :close-button true
                                        :level-default  (= level :default)
                                        :level-warning  (= level :warning)
                                        :level-error    (= level :error)
                                        :level-success  (= level :success)
                                        :level-info     (= level :info))}
      [:> i/icon* {:icon-id i/close}]]]))
