;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.management-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.dashboard :as dd]
   [app.main.data.team :as dtm]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.management :as management]
   [app.plugins.register :as r]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def file-id (uuid/next))
(def page-id (uuid/next))
(def team-id (uuid/next))
(def project-id (uuid/next))

;; The zero plugin id always holds every permission.
(def plugin-id (str uuid/zero))

(def dashboard
  {:route {:data {:name :dashboard-recent}}
   :current-team-id team-id})

(def loading
  (assoc dashboard
         :route {:data {:name :workspace}
                 :params {:query {:file-id (str file-id) :team-id (str team-id)}}}
         :current-file-id file-id
         :workspace-ready file-id))

(def ready
  (assoc loading
         :current-page-id page-id
         :files {file-id {:id file-id :name "Design" :project-id project-id
                          :data {:pages-index {page-id {:id page-id :objects {}}}}}}))

(def team
  {:id team-id :name "Team" :is-default true})

(def project
  {:id project-id :team-id team-id :name "Drafts" :is-default true :is-pinned false :count 2})

(def file
  {:id file-id :team-id team-id :project-id project-id :name "Design"
   :is-shared false :modified-at (js/Date. "2026-01-02T03:04:05Z")})

(defn- props
  "Reads `keys` from a proxy into a map."
  [proxy keys]
  (into {} (map (fn [k] [k (obj/get proxy k)])) keys))

