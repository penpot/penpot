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
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.util.blob :as blob]
   [app.util.json :as json]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(defn- get-subscriptions
  [cfg]
  (let [sql "SELECT email FROM profile where props->>'~:newsletter-updates' = 'true'"]
    (db/run! cfg (fn [{:keys [::db/conn]}]
                   (->> (db/exec! conn [sql])
                        (mapv :email))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LEGACY DATA COLLECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn- get-email-domains
  [conn]
  (let [sql "SELECT DISTINCT split_part(email, '@', 2) AS domain FROM profile ORDER BY 1"]
    (->> (db/exec! conn [sql])
         (mapv :domain))))

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
    WHERE source LIKE 'telemetry:%'
      AND created_at >= date_trunc('day', now())
      AND created_at <  date_trunc('day', now()) + interval '1 day'
    GROUP BY 1
    ORDER BY 2 DESC")

(defn- get-action-counters
  [conn]
  (let [counters (->> (db/exec! conn [sql:get-counters])
                      (d/index-by (comp keyword :name) :count))
        total    (reduce + 0 (vals counters))]
    {:total-accomulated-events total
     :event-counters counters}))

(defn- get-legacy-stats
  [{:keys [::db/conn]}]
  (let [referer (if (cf/get :telemetry-with-taiga)
                  "taiga"
                  (cf/get :telemetry-referer))]
    (-> {:referer             referer
         :public-uri          (str (cf/get :public-uri))
         :total-teams         (get-num-teams conn)
         :total-projects      (get-num-projects conn)
         :total-files         (get-num-files conn)
         :total-users         (get-num-users conn)
         :total-fonts         (get-num-fonts conn)
         :total-comments      (get-num-comments conn)
         :total-file-changes  (get-num-file-changes conn)
         :total-touched-files (get-num-touched-files conn)
         :email-domains       (get-email-domains conn)}
        (merge
         (get-team-averages conn)
         (get-jvm-stats)
         (get-enabled-auth-providers conn)
         (get-action-counters conn))
        (d/without-nils))))

(defn- make-legacy-request
  [cfg data]
  (let [request {:method :post
                 :uri (cf/get :telemetry-uri)
                 :headers {"content-type" "application/json"}
                 :body (json/encode-str data)}
        response (http/req cfg request {:skip-ssrf-check? true})]
    (when (> (:status response) 206)
      (ex/raise :type :internal
                :code :invalid-response
                :response-status (:status response)
                :response-body (:body response)))))

(defn- send-legacy-data
  [{:keys [::setup/props] :as cfg} stats subs]
  (let [data (cond-> {:type        :telemetry-legacy-report
                      :version     (:full cf/version)
                      :instance-id (:instance-id props)}
               (some? stats)
               (assoc :stats stats)

               (seq subs)
               (assoc :subscriptions subs))]

    (make-legacy-request cfg data)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; AUDIT-EVENT BATCH (TELEMETRY MODE)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Telemetry events older than this are purged by the GC step so the
;; buffer stays bounded.
(def ^:private batch-size 10000)

(def ^:private sql:gc-events
  "DELETE FROM audit_log
    WHERE source LIKE 'telemetry:%'
      AND created_at < now() - interval '7 days'")

(defn- gc-events
  "Delete telemetry-mode events older than `telemetry-retention-days`
  so that the buffer stays bounded."
  [{:keys [::db/conn]}]
  (let [result (db/exec-one! conn [sql:gc-events])]
    (when (pos? (:next.jdbc/update-count result))
      (l/warn :hint "purged stale telemetry events"
              :count (:next.jdbc/update-count result)))))

(def ^:private sql:fetch-telemetry-events
  "SELECT id, name, type, source, tracked_at, profile_id, props, context
     FROM audit_log
    WHERE source LIKE 'telemetry:%'
    ORDER BY created_at ASC
    LIMIT ?")

(defn- row->event
  [{:keys [name type source tracked-at profile-id props context]}]
  (d/without-nils
   {:name       name
    :type       type
    :source     source
    :tracked-at tracked-at
    :profile-id profile-id
    :props      (or (some-> props db/decode-transit-pgobject) {})
    :context    (or (some-> context db/decode-transit-pgobject) {})}))

(defn- encode-batch
  "Encode a sequence of event maps into a fressian+zstd base64 string
  suitable for JSON transport."
  ^String [events]
  (blob/encode-str events {:version 4}))

(defn send-event-batch
  "Send a single batch of events to the telemetry endpoint. Returns
  true on success."
  [{:keys [::setup/props] :as cfg} batch]
  (let [payload {:type :telemetry-events
                 :version (:full cf/version)
                 :instance-id (:instance-id props)
                 :events (encode-batch batch)}
        request {:method  :post
                 :uri     (cf/get :telemetry-uri)
                 :headers {"content-type" "application/json"}
                 :body    (json/encode-str payload)}
        resp    (http/req cfg request {:skip-ssrf-check? true})]
    (if (<= (:status resp) 206)
      true
      (do
        (l/warn :hint "telemetry event batch send failed"
                :status (:status resp)
                :body   (:body resp))
        false))))

(defn- delete-sent-events
  "Delete rows by their ids after a successful send."
  [conn ids]
  (let [arr (db/create-array conn "uuid" ids)]
    (db/exec-one! conn ["DELETE FROM audit_log WHERE id = ANY(?)" arr])))

(defn- collect-and-send-audit-events
  "Collect anonymous telemetry-mode audit events and ship them to the
  telemetry endpoint in a loop. Each iteration fetches one page of
  `batch-size` rows, encodes and sends them, then deletes the rows on
   success. The loop stops as soon as a send returns false, leaving
   remaining rows intact for the next run."
  [{:keys [::db/conn] :as cfg}]
  (loop [counter 1]
    (when-let [rows (-> (db/exec! conn [sql:fetch-telemetry-events batch-size])
                        (not-empty))]
      (let [events (mapv row->event rows)
            ids    (mapv :id rows)]
        (l/dbg :hint "shipping telemetry event batch"
               :total (count events)
               :batch counter)
        (when (send-event-batch cfg events)
          (delete-sent-events conn ids)
          (recur (inc counter)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK ENTRY POINT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (http/client? (::http/client params)) "expected a valid http client")
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (some? (::setup/props params)) "expected setup props to be available"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [task]
    (let [params   (:props task)
          send?    (get params :send? true)
          enabled? (or (get params :enabled? false)
                       (contains? cf/flags :telemetry))
          subs     (get-subscriptions cfg)]


      ;; If we have telemetry enabled, then proceed the normal
      ;; operation sending legacy report

      (if enabled?
        (when send?
          (db/run! cfg gc-events)
          ;; Randomize start time to avoid thundering herd when multiple
          ;; instances restart at the same time.
          (px/sleep (rand-int 10000))

          (try
            (let [stats (db/run! cfg get-legacy-stats)]
              (send-legacy-data cfg stats subs))
            (catch Exception cause
              (l/wrn :hint "unable to send legacy report"
                     :cause cause)))

          ;; Ship any anonymous audit-log events accumulated in
          ;; telemetry mode (only when audit-log feature is off).
          (when-not (contains? cf/flags :audit-log)
            (try
              (db/run! cfg collect-and-send-audit-events)
              (catch Exception cause
                (l/wrn :hint "unable to send events"
                       :cause cause)))))

        ;; If we have telemetry disabled, but there are users that are
        ;; explicitly checked the newsletter subscription on the
        ;; onboarding dialog or the profile section, then proceed to
        ;; send a limited telemetry data, that consists in the list of
        ;; subscribed emails and the running penpot version.
        (when (and send? (seq subs))
          (px/sleep (rand-int 10000))
          (ex/ignoring
           (send-legacy-data cfg nil subs)))))))
