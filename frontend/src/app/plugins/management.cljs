;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.management
  (:require
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.main.data.dashboard :as dd]
   [app.main.data.helpers :as dsh]
   [app.main.data.team :as dtm]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.plugins.register :as r]
   [app.plugins.system-events :as se]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(declare project-proxy)
(declare file-proxy)

(defn- dashboard-of-team?
  "True when the dashboard of `team-id` is open. Outside the dashboard `:files`
  holds the workspace files and libraries, so dashboard updates do not apply."
  [state team-id]
  (and (str/starts-with? (name (or (rt/lookup-name state) "")) "dashboard")
       (= team-id (:current-team-id state))))

(defn- update-dashboard
  "Applies `update-fn` to the state when the dashboard of `team-id` is open."
  [team-id update-fn]
  (st/emit! (fn [state]
              (if (dashboard-of-team? state team-id)
                (update-fn state)
                state))))

(defn- valid-name
  "Returns `value` trimmed when it is a name of 1 to 250 characters."
  [value]
  (when (string? value)
    (let [value (str/trim value)]
      (when (<= 1 (count value) 250)
        value))))

(defn- permission-error
  [permission]
  (js/Error. (str "Permission " permission " is not granted")))

(defn- request
  "Resolves with the single value of the stream built by `make-stream` when
  the plugin has `permission`."
  [plugin-id permission make-stream]
  (if (r/check-permission plugin-id permission)
    (js/Promise.
     (fn [resolve reject]
       (rx/subs! resolve reject (make-stream))))
    (js/Promise.reject (permission-error permission))))

(defn- set-project-pin
  [{:keys [id is-pinned] :as params}]
  (ptk/reify ::set-project-pin
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:projects id] assoc :is-pinned is-pinned))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-project-pin params)
           (rx/ignore)))))

(defn workspace-context
  [state]
  (let [params  (rt/get-params state)
        file-id (when (= :workspace (rt/lookup-name state))
                  (uuid/parse* (:file-id params)))
        ready?  (and file-id
                     (nil? (:exception state))
                     (= file-id (:current-file-id state) (:workspace-ready state))
                     (some? (:current-page-id state))
                     (some? (dsh/lookup-page state file-id (:current-page-id state))))]
    {:status (cond ready? "ready" file-id "loading" :else "none")
     :fileId (some-> file-id str)
     :fileName (when file-id (:name (dsh/lookup-file state file-id)))
     :teamId (some-> (:current-team-id state) str)}))

(defn open-file
  [file-id options]
  (let [file-id (uuid/parse* file-id)
        team-id (if-let [id (obj/get options "teamId")]
                  (uuid/parse* id)
                  (:current-team-id @st/state))]
    (cond
      (or (nil? file-id) (nil? team-id))
      (js/Promise.reject (js/Error. "Expected a file UUID and a team UUID"))

      (and (= "ready" (:status (workspace-context @st/state)))
           (= file-id (:current-file-id @st/state))
           (= team-id (:current-team-id @st/state)))
      (js/Promise.resolve nil)

      :else
      (js/Promise.
       (fn [resolve reject]
         (let [key           (js/Symbol)
               initial-route (:route @st/state)
               timer         (atom nil)
               finish        (fn [error]
                               (remove-watch st/state key)
                               (js/clearTimeout @timer)
                               (if error (reject error) (resolve nil)))]
           (reset! timer (js/setTimeout #(finish (js/Error. "Timed out opening file")) 30000))
           (add-watch
            st/state key
            (fn [_ _ _ state]
              (let [workspace (workspace-context state)
                    target?   (and (= (str file-id) (:fileId workspace))
                                   (= (str team-id) (:team-id (rt/get-params state))))]
                (cond
                  (:exception state)
                  (finish (js/Error. "Unable to open file"))

                  (and target? (= "ready" (:status workspace)))
                  (finish nil)

                  (and (not= initial-route (:route state)) (not target?))
                  (finish (js/Error. "File navigation was superseded"))))))
           (st/emit! (rt/nav :workspace {:team-id team-id :file-id file-id}))))))))

(defn- list-projects
  [plugin-id team-id]
  (request plugin-id "content:read"
           #(->> (rp/cmd! :get-projects {:team-id team-id})
                 (rx/map (fn [projects]
                           (->> projects
                                (remove :deleted-at)
                                (map (partial project-proxy plugin-id))
                                (into-array)))))))

(defn- create-project
  [plugin-id team-id options]
  (if-let [name (valid-name (obj/get options "name"))]
    (request plugin-id "manage:projects"
             #(->> (rp/cmd! :create-project {:team-id team-id :name name})
                   (rx/map (fn [project] (assoc project :count 0)))
                   (rx/tap (fn [project]
                             (update-dashboard team-id
                                               (fn [state]
                                                 (assoc-in state [:projects (:id project)] project)))))
                   (rx/map (partial project-proxy plugin-id))))
    (js/Promise.reject (js/Error. "Expected a name with 1 to 250 characters"))))

