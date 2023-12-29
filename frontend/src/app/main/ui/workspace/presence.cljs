;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.presence
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cfg]
   [app.main.refs :as refs]
   [app.util.dom :as dom]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

;; --- SESSION WIDGET

(mf/defc session-widget
  [{:keys [session profile index] :as props}]
  (let [profile (assoc profile :color (:color session))]
    [:li {:class (stl/css :session-icon)
          :style {:z-index (str (or (+ 1 (* -1 index)) 0))
                  :background-color (:color session)}
          :title (:fullname profile)}
     [:img {:alt (:fullname profile)
            :style {:background-color (:color session)}
            :src (cfg/resolve-profile-photo-url profile)}]]))

(mf/defc active-sessions
  {::mf/wrap [mf/memo]}
  []
  (let [users          (mf/deref refs/users)
        presence       (mf/deref refs/workspace-presence)
        user-ids       (vals presence)
        num-users      (count user-ids)
        first-users    (take 2 user-ids)
        open*          (mf/use-state false)
        open?          (deref open*)
        open-users-widget
        (mf/use-fn
         (fn []
           (reset! open* true)
           (tm/schedule-on-idle
            #(dom/focus! (dom/get-element "users-close")))))
        close-users-widget (mf/use-fn #(reset! open* false))]

    [:*
     (when (and (> num-users 2) open?)
       [:button {:id "users-close"
                 :class (stl/css :active-users-opened)
                 :on-click close-users-widget
                 :on-blur close-users-widget}
        [:ul {:class (stl/css :active-users-list)}
         (for [session user-ids]
           [:& session-widget
            {:session session
             :index 0
             :profile (get users (:profile-id session))
             :key (:id session)}])]])

     [:button {:class (stl/css-case :active-users true)
               :on-click open-users-widget}

      [:ul {:class (stl/css :active-users-list)}
       (when (> num-users 2) [:span {:class (stl/css :users-num)} (dm/str "+" (- num-users 2))])
       (for [[index session] (d/enumerate first-users)]
         [:& session-widget
          {:session session
           :index index
           :profile (get users (:profile-id session))
           :key (:id session)}])]]]))


