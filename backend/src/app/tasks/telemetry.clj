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
   [app.setup :as-alias setup]
   [app.util.json :as json]
   [integrant.core :as ig]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- send!
  [cfg data]
  (let [request {:method :post
                 :uri (cf/get :telemetry-uri)
                 :headers {"content-type" "application/json"}
                 :body (json/encode-str data)}
        response (http/req! cfg request)]
    (when (> (:status response) 206)
      (ex/raise :type :internal
                :code :invalid-response
                :response-status (:status response)
                :response-body (:body response)))))

(defn- get-subscriptions-newsletter-updates
  [conn]
  (let [sql "SELECT email FROM profile where props->>'~:newsletter-updates' = 'true'"]
    (->> (db/exec! conn [sql])
         (mapv :email))))

(defn- get-subscriptions-newsletter-news
  [conn]
  (let [sql "SELECT email FROM profile where props->>'~:newsletter-news' = 'true'"]
    (->> (db/exec! conn [sql])
         (mapv :email))))

(defn- get-num-teams
  [conn]
  (-> (db/exec-one! conn ["SELECT count(*) AS count FROM team"]) :count))

(defn- get-num-projects
  [conn]
  (-> (db/exec-one! conn ["SELECT count(*) AS count FROM project"]) :count))

(defn- get-num-files
  [conn]
  (-> (db/exec-one! conn ["SELECT count(*) AS count FROM file"]) :count))

