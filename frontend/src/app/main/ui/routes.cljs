;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.routes
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.team :as dtm]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def routes
  [["/auth"
    ["/login"             :auth-login]
    ["/register"          :auth-register]
    ["/register/validate" :auth-register-validate]
    ["/register/success"  :auth-register-success]
    ["/recovery/request"  :auth-recovery-request]
    ["/recovery"          :auth-recovery]
    ["/verify-token"      :auth-verify-token]]

   ["/settings"
    ["/profile"       :settings-profile]
    ["/password"      :settings-password]
    ["/feedback"      :settings-feedback]
    ["/options"       :settings-options]
    ["/access-tokens" :settings-access-tokens]
    ["/notifications" :settings-notifications]]

   ["/frame-preview" :frame-preview]

   ["/view" :viewer]

   ["/view/:file-id" :viewer-legacy]

   (when *assert*
     ["/debug/icons-preview" :debug-icons-preview])

   ;; Used for export
   ["/render-sprite/:file-id" :render-sprite]

   ["/dashboard"
    ["/members"              :dashboard-members]
    ["/invitations"          :dashboard-invitations]
    ["/webhooks"             :dashboard-webhooks]
    ["/settings"             :dashboard-settings]
    ["/recent"               :dashboard-recent]
    ["/search"               :dashboard-search]
    ["/fonts"                :dashboard-fonts]
    ["/fonts/providers"      :dashboard-font-providers]
    ["/libraries"            :dashboard-libraries]
    ["/files"                :dashboard-files]]

   ["/dashboard/team/:team-id"
    ["/members"              :dashboard-legacy-team-members]
    ["/invitations"          :dashboard-legacy-team-invitations]
    ["/webhooks"             :dashboard-legacy-team-webhooks]
    ["/settings"             :dashboard-legacy-team-settings]
    ["/projects"             :dashboard-legacy-projects]
    ["/search"               :dashboard-legacy-search]
    ["/fonts"                :dashboard-legacy-fonts]
    ["/fonts/providers"      :dashboard-legacy-font-providers]
    ["/libraries"            :dashboard-legacy-libraries]
    ["/projects/:project-id" :dashboard-legacy-files]]

   ["/workspace" :workspace]
   ["/workspace/:project-id/:file-id" :workspace-legacy]])

(defn on-navigate
  [router path]
  (let [location (.-location js/document)
        [base-path qs] (str/split path "?")
        location-path (dm/str (.-origin location) (.-pathname location))
        valid-location? (= location-path (dm/str cf/public-uri))
        match (rt/match router path)
        empty-path? (or (= base-path "") (= base-path "/"))]

    (cond
      (not valid-location?)
      (st/emit! (rt/assign-exception {:type :not-found}))

      (some? match)
      (st/emit! (rt/navigated match))

      :else
      ;; We just recheck with an additional profile request; this
      ;; avoids some race conditions that causes unexpected redirects
      ;; on invitations workflows (and probably other cases).
      (->> (rp/cmd! :get-profile)
           (rx/subs! (fn [{:keys [id] :as profile}]
                       (cond
                         (= id uuid/zero)
                         (st/emit! (rt/nav :auth-login))

                         empty-path?
                         (let [team-id (or (dtm/get-last-team-id)
                                           (:default-team-id profile))]
                           (st/emit! (rt/nav :dashboard-recent
                                             (-> (u/query-string->map qs)
                                                 (assoc :team-id team-id)))))

                         :else
                         (st/emit! (rt/assign-exception {:type :not-found})))))))))

(defn init-routes
  []
  (ptk/reify ::init-routes
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (rt/initialize-router routes)
             (rt/initialize-history on-navigate)))))
