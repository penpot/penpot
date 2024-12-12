;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.team
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.types.team :as ctt]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.media :as di]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.storage :as storage]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(log/set-level! :warn)

(defn get-last-team-id
  "Get last accessed team id"
  []
  (::current-team-id storage/global))

(defn teams-fetched
  [teams]
  (ptk/reify ::teams-fetched
    IDeref
    (-deref [_] teams)

    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state {:keys [id] :as team}]
                (update-in state [:teams id] merge team))
              state
              teams))))

(defn fetch-teams
  []
  (ptk/reify ::fetch-teams
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-teams)
           (rx/map teams-fetched)))))

;; --- EVENT: fetch-members

(defn- members-fetched
  [team-id members]
  (ptk/reify ::members-fetched
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:teams team-id] assoc :members members)
          (update :profiles merge (d/index-by :id members))))))

(defn fetch-members
  []
  (ptk/reify ::fetch-members
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-team-members {:team-id team-id})
             (rx/map (partial members-fetched team-id)))))))

(defn- invitations-fetched
  [team-id invitations]
  (ptk/reify ::invitations-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:teams team-id] assoc :invitations invitations))))

(defn fetch-invitations
  []
  (ptk/reify ::fetch-invitations
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-team-invitations {:team-id team-id})
             (rx/map (partial invitations-fetched team-id)))))))

(defn set-current-team
  [{:keys [id permissions features] :as team}]
  (ptk/reify ::set-current-team
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          ;; FIXME: redundant operation, only necessary on workspace
          ;; until workspace initialization is refactored
          (update-in [:teams id] merge team)
          (assoc :permissions permissions)
          ;; FIXME: this is a redundant operation that only needed by
          ;; workspace; ti will not be needed after workspace
          ;; bootstrap & urls refactor
          (assoc :current-team-id id)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (features/initialize (or features #{}))))

    ptk/EffectEvent
    (effect [_ _ _]
      (swap! storage/global assoc ::current-team-id id))))

(defn- team-initialized
  []
  (ptk/reify ::team-initialized
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            teams   (get state :teams)
            team    (get teams team-id)]
        (rx/of (set-current-team team)
               (fetch-members))))))

(defn initialize-team
  [team-id]
  (ptk/reify ::initialize-team
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-team-id team-id))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::finalize-team) stream)]
        (->> (rx/merge
              (rx/of (fetch-teams))
              (->> stream
                   (rx/filter (ptk/type? ::teams-fetched))
                   (rx/observe-on :async)
                   (rx/map team-initialized)))
             (rx/take-until stopper))))))

(defn finalize-team
  [team-id]
  (ptk/reify ::finalize-team
    ptk/UpdateEvent
    (update [_ state]
      (let [team-id' (get state :current-team-id)]
        (if (= team-id' team-id)
          (-> state
              (dissoc :current-team-id)
              (dissoc :shared-files)
              (dissoc :fonts))
          state)))))

;; --- ROLES

(defn update-member-role
  [{:keys [role member-id] :as params}]
  (dm/assert! (uuid? member-id))
  (dm/assert! (contains? ctt/valid-roles role))

  (ptk/reify ::update-member-role
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)]
        (->> (rp/cmd! :update-team-member-role params)
             (rx/mapcat (fn [_]
                          (rx/of (fetch-members)
                                 (fetch-teams)
                                 (ptk/data-event ::ev/event
                                                 {::ev/name "update-team-member-role"
                                                  :team-id team-id
                                                  :role role
                                                  :member-id member-id})))))))))

(defn delete-member
  [{:keys [member-id] :as params}]
  (dm/assert! (uuid? member-id))
  (ptk/reify ::delete-member
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)]
        (->> (rp/cmd! :delete-team-member params)
             (rx/mapcat (fn [_]
                          (rx/of (fetch-members)
                                 (fetch-teams)
                                 (ptk/data-event ::ev/event
                                                 {::ev/name "delete-team-member"
                                                  :team-id team-id
                                                  :member-id member-id})))))))))


(defn- stats-fetched
  [team-id stats]
  (ptk/reify ::stats-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:teams team-id] assoc :stats stats))))

