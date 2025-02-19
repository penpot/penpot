;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.notifications.inline-notification
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.ds.notifications.actionable :refer [actionable*]]
   [rumext.v2 :as mf]))

(mf/defc inline-notification
  "They are persistent messages and report a special situation
   of the application and require user interaction to disappear."

  {::mf/props :obj}
  [{:keys [content accept cancel links] :as props}]

  [:> actionable* {:class (stl/css :new-inline)
                   :cancel-label (:label cancel)
                   :on-cancel (:callback cancel)
                   :accept-label (:label accept)
                   :on-accept (:callback accept)}
   content

   (when (some? links)
     [:nav {:class (stl/css :link-nav)}
      (for [[index link] (d/enumerate links)]
        [:& lb/link-button {:key (dm/str "link-" index)
                            :class (stl/css :link)
                            :on-click (:callback link)
                            :value (:label link)}])])])
