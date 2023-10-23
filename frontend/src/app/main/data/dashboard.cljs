;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.dashboard
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :refer [handle-notification]]
   [app.main.data.events :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.media :as di]
   [app.main.data.users :as du]
   [app.main.data.websocket :as dws]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [clojure.set :as set]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare fetch-projects)
(declare fetch-team-members)

(defn initialize
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [prev-team-id (:current-team-id state)]
        (cond-> state
          (not= prev-team-id id)
          (-> (dissoc :current-team-id)
              (dissoc :dashboard-files)
              (dissoc :dashboard-projects)
              (dissoc :dashboard-shared-files)
              (dissoc :dashboard-recent-files)
              (dissoc :dashboard-team-members)
              (dissoc :dashboard-team-stats)
              (update :workspace-global dissoc :default-font)))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper-s   (rx/filter (ptk/type? ::finalize) stream)
            profile-id (:profile-id state)]

        (->> (rx/merge
              ;; fetch teams must be first in case the team doesn't exist
              (ptk/watch (du/fetch-teams) state stream)
              (ptk/watch (df/load-team-fonts id) state stream)
              (ptk/watch (fetch-projects id) state stream)
              (ptk/watch (fetch-team-members id) state stream)
              (ptk/watch (du/fetch-users {:team-id id}) state stream)

              (->> stream
                   (rx/filter (ptk/type? ::dws/message))
                   (rx/map deref)
                   (rx/filter (fn [{:keys [subs-id type] :as msg}]
                                (and (or (= subs-id uuid/zero)
                                         (= subs-id profile-id))
                                     (= :notification type))))
                   (rx/map handle-notification))

              ;; Once the teams are fecthed, initialize features related
              ;; to currently active team
              (->> stream
                   (rx/filter (ptk/type? ::du/teams-fetched))
                   (rx/observe-on :async)
                   (rx/mapcat deref)
                   (rx/filter #(= id (:id %)))
                   (rx/map du/set-current-team)))

             (rx/take-until stoper-s))))))

(defn finalize
  [params]
  (ptk/data-event ::finalize params))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching (context aware: current team)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- EVENT: fetch-team-members

(defn team-members-fetched
  [members]
  (ptk/reify ::team-members-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-team-members (d/index-by :id members)))))

(defn fetch-team-members
  [team-id]
  (ptk/reify ::fetch-team-members
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-team-members {:team-id team-id})
           (rx/map team-members-fetched)))))

;; --- EVENT: fetch-team-stats

(defn team-stats-fetched
  [stats]
  (ptk/reify ::team-stats-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-team-stats stats))))

(defn fetch-team-stats
  [team-id]
  (ptk/reify ::fetch-team-stats
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-team-stats {:team-id team-id})
           (rx/map team-stats-fetched)))))

;; --- EVENT: fetch-team-invitations

(defn team-invitations-fetched
  [invitations]
  (ptk/reify ::team-invitations-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-team-invitations invitations))))

(defn fetch-team-invitations
  []
  (ptk/reify ::fetch-team-invitations
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-team-invitations {:team-id team-id})
             (rx/map team-invitations-fetched))))))

;; --- EVENT: fetch-team-webhooks

(defn team-webhooks-fetched
  [webhooks]
  (ptk/reify ::team-webhooks-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-team-webhooks webhooks))))

(defn fetch-team-webhooks
  []
  (ptk/reify ::fetch-team-webhooks
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-webhooks {:team-id team-id})
             (rx/map team-webhooks-fetched))))))

;; --- EVENT: fetch-projects

(defn projects-fetched
  [projects]
  (ptk/reify ::projects-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [projects (d/index-by :id projects)]
        (assoc state :dashboard-projects projects)))))

(defn fetch-projects
  [team-id]
  (ptk/reify ::fetch-projects
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-projects {:team-id team-id})
           (rx/map projects-fetched)))))

;; --- EVENT: search

(defn search-result-fetched
  [result]
  (ptk/reify ::search-result-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :dashboard-search-result result))))

