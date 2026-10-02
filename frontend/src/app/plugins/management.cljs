;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.management
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

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

(defn list-projects
  [options]
  (let [team-id (if-let [id (obj/get options "teamId")]
                  (uuid/parse* id)
                  (:current-team-id @st/state))]
    (if (nil? team-id)
      (js/Promise.reject (js/Error. "Expected a team UUID"))
      (js/Promise.
       (fn [resolve reject]
         (->> (rp/cmd! :get-projects {:team-id team-id})
              (rx/map (fn [projects]
                        (clj->js
                         (mapv (fn [project]
                                 {:id (str (:id project))
                                  :teamId (str (:team-id project))
                                  :name (:name project)
                                  :isDefault (boolean (:is-default project))
                                  :fileCount (:count project)})
                               (remove :deleted-at projects)))))
              (rx/subs! resolve reject)))))))

(defn list-files
  [options]
  (let [project-id (uuid/parse* (obj/get options "projectId"))]
    (if (nil? project-id)
      (js/Promise.reject (js/Error. "Expected a project UUID"))
      (js/Promise.
       (fn [resolve reject]
         (->> (rp/cmd! :get-project-files {:project-id project-id})
              (rx/map (fn [files]
                        (clj->js
                         (mapv (fn [file]
                                 {:id (str (:id file))
                                  :teamId (str (:team-id file))
                                  :projectId (str (:project-id file))
                                  :name (:name file)
                                  :modifiedAt (.toISOString ^js (:modified-at file))})
                               files))))
              (rx/subs! resolve reject)))))))

(defn create-context
  []
  (obj/reify {:name "PenpotManagementContext"}
    :workspace
    {:get (fn [] (clj->js (workspace-context @st/state)))}
    :openFile open-file
    :listProjects list-projects
    :listFiles list-files))
