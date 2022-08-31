;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

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
   [app.tokens :as tokens]
   [app.util.async :as aa]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

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

(defn profile->props
  [profile]
  (-> profile
      (select-keys [:id :is-active :is-muted :auth-backend :email :default-team-id :default-project-id :fullname :lang])
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare persist-http-events)

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

(defmethod ig/init-key ::http-handler
  [_  {:keys [executor pool] :as cfg}]
  (if (or (db/read-only? pool) (not (contains? cf/flags :audit-log)))
    (do
      (l/warn :hint "audit log http handler disabled or db is read-only")
      (fn [_ respond _]
        (respond (yrs/response 204))))

    (letfn [(handler [{:keys [profile-id] :as request}]
              (let [events  (->> (:events (:params request))
                                 (remove #(not= profile-id (:profile-id %)))
                                 (us/conform ::frontend-events))

                    ip-addr (parse-client-ip request)
                    cfg     (-> cfg
                                (assoc :source "frontend")
                                (assoc :events events)
                                (assoc :ip-addr ip-addr))]
                (persist-http-events cfg)))

            (handle-error [cause]
              (let [xdata (ex-data cause)]
                (if (= :spec-validation (:code xdata))
                  (l/error ::l/raw (str "spec validation on persist-events:\n" (us/pretty-explain xdata)))
                  (l/error :hint "error on persist-events" :cause cause))))]

      (fn [request respond _]
        ;; Fire and forget, log error in case of errro
        (-> (px/submit! executor #(handler request))
            (p/catch handle-error))

        (respond (yrs/response 204))))))

(defn- persist-http-events
  [{:keys [pool events ip-addr source] :as cfg}]
  (let [columns    [:id :name :source :type :tracked-at :profile-id :ip-addr :props :context]
        prepare-xf (map (fn [event]
                          [(uuid/next)
                           (:name event)
                           source
                           (:type event)
                           (:timestamp event)
                           (:profile-id event)
                           (db/inet ip-addr)
                           (db/tjson (:props event))
                           (db/tjson (d/without-nils (:context event)))]))]
    (when (seq events)
      (->> (into [] prepare-xf events)
           (db/insert-multi! pool :audit-log columns)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collector
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(declare persist-events)

(defmethod ig/pre-init-spec ::collector [_]
  (s/keys :req-un [::db/pool ::wrk/executor]))

(s/def ::ip-addr string?)
(s/def ::backend-event
  (s/keys :req-un [::type ::name ::profile-id]
          :opt-un [::ip-addr ::props]))

(def ^:private backend-event-xform
  (comp
   (filter #(us/valid? ::backend-event %))
   (map clean-props)))

(defmethod ig/init-key ::collector
  [_ {:keys [pool] :as cfg}]
  (cond
    (not (contains? cf/flags :audit-log))
    (do
      (l/info :hint "audit log collection disabled")
      (constantly nil))

    (db/read-only? pool)
    (do
      (l/warn :hint "audit log collection disabled, db is read-only")
      (constantly nil))

    :else
    (let [input  (a/chan 512 backend-event-xform)
          buffer (aa/batch input {:max-batch-size 100
                                  :max-batch-age (* 10 1000) ; 10s
                                  :init []})]
      (l/info :hint "audit log collector initialized")
      (a/go-loop []
        (when-let [[_type events] (a/<! buffer)]
          (let [res (a/<! (persist-events cfg events))]
            (when (ex/exception? res)
              (l/error :hint "error on persisting events" :cause res))
            (recur))))

      (fn [& {:keys [cmd] :as params}]
        (case cmd
          :stop
          (a/close! input)

          :submit
          (let [params (-> params
                           (dissoc :cmd)
                           (assoc :tracked-at (dt/now)))]
            (when-not (a/offer! input params)
              (l/warn :hint "activity channel is full"))))))))

(defn- persist-events
  [{:keys [pool executor] :as cfg} events]
  (letfn [(event->row [event]
            [(uuid/next)
             (:name event)
             (:type event)
             (:profile-id event)
             (:tracked-at event)
             (some-> (:ip-addr event) db/inet)
             (db/tjson (:props event))
             "backend"])]
    (aa/with-thread executor
      (when (seq events)
        (db/with-atomic [conn pool]
          (db/insert-multi! conn :audit-log
                            [:id :name :type :profile-id :tracked-at :ip-addr :props :source]
                            (sequence (keep event->row) events)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Archive Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is a task responsible to send the accumulated events to an
;; external service for archival.

(declare archive-events)

(s/def ::http-client fn?)
(s/def ::uri ::us/string)
(s/def ::sprops map?)

(defmethod ig/pre-init-spec ::archive-task [_]
  (s/keys :req-un [::db/pool ::sprops ::http-client]
          :opt-un [::uri]))

(defmethod ig/init-key ::archive-task
  [_ {:keys [uri] :as cfg}]
  (fn [props]
    ;; NOTE: this let allows overwrite default configured values from
    ;; the repl, when manually invoking the task.
    (let [enabled (or (contains? cf/flags :audit-log-archive)
                      (:enabled props false))
          uri     (or uri (:uri props))
          cfg     (assoc cfg :uri uri)]

      (when (and enabled (not uri))
        (ex/raise :type :internal
                  :code :task-not-configured
                  :hint "archive task not configured, missing uri"))

      (when enabled
        (loop [total 0]
          (let [n (archive-events cfg)]
            (if n
              (do
                (aa/thread-sleep 200)
                (recur (+ total n)))
              (when (pos? total)
                (l/trace :hint "events chunk archived" :num total)))))))))

(def sql:retrieve-batch-of-audit-log
  "select * from audit_log
    where archived_at is null
    order by created_at asc
    limit 256
      for update skip locked;")

(defn archive-events
  [{:keys [pool uri sprops http-client] :as cfg}]
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
            (let [token   (tokens/generate sprops {:iss "authentication"
                                                   :iat (dt/now)
                                                   :uid uuid/zero})
                  body    (t/encode {:events events})
                  headers {"content-type" "application/transit+json"
                           "origin" (cf/get :public-uri)
                           "cookie" (u/map->query-string {:auth-token token})}
                  params  {:uri uri
                           :timeout 6000
                           :method :post
                           :headers headers
                           :body body}
                  resp    (http-client params {:sync? true})]
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
          (l/debug :action "archive-events" :uri uri :events (count events))
          (when (send events)
            (mark-as-archived conn rows)
            (count events)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GC Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:clean-archived
  "delete from audit_log
    where archived_at is not null
      and archived_at < now() - ?::interval")

(defn- clean-archived
  [{:keys [pool max-age]}]
  (let [interval (db/interval max-age)
        result   (db/exec-one! pool [sql:clean-archived interval])
        result   (:next.jdbc/update-count result)]
    (l/debug :action "clean archived audit log" :removed result)
    result))

(s/def ::max-age ::cf/audit-log-gc-max-age)

(defmethod ig/pre-init-spec ::gc-task [_]
  (s/keys :req-un [::db/pool ::max-age]))

(defmethod ig/init-key ::gc-task
  [_ cfg]
  (fn [_]
    (clean-archived cfg)))