(def schema:search-params
  [:map {:closed true}
   [:search-term [:maybe :string]]])

(defn search
  [params]
  (dm/assert! schema:search-params params)
  (ptk/reify ::search
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :dashboard-search-result))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)]
        (->> (rp/cmd! :search-files params)
             (rx/map search-result-fetched))))))

;; --- EVENT: files

(defn files-fetched
  [project-id files]
  (letfn [(remove-project-files [files]
            (reduce-kv (fn [result id file]
                         (cond-> result
                           (= (:project-id file) project-id) (dissoc id)))
                       files
                       files))]
    (ptk/reify ::files-fetched
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (update :dashboard-files
                    (fn [state]
                      (let [state (remove-project-files state)]
                        (reduce #(assoc %1 (:id %2) %2) state files))))
            (assoc-in [:dashboard-projects project-id :count] (count files)))))))

(defn fetch-files
  [{:keys [project-id] :as params}]
  (dm/assert! (uuid? project-id))
  (ptk/reify ::fetch-files
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-project-files {:project-id project-id})
           (rx/map #(files-fetched project-id %))))))

;; --- EVENT: shared-files

(defn shared-files-fetched
  [files]
  (ptk/reify ::shared-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [files (d/index-by :id files)]
        (-> state
            (assoc :dashboard-shared-files files)
            (update :dashboard-files d/merge files))))))

(defn fetch-shared-files
  []
  (ptk/reify ::fetch-shared-files
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-team-shared-files {:team-id team-id})
             (rx/map shared-files-fetched))))))

;; --- EVENT: recent-files

(defn recent-files-fetched
  [files]
  (ptk/reify ::recent-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [files (d/index-by :id files)]
        (-> state
            (assoc :dashboard-recent-files files)
            (update :dashboard-files d/merge files))))))

(defn fetch-recent-files
  ([] (fetch-recent-files nil))
  ([team-id]
   (ptk/reify ::fetch-recent-files
     ptk/WatchEvent
     (watch [_ state _]
       (let [team-id (or team-id (:current-team-id state))]
         (->> (rp/cmd! :get-team-recent-files {:team-id team-id})
              (rx/map recent-files-fetched)))))))


;; --- EVENT: fetch-template-files

(defn builtin-templates-fetched
  [libraries]
  (ptk/reify ::libraries-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :builtin-templates libraries))))

(defn fetch-builtin-templates
  []
  (ptk/reify ::fetch-builtin-templates
    ptk/WatchEvent
    (watch [_ _ _]
        (->> (rp/cmd! :get-builtin-templates)
             (rx/map builtin-templates-fetched)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Selection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clear-selected-files
  []
  (ptk/reify ::clear-file-select
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-local
              assoc :selected-files #{}
                    :selected-project nil))))

(defn toggle-file-select
  [{:keys [id project-id] :as file}]
  (ptk/reify ::toggle-file-select
    ptk/UpdateEvent
    (update [_ state]
      (let [selected-project-id (get-in state [:dashboard-local :selected-project])]
        (if (or (nil? selected-project-id)
                (= selected-project-id project-id))
          (update state :dashboard-local
                  (fn [local]
                    (-> local
                        (update :selected-files #(if (contains? % id)
                                                   (disj % id)
                                                   (conj % id)))
                        (assoc :selected-project project-id))))
          state)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- EVENT: create-team

(defn team-created
  [team]
  (ptk/reify ::team-created
    IDeref
    (-deref [_] team)))

(defn create-team
  [{:keys [name] :as params}]
  (dm/assert! (string? name))
  (ptk/reify ::create-team
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            features (features/get-enabled-features state)]
        (->> (rp/cmd! :create-team {:name name :features features})
             (rx/tap on-success)
             (rx/map team-created)
             (rx/catch on-error))))))

;; --- EVENT: create-team-with-invitations

(defn create-team-with-invitations
  [{:keys [name emails role] :as params}]
  (ptk/reify ::create-team-with-invitations
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            features (features/get-enabled-features state)]
            params   {:name name
                      :emails #{emails}
                      :role role
                      :features features}
        (->> (rp/cmd! :create-team-with-invitations params)
             (rx/tap on-success)
             (rx/map team-created)
             (rx/catch on-error))))))

