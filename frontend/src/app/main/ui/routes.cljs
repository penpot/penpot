;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.routes
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::section ::us/keyword)
(s/def ::index ::us/integer)
(s/def ::token (s/nilable ::us/not-empty-string))
(s/def ::share-id ::us/uuid)

(s/def ::viewer-path-params
  (s/keys :req-un [::file-id]))

(s/def ::viewer-query-params
  (s/keys :opt-un [::index ::share-id ::section ::page-id]))

(s/def ::any any?)

(def routes
  [["/auth"
    ["/login"            :auth-login]
    (when (contains? @cf/flags :registration)
      ["/register"         :auth-register])
    (when (contains? @cf/flags :registration)
      ["/register/validate" :auth-register-validate])
    (when (contains? @cf/flags :registration)
      ["/register/success" :auth-register-success])
    ["/recovery/request" :auth-recovery-request]
    ["/recovery"         :auth-recovery]
    ["/verify-token"     :auth-verify-token]]

   ["/settings"
    ["/profile"  :settings-profile]
    ["/password" :settings-password]
    ["/feedback" :settings-feedback]
    ["/options"  :settings-options]]

   ["/view/:file-id"
    {:name :viewer
     :conform
     {:path-params ::viewer-path-params
      :query-params ::viewer-query-params}}]

   (when *assert*
     ["/debug/icons-preview" :debug-icons-preview])

   ;; Used for export
   ["/render-object/:file-id/:page-id/:object-id" :render-object]
   ["/render-sprite/:file-id" :render-sprite]

   ["/dashboard/team/:team-id"
    ["/members"              :dashboard-team-members]
    ["/invitations"          :dashboard-team-invitations]
    ["/settings"             :dashboard-team-settings]
    ["/projects"             :dashboard-projects]
    ["/search"               :dashboard-search]
    ["/fonts"                :dashboard-fonts]
    ["/fonts/providers"      :dashboard-font-providers]
    ["/libraries"            :dashboard-libraries]
    ["/projects/:project-id" :dashboard-files]]

   ["/workspace/:project-id/:file-id" :workspace]])

(defn- match-path
  [router path]
  (when-let [match (rt/match router path)]
    (if-let [conform (get-in match [:data :conform])]
      (let [spath  (get conform :path-params ::any)
            squery (get conform :query-params ::any)]
        (try
          (-> (dissoc match :params)
              (assoc :path-params (us/conform spath (get match :path-params))
                     :query-params (us/conform squery (get match :query-params))))
          (catch :default _
            nil)))
      match)))

(defn on-navigate
  [router path]
  (let [match   (match-path router path)
        profile (:profile @storage)
        nopath? (or (= path "") (= path "/"))
        authed? (and (not (nil? profile))
                     (not= (:id profile) uuid/zero))]

    (cond
      (and nopath? authed? (nil? match))
      (if (not= uuid/zero profile)
        (st/emit! (rt/nav :dashboard-projects {:team-id (du/get-current-team-id profile)}))
        (st/emit! (rt/nav :auth-login)))

      (and (not authed?) (nil? match))
      (st/emit! (rt/nav :auth-login))

      (nil? match)
      (st/emit! (rt/assign-exception {:type :not-found}))

      :else
      (st/emit! (rt/navigated match)))))

(defn init-routes
  []
  (ptk/reify ::init-routes
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (rt/initialize-router routes)
             (rt/initialize-history on-navigate)))))
