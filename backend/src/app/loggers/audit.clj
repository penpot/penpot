;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.audit
  "Services related to the user activity (audit log)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as-alias actoken]
   [app.loggers.audit.tasks :as-alias tasks]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.retry :as rtry]
   [app.setup :as-alias setup]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [ring.request :as rreq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-client-ip
  [request]
  (or (some-> (rreq/get-header request "x-forwarded-for") (str/split ",") first)
      (rreq/get-header request "x-real-ip")
      (some-> (rreq/remote-addr request) str)))

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

(def reserved-props
  #{:session-id
    :password
    :old-password
    :token})

(defn clean-props
  [props]
  (into {}
        (comp
         (d/without-nils)
         (d/without-qualified)
         (remove #(contains? reserved-props (key %))))
        props))

;; --- SPECS


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLLECTOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(s/def ::profile-id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::type ::us/string)
(s/def ::props (s/map-of ::us/keyword any?))
(s/def ::ip-addr ::us/string)

(s/def ::webhooks/event? ::us/boolean)
(s/def ::webhooks/batch-timeout ::dt/duration)
(s/def ::webhooks/batch-key
  (s/or :fn fn? :str string? :kw keyword?))

(s/def ::event
  (s/keys :req [::type ::name ::profile-id]
          :opt [::ip-addr
                ::props
                ::webhooks/event?
                ::webhooks/batch-timeout
                ::webhooks/batch-key]))

(s/def ::collector
  (s/keys :req [::wrk/executor ::db/pool]))

(defmethod ig/pre-init-spec ::collector [_]
  (s/keys :req [::db/pool ::wrk/executor]))

(defmethod ig/init-key ::collector
  [_ {:keys [::db/pool] :as cfg}]
  (cond
    (db/read-only? pool)
    (l/warn :hint "audit disabled (db is read-only)")

    :else
    cfg))

(defn prepare-event
  [cfg mdata params result]
  (let [resultm    (meta result)
        request    (-> params meta ::http/request)
        profile-id (or (::profile-id resultm)
                       (:profile-id result)
                       (::rpc/profile-id params)
                       uuid/zero)

        props      (-> (or (::replace-props resultm)
                           (-> params
                               (merge (::props resultm))
                               (dissoc :profile-id)
                               (dissoc :type)))

                       (clean-props))

        token-id  (::actoken/id request)
        context   (-> (::context resultm)
                      (assoc :access-token-id (some-> token-id str))
                      (d/without-nils))]

    {::type (or (::type resultm)
                (::rpc/type cfg))
     ::name (or (::name resultm)
                (::sv/name mdata))
     ::profile-id profile-id
     ::ip-addr (some-> request parse-client-ip)
     ::props props
     ::context context

     ;; NOTE: for batch-key lookup we need the params as-is
     ;; because the rpc api does not need to know the
     ;; audit/webhook specific object layout.
     ::rpc/params params

     ::webhooks/batch-key
     (or (::webhooks/batch-key mdata)
         (::webhooks/batch-key resultm))

     ::webhooks/batch-timeout
     (or (::webhooks/batch-timeout mdata)
         (::webhooks/batch-timeout resultm))

     ::webhooks/event?
     (or (::webhooks/event? mdata)
         (::webhooks/event? resultm)
         false)}))

(defn- handle-event!
  [cfg event]
  (let [params {:id (uuid/next)
                :name (::name event)
                :type (::type event)
                :profile-id (::profile-id event)
                :ip-addr (::ip-addr event)
                :context (::context event)
                :props (::props event)}
        tnow   (dt/now)]

    (when (contains? cf/flags :audit-log)
      ;; NOTE: this operation may cause primary key conflicts on inserts
      ;; because of the timestamp precission (two concurrent requests), in
      ;; this case we just retry the operation.
      (let [params (-> params
                       (assoc :created-at tnow)
                       (assoc :tracked-at tnow)
                       (update :props db/tjson)
                       (update :context db/tjson)
                       (update :ip-addr db/inet)
                       (assoc :source "backend"))]
        (db/insert! cfg :audit-log params)))

    (when (and (or (contains? cf/flags :telemetry)
                   (cf/get :telemetry-enabled))
               (not (contains? cf/flags :audit-log)))
      ;; NOTE: this operation may cause primary key conflicts on inserts
      ;; because of the timestamp precission (two concurrent requests), in
      ;; this case we just retry the operation.
      ;;
      ;; NOTE: this is only executed when general audit log is disabled
      (let [params (-> params
                       (assoc :created-at tnow)
                       (assoc :tracked-at tnow)
                       (assoc :props (db/tjson {}))
                       (assoc :context (db/tjson {}))
                       (assoc :ip-addr (db/inet "0.0.0.0"))
                       (assoc :source "backend"))]
        (db/insert! cfg :audit-log params)))

    (when (and (contains? cf/flags :webhooks)
               (::webhooks/event? event))
      (let [batch-key     (::webhooks/batch-key event)
            batch-timeout (::webhooks/batch-timeout event)
            label         (dm/str "rpc:" (:name params))
            label         (cond
                            (ifn? batch-key)    (dm/str label ":" (batch-key (::rpc/params event)))
                            (string? batch-key) (dm/str label ":" batch-key)
                            :else               label)
            dedupe?       (boolean (and batch-key batch-timeout))]

        (wrk/submit! ::wrk/conn (::db/conn cfg)
                     ::wrk/task :process-webhook-event
                     ::wrk/queue :webhooks
                     ::wrk/max-retries 0
                     ::wrk/delay (or batch-timeout 0)
                     ::wrk/dedupe dedupe?
                     ::wrk/label label

                     ::webhooks/event
                     (-> params
                         (dissoc :ip-addr)
                         (dissoc :type)))))
    params))

(defn submit!
  "Submit audit event to the collector."
  [cfg params]
  (try
    (let [event (d/without-nils params)
          cfg   (-> cfg
                    (assoc ::rtry/when rtry/conflict-exception?)
                    (assoc ::rtry/max-retries 6)
                    (assoc ::rtry/label "persist-audit-log"))]
      (us/verify! ::event event)
      (rtry/invoke! cfg db/tx-run! handle-event! event))
    (catch Throwable cause
      (l/error :hint "unexpected error processing event" :cause cause))))