;; --- EVENT: update-team

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
             (rx/do on-success)
             (rx/map du/fetch-teams)
             (rx/catch on-error))))))

(defn update-team-member-role
  [{:keys [role member-id] :as params}]
  (dm/assert! (uuid? member-id))
  (dm/assert! (keyword? role)) ;  FIXME: validate proper role?
  (ptk/reify ::update-team-member-role
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)]
        (->> (rp/cmd! :update-team-member-role params)
             (rx/mapcat (fn [_]
                          (rx/of (fetch-team-members team-id)
                                 (du/fetch-teams)))))))))

(defn delete-team-member
  [{:keys [member-id] :as params}]
  (dm/assert! (uuid? member-id))
  (ptk/reify ::delete-team-member
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)]
        (->> (rp/cmd! :delete-team-member params)
             (rx/mapcat (fn [_]
                          (rx/of (fetch-team-members team-id)
                                 (du/fetch-teams)))))))))

(defn leave-team
  [{:keys [reassign-to] :as params}]
  (dm/assert! (or (nil? reassign-to)
                  (uuid? reassign-to)))

  (ptk/reify ::leave-team
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            team-id (:current-team-id state)
            params  (cond-> {:id team-id}
                      (uuid? reassign-to)
                      (assoc :reassign-to reassign-to))]
        (->> (rp/cmd! :leave-team params)
             (rx/tap #(tm/schedule on-success))
             (rx/catch on-error))))))

(defn invite-team-members
  [{:keys [emails role team-id resend?] :as params}]
  (dm/assert! (keyword? role))
  (dm/assert! (uuid? team-id))
  (dm/assert! (sm/set-of-emails? emails))

  (ptk/reify ::invite-team-members
    IDeref
    (-deref [_] {:role role :team-id team-id :resend? resend?})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            params (dissoc params :resend?)]
        (->> (rp/cmd! :create-team-invitations params)
             (rx/tap on-success)
             (rx/catch on-error))))))


(defn copy-invitation-link
  [{:keys [email team-id] :as params}]
  (dm/assert! (sm/email? email))
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
                       (rt/resolve router :auth-verify-token {} params)))
             (rx/map (fn [fragment]
                       (assoc cf/public-uri :fragment fragment)))
             (rx/tap (fn [uri]
                       (wapi/write-to-clipboard (str uri))))
             (rx/tap on-success)
             (rx/ignore)
             (rx/catch on-error))))))


