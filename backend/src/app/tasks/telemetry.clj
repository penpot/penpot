;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.telemetry
  "A task that is responsible to collect anonymous statistical
  information about the current instance and send it to the telemetry
  server."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.async :refer [thread-sleep]]
   [app.util.http :as http]
   [app.util.json :as json]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK ENTRY POINT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare get-stats)
(declare send!)

(s/def ::version ::us/string)
(s/def ::uri ::us/string)
(s/def ::instance-id ::us/uuid)
(s/def ::sprops
  (s/keys :req-un [::instance-id]))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::version ::uri ::sprops]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool sprops version] :as cfg}]
  (fn [_]
    ;; Sleep randomly between 0 to 10s
    (thread-sleep (rand-int 10000))

    (let [instance-id (:instance-id sprops)]
      (-> (get-stats pool version)
          (assoc :instance-id instance-id)
          (send! cfg)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- send!
  [data cfg]
  (let [response (http/send! {:method :post
                              :uri (:uri cfg)
                              :headers {"content-type" "application/json"}
                              :body (json/write-str data)})]
    (when (> (:status response) 206)
      (ex/raise :type :internal
                :code :invalid-response
                :response-status (:status response)
                :response-body (:body response)))))

(defn- retrieve-num-teams
  [conn]
  (-> (db/exec-one! conn ["select count(*) as count from team;"]) :count))

(defn- retrieve-num-projects
  [conn]
  (-> (db/exec-one! conn ["select count(*) as count from project;"]) :count))

(defn- retrieve-num-files
  [conn]
  (-> (db/exec-one! conn ["select count(*) as count from file;"]) :count))

(defn- retrieve-num-file-changes
  [conn]
  (let [sql (str "select count(*) as count "
                 "  from file_change "
                 " where date_trunc('day', created_at) = date_trunc('day', now())")]
    (-> (db/exec-one! conn [sql]) :count)))

(defn- retrieve-num-touched-files
  [conn]
  (let [sql (str "select count(distinct file_id) as count "
                 "  from file_change "
                 " where date_trunc('day', created_at) = date_trunc('day', now())")]
    (-> (db/exec-one! conn [sql]) :count)))

(defn- retrieve-num-users
  [conn]
  (-> (db/exec-one! conn ["select count(*) as count from profile;"]) :count))

(defn- retrieve-num-fonts
  [conn]
  (-> (db/exec-one! conn ["select count(*) as count from team_font_variant;"]) :count))

(defn- retrieve-num-comments
  [conn]
  (-> (db/exec-one! conn ["select count(*) as count from comment;"]) :count))

(def sql:team-averages
  "with projects_by_team as (
     select t.id, count(p.id) as num_projects
       from team as t
       left join project as p on (p.team_id = t.id)
      group by 1
   ), files_by_project as (
     select p.id, count(f.id) as num_files
       from project as p
       left join file as f on (f.project_id = p.id)
      group by 1
   ), comment_threads_by_file as (
     select f.id, count(ct.id) as num_comment_threads
       from file as f
       left join comment_thread as ct on (ct.file_id = f.id)
      group by 1
   ), users_by_team as (
     select t.id, count(tp.profile_id) as num_users
       from team as t
       left join team_profile_rel as tp on(tp.team_id = t.id)
      group by 1
   )
   select (select avg(num_projects)::integer from projects_by_team) as avg_projects_on_team,
          (select max(num_projects)::integer from projects_by_team) as max_projects_on_team,
          (select avg(num_files)::integer from files_by_project) as avg_files_on_project,
          (select max(num_files)::integer from files_by_project) as max_files_on_project,
          (select avg(num_comment_threads)::integer from comment_threads_by_file) as avg_comment_threads_on_file,
          (select max(num_comment_threads)::integer from comment_threads_by_file) as max_comment_threads_on_file,
          (select avg(num_users)::integer from users_by_team) as avg_users_on_team,
          (select max(num_users)::integer from users_by_team) as max_users_on_team;")

(defn- retrieve-team-averages
  [conn]
  (->> [sql:team-averages]
       (db/exec-one! conn)))

(defn- retrieve-jvm-stats
  []
  (let [^Runtime runtime (Runtime/getRuntime)]
    {:jvm-heap-current (.totalMemory runtime)
     :jvm-heap-max     (.maxMemory runtime)
     :jvm-cpus         (.availableProcessors runtime)}))

(defn get-stats
  [conn version]
  (let [referer (if (cfg/get :telemetry-with-taiga)
                  "taiga"
                  (cfg/get :telemetry-referer))]
    (-> {:version        version
         :referer        referer
         :total-teams    (retrieve-num-teams conn)
         :total-projects (retrieve-num-projects conn)
         :total-files    (retrieve-num-files conn)
         :total-users    (retrieve-num-users conn)
         :total-fonts    (retrieve-num-fonts conn)
         :total-comments (retrieve-num-comments conn)
         :total-file-changes  (retrieve-num-file-changes conn)
         :total-touched-files (retrieve-num-touched-files conn)}
        (d/merge
         (retrieve-team-averages conn)
         (retrieve-jvm-stats))
        (d/without-nils))))

