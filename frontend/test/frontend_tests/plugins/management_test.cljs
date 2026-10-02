;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.management-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.management :as management]
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