(defn- message
  "Resolves with the rejection message of `promise`, or nil when it resolves."
  [promise]
  (.then promise (fn [] nil) #(.-message %)))

(defn- cmd-stub
  "Mock `rp/cmd!` recording calls in `requests` and answering with `respond`."
  [requests respond]
  (mock/stub
   (fn
     ([command] (swap! requests conj [command]) (respond command nil))
     ([command params] (swap! requests conj [command params]) (respond command params)))))

(defn- emit-stub
  "Mock `st/emit!` applying update functions to `state` and recording events."
  [state events]
  (mock/stub
   (fn [event]
     (if (fn? event)
       (swap! state event)
       (swap! events conj event)))))

(defn- throwing
  "Makes validation errors of the test plugin throw."
  [state]
  (assoc-in state [:plugins :flags plugin-id :throw-validation-errors] true))

(t/deftest workspace-readiness-requires-an-initialized-page
  (t/is (= "none" (:status (management/workspace-context dashboard))))
  (t/is (= "loading" (:status (management/workspace-context loading))))
  (t/is (= "ready" (:status (management/workspace-context ready))))
  (t/is (= "none" (:status (management/workspace-context (assoc ready :route (:route dashboard)))))))

(t/deftest ^:async open-file-waits-for-page-readiness
  (let [state  (atom dashboard)
        seen   (atom [])
        done?  (atom false)]
    (await
     (mock/with-mocks*
       {st/state state
        st/emit! (fn ([_] (js/setTimeout #(reset! state loading) 0))
                   ([_ & _] nil))}
       (let [listener (events/add-listener "workspacechange" (str uuid/zero)
                                           #(swap! seen conj (.-status %)) nil)
             result   (-> (management/open-file (str file-id) nil)
                          (.then #(reset! done? true)))]
         (try
           (await (a/wait-for #(= ["loading"] @seen) "workspace loading"))
           (t/is (false? @done?))
           (reset! state ready)
           (await result)
           (t/is (= ["loading" "ready"] @seen))
           (t/is (true? @done?))
           (finally
             (events/remove-listener listener))))))))

(t/deftest ^:async open-file-rejects-superseded-navigation
  (let [state (atom dashboard)]
    (await
     (mock/with-mocks*
       {st/state state
        st/emit! (fn ([_] (js/setTimeout #(reset! state loading) 0))
                   ([_ & _] nil))}
       (let [result (message (management/open-file (str file-id) nil))]
         (await (a/wait-for #(= loading @state) "navigation started"))
         (reset! state {:route {:data {:name :settings-profile}}})
         (t/is (= "File navigation was superseded" (await result))))))))

(t/deftest ^:async open-file-rejects-invalid-identifiers
  (t/is (= "Expected a file UUID and a team UUID"
           (await (message (management/open-file "invalid" #js {:teamId (str team-id)}))))))

(t/deftest ^:async listing-returns-team-project-and-file-objects
  (let [requests (atom [])
        other-id (uuid/next)]
    (await
     (mock/with-mocks*
       {st/state (atom dashboard)
        rp/cmd!  (cmd-stub requests
                           (fn [command _]
                             (rx/of (case command
                                      :get-teams         [team]
                                      :get-projects      [project {:id other-id :deleted-at (js/Date.)}]
                                      :get-project-files [file]))))}
       (let [context      (management/create-context plugin-id)
             [team*]      (await (.listTeams context))
             [project*]   (await (.listProjects context))
             team-project (first (await (.listProjects team*)))
             [file*]      (await (.listFiles project*))]
         (t/is (= {"id" (str team-id) "name" "Team" "isDefault" true}
                  (props team* ["id" "name" "isDefault"])))
         (t/is (= {"id" (str project-id) "teamId" (str team-id) "name" "Drafts"
                   "isDefault" true "pinned" false "fileCount" 2}
                  (props project* ["id" "teamId" "name" "isDefault" "pinned" "fileCount"])))
         (t/is (= (str project-id) (obj/get team-project "id")))
         (t/is (= {"id" (str file-id) "teamId" (str team-id) "projectId" (str project-id)
                   "name" "Design" "shared" false "modifiedAt" "2026-01-02T03:04:05.000Z"}
                  (props file* ["id" "teamId" "projectId" "name" "shared" "modifiedAt"])))
         (t/is (= [[:get-teams]
                   [:get-projects {:team-id team-id}]
                   [:get-projects {:team-id team-id}]
                   [:get-project-files {:project-id project-id}]]
                  @requests)))))))

(t/deftest ^:async get-file-reads-loaded-files-and-fetches-the-rest
  (let [other-id (uuid/next)]
    (doseq [[state id expected]
            [[ready file-id
              [[:get-project {:id project-id}]]]
             [dashboard other-id
              [[:get-file {:id other-id :features nil}]
               [:get-project {:id project-id}]]]]]
      (let [requests (atom [])]
        (await
         (mock/with-mocks*
           {st/state (atom state)
            rp/cmd!  (cmd-stub requests
                               (fn [command _]
                                 (rx/of (case command
                                          :get-file    (assoc file :id other-id :data {})
                                          :get-project project))))}
           (let [file* (await (.getFile (management/create-context plugin-id) (str id)))]
             (t/is (= {"id" (str id) "teamId" (str team-id) "projectId" (str project-id)}
                      (props file* ["id" "teamId" "projectId"])))
             (t/is (= expected @requests)))))))))

(t/deftest ^:async listing-rejects-invalid-identifiers-and-preserves-backend-errors
  (let [requests (atom [])]
    (await
     (mock/with-mocks*
       {st/state (atom {})
        rp/cmd!  (cmd-stub requests (fn [_ _] (rx/of nil)))}
       (let [context (management/create-context plugin-id)]
         (t/is (= "Expected a team UUID" (await (message (.listProjects context nil)))))
         (t/is (= "Expected a team UUID" (await (message (.listProjects context #js {:teamId "invalid"})))))
         (t/is (= "Expected a file UUID" (await (message (.getFile context "invalid")))))
         (await (a/settle))
         (t/is (empty? @requests))))))
  (let [error (js/Error. "Access denied")]
    (await
     (mock/with-mocks*
       {st/state (atom dashboard)
        rp/cmd!  (mock/stub (fn [& _] (rx/throw error)))}
       (let [context (management/create-context plugin-id)]
         (t/is (identical? error (await (.then (.listTeams context) (fn [] nil) identity))))
         (t/is (identical? error (await (.then (.listProjects context) (fn [] nil) identity)))))))))

(t/deftest ^:async teams-are-created-and-removed
  (let [requests (atom [])
        events   (atom [])
        created  {:id (uuid/next) :name "Brand" :is-default false}]
    (await
     (mock/with-mocks*
       {st/state (atom dashboard)
        st/emit! (emit-stub (atom dashboard) events)
        rp/cmd!  (cmd-stub requests (fn [command _]
                                      (rx/of (when (= command :create-team) created))))}
       (let [context (management/create-context plugin-id)
             team*   (await (.createTeam context #js {:name " Brand "}))]
         (t/is (= {"id" (str (:id created)) "name" "Brand" "isDefault" false}
                  (props team* ["id" "name" "isDefault"])))
         (t/is (nil? (await (.remove team*))))
         (t/is (= [[:create-team "Brand"] [:delete-team {:id (:id created)}]]
                  (mapv (fn [[command params]]
                          [command (if (= command :create-team) (:name params) params)])
                        @requests)))
         (t/is (= [::dtm/fetch-teams ::dtm/fetch-teams] (mapv ptk/type @events)))
         (t/is (= "Cannot delete the current team"
                  (await (message (.remove (management/team-proxy plugin-id team))))))
         (t/is (= "Expected a name with 1 to 250 characters"
                  (await (message (.createTeam context #js {:name ""})))))
         (t/is (= 2 (count @requests))))))))

(t/deftest setters-update-the-snapshot-and-emit-dashboard-events
  (let [state  (atom (throwing dashboard))
        events (atom [])]
    (with-redefs [st/state state
                  st/emit! (emit-stub state events)]
      (let [team*    (management/team-proxy plugin-id team)
            project* (management/project-proxy plugin-id project)
            file*    (management/file-proxy plugin-id file)]
        (obj/set! team* "name" "  Brand  ")
        (obj/set! project* "name" "Icons")
        (obj/set! project* "pinned" true)
        (obj/set! file* "name" "Login")
        (obj/set! file* "shared" true)
        (t/is (= "Brand" (obj/get team* "name")))
        (t/is (= "Icons" (obj/get project* "name")))
        (t/is (true? (obj/get project* "pinned")))
        (t/is (= "Login" (obj/get file* "name")))
        (t/is (true? (obj/get file* "shared")))
        (t/is (= [::dtm/update-team ::dd/rename-project :app.plugins.management/set-project-pin
                  ::dd/rename-file ::dd/set-file-shared]
                 (mapv ptk/type @events)))))))

(t/deftest setters-reject-invalid-values-and-missing-permissions
  (let [state  (atom (throwing dashboard))
        events (atom [])]
    (with-redefs [st/state state
                  st/emit! (emit-stub state events)]
      (let [team*    (management/team-proxy plugin-id team)
            project* (management/project-proxy plugin-id project)
            file*    (management/file-proxy plugin-id file)]
        (doseq [[proxy key value] [[team* "name" "  "]
                                   [project* "name" (apply str (repeat 251 "a"))]
                                   [project* "pinned" "yes"]
                                   [file* "name" nil]
                                   [file* "shared" 1]]]
          (t/is (thrown? js/Error (obj/set! proxy key value)) key))
        (with-redefs [r/check-permission (mock/stub (constantly false))]
          (doseq [[proxy key value permission] [[team* "name" "Brand" "manage:teams"]
                                                [project* "name" "Icons" "manage:projects"]
                                                [project* "pinned" true "manage:projects"]
                                                [file* "name" "Login" "content:write"]
                                                [file* "shared" true "library:write"]]]
            (t/is (thrown-with-msg? js/Error (re-pattern permission) (obj/set! proxy key value)))))
        (t/is (= "Team" (obj/get team* "name")))
        (t/is (= "Design" (obj/get file* "name")))
        (t/is (empty? @events))))))

(t/deftest ^:async creation-returns-objects-and-updates-only-the-dashboard
  (let [created-project {:id (uuid/next) :team-id team-id :name "Icons" :is-default false}
        created-file    (assoc file :id (uuid/next) :name "Login" :data {})]
    (doseq [initial [dashboard ready]]
      (let [state    (atom (assoc initial :projects {project-id project}))
            requests (atom [])]
        (await
         (mock/with-mocks*
           {st/state state
            st/emit! (emit-stub state (atom []))
            rp/cmd!  (cmd-stub requests
                               (fn [command _]
                                 (->> (rx/of (case command
                                               :create-project created-project
                                               :create-file    created-file))
                                      (rx/observe-on :async))))}
           (let [project* (await (.createProject (management/team-proxy plugin-id team) #js {:name " Icons "}))
                 parent   (management/project-proxy plugin-id project)
                 file*    (await (.createFile parent #js {:name "Login"}))]
             (t/is (= {"name" "Icons" "fileCount" 0} (props project* ["name" "fileCount"])))
             (t/is (= {"name" "Login" "teamId" (str team-id)} (props file* ["name" "teamId"])))
             (t/is (= 3 (obj/get parent "fileCount")))
             (t/is (= [:create-project {:team-id team-id :name "Icons"}] (first @requests)))
             (if (= initial dashboard)
               (do (t/is (contains? (:projects @state) (:id created-project)))
                   (t/is (contains? (:files @state) (:id created-file))))
               (t/is (= (assoc ready :projects {project-id project}) @state))))))))))

(t/deftest ^:async file-and-project-methods-call-the-backend
  (let [requests  (atom [])
        other-id  (uuid/next)
        copy      (assoc file :id (uuid/next) :name "Design copy")
        project-2 (assoc project :id other-id :is-default false)]
    (await
     (mock/with-mocks*
       {st/state (atom dashboard)
        st/emit! (mock/stub (fn [_] nil))
        rp/cmd!  (cmd-stub requests
                           (fn [command _]
                             (rx/of (case command
                                      :duplicate-file    copy
                                      :duplicate-project project-2
                                      nil))))}
       (let [file*     (management/file-proxy plugin-id file)
             project*  (management/project-proxy plugin-id project-2)
             team*     (management/team-proxy plugin-id (assoc team :id other-id))
             duplicate (await (.duplicate file* #js {:name "Design copy"}))]
         (t/is (= "Design copy" (obj/get duplicate "name")))
         (t/is (= "Drafts" (obj/get (await (.duplicate project*)) "name")))
         (await (.moveTo file* project*))
         (t/is (= (str other-id) (obj/get file* "projectId")))
         (await (.moveTo project* team*))
         (t/is (= (str other-id) (obj/get project* "teamId")))
         (await (.remove file*))
         (await (.remove project*))
         (t/is (= [[:duplicate-file {:file-id file-id :name "Design copy"}]
                   [:duplicate-project {:project-id other-id}]
                   [:move-files {:ids #{file-id} :project-id other-id}]
                   [:move-project {:project-id other-id :team-id other-id}]
                   [:delete-file {:id file-id}]
                   [:delete-project {:id other-id}]]
                  @requests)))))))

(t/deftest ^:async methods-reject-invalid-input-and-missing-permissions
  (let [requests (atom [])]
    (await
     (mock/with-mocks*
       {st/state (atom dashboard)
        rp/cmd!  (cmd-stub requests (fn [_ _] (rx/of nil)))}
       (let [team*    (management/team-proxy plugin-id team)
             project* (management/project-proxy plugin-id project)
             file*    (management/file-proxy plugin-id file)]
         (t/is (= "Expected a name with 1 to 250 characters" (await (message (.createProject team* #js {:name ""})))))
         (t/is (= "Expected a name with 1 to 250 characters" (await (message (.createFile project* nil)))))
         (t/is (= "Expected a name with 1 to 250 characters" (await (message (.duplicate file* #js {:name " "})))))
         (t/is (= "Expected a project" (await (message (.moveTo file* #js {:id "invalid"})))))
         (t/is (= "Expected a team" (await (message (.moveTo project* nil)))))
         (with-redefs [r/check-permission (mock/stub (constantly false))]
           (doseq [[promise permission] [[(.createTeam (management/create-context plugin-id) #js {:name "Brand"}) "manage:teams"]
                                         [(.remove (management/team-proxy plugin-id (assoc team :id (uuid/next)))) "manage:delete"]
                                         [(.listProjects team*) "content:read"]
                                         [(.createProject team* #js {:name "Icons"}) "manage:projects"]
                                         [(.createFile project* #js {:name "Login"}) "content:write"]
                                         [(.duplicate project*) "manage:projects"]
                                         [(.moveTo project* team*) "manage:teams"]
                                         [(.remove project*) "manage:delete"]
                                         [(.open file*) "content:read"]
                                         [(.duplicate file*) "content:write"]
                                         [(.moveTo file* project*) "content:write"]
                                         [(.remove file*) "manage:delete"]]]
             (t/is (= (str "Permission " permission " is not granted") (await (message promise))))))
         (await (a/settle))
         (t/is (empty? @requests)))))))
