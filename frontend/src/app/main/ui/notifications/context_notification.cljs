;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.notifications.context-notification
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(defn- level->icon-id
  [level]
  (case level
    :error   i/delete-text
    :success i/status-tick
    :info    i/help
    i/msg-neutral))

(mf/defc context-notification
  "They are persistent, informative and non-actionable.
  They are contextual messages in specific areas off the app"
  {::mf/props :obj}
  [{:keys [level content links is-html class] :as props}]
  [:aside {:class (dm/str
                   class
                   " "
                   (stl/css-case :context-notification true
                                 :contain-html is-html
                                 :warning      (= level :warning)
                                 :error        (= level :error)
                                 :success      (= level :success)
                                 :info         (= level :info)))}

   [:> icon* {:icon-id (level->icon-id level)
              :class (stl/css :icon)}]

   ;; The content can arrive in markdown format, in these cases
   ;;  we will use the prop is-html to true to indicate it and
   ;; that the html injection is performed and the necessary css classes are applied.
   (if is-html
     [:div {:class (stl/css :context-text)
            :dangerouslySetInnerHTML #js {:__html content}}]
     [:div {:class (stl/css :context-text)}
      content
      (when (some? links)
        (for [[index link] (d/enumerate links)]
          ;; TODO Review this component
          [:> lb/link-button* {:class (stl/css :link)
                               :on-click (:callback link)
                               :value (:label link)
                               :key (dm/str "link-" index)}]))])])
