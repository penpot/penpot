;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.routes
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
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
   [potok.v2.core :as ptk]
   [reitit.core :as r]))

(def ^:private sso-authorization-max-age-ms
  (* 5 60 1000))

(defonce ^:private sso-authorization-cache
  (atom {}))

(def routes
  "Enabled route names. Query-string routing: the `screen` query param
  carries the route name (`?screen=<name>&params`); it is router-owned
  and reserved, every other param travels as a plain query param."
  (into #{:auth-login
          :auth-register
          :auth-register-validate
          :auth-register-success
          :auth-recovery-request
          :auth-recovery
          :auth-verify-token
          :settings-profile
          :settings-password
          :settings-feedback
          :settings-options
          :settings-subscription
          :settings-integrations
          :settings-notifications
          :settings-shortcuts
          :frame-preview
          :viewer
          :render-sprite
          :dashboard-members
          :dashboard-invitations
          :dashboard-webhooks
          :dashboard-settings
          :dashboard-recent
          :dashboard-search
          :dashboard-fonts
          :dashboard-font-providers
          :dashboard-libraries
          :dashboard-files
          :dashboard-deleted
          :workspace}
        (concat
         (when (contains? cf/flags :admin-console)
           [:nitrate-entry])
         (when *assert*
           [:debug-icons-preview
            :debug-playground]))))

;; TODO(next-version): delete the legacy hash table, `legacy-match`
;; and the hash branch of `on-navigate` below. Legacy `#/…` URLs stop
;; resolving after one Penpot version of compatibility.
(def ^:private legacy-routes
  "Pre-query-string route table, kept only to translate legacy
  `#/…` hash URLs during the compatibility window."
  [["/auth"
    ["/login"             :auth-login]
    ["/register"          :auth-register]
    ["/register/validate" :auth-register-validate]
    ["/register/success"  :auth-register-success]
    ["/recovery/request"  :auth-recovery-request]
    ["/recovery"          :auth-recovery]
    ["/verify-token"      :auth-verify-token]]

   (when (contains? cf/flags :admin-console)
     ["/subscribe-nitrate" :nitrate-entry])

   ["/settings"
    ["/profile"       :settings-profile]
    ["/password"      :settings-password]
    ["/feedback"      :settings-feedback]
    ["/options"       :settings-options]
    ["/subscriptions" :settings-subscription]
    ["/integrations"  :settings-integrations]
    ["/notifications" :settings-notifications]
    ["/shortcuts"     :settings-shortcuts]]

   ["/frame-preview" :frame-preview]

   ["/view" :viewer]

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

   ["/workspace" :workspace]])

(defonce ^:private legacy-router
  (r/router legacy-routes))

(defn- legacy-match
  "Match a legacy hash path (`/workspace?...`, without the `#`) against
  the pre-query-string table. Returns `{:name params}` or nil."
  [hash-path]
  (let [uri (u/uri hash-path)]
    (when-let [match (r/match-by-path legacy-router (:path uri))]
      {:name   (get-in match [:data :name])
       :params (merge (:path-params match)
                      (u/query-string->map (:query uri)))})))


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
  active. If so, calls :check-nitrate-sso and either proceeds with navigation
  or redirects to the SSO provider URL. Successful checks are cached for five
  minutes per profile and team; redirect results are never cached."
  [match send-event-info? url]
  (let [route-name      (name (get-in match [:data :name]))
        relevant?       (and (contains? cf/flags :admin-console)
                             (or (str/starts-with? route-name "dashboard")
                                 (str/starts-with? route-name "workspace")))
        team-id-str     (when relevant?
                          (or (get-in match [:query-params :team-id])
                              (get-in match [:params :path :team-id])))
        team-id         (some-> team-id-str uuid/parse*)
        profile-id      (get-in @st/state [:profile :id])
        cache-key       [profile-id team-id]
        authorized-at   (get @sso-authorization-cache cache-key)
        cache-valid?    (and (some? authorized-at)
                             (< (ct/diff-ms authorized-at (ct/now))
                                sso-authorization-max-age-ms))
        navigate        #(st/emit! (rt/navigated match send-event-info?))]
    (cond
      (nil? team-id)
      (navigate)

      cache-valid?
      (navigate)

      :else
      (->> (rp/cmd! :check-nitrate-sso {:team-id team-id :url url})
           (rx/subs!
            (fn [{:keys [authorized redirect-uri]}]
              (if authorized
                (do
                  (swap! sso-authorization-cache assoc cache-key (ct/now))
                  (navigate))
                (when redirect-uri
                  (st/emit! (rt/nav-raw :uri (str redirect-uri))))))
            (fn [cause]
              (errors/on-error cause)))))))