(defn fetch-stats
  []
  (ptk/reify ::fetch-stats
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-team-stats {:team-id team-id})
             (rx/map (partial stats-fetched team-id)))))))

(defn- webhooks-fetched
  [team-id webhooks]
  (ptk/reify ::webhooks-fetched
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:team-id team-id] assoc :webhooks webhooks))))

(defn fetch-webhooks
  []
  (ptk/reify ::fetch-webhooks
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-webhooks {:team-id team-id})
             (rx/map (partial webhooks-fetched team-id)))))))

(defn- shared-files-fetched
  [files]
  (ptk/reify ::shared-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [files (d/index-by :id files)]
        (assoc state :shared-files files)))))

(defn fetch-shared-files
  "Event mainly used for fetch a list of shared libraries for a team,
  this list does not includes the content of the library per se.  It
  is used mainly for show available libraries and a summary of it."
  []
  (ptk/reify ::fetch-shared-files
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-team-shared-files {:team-id team-id})
             (rx/map shared-files-fetched))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-team-photo
  [file]
  (dm/assert!
   "expected a valid blob for `file` param"
   (di/blob? file))
  (ptk/reify ::update-team-photo
    ptk/WatchEvent
    (watch [_ state _]
      (let [on-success di/notify-finished-loading
            on-error   #(do (di/notify-finished-loading)
                            (di/process-error %))
            team-id    (:current-team-id state)
            prepare    #(hash-map :file % :team-id team-id)]

        (di/notify-start-loading)
        (->> (rx/of file)
             (rx/map di/validate-file)
             (rx/map prepare)
             (rx/mapcat #(rp/cmd! :update-team-photo %))
             (rx/tap on-success)
             (rx/mapcat (fn [_]
                          (rx/of (fetch-teams)
                                 (ptk/data-event ::ev/event
                                                 {::ev/name "update-team-photo"
                                                  :team-id team-id}))))
             (rx/catch on-error))))))


;; --- EVENT: create-team

(defn- team-created
  [team]
  (ptk/reify ::team-created
    IDeref
    (-deref [_] team)))

(defn create-team
  [{:keys [name] :as params}]
  (dm/assert! (string? name))
  (ptk/reify ::create-team
    ptk/WatchEvent
    (watch [it state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            features (features/get-enabled-features state)
            params {:name name :features features}]
        (->> (rp/cmd! :create-team (with-meta params (meta it)))
             (rx/tap on-success)
             (rx/map team-created)
             (rx/catch on-error))))))

;; --- EVENT: create-team-with-invitations

(defn create-team-with-invitations
  [{:keys [name emails role] :as params}]
  (ptk/reify ::create-team-with-invitations
    ptk/WatchEvent
    (watch [it state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            features (features/get-enabled-features state)
            params   {:name name
                      :emails emails
                      :role role
                      :features features}]
        (->> (rp/cmd! :create-team-with-invitations (with-meta params (meta it)))
             (rx/tap on-success)
             (rx/map team-created)
             (rx/catch on-error))))))

(defn update-team
  [{:keys [id name] :as params}]
  (ptk/reify ::update-team
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:teams id :name] name))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-team params)
           (rx/ignore)))))

(defn- team-leaved
  [{:keys [id] :as params}]
  (ptk/reify ::team-leaved
    IDeref
    (-deref [_] params)

    ptk/UpdateEvent
    (update [_ state]
      (update state :teams dissoc id))

    ptk/EffectEvent
    (effect [_ state _]
      (let [teams (get state :teams)]
        (when-let [ctid (::current-team-id storage/user)]
          (when-not (contains? teams ctid)
            (swap! storage/user dissoc ::current-team-id)))))))