(defn- file-metadata
  "Fetches the metadata of a file and its team. A file loaded in the workspace
  is read from the state instead of downloading it again."
  [file-id]
  (let [file (dsh/lookup-file @st/state file-id)]
    (->> (if (:project-id file)
           (rx/of file)
           (rp/cmd! :get-file {:id file-id :features (:features @st/state)}))
         (rx/mapcat (fn [file]
                      (->> (rp/cmd! :get-project {:id (:project-id file)})
                           (rx/map (fn [project]
                                     (-> (dissoc file :data)
                                         (assoc :team-id (:team-id project)))))))))))

(defn- get-file
  [plugin-id file-id]
  (if-let [file-id (uuid/parse* file-id)]
    (request plugin-id "content:read"
             #(->> (file-metadata file-id)
                   (rx/map (partial file-proxy plugin-id))))
    (js/Promise.reject (js/Error. "Expected a file UUID"))))

(defn team-proxy
  [plugin-id team]
  (let [data (atom team)]
    (obj/reify {:name "TeamProxy"}
      :$plugin {:enumerable false :get (fn [] plugin-id)}

      :id
      {:get #(str (:id @data))}

      :isDefault
      {:get #(boolean (:is-default @data))}

      :name
      {:get #(:name @data)
       :set
       (fn [value]
         (let [name (valid-name value)]
           (cond
             (not (r/check-permission plugin-id "manage:teams"))
             (u/not-valid plugin-id :name "Plugin doesn't have 'manage:teams' permission")

             (nil? name)
             (u/not-valid plugin-id :name value)

             :else
             (do (swap! data assoc :name name)
                 (st/emit! (dtm/update-team {:id (:id @data) :name name}))))))}

      :listProjects
      (fn [] (list-projects plugin-id (:id @data)))

      :createProject
      (fn [options] (create-project plugin-id (:id @data) options))

      :remove
      (fn []
        (if (= (:id @data) (:current-team-id @st/state))
          (js/Promise.reject (js/Error. "Cannot delete the current team"))
          (request plugin-id "manage:delete"
                   #(->> (rp/cmd! :delete-team {:id (:id @data)})
                         (rx/tap (fn [_] (st/emit! (dtm/fetch-teams))))
                         (rx/map (constantly nil)))))))))

(defn project-proxy
  [plugin-id project]
  (let [data (atom project)]
    (obj/reify {:name "ProjectProxy"}
      :$plugin {:enumerable false :get (fn [] plugin-id)}

      :id
      {:get #(str (:id @data))}

      :teamId
      {:get #(str (:team-id @data))}

      :isDefault
      {:get #(boolean (:is-default @data))}

      :fileCount
      {:get #(or (:count @data) 0)}

      :name
      {:get #(:name @data)
       :set
       (fn [value]
         (let [name (valid-name value)]
           (cond
             (not (r/check-permission plugin-id "manage:projects"))
             (u/not-valid plugin-id :name "Plugin doesn't have 'manage:projects' permission")

             (nil? name)
             (u/not-valid plugin-id :name value)

             :else
             (do (swap! data assoc :name name)
                 (st/emit! (dd/rename-project {:id (:id @data) :name name}))))))}

      :pinned
      {:get #(boolean (:is-pinned @data))
       :set
       (fn [value]
         (cond
           (not (r/check-permission plugin-id "manage:projects"))
           (u/not-valid plugin-id :pinned "Plugin doesn't have 'manage:projects' permission")

           (not (boolean? value))
           (u/not-valid plugin-id :pinned value)

           :else
           (do (swap! data assoc :is-pinned value)
               (st/emit! (set-project-pin {:id (:id @data)
                                           :team-id (:team-id @data)
                                           :is-pinned value})))))}

      :listFiles
      (fn []
        (request plugin-id "content:read"
                 #(->> (rp/cmd! :get-project-files {:project-id (:id @data)})
                       (rx/map (fn [files]
                                 (into-array (map (partial file-proxy plugin-id) files)))))))

      :createFile
      (fn [options]
        (if-let [name (valid-name (obj/get options "name"))]
          (request plugin-id "content:write"
                   #(let [{:keys [id team-id]} @data
                          features (set/difference (:features @st/state #{}) cfeat/frontend-only-features)]
                      (->> (rp/cmd! :create-file {:project-id id :name name :features features})
                           (rx/map (fn [file] (-> (dissoc file :data) (assoc :team-id team-id))))
                           (rx/tap (fn [file]
                                     (swap! data update :count (fnil inc 0))
                                     (update-dashboard team-id (fn [state] (ptk/update (dd/file-created file) state)))))
                           (rx/map (partial file-proxy plugin-id)))))
          (js/Promise.reject (js/Error. "Expected a name with 1 to 250 characters"))))

      :duplicate
      (fn [options]
        (let [name (obj/get options "name")]
          (if (and (some? name) (nil? (valid-name name)))
            (js/Promise.reject (js/Error. "Expected a name with 1 to 250 characters"))
            (request plugin-id "manage:projects"
                     #(let [{:keys [id team-id]} @data]
                        (->> (rp/cmd! :duplicate-project (cond-> {:project-id id}
                                                           (some? name) (assoc :name (valid-name name))))
                             (rx/tap (fn [project]
                                       (update-dashboard team-id (fn [state] (ptk/update (dd/project-duplicated project) state)))))
                             (rx/map (partial project-proxy plugin-id))))))))

      :moveTo
      (fn [team]
        (if-let [team-id (uuid/parse* (obj/get team "id"))]
          (request plugin-id "manage:teams"
                   #(let [{:keys [id] :as project} @data]
                      (->> (rp/cmd! :move-project {:project-id id :team-id team-id})
                           (rx/tap (fn [_]
                                     (swap! data assoc :team-id team-id)
                                     (update-dashboard (:team-id project) (fn [state] (update state :projects dissoc id)))))
                           (rx/map (constantly nil)))))
          (js/Promise.reject (js/Error. "Expected a team"))))

      :remove
      (fn []
        (request plugin-id "manage:delete"
                 #(let [{:keys [id team-id]} @data]
                    (->> (rp/cmd! :delete-project {:id id})
                         (rx/tap (fn [_]
                                   (update-dashboard team-id (fn [state] (update state :projects dissoc id)))))
                         (rx/map (constantly nil)))))))))

(defn file-proxy
  [plugin-id file]
  (let [data (atom file)]
    (obj/reify {:name "ProjectFileProxy"}
      :$plugin {:enumerable false :get (fn [] plugin-id)}

      :id
      {:get #(str (:id @data))}

      :teamId
      {:get #(str (:team-id @data))}

      :projectId
      {:get #(str (:project-id @data))}

      :modifiedAt
      {:get #(.toISOString ^js (:modified-at @data))}

      :name
      {:get #(:name @data)
       :set
       (fn [value]
         (let [name (valid-name value)]
           (cond
             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :name "Plugin doesn't have 'content:write' permission")

             (nil? name)
             (u/not-valid plugin-id :name value)

             :else
             (do (swap! data assoc :name name)
                 (st/emit! (-> (dd/rename-file {:id (:id @data) :name name})
                               (se/add-event plugin-id)))))))}

      :shared
      {:get #(boolean (:is-shared @data))
       :set
       (fn [value]
         (cond
           (not (r/check-permission plugin-id "library:write"))
           (u/not-valid plugin-id :shared "Plugin doesn't have 'library:write' permission")

           (not (boolean? value))
           (u/not-valid plugin-id :shared value)

           :else
           (do (swap! data assoc :is-shared value)
               (st/emit! (-> (dd/set-file-shared {:id (:id @data) :is-shared value})
                             (se/add-event plugin-id))))))}

      :open
      (fn []
        (if (r/check-permission plugin-id "content:read")
          (open-file (str (:id @data)) #js {:teamId (str (:team-id @data))})
          (js/Promise.reject (permission-error "content:read"))))

      :moveTo
      (fn [project]
        (if-let [project-id (uuid/parse* (obj/get project "id"))]
          (request plugin-id "content:write"
                   #(let [{:keys [id team-id]} @data]
                      (->> (rp/cmd! :move-files {:ids #{id} :project-id project-id})
                           (rx/tap (fn [_]
                                     (update-dashboard team-id (fn [state] (ptk/update (dd/move-files {:ids #{id} :project-id project-id}) state)))
                                     (swap! data assoc :project-id project-id)))
                           (rx/map (constantly nil)))))
          (js/Promise.reject (js/Error. "Expected a project"))))

      :duplicate
      (fn [options]
        (let [name (obj/get options "name")]
          (if (and (some? name) (nil? (valid-name name)))
            (js/Promise.reject (js/Error. "Expected a name with 1 to 250 characters"))
            (request plugin-id "content:write"
                     #(let [{:keys [id team-id]} @data]
                        (->> (rp/cmd! :duplicate-file (cond-> {:file-id id}
                                                        (some? name) (assoc :name (valid-name name))))
                             (rx/map (fn [file] (-> (dissoc file :data) (assoc :team-id team-id))))
                             (rx/tap (fn [file]
                                       (update-dashboard team-id (fn [state] (ptk/update (dd/file-created file) state)))))
                             (rx/map (partial file-proxy plugin-id))))))))

      :remove
      (fn []
        (request plugin-id "manage:delete"
                 #(let [{:keys [id project-id team-id]} @data]
                    (->> (rp/cmd! :delete-file {:id id})
                         (rx/tap (fn [_]
                                   (update-dashboard team-id
                                                     (fn [state]
                                                       (->> state
                                                            (ptk/update (dd/delete-file {:id id :project-id project-id}))
                                                            (ptk/update (dd/file-deleted project-id)))))))
                         (rx/map (constantly nil)))))))))

(defn create-context
  [plugin-id]
  (obj/reify {:name "PenpotManagementContext"}
    :workspace
    {:get (fn [] (clj->js (workspace-context @st/state)))}

    :openFile open-file

    :getFile
    (fn [file-id] (get-file plugin-id file-id))

    :listTeams
    (fn []
      (request plugin-id "manage:teams"
               #(->> (rp/cmd! :get-teams)
                     (rx/map (fn [teams]
                               (into-array (map (partial team-proxy plugin-id) teams)))))))

    :createTeam
    (fn [options]
      (if-let [name (valid-name (obj/get options "name"))]
        (request plugin-id "manage:teams"
                 #(->> (rp/cmd! :create-team {:name name :features features/global-enabled-features})
                       (rx/tap (fn [_] (st/emit! (dtm/fetch-teams))))
                       (rx/map (partial team-proxy plugin-id))))
        (js/Promise.reject (js/Error. "Expected a name with 1 to 250 characters"))))

    :listProjects
    (fn [options]
      (if-let [team-id (if-let [id (obj/get options "teamId")]
                         (uuid/parse* id)
                         (:current-team-id @st/state))]
        (list-projects plugin-id team-id)
        (js/Promise.reject (js/Error. "Expected a team UUID"))))))
