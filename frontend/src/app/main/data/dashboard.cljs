;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.dashboard
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.data.events :as ev]
   [app.main.data.users :as du]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [app.util.avatars :as avatars]
   [app.main.data.media :as di]
   [app.main.data.messages :as dm]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::name string?)
(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::is-pinned ::us/boolean)

(s/def ::team
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at]))

(s/def ::project
  (s/keys ::req-un [::id
                    ::name
                    ::team-id
                    ::profile-id
                    ::created-at
                    ::modified-at
                    ::is-pinned]))

(s/def ::file
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::project-id]))

(s/def ::set-of-uuid
  (s/every ::us/uuid :kind set?))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-team
  [{:keys [id] :as params}]
  (letfn [(fetched [team state]
            (update state :teams assoc id team))]
    (ptk/reify ::fetch-team
      ptk/WatchEvent
      (watch [_ state stream]
        (let [profile (:profile state)]
          (->> (rp/query :team params)
               (rx/map #(partial fetched %))))))))

(defn fetch-team-members
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (letfn [(fetched [members state]
            (->> members
                 (d/index-by :id)
                 (assoc-in state [:team-members id])))]
    (ptk/reify ::fetch-team-members
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :team-members {:team-id id})
             (rx/map #(partial fetched %)))))))

(defn fetch-team-stats
  [{:keys [id] :as team}]
  (us/assert ::us/uuid id)
  (ptk/reify ::fetch-team-members
    ptk/WatchEvent
    (watch [_ state stream]
      (let [fetched #(assoc-in %2 [:team-stats id] %1)]
        (->> (rp/query :team-stats {:team-id id})
             (rx/map #(partial fetched %)))))))

(defn fetch-projects
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (letfn [(fetched [projects state]
            (assoc-in state [:projects team-id] (d/index-by :id projects)))]
    (ptk/reify ::fetch-projects
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :projects {:team-id team-id})
             (rx/map #(partial fetched %)))))))

(defn fetch-bundle
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (let [profile (:profile state)]
        (rx/merge (ptk/watch (fetch-team params) state stream)
                  (ptk/watch (fetch-projects {:team-id id}) state stream)
                  (ptk/watch (du/fetch-users {:team-id id}) state stream))))))

(s/def :internal.event.search-files/team-id ::us/uuid)
(s/def :internal.event.search-files/search-term (s/nilable ::us/string))

(s/def :internal.event/search-files
  (s/keys :req-un [:internal.event.search-files/search-term
                   :internal.event.search-files/team-id]))

(defn search-files
  [params]
  (us/assert :internal.event/search-files params)
  (letfn [(fetched [result state]
            (update state :dashboard-local
                    assoc :search-result result))]
    (ptk/reify ::search-files
      ptk/UpdateEvent
      (update [_ state]
        (update state :dashboard-local
                assoc :search-result nil))

      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :search-files params)
             (rx/map #(partial fetched %)))))))

(defn fetch-files
  [{:keys [project-id] :as params}]
  (us/assert ::us/uuid project-id)
  (letfn [(fetched [files state]
            (update state :files assoc project-id (d/index-by :id files)))]
    (ptk/reify ::fetch-files
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :files params)
             (rx/map #(partial fetched %)))))))

(defn fetch-shared-files
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (letfn [(fetched [files state]
            (update state :shared-files assoc team-id (d/index-by :id files)))]
    (ptk/reify ::fetch-shared-files
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :shared-files {:team-id team-id})
             (rx/map #(partial fetched %)))))))

(declare recent-files-fetched)

(defn fetch-recent-files
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (ptk/reify ::fetch-recent-files
    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:team-id team-id}]
        (->> (rp/query :recent-files params)
             (rx/map #(recent-files-fetched team-id %)))))))

(defn recent-files-fetched
  [team-id files]
  (ptk/reify ::recent-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [projects (keys (get-in state [:projects team-id]))]
        (reduce (fn [state project-id]
                  (let [files (filter #(= project-id (:project-id %)) files)]
                    (-> state
                        (update-in [:files project-id] merge (d/index-by :id files))
                        (assoc-in [:recent-files project-id] (into #{} (map :id) files)))))
                state
                projects)))))


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
  [{:keys [file] :as params}]
  (ptk/reify ::toggle-file-select
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id          (:id file)
            selected-project (get-in state [:dashboard-local
                                            :selected-project])]
        (if (or (nil? selected-project)
                (= selected-project (:project-id file)))
          (update state :dashboard-local
                  (fn [local]
                    (-> local
                        (update :selected-files
                                #(if (contains? % file-id)
                                   (disj % file-id)
                                   (conj % file-id)))
                        (assoc :selected-project
                               (:project-id file)))))
          state)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Create Project

(defn create-team
  [{:keys [name] :as params}]
  (us/assert string? name)
  (ptk/reify ::create-team
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/mutation! :create-team {:name name})
             (rx/tap on-success)
             (rx/catch on-error)
             (rx/map (fn [team]
                       (ptk/event ::ev/event {::ev/name "create-team" :id (:id team)}))))))))

(defn update-team
  [{:keys [id name] :as params}]
  (us/assert ::team params)
  (ptk/reify ::update-team
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:teams id :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :update-team params)
           (rx/ignore)))))

(defn update-team-photo
  [{:keys [file team-id] :as params}]
  (us/assert ::di/file file)
  (us/assert ::us/uuid team-id)
  (ptk/reify ::update-team-photo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [on-success di/notify-finished-loading

            on-error  #(do (di/notify-finished-loading)
                           (di/process-error %))

            prepare   #(hash-map :file % :team-id team-id)]

        (di/notify-start-loading)

        (->> (rx/of file)
             (rx/map di/validate-file)
             (rx/map prepare)
             (rx/mapcat #(rp/mutation :update-team-photo %))
             (rx/do on-success)
             (rx/map #(fetch-team %))
             (rx/catch on-error))))))

(defn update-team-member-role
  [{:keys [team-id role member-id] :as params}]
  (us/assert ::us/uuid team-id)
  (us/assert ::us/uuid member-id)
  (us/assert ::us/keyword role)
  (ptk/reify ::update-team-member-role
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :update-team-member-role params)
           (rx/mapcat #(rx/of (fetch-team-members {:id team-id})
                              (fetch-team {:id team-id})))))))

(defn delete-team-member
  [{:keys [team-id member-id] :as params}]
  (us/assert ::us/uuid team-id)
  (us/assert ::us/uuid member-id)
  (ptk/reify ::delete-team-member
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :delete-team-member params)
           (rx/mapcat #(rx/of (fetch-team-members {:id team-id})
                              (fetch-team {:id team-id})))))))

(defn leave-team
  [{:keys [id reassign-to] :as params}]
  (us/assert ::team params)
  (us/assert (s/nilable ::us/uuid) reassign-to)
  (ptk/reify ::leave-team
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (rx/concat
         (when (uuid? reassign-to)
           (->> (rp/mutation! :update-team-member-role {:team-id id
                                                        :role :owner
                                                        :member-id reassign-to})
                (rx/ignore)))
         (->> (rp/mutation! :leave-team {:id id})
              (rx/tap on-success)
              (rx/catch on-error)))))))

(defn invite-team-member
  [{:keys [team-id email role] :as params}]
  (us/assert ::us/uuid team-id)
  (us/assert ::us/email email)
  (us/assert ::us/keyword role)
  (ptk/reify ::invite-team-member
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/mutation! :invite-team-member params)
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn delete-team
  [{:keys [id] :as params}]
  (us/assert ::team params)
  (ptk/reify ::delete-team
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/mutation! :delete-team {:id id})
             (rx/tap on-success)
             (rx/catch on-error))))))


(defn- project-created
  [{:keys [id team-id] :as project}]
  (ptk/reify ::project-created
    IDeref
    (-deref [_] project)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:projects team-id id] project)
          (assoc-in [:dashboard-local :project-for-edit] id)))))

(defn create-project
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (ptk/reify ::create-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [name (name (gensym "New Project "))
            {:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/mutation! :create-project {:name name :team-id team-id})
             (rx/tap on-success)
             (rx/catch on-error)
             (rx/map project-created))))))

(defn duplicate-project
  [{:keys [id name] :as params}]
  (us/assert ::us/uuid id)
  (letfn [(duplicated [project state]
            (-> state
                (assoc-in [:projects (:team-id project) (:id project)] project)))]
    (ptk/reify ::duplicate-project
      ptk/WatchEvent
      (watch [_ state stream]
        (let [{:keys [on-success on-error]
               :or {on-success identity
                    on-error identity}} (meta params)

              new-name (str name " " (tr "dashboard.copy-suffix"))]

          (->> (rp/mutation! :duplicate-project {:project-id id
                                                 :name new-name})
               (rx/tap on-success)
               (rx/map #(partial duplicated %))
               (rx/catch on-error)))))))

(defn move-project
  [{:keys [id team-id] :as params}]
  (us/assert ::us/uuid id)
  (us/assert ::us/uuid team-id)
  (ptk/reify ::move-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error identity}} (meta params)]

        (->> (rp/mutation! :move-project {:project-id id
                                          :team-id team-id})
             (rx/tap on-success)
             (rx/catch on-error))))))

(defn toggle-project-pin
  [{:keys [id is-pinned team-id] :as params}]
  (us/assert ::project params)
  (ptk/reify ::toggle-project-pin
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:projects team-id id :is-pinned] (not is-pinned)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [project (get-in state [:projects team-id id])
            params  (select-keys project [:id :is-pinned :team-id])]
        (->> (rp/mutation :update-project-pin params)
             (rx/ignore))))))

