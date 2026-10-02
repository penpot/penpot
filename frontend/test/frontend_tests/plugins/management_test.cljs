;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.management-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.management :as management]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]))

(def file-id (uuid/next))
(def page-id (uuid/next))
(def team-id (uuid/next))

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
         :files {file-id {:id file-id :name "Design"
                          :data {:pages-index {page-id {:id page-id :objects {}}}}}}))

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
           (await (a/wait-for #(= ["loading"] @seen)))
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
       (let [result (-> (management/open-file (str file-id) nil)
                        (.then (fn [] nil) (fn [error] (.-message error))))]
         (await (a/wait-for #(= loading @state)))
         (reset! state {:route {:data {:name :settings-profile}}})
         (t/is (= "File navigation was superseded" (await result))))))))

(t/deftest ^:async open-file-rejects-invalid-identifiers
  (let [result (-> (management/open-file "invalid" #js {:teamId (str team-id)})
                   (.then (fn [] nil) (fn [error] (.-message error))))]
    (t/is (= "Expected a file UUID and a team UUID" (await result)))))

(t/deftest ^:async list-projects-returns-active-metadata
  (let [project-id (uuid/next)
        other-team-id (uuid/next)]
    (doseq [[state options expected-team-id]
            [[dashboard nil team-id]
             [ready #js {:teamId (str other-team-id)} other-team-id]]]
      (let [state* (atom state)]
        (await
         (mock/with-mocks*
           {st/state state*
            rp/cmd! (mock/stub
                     (fn [command params]
                       (t/is (= [:get-projects {:team-id expected-team-id}]
                                [command params]))
                       (rx/of [{:id project-id :team-id expected-team-id
                                :name "Drafts" :is-default true :count 2 :total-count 3}
                               {:id (uuid/next) :deleted-at (js/Date.)}])))}
           (let [projects (await (management/list-projects options))]
             (t/is (= [{"id" (str project-id) "teamId" (str expected-team-id)
                        "name" "Drafts" "isDefault" true "fileCount" 2}]
                      (js->clj projects)))
             (t/is (= state @state*)))))))))

(t/deftest ^:async list-files-returns-metadata-without-opening-a-file
  (let [project-id (uuid/next)]
    (doseq [state [dashboard ready]]
      (let [state* (atom state)]
        (await
         (mock/with-mocks*
           {st/state state*
            rp/cmd! (mock/stub
                     (fn [command params]
                       (t/is (= [:get-project-files {:project-id project-id}]
                                [command params]))
                       (rx/of [{:id file-id :team-id team-id :project-id project-id
                                :name "Design" :modified-at (js/Date. "2026-01-02T03:04:05Z")
                                :revn 10}])))}
           (let [files (await (management/list-files #js {:projectId (str project-id)}))]
             (t/is (= [{"id" (str file-id) "teamId" (str team-id)
                        "projectId" (str project-id) "name" "Design"
                        "modifiedAt" "2026-01-02T03:04:05.000Z"}]
                      (js->clj files)))
             (t/is (= state @state*)))))))))

(t/deftest ^:async listing-rejects-invalid-identifiers-without-querying
  (await
   (mock/with-mocks*
     {st/state (atom {})
      rp/cmd! (mock/stub (fn [_ _] (t/is false "Invalid IDs must not query the backend")))}
     (doseq [[result message]
             [[(management/list-projects nil) "Expected a team UUID"]
              [(management/list-projects #js {:teamId "invalid"}) "Expected a team UUID"]
              [(management/list-files nil) "Expected a project UUID"]
              [(management/list-files #js {:projectId "invalid"}) "Expected a project UUID"]]]
       (t/is (= message (await (.then result (fn [] nil) #(.-message %)))))))))

(t/deftest ^:async listing-preserves-empty-results-and-backend-errors
  (doseq [list-items [(fn [] (management/list-projects #js {:teamId (str team-id)}))
                      (fn [] (management/list-files #js {:projectId (str (uuid/next))}))]]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _] (rx/of [])))}
       (t/is (= [] (js->clj (await (list-items)))))))
    (let [error (js/Error. "Access denied")]
      (await
       (mock/with-mocks*
         {rp/cmd! (mock/stub (fn [_ _] (rx/throw error)))}
         (t/is (identical? error (await (.then (list-items) (fn [] nil) identity)))))))))
