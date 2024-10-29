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
   [app.common.schema :as sm]
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
   [app.util.inet :as inet]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(def profile-props
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

(defn event-from-rpc-params
  "Create a base event skeleton with pre-filled some important
  data that can be extracted from RPC params object"
  [params]
  (let [context {:external-session-id (::rpc/external-session-id params)
                 :external-event-origin (::rpc/external-event-origin params)
                 :triggered-by (::rpc/handler-name params)}]
    {::type "action"
     ::profile-id (::rpc/profile-id params)
     ::ip-addr (::rpc/ip-addr params)
     ::context (d/without-nils context)}))

;; --- SPECS

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLLECTOR API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(def ^:private schema:event
  [:map {:title "event"}
   [::type ::sm/text]
   [::name ::sm/text]
   [::profile-id ::sm/uuid]
   [::ip-addr {:optional true} ::sm/text]
   [::props {:optional true} [:map-of :keyword :any]]
   [::context {:optional true} [:map-of :keyword :any]]
   [::webhooks/event? {:optional true} ::sm/boolean]
   [::webhooks/batch-timeout {:optional true} ::dt/duration]
   [::webhooks/batch-key {:optional true}
    [:or ::sm/fn ::sm/text :keyword]]])

(def ^:private check-event
  (sm/check-fn schema:event))

(defn prepare-event
  [cfg mdata params result]
  (let [resultm    (meta result)
        request    (-> params meta ::http/request)
        profile-id (or (::profile-id resultm)
                       (:profile-id result)
                       (::rpc/profile-id params)
                       uuid/zero)

        session-id   (get params ::rpc/external-session-id)
        event-origin (get params ::rpc/external-event-origin)
        props        (-> (or (::replace-props resultm)
                             (-> params
                                 (merge (::props resultm))
                                 (dissoc :profile-id)
                                 (dissoc :type)))

                         (clean-props))

        token-id  (::actoken/id request)
        context   (-> (::context resultm)
                      (assoc :external-session-id session-id)
                      (assoc :external-event-origin event-origin)
                      (assoc :access-token-id (some-> token-id str))
                      (d/without-nils))

        ip-addr   (inet/parse-request request)]

    {::type (or (::type resultm)
                (::rpc/type cfg))
     ::name (or (::name resultm)
                (::sv/name mdata))
     ::profile-id profile-id
     ::ip-addr ip-addr
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

(defn- event->params
  [event]
  (let [params {:id (uuid/next)
                :name (::name event)
                :type (::type event)
                :profile-id (::profile-id event)
                :ip-addr (::ip-addr event)
                :context (::context event {})
                :props (::props event {})
                :source "backend"}
        tnow   (::tracked-at event)]

    (cond-> params
      (some? tnow)
      (assoc :tracked-at tnow))))

(defn- append-audit-entry!
  [cfg params]
  (let [params (-> params
                   (update :props db/tjson)
                   (update :context db/tjson)
                   (update :ip-addr db/inet))]
    (db/insert! cfg :audit-log params)))

(defn- handle-event!
  [cfg event]
  (let [params (event->params event)
        tnow   (dt/now)]

    (when (contains? cf/flags :audit-log)
      ;; NOTE: this operation may cause primary key conflicts on inserts
      ;; because of the timestamp precission (two concurrent requests), in
      ;; this case we just retry the operation.
      (let [params (-> params
                       (assoc :created-at tnow)
                       (update :tracked-at #(or % tnow)))]
        (append-audit-entry! cfg params)))

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
                       (update :tracked-at #(or % tnow))
                       (assoc :props {})
                       (assoc :context {}))]
        (append-audit-entry! cfg params)))

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

        (wrk/submit! (-> cfg
                         (assoc ::wrk/task :process-webhook-event)
                         (assoc ::wrk/queue :webhooks)
                         (assoc ::wrk/max-retries 0)
                         (assoc ::wrk/delay (or batch-timeout 0))
                         (assoc ::wrk/dedupe dedupe?)
                         (assoc ::wrk/label label)
                         (assoc ::wrk/params (-> params
                                                 (dissoc :source)
                                                 (dissoc :context)
                                                 (dissoc :ip-addr)
                                                 (dissoc :type)))))))
    params))

(defn submit!
  "Submit audit event to the collector."
  [cfg event]
  (try
    (let [event (-> (d/without-nils event)
                    (check-event))
          cfg   (-> cfg
                    (assoc ::rtry/when rtry/conflict-exception?)
                    (assoc ::rtry/max-retries 6)
                    (assoc ::rtry/label "persist-audit-log"))]
      (rtry/invoke! cfg db/tx-run! handle-event! event))
    (catch Throwable cause
      (l/error :hint "unexpected error processing event" :cause cause))))

(defn insert!
  "Submit audit event to the collector, intended to be used only from
  command line helpers because this skips all webhooks and telemetry
  logic."
  [cfg event]
  (when (contains? cf/flags :audit-log)
    (let [event (-> (d/without-nils event)
                    (check-event))]
      (db/run! cfg (fn [cfg]
                     (let [tnow   (dt/now)
                           params (-> (event->params event)
                                      (assoc :created-at tnow)
                                      (update :tracked-at #(or % tnow)))]
                       (append-audit-entry! cfg params)))))))