;; --- Rename Project

(defn rename-project
  [{:keys [id name team-id] :as params}]
  (us/assert ::project params)
  (ptk/reify ::rename-project
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:projects team-id id :name] name)
          (update :dashboard-local dissoc :project-for-edit)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-project params)
             (rx/ignore))))))

;; --- Delete Project (by id)

(defn delete-project
  [{:keys [id team-id] :as params}]
  (us/assert ::project params)
  (ptk/reify ::delete-project
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:projects team-id] dissoc id))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :delete-project {:id id})
           (rx/ignore)))))

;; --- Delete File (by id)

(declare delete-file-result)

(defn delete-file
  [{:keys [id project-id] :as params}]
  (us/assert ::file params)
  (ptk/reify ::delete-file
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:files project-id] dissoc id)
          (update-in [:recent-files project-id] (fnil disj #{}) id)))

    ptk/WatchEvent
    (watch [_ state s]
      (let [team-id (uuid/uuid (get-in state [:route :path-params :team-id]))]
        (->> (rp/mutation :delete-file {:id id})
             (rx/map #(delete-file-result team-id project-id)))))))

(defn delete-file-result
  [team-id project-id]

  (ptk/reify ::delete-file
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:projects team-id project-id :count] dec)))))

;; --- Rename File

(defn rename-file
  [{:keys [id name project-id] :as params}]
  (us/assert ::file params)
  (ptk/reify ::rename-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:files project-id id :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params (select-keys params [:id :name])]
        (->> (rp/mutation :rename-file params)
             (rx/ignore))))))