(defn update-team-invitation-role
  [{:keys [email team-id role] :as params}]
  (dm/assert! (sm/email? email))
  (dm/assert! (uuid? team-id))
  (dm/assert! (keyword? role)) ;; FIXME validate role

  (ptk/reify ::update-team-invitation-role
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

(defn delete-team-invitation
  [{:keys [email team-id] :as params}]
  (dm/assert! (sm/email? email))
  (dm/assert! (uuid? team-id))
  (ptk/reify ::delete-team-invitation
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-team-invitation params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn delete-team-webhook
  [{:keys [id] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::delete-team-webhook
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

(defn update-team-webhook
  [{:keys [id uri mtype is-active] :as params}]
  (dm/assert! (uuid? id))
  (dm/assert! (contains? valid-mtypes mtype))
  (dm/assert! (boolean? is-active))
  (dm/assert! (u/uri? uri))
  (ptk/reify ::update-team-webhook
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (assoc params :team-id team-id)
            {:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :update-webhook params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn create-team-webhook
  [{:keys [uri mtype is-active] :as params}]
  (dm/assert! (contains? valid-mtypes mtype))
  (dm/assert! (boolean? is-active))
  (dm/assert! (u/uri? uri))

  (ptk/reify ::create-team-webhook
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            params  (-> params
                        (assoc :team-id team-id)
                        (update :uri str))
            {:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-webhook params)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: delete-team

(defn delete-team
  [{:keys [id] :as params}]
  (ptk/reify ::delete-team
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-team {:id id})
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: create-project

(defn- project-created
  [{:keys [id] :as project}]
  (ptk/reify ::project-created
    IDeref
    (-deref [_] project)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:dashboard-projects id] project)
          (assoc-in [:dashboard-local :project-for-edit] id)))))

(defn create-project
  []
  (ptk/reify ::create-project
    ptk/WatchEvent
    (watch [_ state _]
      (let [projects (get state :dashboard-projects)
            unames   (cfh/get-used-names projects)
            name     (cfh/generate-unique-name unames (str (tr "dashboard.new-project-prefix") " 1"))
            team-id  (:current-team-id state)
            params   {:name name
                      :team-id team-id}
            {:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-project params)
             (rx/tap on-success)
             (rx/map project-created)
             (rx/catch on-error))))))

;; --- EVENT: duplicate-project

(defn project-duplicated
  [{:keys [id] :as project}]
  (ptk/reify ::project-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:dashboard-projects id] project))))

(defn duplicate-project
  [{:keys [id name] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::duplicate-project
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)

            new-name (str name " " (tr "dashboard.copy-suffix"))]

        (->> (rp/cmd! :duplicate-project {:project-id id :name new-name})
             (rx/tap on-success)
             (rx/map project-duplicated)
             (rx/catch on-error))))))

(defn move-project
  [{:keys [id team-id] :as params}]
  (dm/assert! (uuid? id))
  (dm/assert! (uuid? team-id))
  (ptk/reify ::move-project
    IDeref
    (-deref [_]
      {:id id :team-id team-id})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]

        (->> (rp/cmd! :move-project {:project-id id :team-id team-id})
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn toggle-project-pin
  [{:keys [id is-pinned] :as project}]
  (ptk/reify ::toggle-project-pin
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:dashboard-projects id :is-pinned] (not is-pinned)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [project (get-in state [:dashboard-projects id])
            params  (select-keys project [:id :is-pinned :team-id])]
        (->> (rp/cmd! :update-project-pin params)
             (rx/ignore))))))

;; --- EVENT: rename-project

(defn rename-project
  [{:keys [id name] :as params}]
  (ptk/reify ::rename-project
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:dashboard-projects id :name] (constantly name))
          (update :dashboard-local dissoc :project-for-edit)))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:id id :name name}]
        (->> (rp/cmd! :rename-project params)
             (rx/ignore))))))

;; --- EVENT: delete-project

(defn delete-project
  [{:keys [id] :as params}]
  (ptk/reify ::delete-project
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-projects dissoc id))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-project {:id id})
           (rx/ignore)))))

;; --- EVENT: delete-file

(defn file-deleted
  [_team-id project-id]
  (ptk/reify ::file-deleted
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:dashboard-projects project-id :count] dec))))

(defn delete-file
  [{:keys [id project-id] :as params}]
  (ptk/reify ::delete-file
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-when :dashboard-files dissoc id)
          (d/update-when :dashboard-shared-files dissoc id)
          (d/update-when :dashboard-recent-files dissoc id)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (uuid/uuid (get-in state [:route :path-params :team-id]))]
        (->> (rp/cmd! :delete-file {:id id})
             (rx/map #(file-deleted team-id project-id)))))))

;; --- Rename File

(defn rename-file
  [{:keys [id name] :as params}]
  (ptk/reify ::rename-file
    IDeref
    (-deref [_]
      {::ev/origin "dashboard" :id id :name name})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:dashboard-files id :name] (constantly name))
          (d/update-in-when [:dashboard-shared-files id :name] (constantly name))
          (d/update-in-when [:dashboard-recent-files id :name] (constantly name))))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params (select-keys params [:id :name])]
        (->> (rp/cmd! :rename-file params)
             (rx/ignore))))))

;; --- Set File shared

