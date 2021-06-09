;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.audit
  "Services related to the user activity (audit log)."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.util.async :as aa]
   [app.util.http :as http]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]))

(defn clean-props
  [{:keys [profile-id] :as event}]
  (letfn [(clean-common [props]
            (-> props
                (dissoc :session-id)
                (dissoc :password)
                (dissoc :old-password)
                (dissoc :token)))

          (clean-profile-id [props]
            (cond-> props
              (= profile-id (:profile-id props))
              (dissoc :profile-id)))

          (clean-complex-data [props]
            (reduce-kv (fn [props k v]
                         (cond-> props
                           (or (string? v)
                               (uuid? v)
                               (boolean? v)
                               (number? v))
                           (assoc k v)

                           (keyword? v)
                           (assoc k (name v))))
                       {}
                       props))]
    (update event :props #(-> % clean-common clean-profile-id clean-complex-data))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Collector
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(declare persist-events)
(s/def ::enabled ::us/boolean)

(defmethod ig/pre-init-spec ::collector [_]
  (s/keys :req-un [::db/pool ::wrk/executor ::enabled]))

(def event-xform
  (comp
   (filter :profile-id)
   (map clean-props)))

(defmethod ig/init-key ::collector
  [_ {:keys [enabled] :as cfg}]
  (when enabled
    (l/info :msg "intializing audit collector")
    (let [input  (a/chan 512 event-xform)
          buffer (aa/batch input {:max-batch-size 100
                                  :max-batch-age (* 10 1000) ; 10s
                                  :init []})]
      (a/go-loop []
        (when-let [[_type events] (a/<! buffer)]
          (let [res (a/<! (persist-events cfg events))]
            (when (ex/exception? res)
              (l/error :hint "error on persiting events"
                       :cause res)))
          (recur)))

      (fn [& [cmd & params]]
        (case cmd
          :stop (a/close! input)
          :submit (when-not (a/offer! input (first params))
                    (l/warn :msg "activity channel is full")))))))


(defn- persist-events
  [{:keys [pool executor] :as cfg} events]
  (letfn [(event->row [event]
            [(uuid/next)
             (:name event)
             (:type event)
             (:profile-id event)
             (some-> (:ip-addr event) db/inet)
             (db/tjson (:props event))])]

    (aa/with-thread executor
      (db/with-atomic [conn pool]
        (db/insert-multi! conn :audit-log
                          [:id :name :type :profile-id :ip-addr :props]
                          (sequence (map event->row) events))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Archive Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is a task responsible to send the accomulated events to an
;; external service for archival.

(declare archive-events)

(s/def ::uri ::us/string)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec ::archive-task [_]
  (s/keys :req-un [::db/pool ::tokens ::enabled]
          :opt-un [::uri]))

(defmethod ig/init-key ::archive-task
  [_ {:keys [uri enabled] :as cfg}]
  (fn [_]
    (when (and enabled (not uri))
      (ex/raise :type :internal
                :code :task-not-configured
                :hint "archive task not configured, missing uri"))
    (loop []
      (let [res (archive-events cfg)]
        (when (= res :continue)
          (aa/thread-sleep 200)
          (recur))))))

(def sql:retrieve-batch-of-audit-log
  "select * from audit_log
    where archived_at is null
    order by created_at asc
    limit 100
      for update skip locked;")

(defn archive-events
  [{:keys [pool uri tokens] :as cfg}]
  (letfn [(decode-row [{:keys [props ip-addr] :as row}]
            (cond-> row
              (db/pgobject? props)
              (assoc :props (db/decode-transit-pgobject props))

              (db/pgobject? ip-addr "inet")
              (assoc :ip-addr (db/decode-inet ip-addr))))

          (row->event [{:keys [name type created-at profile-id props ip-addr]}]
            (cond-> {:type type
                     :name name
                     :timestamp created-at
                     :profile-id profile-id
                     :props props}
              (some? ip-addr)
              (update :context assoc :source-ip ip-addr)))

          (send [events]
            (let [token   (tokens :generate {:iss "authentication"
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
                  resp    (http/send! params)]
              (when (not= (:status resp) 204)
                (ex/raise :type :internal
                          :code :unable-to-send-events
                          :hint "unable to send events"
                          :context resp))))

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
        (l/debug :action "archive-events" :uri uri :events (count events))
        (if (empty? events)
          :empty
          (do
            (send events)
            (mark-as-archived conn rows)
            :continue))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GC Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare clean-archived)

(s/def ::max-age ::cf/audit-archive-gc-max-age)

(defmethod ig/pre-init-spec ::archive-gc-task [_]
  (s/keys :req-un [::db/pool ::enabled ::max-age]))

(defmethod ig/init-key ::archive-gc-task
  [_ cfg]
  (fn [_]
    (clean-archived cfg)))

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
