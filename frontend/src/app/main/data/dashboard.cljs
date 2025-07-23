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
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.websocket :as dws]
   [app.main.repo :as rp]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(log/set-level! :warn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare fetch-projects)
(declare process-message)

(defn initialize
  [team-id]
  (assert (uuid? team-id) "expected uuid instance for `team-id`")

  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper    (rx/filter (ptk/type? ::finalize) stream)
            profile-id (:profile-id state)]

        (->> (rx/merge
              (rx/of (fetch-projects team-id)
                     (df/fetch-fonts team-id))
              (->> stream
                   (rx/filter (ptk/type? ::dws/message))
                   (rx/map deref)
                   (rx/filter (fn [{:keys [topic] :as msg}]
                                (or (= topic uuid/zero)
                                    (= topic profile-id))))
                   (rx/map process-message)))

             (rx/take-until stopper))))))

(defn finalize
  [team-id]
  (ptk/data-event ::finalize {:team-id team-id}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching (context aware: current team)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- EVENT: fetch-projects

(defn- projects-fetched
  [projects]
  (ptk/reify ::projects-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state {:keys [id] :as project}]
                (update-in state [:projects id] merge project))
              state
              projects))))

(defn fetch-projects
  [team-id]
  (ptk/reify ::fetch-projects
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-projects {:team-id team-id})
           (rx/map projects-fetched)))))

;; --- EVENT: search

(def ^:private schema:search-params
  [:map {:closed true}
   [:search-term [:maybe :string]]])

(def ^:private check-search-params
  (sm/check-fn schema:search-params))

(defn search
  [params]
  (let [params (check-search-params params)]
    (ptk/reify ::search
      ptk/UpdateEvent
      (update [_ state]
        (dissoc state :search-result))

      ptk/WatchEvent
      (watch [_ state _]
        (let [team-id (:current-team-id state)
              params  (assoc params :team-id team-id)]
          (->> (rp/cmd! :search-files params)
               (rx/map (fn [result]
                         #(assoc % :search-result result)))))))))

;; --- EVENT: recent-files

(defn- recent-files-fetched
  [files]
  (ptk/reify ::recent-files-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [files (d/index-by :id files)]
        (-> state
            (assoc :recent-files files)
            (update :files d/merge files))))))

(defn fetch-recent-files
  ([] (fetch-recent-files nil))
  ([team-id]
   (ptk/reify ::fetch-recent-files
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [team-id (or team-id (:current-team-id state))]
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
      (-> state
          (dissoc :selected-files)
          (dissoc :selected-project)
          (update :dashboard-local dissoc :menu-pos)))))

(defn toggle-file-select
  [{:keys [id project-id] :as file}]
  (ptk/reify ::toggle-file-select
    ptk/UpdateEvent
    (update [_ state]
      (let [selected-project-id (get state :selected-project)]
        (if (or (nil? selected-project-id)
                (= selected-project-id project-id))
          (-> state
              (update :selected-files #(if (contains? % id) (disj % id) (conj % id)))
              (assoc :selected-project project-id))
          state)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handle dropdowns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hide-dropdown
  ([]
   (ptk/reify ::hide-dropdown
     ptk/UpdateEvent
     (update [_ state]
       (update state :dashboard-local assoc
               :menu-open false
               :menu-pos nil
               :menu-id nil)))))

(defn show-dropdown
  ([dropdown-id] (show-dropdown dropdown-id nil))
  ([dropdown-id pos]
   (ptk/reify ::show-dropdown
     ptk/UpdateEvent
     (update [_ state]
       (update state :dashboard-local assoc
               :menu-open true
               :menu-id dropdown-id
               :menu-pos pos)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Show grid menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-edit-file-name
  [file-id]
  (ptk/reify ::start-edit-file-menu
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-local
              assoc :edition true
              :menu-id file-id))))

(defn stop-edit-file-name
  []
  (ptk/reify ::stop-edit-file-name
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-local
              assoc :edition false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- EVENT: create-project

(defn- project-created
  [{:keys [id] :as project}]
  (ptk/reify ::project-created
    IDeref
    (-deref [_] project)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:projects id] project)
          (assoc-in [:dashboard-local :project-for-edit] id)))))

(defn create-project
  []
  (ptk/reify ::create-project
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id   (:current-team-id state)
            projects  (dsh/lookup-team-projects state team-id)
            unames    (cfh/get-used-names projects)
            base-name (tr "dashboard.new-project-prefix")
            name      (cfh/generate-unique-name base-name unames :immediate-suffix? true)
            team-id   (:current-team-id state)
            params    {:name name
                       :team-id team-id}
            {:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}}
            (meta params)]
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
      (assoc-in state [:projects id] project))))

(defn duplicate-project
  [{:keys [id name] :as params}]
  (dm/assert! (uuid? id))
  (ptk/reify ::duplicate-project
    ev/Event
    (-data [_]
      {:project-id id
       :name name})

    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            projects (get state :projects)
            unames (cfh/get-used-names projects)
            suffix-fn (fn [copy-count]
                        (str/concat " "
                                    (tr "dashboard.copy-suffix")
                                    (when (> copy-count 1)
                                      (str " " copy-count))))
            new-name (cfh/generate-unique-name name unames :suffix-fn suffix-fn)]
        (->> (rp/cmd! :duplicate-project {:project-id id :name new-name})
             (rx/tap on-success)
             (rx/map project-duplicated)
             (rx/catch on-error))))))

(defn move-project
  [{:keys [id team-id] :as params}]
  (dm/assert! (uuid? id))
  (dm/assert! (uuid? team-id))

  (ptk/reify ::move-project
    ev/Event
    (-data [_]
      {:id id
       :team-id team-id})

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
      (assoc-in state [:projects id :is-pinned] (not is-pinned)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [project (get-in state [:projects id])
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
          (update-in [:projects id :name] (constantly name))
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
      (update state :projects dissoc id))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-project {:id id})
           (rx/ignore)))))

;; --- EVENT: delete-file

(defn file-deleted
  [project-id]
  (ptk/reify ::file-deleted
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:projects project-id :count] dec))))