(defn set-file-shared
  [{:keys [id is-shared] :as params}]
  (ptk/reify ::set-file-shared
    IDeref
    (-deref [_]
      {::ev/origin "dashboard" :id id :shared is-shared})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:dashboard-files id :is-shared] (constantly is-shared))
          (d/update-in-when [:dashboard-recent-files id :is-shared] (constantly is-shared))
          (cond->
            (not is-shared)
            (d/update-when :dashboard-shared-files dissoc id))))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:id id :is-shared is-shared}]
        (->> (rp/cmd! :set-file-shared params)
             (rx/ignore))))))

(defn set-file-thumbnail
  [file-id thumbnail-uri]
  (ptk/reify ::set-file-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:dashboard-files file-id] assoc :thumbnail-uri thumbnail-uri)
          (d/update-in-when [:dashboard-recent-files file-id] assoc :thumbnail-uri thumbnail-uri)))))

;; --- EVENT: create-file

(declare file-created)

(defn file-created
  [{:keys [id project-id] :as file}]
  (ptk/reify ::file-created
    IDeref
    (-deref [_] {:file-id id
                 :file-name (:name file)})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:dashboard-files id] file)
          (assoc-in [:dashboard-recent-files id] file)
          (update-in [:dashboard-projects project-id :count] inc)))))

(defn create-file
  [{:keys [project-id] :as params}]
  (dm/assert! (uuid? project-id))
  (ptk/reify ::create-file

    IDeref
    (-deref [_] {:project-id project-id})

    ptk/WatchEvent
    (watch [it state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)

            files    (get state :dashboard-files)
            unames   (cfh/get-used-names files)
            name     (cfh/generate-unique-name unames (str (tr "dashboard.new-file-prefix") " 1"))
            features (-> (features/get-team-enabled-features state)
                         (set/difference cfeat/frontend-only-features))
            params   (-> params
                         (assoc :name name)
                         (assoc :features features))]

        (->> (rp/cmd! :create-file params)
             (rx/tap on-success)
             (rx/map #(with-meta (file-created %) (meta it)))
             (rx/catch on-error))))))

;; --- EVENT: duplicate-file

(defn duplicate-file
  [{:keys [id name] :as params}]
  (dm/assert! (uuid? id))
  (dm/assert! (string? name))
  (ptk/reify ::duplicate-file
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)

            new-name (str name " " (tr "dashboard.copy-suffix"))]

        (->> (rp/cmd! :duplicate-file {:file-id id :name new-name})
             (rx/tap on-success)
             (rx/map file-created)
             (rx/catch on-error))))))

;; --- EVENT: move-files

(defn move-files
  [{:keys [ids project-id] :as params}]
  (dm/assert! (sm/set-of-uuid? ids))
  (dm/assert! (uuid? project-id))

  (ptk/reify ::move-files
    IDeref
    (-deref [_]
      {:num-files (count ids)
       :project-id project-id})

    ptk/UpdateEvent
    (update [_ state]
      (let [origin-project (get-in state [:dashboard-files (first ids) :project-id])
            update-project (fn [project delta op]
                             (-> project
                                 (update :count #(op % (count ids)))
                                 (assoc :modified-at (dt/plus (dt/now) {:milliseconds delta}))))]
        (-> state
            (d/update-in-when [:dashboard-projects origin-project] update-project 0 -)
            (d/update-in-when [:dashboard-projects project-id] update-project 10 +))))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :move-files {:ids ids :project-id project-id})
             (rx/tap on-success)
             (rx/catch on-error))))))


;; --- EVENT: clone-template
(defn clone-template
  [{:keys [template-id project-id] :as params}]
  (dm/assert! (uuid? project-id))
  (ptk/reify ::clone-template
    IDeref
    (-deref [_]
      {:template-id template-id
       :project-id project-id})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :clone-template {:project-id project-id :template-id template-id})
             (rx/tap on-success)
             (rx/catch on-error))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn go-to-workspace
  [{:keys [id project-id] :as file}]
  (ptk/reify ::go-to-workspace
    ptk/WatchEvent
    (watch [_ _ _]
      (let [pparams {:project-id project-id :file-id id}]
        (rx/of (rt/nav :workspace pparams))))))