(defn- handle-sso-error-and-navigate
  "Check if the current route has an SSO error marker. If so, assign an
  exception with type :sso-error and organization-id/name from query params,
  and deliberately do NOT proceed with normal navigation: emitting
  `rt/navigated` would clear the exception that was just assigned.
  Otherwise, delegate to `check-sso-and-navigate`."
  [match send-event-info? url]
  (let [route-name        (name (get-in match [:data :name]))
        sso-error?        (some? (get-in match [:query-params :sso-error]))
        organization-id   (some-> (get-in match [:query-params :organization-id]) uuid/parse*)
        organization-name (some-> (get-in match [:query-params :organization-name]) str/trim)
        team-id-str       (or (get-in match [:query-params :team-id])
                              (get-in match [:params :path :team-id])) ;; Fallback: team-id may be in path params for workspace routes
        team-id           (some-> team-id-str uuid/parse*)
        is-workspace?     (str/starts-with? route-name "workspace")
        is-dashboard?     (str/starts-with? route-name "dashboard")]
    (if sso-error?
      (st/emit! (rt/assign-exception {:type :sso-error
                                      :organization-id organization-id
                                      :organization-name organization-name
                                      :team-id team-id
                                      :is-workspace is-workspace?
                                      :is-dashboard is-dashboard?}))
      (check-sso-and-navigate match send-event-info? url))))

(declare on-query-navigate)

(defn on-navigate
  "Query-string routing entry point. `token` is the history token (the
  query string, `?screen=<name>&params`, or empty on bootstrap)."
  [router token send-event-info?]
  (let [location        (.-location js/document)
        location-path   (dm/str (.-origin location) (.-pathname location))
        valid-location? (= location-path (dm/str cf/public-uri))
        legacy-hash     (.-hash location)]

    (cond
      (not valid-location?)
      (st/emit! (rt/assign-exception {:type :not-found}))

      ;; TODO(next-version): delete with `legacy-routes`. Legacy `#/…`
      ;; URLs translate to the query format once (replace, no extra
      ;; history entry); the fragment never reaches the server, so this
      ;; can only run client-side. Untranslatable hashes fall through
      ;; to the normal query flow below.
      (str/starts-with? legacy-hash "#/")
      (if-let [{:keys [name params]} (legacy-match (subs legacy-hash 1))]
        (st/emit! (rt/nav name params {::rt/replace true}))
        (on-query-navigate router token send-event-info?))

      :else
      (on-query-navigate router token send-event-info?))))

(defn- on-query-navigate
  [router token send-event-info?]
  (let [token-query  (if (str/starts-with? (or token "") "?")
                       (subs token 1)
                       (or token ""))
        query-params (u/query-string->map token-query)
        empty-token? (str/blank? token-query)
        match        (rt/match router token)]
    (if (some? match)
      (handle-sso-error-and-navigate match send-event-info? (rt/get-current-href))

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

                         empty-token?
                         (let [default-team-id (:default-team-id profile)
                               last-team-id    (dtm/get-last-team-id)
                               team-id         (if (contains? teams last-team-id)
                                                 last-team-id
                                                 default-team-id)]
                           (->> (dtm/resolve-login-team-id {:team-id team-id
                                                            :default-team-id default-team-id})
                                (rx/subs!
                                 (fn [team-id]
                                   (st/emit! (rt/nav :dashboard-recent
                                                     (assoc query-params :team-id team-id))))
                                 (fn [cause]
                                   (errors/on-error cause)))))

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