(defn delete-file
  [{:keys [id project-id] :as params}]
  (ptk/reify ::delete-file
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-when :files dissoc id)
          (d/update-when :shared-files dissoc id)
          (d/update-when :recent-files dissoc id)))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-file {:id id})
           (rx/map (partial file-deleted project-id))))))

;; --- Rename File

(defn rename-file
  [{:keys [id name] :as params}]
  (ptk/reify ::rename-file
    ev/Event
    (-data [_]
      {::ev/origin "dashboard"
       :id id
       :name name})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:files id :name] (constantly name))
          (d/update-in-when [:shared-files id :name] (constantly name))
          (d/update-in-when [:recent-files id :name] (constantly name))))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params (select-keys params [:id :name])]
        (->> (rp/cmd! :rename-file params)
             (rx/ignore))))))

;; --- Set File shared

(defn set-file-shared
  [{:keys [id is-shared] :as params}]
  (ptk/reify ::set-file-shared
    ev/Event
    (-data [_]
      {::ev/origin "dashboard"
       :id id
       :shared is-shared})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:files id :is-shared] (constantly is-shared))
          (d/update-in-when [:recent-files id :is-shared] (constantly is-shared))
          (cond-> (not is-shared)
            (d/update-when :shared-files dissoc id))))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [params {:id id :is-shared is-shared}]
        (->> (rp/cmd! :set-file-shared params)
             (rx/ignore))))))

(defn set-file-thumbnail
  [file-id thumbnail-id]
  (ptk/reify ::set-file-thumbnail
    ptk/UpdateEvent
    (update [_ state]
      (letfn [(update-search-files [files]
                (->> files
                     (mapv #(cond-> %
                              (= file-id (:id %))
                              (assoc :thumbnail-id thumbnail-id)))))]
        (-> state
            (d/update-in-when [:files file-id] assoc :thumbnail-id thumbnail-id)
            (d/update-in-when [:recent-files file-id] assoc :thumbnail-id thumbnail-id)
            (d/update-when :dashboard-search-result update-search-files))))))

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
      (let [file (dissoc file :data)]
        (-> state
            (assoc-in [:files id] file)
            (assoc-in [:recent-files id] file)
            (update-in [:projects project-id :count] inc))))))

