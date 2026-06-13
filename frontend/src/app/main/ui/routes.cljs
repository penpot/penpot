;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.routes
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.team :as dtm]
   [app.main.errors :as errors]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.storage :as storage]
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

   (when (contains? cf/flags :nitrate)
     ["/subscribe-nitrate" :nitrate-entry])

   ["/settings"
    ["/profile"       :settings-profile]
    ["/password"      :settings-password]
    ["/feedback"      :settings-feedback]
    ["/options"       :settings-options]
    ["/subscriptions" :settings-subscription]
    ["/integrations"  :settings-integrations]
    ["/notifications" :settings-notifications]]

   ["/frame-preview" :frame-preview]

   ["/view" :viewer]

   ["/view/:file-id" :viewer-legacy]

   (when *assert*
     ["/debug/icons-preview" :debug-icons-preview])

   (when *assert*
     ["/debug/playground" :debug-playground])

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
    ["/files"                :dashboard-files]
    ["/deleted" :dashboard-deleted]]

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


(defn- store-session-params
  [{:keys [template plugin]}]
  (binding [storage/*sync* true]
    (when (some? template)
      (swap! storage/session assoc
             :template template))
    (when (some? plugin)
      (swap! storage/session assoc
             :plugin-url plugin))))

(defn- check-sso-and-navigate
  "Authorization filter for dashboard and workspace routes.
  Checks if the team being navigated to has an organization with SSO
  active. If so, calls :auth-sso and either proceeds with navigation
  or redirects to the SSO provider URL."
  [match send-event-info? url]
  (let [route-name     (name (get-in match [:data :name]))
        relevant?      (and (contains? cf/flags :nitrate)
                            (or (str/starts-with? route-name "dashboard")
                                (str/starts-with? route-name "workspace")))
        team-id-str    (when relevant?
                         (or (get-in match [:query-params :team-id])
                             (get-in match [:params :path :team-id])))
        team-id        (some-> team-id-str uuid/parse*)]
    (if (some? team-id)
      (->> (rp/cmd! :auth-sso {:team-id team-id :url url})
           (rx/subs!
            (fn [{:keys [authorized redirect-uri]}]
              (if authorized
                (st/emit! (rt/navigated match send-event-info?))
                (when redirect-uri (st/emit! (rt/nav-raw :uri (str redirect-uri))))))
            (fn [cause]
              (errors/on-error cause))))
      (st/emit! (rt/navigated match send-event-info?)))))

(defn on-navigate
  [router path send-event-info?]
  (let [location        (.-location js/document)
        [base-path qs]  (str/split path "?")
        location-path   (dm/str (.-origin location) (.-pathname location))
        valid-location? (= location-path (dm/str cf/public-uri))
        match           (rt/match router path)
        empty-path?     (or (= base-path "") (= base-path "/"))
        query-params    (u/query-string->map qs)]

    (cond
      (not valid-location?)
      (st/emit! (rt/assign-exception {:type :not-found}))

      (some? match)
      (check-sso-and-navigate match send-event-info? (rt/get-current-href))

      :else
      ;; We just recheck with an additional profile request; this
      ;; avoids some race conditions that causes unexpected redirects
      ;; on invitations workflows (and probably other cases).
      (->> (rp/cmd! :get-profile)
           (rx/mapcat (fn [profile]
                        (->> (rp/cmd! :get-teams {})
                             (rx/map (fn [teams]
                                       (assoc profile ::teams (into #{} (map :id) teams)))))))
           (rx/subs! (fn [{:keys [id ::teams] :as profile}]
                       (cond
                         (= id uuid/zero)
                         (do
                           (store-session-params query-params)
                           (st/emit! (rt/nav :auth-login)))

                         empty-path?
                         (let [team-id (dtm/get-last-team-id)]
                           (if (contains? teams team-id)
                             (st/emit! (rt/nav :dashboard-recent
                                               (assoc query-params :team-id team-id)))
                             (st/emit! (rt/nav :dashboard-recent
                                               (assoc query-params :team-id (:default-team-id profile))))))

                         :else
                         (st/emit! (rt/assign-exception {:type :not-found}))))

                     (fn [cause]
                       (errors/on-error cause)))))))

(defn init-routes
  []
  (ptk/reify ::init-routes
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (rt/initialize-router routes)
              (rt/initialize-history on-navigate))
       (->> stream
            (rx/filter (ptk/type? ::rt/navigated))
            (rx/map deref)
            (rx/map #(dm/get-in % [:query-params :wasm]))
            (rx/buffer 2 1)
            (rx/filter (fn [[v1 v2]] (not= v1 v2)))
            (rx/map features/recompute-features))))))
