;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.telemetry
  "A task that is responsible to collect anonymous statistical
  information about the current instance and send it to the telemetry
  server."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.main :as-alias main]
   [app.util.json :as json]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK ENTRY POINT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare get-stats)
(declare send!)
(declare get-subscriptions-newsletter-updates)
(declare get-subscriptions-newsletter-news)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::http/client
                ::db/pool
                ::main/props]))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool ::main/props] :as cfg}]
  (fn [{:keys [send? enabled?] :or {send? true enabled? false}}]
    (let [subs     {:newsletter-updates (get-subscriptions-newsletter-updates pool)
                    :newsletter-news (get-subscriptions-newsletter-news pool)}
          enabled? (or enabled?
                       (contains? cf/flags :telemetry)
                       (cf/get :telemetry-enabled))

          data     {:subscriptions subs
                    :version (:full cf/version)
                    :instance-id (:instance-id props)}]
      (cond
        ;; If we have telemetry enabled, then proceed the normal
        ;; operation.
        enabled?
        (let [data (merge data (get-stats pool))]
          (when send?
            (px/sleep (rand-int 10000))
            (send! cfg data))
          data)

        ;; If we have telemetry disabled, but there are users that are
        ;; explicitly checked the newsletter subscription on the
        ;; onboarding dialog or the profile section, then proceed to
        ;; send a limited telemetry data, that consists in the list of
        ;; subscribed emails and the running penpot version.
        (seq subs)
        (do
          (when send?
            (px/sleep (rand-int 10000))
            (send! cfg data))
          data)

        :else
        data))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- send!
  [cfg data]
  (let [response (http/req! cfg
                            {:method :post
                             :uri (cf/get :telemetry-uri)
                             :headers {"content-type" "application/json"}
                             :body (json/encode-str data)}
                            {:sync? true})]
    (when (> (:status response) 206)
      (ex/raise :type :internal
                :code :invalid-response
                :response-status (:status response)
                :response-body (:body response)))))

(defn- get-subscriptions-newsletter-updates
  [conn]
  (let [sql "select email from profile where props->>'~:newsletter-updates' = 'true'"]
    (->> (db/exec! conn [sql])
         (mapv :email))))

(defn- get-subscriptions-newsletter-news
  [conn]
  (let [sql "select email from profile where props->>'~:newsletter-news' = 'true'"]
    (->> (db/exec! conn [sql])
         (mapv :email))))

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

(defn- retrieve-enabled-auth-providers
  [conn]
  (let [sql  (str "select auth_backend as backend, count(*) as total "
                 "  from profile group by 1")
        rows (db/exec! conn [sql])]
    (->> rows
         (map (fn [{:keys [backend total]}]
                (let [backend (or backend "penpot")]
                  [(keyword (str "auth-backend-" backend))
                   total])))
         (into {}))))

(defn- retrieve-jvm-stats
  []
  (let [^Runtime runtime (Runtime/getRuntime)]
    {:jvm-heap-current (.totalMemory runtime)
     :jvm-heap-max     (.maxMemory runtime)
     :jvm-cpus         (.availableProcessors runtime)
     :os-arch          (System/getProperty "os.arch")
     :os-name          (System/getProperty "os.name")
     :os-version       (System/getProperty "os.version")
     :user-tz          (System/getProperty "user.timezone")}))

(defn get-stats
  [conn]
  (let [referer (if (cf/get :telemetry-with-taiga)
                  "taiga"
                  (cf/get :telemetry-referer))]
    (-> {:referer        referer
         :public-uri     (cf/get :public-uri)
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
         (retrieve-jvm-stats)
         (retrieve-enabled-auth-providers conn))
        (d/without-nils))))

