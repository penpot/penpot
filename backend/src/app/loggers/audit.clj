;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.audit
  "Services related to the user activity (audit log)."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit.tasks :as-alias tasks]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pxb]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-client-ip
  [request]
  (or (some-> (yrq/get-header request "x-forwarded-for") (str/split ",") first)
      (yrq/get-header request "x-real-ip")
      (some-> (yrq/remote-addr request) str)))

(defn extract-utm-params
  "Extracts additional data from params and namespace them under
  `penpot` ns."
  [params]
  (letfn [(process-param [params k v]
            (let [sk (d/name k)]
              (cond-> params
                (str/starts-with? sk "utm_")
                (assoc (->> sk str/kebab (keyword "penpot")) v)

                (str/starts-with? sk "mtm_")
                (assoc (->> sk str/kebab (keyword "penpot")) v))))]
    (reduce-kv process-param {} params)))


(def ^:private
  profile-props
  [:id
   :is-active
   :is-muted
   :auth-backend
   :email
   :default-team-id
   :default-project-id
   :fullname
   :lang])

(defn profile->props
  [profile]
  (-> profile
      (select-keys profile-props)
      (merge (:props profile))
      (d/without-nils)))

(defn clean-props
  [{:keys [profile-id] :as event}]
  (let [invalid-keys #{:session-id
                       :password
                       :old-password
                       :token}
        xform (comp
               (remove (fn [kv]
                         (qualified-keyword? (first kv))))
               (remove (fn [kv]
                         (contains? invalid-keys (first kv))))
               (remove (fn [[k v]]
                         (and (= k :profile-id)
                              (= v profile-id))))
               (filter (fn [[_ v]]
                         (or (string? v)
                             (keyword? v)
                             (uuid? v)
                             (boolean? v)
                             (number? v)))))]

    (update event :props #(into {} xform %))))

;; --- SPECS

(s/def ::profile-id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::type ::us/string)
(s/def ::props (s/map-of ::us/keyword any?))
(s/def ::timestamp dt/instant?)
(s/def ::context (s/map-of ::us/keyword any?))

(s/def ::frontend-event
  (s/keys :req-un [::type ::name ::props ::timestamp ::profile-id]
          :opt-un [::context]))

(s/def ::frontend-events (s/every ::frontend-event))

(s/def ::ip-addr ::us/string)
(s/def ::backend-event
  (s/keys :req-un [::type ::name ::profile-id]
          :opt-un [::ip-addr ::props]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::concurrency ::us/integer)

(defmethod ig/pre-init-spec ::http-handler [_]
  (s/keys :req [::wrk/executor ::db/pool ::mtx/metrics ::concurrency]))

(defmethod ig/prep-key ::http-handler
  [_ cfg]
  (merge {::concurrency (cf/get :audit-log-http-handler-concurrency 8)}
         (d/without-nils cfg)))

(defmethod ig/init-key ::http-handler
  [_ {:keys [::wrk/executor ::db/pool ::mtx/metrics ::concurrency] :as cfg}]
  (if (or (db/read-only? pool)
          (not (contains? cf/flags :audit-log)))
    (do
      (l/warn :hint "audit: http handler disabled or db is read-only")
      (fn [_ respond _]
        (respond (yrs/response 204))))

    (letfn [(event->row [event]
              [(uuid/next)
               (:name event)
               (:source event)
               (:type event)
               (:timestamp event)
               (:profile-id event)
               (db/inet (:ip-addr event))
               (db/tjson (:props event))
               (db/tjson (d/without-nils (:context event)))])

            (handle-request [{:keys [profile-id] :as request}]
              (let [events  (->> (:events (:params request))
                                 (remove #(not= profile-id (:profile-id %)))
                                 (us/conform ::frontend-events))
                    ip-addr (parse-client-ip request)
                    xform   (comp
                             (map #(assoc % :ip-addr ip-addr))
                             (map #(assoc % :source "frontend"))
                             (map event->row))

                    columns [:id :name :source :type :tracked-at
                             :profile-id :ip-addr :props :context]]
                (when (seq events)
                  (->> (into [] xform events)
                       (db/insert-multi! pool :audit-log columns)))))

            (report-error! [cause]
              (if-let [xdata (us/validation-error? cause)]
                (l/error ::l/raw (str "audit: validation error frontend events request\n" (ex/explain xdata)))
                (l/error :hint "audit: unexpected error on processing frontend events" :cause cause)))

            (on-queue [instance]
              (l/trace :hint "http-handler: enqueued"
                       :queue-size (get instance ::pxb/current-queue-size)
                       :concurrency (get instance ::pxb/current-concurrency))
              (mtx/run! metrics
                        :id :audit-http-handler-queue-size
                        :val (get instance ::pxb/current-queue-size))
              (mtx/run! metrics
                        :id :audit-http-handler-concurrency
                        :val (get instance ::pxb/current-concurrency)))

            (on-run [instance task]
              (let [elapsed (- (inst-ms (dt/now))
                               (inst-ms task))]
                (l/trace :hint "http-handler: execute"
                         :elapsed (str elapsed "ms"))
                (mtx/run! metrics
                          :id :audit-http-handler-timing
                          :val elapsed)
                (mtx/run! metrics
                          :id :audit-http-handler-queue-size
                          :val (get instance ::pxb/current-queue-size))
                (mtx/run! metrics
                          :id :audit-http-handler-concurrency
                          :val (get instance ::pxb/current-concurrency))))]

      (let [limiter (pxb/create :executor executor
                                :concurrency concurrency
                                :on-queue on-queue
                                :on-run on-run)]
        (fn [request respond _]
          (->> (px/submit! limiter (partial handle-request request))
               (p/fnly (fn [_ cause]
                         (some-> cause report-error!)
                         (respond (yrs/response 204))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLLECTOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(s/def ::collector
  (s/nilable
   (s/keys :req [::wrk/executor ::db/pool])))

(defmethod ig/pre-init-spec ::collector [_]
  (s/keys :req [::db/pool ::wrk/executor ::mtx/metrics]))

(defmethod ig/init-key ::collector
  [_ {:keys [::db/pool] :as cfg}]
  (cond
    (not (contains? cf/flags :audit-log))
    (l/info :hint "audit: log collection disabled")

    (db/read-only? pool)
    (l/warn :hint "audit: log collection disabled (db is read-only)")

    :else
    cfg))

(defn- persist-event!
  [pool event]
  (us/verify! ::backend-event event)
  (db/insert! pool :audit-log
              {:id (uuid/next)
               :name (:name event)
               :type (:type event)
               :profile-id (:profile-id event)
               :tracked-at (dt/now)
               :ip-addr (some-> (:ip-addr event) db/inet)
               :props (db/tjson (:props event))
               :source "backend"}))

(defn submit!
  "Submit audit event to the collector."
  [{:keys [::wrk/executor ::db/pool]} params]
  (->> (px/submit! executor (partial persist-event! pool (d/without-nils params)))
       (p/merr (fn [cause]
                 (l/error :hint "audit: unexpected error processing event" :cause cause)
                 (p/resolved nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK: ARCHIVE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is a task responsible to send the accumulated events to
;; external service for archival.

(declare archive-events)

(s/def ::tasks/uri ::us/string)

(defmethod ig/pre-init-spec ::tasks/archive-task [_]
  (s/keys :req [::db/pool ::main/props ::http/client]))

(defmethod ig/init-key ::tasks/archive
  [_ cfg]
  (fn [params]
    ;; NOTE: this let allows overwrite default configured values from
    ;; the repl, when manually invoking the task.
    (let [enabled (or (contains? cf/flags :audit-log-archive)
                      (:enabled params false))
          uri     (cf/get :audit-log-archive-uri)
          uri     (or uri (:uri params))
          cfg     (assoc cfg ::uri uri)]

      (when (and enabled (not uri))
        (ex/raise :type :internal
                  :code :task-not-configured
                  :hint "archive task not configured, missing uri"))

      (when enabled
        (loop [total 0]
          (let [n (archive-events cfg)]
            (if n
              (do
                (px/sleep 100)
                (recur (+ total n)))
              (when (pos? total)
                (l/debug :hint "events archived" :total total)))))))))

(def ^:private sql:retrieve-batch-of-audit-log
  "select *
     from audit_log
    where archived_at is null
    order by created_at asc
    limit 256
      for update skip locked;")

(defn archive-events
  [{:keys [::db/pool ::uri] :as cfg}]
  (letfn [(decode-row [{:keys [props ip-addr context] :as row}]
            (cond-> row
              (db/pgobject? props)
              (assoc :props (db/decode-transit-pgobject props))

              (db/pgobject? context)
              (assoc :context (db/decode-transit-pgobject context))

              (db/pgobject? ip-addr "inet")
              (assoc :ip-addr (db/decode-inet ip-addr))))

          (row->event [row]
            (select-keys row [:type
                              :name
                              :source
                              :created-at
                              :tracked-at
                              :profile-id
                              :ip-addr
                              :props
                              :context]))

          (send [events]
            (let [token   (tokens/generate (::main/props cfg)
                                           {:iss "authentication"
                                            :iat (dt/now)
                                            :uid uuid/zero})
                  ;; FIXME tokens/generate
                  body    (t/encode {:events events})
                  headers {"content-type" "application/transit+json"
                           "origin" (cf/get :public-uri)
                           "cookie" (u/map->query-string {:auth-token token})}
                  params  {:uri uri
                           :timeout 6000
                           :method :post
                           :headers headers
                           :body body}
                  resp    (http/req! cfg params {:sync? true})]
              (if (= (:status resp) 204)
                true
                (do
                  (l/error :hint "unable to archive events"
                           :resp-status (:status resp)
                           :resp-body (:body resp))
                  false))))

          (mark-as-archived [conn rows]
            (db/exec-one! conn ["update audit_log set archived_at=now() where id = ANY(?)"
                                (->> (map :id rows)
                                     (into-array java.util.UUID)
                                     (db/create-array conn "uuid"))]))]

    (db/with-atomic [conn pool]
      (let [rows   (db/exec! conn [sql:retrieve-batch-of-audit-log])
            xform  (comp (map decode-row)
                         (map row->event))
            events (into [] xform rows)]
        (when-not (empty? events)
          (l/trace :hint "archive events chunk" :uri uri :events (count events))
          (when (send events)
            (mark-as-archived conn rows)
            (count events)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GC Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:clean-archived
  "delete from audit_log
    where archived_at is not null")

(defn- clean-archived
  [{:keys [pool]}]
  (let [result (db/exec-one! pool [sql:clean-archived])
        result (:next.jdbc/update-count result)]
    (l/debug :hint "delete archived audit log entries" :deleted result)
    result))

(defmethod ig/pre-init-spec ::tasks/gc [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::tasks/gc
  [_ cfg]
  (fn [_]
    (clean-archived cfg)))