;; --- Set File shared

(defn set-file-shared
  [{:keys [id project-id is-shared] :as params}]
  (us/assert ::file params)
  (ptk/reify ::set-file-shared
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:files project-id id :is-shared] is-shared))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :is-shared is-shared}]
        (->> (rp/mutation :set-file-shared params)
             (rx/ignore))))))

;; --- Create File

(declare file-created)

(defn create-file
  [{:keys [project-id] :as params}]
  (us/assert ::us/uuid project-id)
  (ptk/reify ::create-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)

            name   (name (gensym "New File "))
            params (assoc params :name name)]

        (->> (rp/mutation! :create-file params)
             (rx/tap on-success)
             (rx/catch on-error)
             (rx/map file-created))))))

(defn file-created
  [{:keys [project-id id] :as file}]
  (us/verify ::file file)
  (ptk/reify ::file-created
    IDeref
    (-deref [_] file)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:files project-id id] file)
          (update-in [:recent-files project-id] (fnil conj #{}) id)))))

;; --- Duplicate File

(defn duplicate-file
  [{:keys [id name] :as params}]
  (us/assert ::us/uuid id)
  (us/assert ::name name)
  (ptk/reify ::duplicate-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error identity}} (meta params)

            new-name (str name " " (tr "dashboard.copy-suffix"))]

        (->> (rp/mutation! :duplicate-file {:file-id id
                                            :name new-name})
             (rx/tap on-success)
             (rx/map file-created)
             (rx/catch on-error))))))

;; --- Move File

(defn move-files
  [{:keys [ids project-id] :as params}]
  (us/assert ::set-of-uuid ids)
  (us/assert ::us/uuid project-id)
  (ptk/reify ::move-files
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error identity}} (meta params)]

        (->> (rp/mutation! :move-files {:ids ids
                                        :project-id project-id})
             (rx/tap on-success)
             (rx/catch on-error))))))