(defn leave-current-team
  "High-level event for leave team, mainly executed from the
  dashboard. It automatically redirects user to the default team, once
  the team-leave operation succeed"
  [{:keys [reassign-to] :as params}]

  (when reassign-to
    (assert (uuid? reassign-to) "expect a valid uuid for `reassign-to`"))

  (ptk/reify ::leave-current-team
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (get state :current-team-id)
            params  (assoc params :id team-id)

            {:keys [on-error on-success]
             :or {on-success rx/empty
                  on-error rx/throw}}
            (meta params)]

        (->> (rp/cmd! :leave-team params)
             (rx/mapcat
              (fn [_]
                (rx/merge
                 (rx/of (team-leaved params)
                        (fetch-teams)
                        (ptk/data-event ::ev/event
                                        {::ev/name "leave-team"
                                         :reassign-to reassign-to
                                         :team-id team-id}))
                 (on-success))))
             (rx/catch on-error))))))

(defn create-invitations
  [{:keys [emails role team-id resend?] :as params}]
  (dm/assert! (keyword? role))
  (dm/assert! (uuid? team-id))

  (dm/assert!
   "expected a valid set of emails"
   (sm/check-set-of-emails! emails))

  (ptk/reify ::create-invitations
    ev/Event
    (-data [_]
      {:role role
       :team-id team-id
       :resend resend?})

    ptk/WatchEvent
    (watch [it _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            params (dissoc params :resend?)]
        (->> (rp/cmd! :create-team-invitations (with-meta params (meta it)))
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn copy-invitation-link
  [{:keys [email team-id] :as params}]
  (dm/assert!
   "expected a valid email"
   (sm/check-email! email))

  (dm/assert! (uuid? team-id))

  (ptk/reify ::copy-invitation-link
    IDeref
    (-deref [_] {:email email :team-id team-id})

    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            router (:router state)]

        (->> (rp/cmd! :get-team-invitation-token params)
             (rx/map (fn [params]
                       (rt/resolve router :auth-verify-token params)))
             (rx/map (fn [fragment]
                       (assoc cf/public-uri :fragment fragment)))
             (rx/tap (fn [uri]
                       (wapi/write-to-clipboard (str uri))))
             (rx/tap on-success)
             (rx/ignore)
             (rx/catch on-error))))))

(defn update-invitation-role
  [{:keys [email team-id role] :as params}]
  (dm/assert!
   "expected a valid email"
   (sm/check-email! email))

  (dm/assert! (uuid? team-id))
  (dm/assert! (contains? ctt/valid-roles role))

  (ptk/reify ::update-invitation-role
    IDeref
    (-deref [_] {:role role})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :update-team-invitation-role params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn delete-invitation
  [{:keys [email team-id] :as params}]
  (dm/assert! (sm/check-email! email))
  (dm/assert! (uuid? team-id))
  (ptk/reify ::delete-invitation
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-team-invitation params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn delete-team
  [{:keys [id] :as params}]
  (ptk/reify ::delete-team
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}}
            (meta params)]

        (->> (rp/cmd! :delete-team {:id id})
             (rx/mapcat on-success)
             (rx/catch on-error))))))

(defn delete-webhook
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))

  (ptk/reify ::delete-webhook
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)
            {:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-webhook params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(def valid-mtypes
  #{"application/json"
    "application/x-www-form-urlencoded"
    "application/transit+json"})

(defn update-webhook
  [{:keys [id uri mtype is-active] :as params}]
  (dm/assert! (uuid? id))
  (dm/assert! (contains? valid-mtypes mtype))
  (dm/assert! (boolean? is-active))
  (dm/assert! (u/uri? uri))

  (ptk/reify ::update-webhook
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)
            {:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :update-webhook params)
             (rx/mapcat (fn [_]
                          (rx/concat
                           (on-success)
                           (rx/of (fetch-webhooks)))))
             (rx/catch on-error))))))

(defn create-webhook
  [{:keys [uri mtype is-active] :as params}]
  (dm/assert! (contains? valid-mtypes mtype))
  (dm/assert! (boolean? is-active))
  (dm/assert! (u/uri? uri))

  (ptk/reify ::create-webhook
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (-> params
                        (assoc :team-id team-id)
                        (update :uri str))
            {:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-webhook params)
             (rx/mapcat (fn [_]
                          (rx/concat
                           (on-success)
                           (rx/of (fetch-webhooks)))))
             (rx/catch on-error))))))