(defn go-to-files
  ([project-id]
   (ptk/reify ::go-to-files-1
     ptk/WatchEvent
     (watch [_ state _]
       (let [team-id (:current-team-id state)]
         (rx/of (rt/nav :dashboard-files {:team-id team-id
                                          :project-id project-id}))))))
  ([team-id project-id]
   (ptk/reify ::go-to-files-2
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of (rt/nav :dashboard-files {:team-id team-id
                                        :project-id project-id}))))))

(defn go-to-search
  ([] (go-to-search nil))
  ([term]
   (ptk/reify ::go-to-search
     ptk/WatchEvent
     (watch [_ state _]
       (let [team-id (:current-team-id state)]
         (if (empty? term)
           (do
              (dom/focus! (dom/get-element "search-input"))
              (rx/of (rt/nav :dashboard-search
                              {:team-id team-id})))
           (rx/of (rt/nav :dashboard-search
                          {:team-id team-id}
                          {:search-term term})))))

     ptk/EffectEvent
     (effect [_ _ _]
       (dom/focus! (dom/get-element "search-input"))))))

(defn go-to-projects
  ([]
   (ptk/reify ::go-to-projects-0
     ptk/WatchEvent
     (watch [_ state _]
       (let [team-id (:current-team-id state)]
         (rx/of (rt/nav :dashboard-projects {:team-id team-id}))))))
  ([team-id]
   (ptk/reify ::go-to-projects-1
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of (rt/nav :dashboard-projects {:team-id team-id}))))))

(defn go-to-team-members
  []
  (ptk/reify ::go-to-team-members
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-team-members {:team-id team-id}))))))

(defn go-to-team-invitations
  []
  (ptk/reify ::go-to-team-invitations
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-team-invitations {:team-id team-id}))))))

(defn go-to-team-webhooks
  []
  (ptk/reify ::go-to-team-webhooks
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-team-webhooks {:team-id team-id}))))))

(defn go-to-team-settings
  []
  (ptk/reify ::go-to-team-settings
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-team-settings {:team-id team-id}))))))

(defn go-to-drafts
  []
  (ptk/reify ::go-to-drafts
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            projects (:dashboard-projects state)
            default-project (d/seek :is-default (vals projects))]
        (when default-project
          (rx/of (rt/nav :dashboard-files {:team-id team-id
                                           :project-id (:id default-project)})))))))

(defn go-to-libs
  []
  (ptk/reify ::go-to-libs
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (rx/of (rt/nav :dashboard-libraries {:team-id team-id}))))))

(defn create-element
  []
  (ptk/reify ::create-element
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id       (:current-team-id state)
            route         (:route state)
            pparams       (:path-params route)
            in-project?   (contains? pparams :project-id)
            name          (if in-project?
                            (let [files  (get state :dashboard-files)
                                  unames (cfh/get-used-names files)]
                              (cfh/generate-unique-name unames (str (tr "dashboard.new-file-prefix") " 1")))
                            (let [projects (get state :dashboard-projects)
                                  unames   (cfh/get-used-names projects)]
                              (cfh/generate-unique-name unames (str (tr "dashboard.new-project-prefix") " 1"))))
            params        (if in-project?
                            {:project-id (:project-id pparams)
                             :name name}
                            {:name name
                             :team-id team-id})
            action-name   (if in-project? :create-file :create-project)
            action        (if in-project? file-created project-created)]

        (->> (rp/cmd! action-name params)
             (rx/map action))))))

(defn open-selected-file
  []
  (ptk/reify ::open-selected-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [files (get-in state [:dashboard-local :selected-files])]
        (if (= 1 (count files))
          (let [file (get-in state [:dashboard-files (first files)])]
            (rx/of (go-to-workspace file)))
          (rx/empty))))))
