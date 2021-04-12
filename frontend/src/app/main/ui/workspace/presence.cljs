;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.presence
  (:require
   [app.config :as cfg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.router :as rt]
   [rumext.alpha :as mf]))

;; --- SESSION WIDGET

(mf/defc session-widget
  [{:keys [session self? profile] :as props}]
  [:li.tooltip.tooltip-bottom
   {:alt (:fullname profile)
    :on-click (when self? (st/emitf (rt/navigate :settings/profile)))}
   [:img {:style {:border-color (:color session)}
          :src (cfg/resolve-profile-photo-url profile)}]])

(mf/defc active-sessions
  {::mf/wrap [mf/memo]}
  []
  (let [profile  (mf/deref refs/profile)
        users    (mf/deref refs/users)
        presence (mf/deref refs/workspace-presence)]
    [:ul.active-users
     (for [session (vals presence)]
       [:& session-widget
        {:session session
         :profile (get users (:profile-id session))
         :self? (= (:profile-id session) (:id profile))
         :key (:id session)}])]))