(defn create-file
  [{:keys [project-id name] :as params}]
  (dm/assert! (uuid? project-id))

  (ptk/reify ::create-file
    ev/Event
    (-data [_] {:project-id project-id})

    ptk/WatchEvent
    (watch [it state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}}
            (meta params)

            files     (dsh/lookup-team-files state)
            unames    (cfh/get-used-names files)
            base-name (tr "dashboard.new-file-prefix")
            name      (or name
                          (cfh/generate-unique-name base-name unames :immediate-suffix? true))
            features  (-> (get state :features)
                          (set/difference cfeat/frontend-only-features))
            params    (-> params
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
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            unames (cfh/get-used-names (get state :files))
            suffix-fn (fn [copy-count]
                        (str/concat " "
                                    (tr "dashboard.copy-suffix")
                                    (when (> copy-count 1)
                                      (str " " copy-count))))
            new-name (cfh/generate-unique-name name unames :suffix-fn suffix-fn)]
        (->> (rp/cmd! :duplicate-file {:file-id id :name new-name})
             (rx/tap on-success)
             (rx/map file-created)
             (rx/catch on-error))))))

;; --- EVENT: move-files

(defn move-files
  [{:keys [ids project-id] :as params}]
  (assert (uuid? project-id))
  (assert (sm/check-set-of-uuid ids))

  (ptk/reify ::move-files
    ev/Event
    (-data [_]
      {:num-files (count ids)
       :project-id project-id})

    ptk/UpdateEvent
    (update [_ state]
      (let [origin-project (get-in state [:files (first ids) :project-id])
            update-project (fn [project delta op]
                             (-> project
                                 (update :count #(op % (count ids)))
                                 (assoc :modified-at (ct/in-future {:milliseconds delta}))))]
        (-> state
            (d/update-in-when [:projects origin-project] update-project 0 -)
            (d/update-in-when [:projects project-id] update-project 10 +))))

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
  (ptk/reify ::clone-template
    ev/Event
    (-data [_]
      {:template-id template-id})

    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)
            project-id (or project-id (:current-project-id state))]
        (->> (rp/cmd! ::sse/clone-template {:project-id project-id
                                            :template-id template-id})
             (rx/tap (fn [event]
                       (let [payload (sse/get-payload event)
                             type    (sse/get-type event)]
                         (if (= type "progress")
                           (log/dbg :hint "clone-template: progress" :section (:section payload) :name (:name payload))
                           (log/dbg :hint "clone-template: end")))))

             (rx/filter sse/end-of-stream?)
             (rx/map sse/get-payload)
             (rx/tap on-success)
             (rx/catch on-error))))))

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
                            (let [files  (dsh/lookup-team-files state team-id)
                                  unames (cfh/get-used-names files)]
                              (cfh/generate-unique-name (tr "dashboard.new-file-prefix") unames :immediate-suffix? true))
                            (let [projects (dsh/lookup-team-projects  state team-id)
                                  unames   (cfh/get-used-names projects)]
                              (cfh/generate-unique-name (tr "dashboard.new-project-prefix") unames :immediate-suffix? true)))
            params        (if in-project?
                            {:project-id (:project-id pparams)
                             :name name}
                            {:name name
                             :team-id team-id})
            action-name   (if in-project? :create-file :create-project)
            action        (if in-project? file-created project-created)
            can-edit?     (dm/get-in state [:teams team-id :permissions :can-edit])]

        (when can-edit?
          (->> (rp/cmd! action-name params)
               (rx/map action)))))))

(defn open-selected-file
  []
  (ptk/reify ::open-selected-file
    ptk/WatchEvent
    (watch [_ state _]
      (let [[file-id :as files] (get state :selected-files)]
        (if (= 1 (count files))
          (rx/of (dcm/go-to-workspace :file-id file-id))
          (rx/empty))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notifications
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-change-team-role
  [params]
  (ptk/reify ::handle-change-team-role
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dcm/change-team-role params)
             (modal/hide)))))

(defn- process-message
  [{:keys [type] :as msg}]
  (case type
    :notification           (dcm/handle-notification msg)
    :team-role-change       (handle-change-team-role msg)
    :team-membership-change (dcm/team-membership-change msg)
    nil))