(def ^:private sql:num-file-changes
  "SELECT count(*) AS count
     FROM file_change
    WHERE created_at < date_trunc('day', now()) + '24 hours'::interval
      AND created_at > date_trunc('day', now())")

(defn- get-num-file-changes
  [conn]
  (-> (db/exec-one! conn [sql:num-file-changes]) :count))

(def ^:private sql:num-touched-files
  "SELECT count(distinct file_id) AS count
     FROM file_change
    WHERE created_at < date_trunc('day', now()) + '24 hours'::interval
      AND created_at > date_trunc('day', now())")

(defn- get-num-touched-files
  [conn]
  (-> (db/exec-one! conn [sql:num-touched-files]) :count))

(defn- get-num-users
  [conn]
  (-> (db/exec-one! conn ["SELECT count(*) AS count FROM profile"]) :count))

(defn- get-num-fonts
  [conn]
  (-> (db/exec-one! conn ["SELECT count(*) AS count FROM team_font_variant"]) :count))

(defn- get-num-comments
  [conn]
  (-> (db/exec-one! conn ["SELECT count(*) AS count FROM comment"]) :count))

(def sql:team-averages
  "with projects_by_team AS (
     SELECT t.id, count(p.id) AS num_projects
       FROM team AS t
       LEFT JOIN project AS p ON (p.team_id = t.id)
      GROUP BY 1
   ), files_by_project AS (
     SELECT p.id, count(f.id) AS num_files
       FROM project AS p
       LEFT JOIN file AS f ON (f.project_id = p.id)
      GROUP BY 1
   ), comment_threads_by_file AS (
     SELECT f.id, count(ct.id) AS num_comment_threads
       FROM file AS f
       LEFT JOIN comment_thread AS ct ON (ct.file_id = f.id)
      GROUP BY 1
   ), users_by_team AS (
     SELECT t.id, count(tp.profile_id) AS num_users
       FROM team AS t
       LEFT JOIN team_profile_rel AS tp ON(tp.team_id = t.id)
      GROUP BY 1
   )
   SELECT (SELECT avg(num_projects)::integer FROM projects_by_team) AS avg_projects_on_team,
          (SELECT max(num_projects)::integer FROM projects_by_team) AS max_projects_on_team,
          (SELECT avg(num_files)::integer FROM files_by_project) AS avg_files_on_project,
          (SELECT max(num_files)::integer FROM files_by_project) AS max_files_on_project,
          (SELECT avg(num_comment_threads)::integer FROM comment_threads_by_file) AS avg_comment_threads_on_file,
          (SELECT max(num_comment_threads)::integer FROM comment_threads_by_file) AS max_comment_threads_on_file,
          (SELECT avg(num_users)::integer FROM users_by_team) AS avg_users_on_team,
          (SELECT max(num_users)::integer FROM users_by_team) AS max_users_on_team")

(defn- get-team-averages
  [conn]
  (->> [sql:team-averages]
       (db/exec-one! conn)))

(defn- get-enabled-auth-providers
  [conn]
  (let [sql  (str "SELECT auth_backend AS backend, count(*) AS total "
                  "  FROM profile GROUP BY 1")
        rows (db/exec! conn [sql])]
    (->> rows
         (map (fn [{:keys [backend total]}]
                (let [backend (or backend "penpot")]
                  [(keyword (str "auth-backend-" backend))
                   total])))
         (into {}))))

(defn- get-jvm-stats
  []
  (let [^Runtime runtime (Runtime/getRuntime)]
    {:jvm-heap-current (.totalMemory runtime)
     :jvm-heap-max     (.maxMemory runtime)
     :jvm-cpus         (.availableProcessors runtime)
     :os-arch          (System/getProperty "os.arch")
     :os-name          (System/getProperty "os.name")
     :os-version       (System/getProperty "os.version")
     :user-tz          (System/getProperty "user.timezone")}))

(def ^:private sql:get-counters
  "SELECT name, count(*) AS count
     FROM audit_log
    WHERE source = 'backend'
      AND tracked_at >= date_trunc('day', now())
    GROUP BY 1
    ORDER BY 2 DESC")

(defn- get-action-counters
  [conn]
  (let [counters (->> (db/exec! conn [sql:get-counters])
                      (d/index-by (comp keyword :name) :count))
        total    (reduce + 0 (vals counters))]
    {:total-accomulated-events total
     :event-counters counters}))

(def ^:private sql:clean-counters
  "DELETE FROM audit_log
    WHERE ip_addr = '0.0.0.0'::inet -- we know this is from telemetry
      AND tracked_at < (date_trunc('day', now()) - '1 day'::interval)")

(defn- clean-counters-data!
  [conn]
  (when-not (contains? cf/flags :audit-log)
    (db/exec-one! conn [sql:clean-counters])))

(defn- get-stats
  [conn]
  (let [referer (if (cf/get :telemetry-with-taiga)
                  "taiga"
                  (cf/get :telemetry-referer))]
    (-> {:referer             referer
         :public-uri          (cf/get :public-uri)
         :total-teams         (get-num-teams conn)
         :total-projects      (get-num-projects conn)
         :total-files         (get-num-files conn)
         :total-users         (get-num-users conn)
         :total-fonts         (get-num-fonts conn)
         :total-comments      (get-num-comments conn)
         :total-file-changes  (get-num-file-changes conn)
         :total-touched-files (get-num-touched-files conn)}
        (merge
         (get-team-averages conn)
         (get-jvm-stats)
         (get-enabled-auth-providers conn)
         (get-action-counters conn))
        (d/without-nils))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK ENTRY POINT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (http/client? (::http/client params)) "expected a valid http client")
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (some? (::setup/props params)) "expected setup props to be available"))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool ::setup/props] :as cfg}]
  (fn [task]
    (let [params   (:props task)
          send?    (get params :send? true)
          enabled? (or (get params :enabled? false)
                       (contains? cf/flags :telemetry)
                       (cf/get :telemetry-enabled))

          subs     {:newsletter-updates (get-subscriptions-newsletter-updates pool)
                    :newsletter-news (get-subscriptions-newsletter-news pool)}

          data     {:subscriptions subs
                    :version (:full cf/version)
                    :instance-id (:instance-id props)}]

      (when enabled?
        (clean-counters-data! pool))

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
        (or (seq (:newsletter-updates subs))
            (seq (:newsletter-news subs)))
        (do
          (when send?
            (px/sleep (rand-int 10000))
            (send! cfg data))
          data)

        :else
        data))))
