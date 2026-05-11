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
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as email]
   [app.http :as-alias http]
   [app.http.access-token :as-alias actoken]
   [app.loggers.audit.tasks :as-alias tasks]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [app.util.inet :as inet]
   [app.util.services :as-alias sv]
   [app.worker :as wrk]
   [cuerdas.core :as str]
   [yetti.request :as yreq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private filter-auth-events
  #{"login-with-oidc" "login-with-password" "register-profile" "update-profile"})

(def ^:private safe-backend-context-keys
  #{:version
    :initiator
    :client-version
    :client-user-agent})

(def ^:private safe-frontend-context-keys
  #{:version
    :locale
    :browser
    :browser-version
    :engine
    :engine-version
    :os
    :os-version
    :device-type
    :device-arch
    :screen-width
    :screen-height
    :screen-color-depth
    :screen-orientation
    :event-origin
    :event-namespace
    :event-symbol})

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

(def ^:private event-keys
  #{:id
    :name
    :type
    :profile-id
    :ip-addr
    :props
    :context
    :source
    :tracked-at
    :created-at})

(def reserved-props
  #{:session-id
    :password
    :old-password
    :token})

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
      (select-keys profile-props)
      (merge (:props profile))
      (d/without-nils)))

(defn clean-props
  [props]
  (into {}
        (comp
         (d/without-nils)
         (d/without-qualified)
         (remove #(contains? reserved-props (key %))))
        props))

(defn get-external-session-id
  [request]
  (when-let [session-id (yreq/get-header request "x-external-session-id")]
    (when-not (or (> (count session-id) 256)
                  (= session-id "null")
                  (str/blank? session-id))
      session-id)))

(defn- get-client-event-origin
  [request]
  (when-let [origin (yreq/get-header request "x-event-origin")]
    (when-not (or (= origin "null")
                  (str/blank? origin))
      (str/prune origin 200))))

(defn get-client-user-agent
  [request]
  (when-let [user-agent (yreq/get-header request "user-agent")]
    (str/prune user-agent 500)))

(defn- get-client-version
  [request]
  (when-let [origin (yreq/get-header request "x-frontend-version")]
    (when-not (or (= origin "null")
                  (str/blank? origin))
      (str/prune origin 100))))

;; --- SPECS

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLLECTOR API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private prepare-context-from-request)

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(def ^:private schema:event
  [:map {:title "AuditEvent"}
   [:id {:optional true} ::sm/uuid]
   [:type ::sm/text]
   [:name ::sm/text]
   [:profile-id ::sm/uuid]
   [:props [:map-of :keyword :any]]
   [:context [:map-of :keyword :any]]
   [:tracked-at ::ct/inst]
   [:created-at ::ct/inst]
   [:source ::sm/text]
   [:ip-addr {:optional true} ::sm/text]
   [::webhooks/event? {:optional true} ::sm/boolean]
   [::webhooks/batch-timeout {:optional true} ::ct/duration]
   [::webhooks/batch-key {:optional true}
    [:or ::sm/fn ::sm/text :keyword]]])

(def ^:private check-event
  (sm/check-fn schema:event))

(def valid-event?
  (sm/validator schema:event))

(defn- prepare-context-from-request
  "Prepare backend event context from request"
  [request]
  (let [client-event-origin (get-client-event-origin request)
        client-version      (get-client-version request)
        client-user-agent   (get-client-user-agent request)
        session-id          (get-external-session-id request)
        key-id              (::http/auth-key-id request)
        token-id            (::actoken/id request)
        token-type          (::actoken/type request)]
    {:external-session-id session-id
     :initiator (or key-id "app")
     :access-token-id (some-> token-id str)
     :access-token-type (some-> token-type str)
     :client-event-origin client-event-origin
     :client-user-agent client-user-agent
     :client-version client-version
     :version (:full cf/version)}))

(defn- append-audit-entry
  [cfg params]
  (let [params (-> params
                   (assoc :id (uuid/next))
                   (update :props db/tjson)
                   (update :context db/tjson)
                   (update :ip-addr db/inet))
        params (select-keys params event-keys)]
    (db/insert! cfg :audit-log params)))

(def ^:private xf:filter-telemetry-props
  "Transducer that keeps only map entries whose values are UUIDs,
  booleans or numbers."
  (filter (fn [[k v]]
            (and (simple-keyword? k)
                 (or (uuid? v) (boolean? v) (number? v))))))

(declare filter-telemetry-props)
(declare filter-telemetry-context)

(defn- process-event
  [cfg event]
  (when (contains? cf/flags :audit-log-logger)
    (l/log! ::l/logger "app.audit"
            ::l/level :info
            :profile-id (str (:profile-id event))
            :ip-addr (str (:ip-addr event))
            :type (:type event)
            :name (:name event)
            :props (json/encode (:props event) :key-fn json/write-camel-key)
            :context (json/encode (:context event) :key-fn json/write-camel-key)))

  (when (contains? cf/flags :audit-log)
    (append-audit-entry cfg event))

  (when (contains? cf/flags :telemetry)
    ;; NOTE: when both audit-log and telemetry are enabled, events are stored
    ;; twice: once with full details (above) and once stripped of props and
    ;; ip-addr, tagged with source="telemetry" so the telemetry task can
    ;; collect and ship them.  The profile-id is preserved (UUIDs are already
    ;; anonymous random identifiers).  Only a safe subset of context fields
    ;; is kept: initiator, version, client-version and client-user-agent.
    ;; Timestamps are truncated to day precision to avoid leaking exact event
    ;; timing.
    (let [event (-> event
                    (filter-telemetry-props)
                    (filter-telemetry-context)
                    (update :created-at ct/truncate :days)
                    (update :tracked-at ct/truncate :days)
                    (assoc :source "telemetry:backend")
                    (assoc :ip-addr "0.0.0.0"))]
      (append-audit-entry cfg event)))

  (when (and (contains? cf/flags :webhooks)
             (::webhooks/event? event))
    (let [batch-key     (::webhooks/batch-key event)
          batch-timeout (::webhooks/batch-timeout event)
          label         (dm/str "rpc:" (:name event))
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
                       (assoc ::wrk/params (-> event
                                               (dissoc :source)
                                               (dissoc :context)
                                               (dissoc :ip-addr)
                                               (dissoc :type)))))))
  event)

(defn submit*
  "A public API, lower-level than submit, assumes all required fields are filled"
  [cfg event]
  (try
    (let [event (check-event event)]
      (db/tx-run! cfg process-event event))
    (catch Throwable cause
      (l/error :hint "unexpected error processing event" :cause cause))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn filter-telemetry-props
  [{:keys [source name props type] :as params}]
  (cond
    (or (and (= source "frontend")
             (= type "identify"))
        (and (= source "backend")
             (filter-auth-events name)))

    (let [props' (into {} xf:filter-telemetry-props props)
          props' (-> props'
                     (assoc :lang (:lang props))
                     (assoc :auth-backend (:auth-backend props))
                     (assoc :email-domain (email/get-domain (:email props)))
                     (d/without-nils))]
      (assoc params :props props'))

    (and (= source "backend")
         (= type "trigger")
         (= name "instance-start"))
    params

    (and (= source "frontend")
         (= type "action")
         (= name "navigate"))
    (assoc params :props (select-keys props [:route :file-id :team-id :page-id]))

    :else
    (let [props (into {} xf:filter-telemetry-props props)]
      (assoc params :props props))))

(defn filter-telemetry-context
  [{:keys [source context] :as params}]
  (let [context (case source
                  "backend"  (select-keys context safe-backend-context-keys)
                  "frontend" (select-keys context safe-frontend-context-keys)
                  {})]
    (assoc params :context context)))

(defn prepare-rpc-event
  [cfg mdata params result]
  (let [resultm      (meta result)
        request      (-> params meta ::http/request)
        profile-id   (or (::profile-id resultm)
                         (:profile-id result)
                         (::rpc/profile-id params)
                         uuid/zero)

        props        (-> (or (::replace-props resultm)
                             (merge params (::props resultm)))
                         (clean-props))

        context      (-> (::context resultm)
                         (merge (prepare-context-from-request request))
                         (assoc :request-id (::rpc/request-id params))
                         (d/without-nils))

        ip-addr      (inet/parse-request request)
        module       (get cfg ::rpc/module)]

    {:type (or (::type resultm)
               (::rpc/type cfg))
     :name (or (::name resultm)
               (let [sname (::sv/name mdata)]
                 (if (not= module "main")
                   (str module "-" sname)
                   sname)))

     :profile-id profile-id
     :ip-addr ip-addr
     :props props
     :context context

     :created-at (::rpc/request-at params)
     :tracked-at (::rpc/request-at params)

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

(defn event-from-rpc-params
  "Create a base event skeleton with pre-filled some important
  data that can be extracted from RPC params object"
  [params]
  (let [context    (some-> params meta ::http/request prepare-context-from-request)
        context    (assoc context :request-id (::rpc/request-id params))
        request-at (::rpc/request-at params)]
    {:type "action"
     :profile-id (::rpc/profile-id params)
     :created-at request-at
     :tracked-at request-at
     :ip-addr (::rpc/ip-addr params)
     :context (d/without-nils context)}))

(defn submit
  "Submit an event to be registered under audit-log subsystem"
  [cfg event]
  (let [tnow  (ct/now)
        event (-> event
                  (assoc :created-at tnow)
                  (update :profile-id d/nilv uuid/zero)
                  (update :tracked-at d/nilv tnow)
                  (update :ip-addr d/nilv "0.0.0.0")
                  (update :props d/nilv {})
                  (update :context d/nilv {})
                  (assoc :source "backend")
                  (d/without-nils))]
    (submit* cfg event)))

(defn insert
  "Submit audit event to the collector, intended to be used only from
  command line helpers because this skips all webhooks and telemetry
  logic."
  [cfg event]
  (when (contains? cf/flags :audit-log)
    (let [tnow  (ct/now)
          event (-> event
                    (assoc :created-at tnow)
                    (update :tracked-at d/nilv tnow)
                    (update :profile-id d/nilv uuid/zero)
                    (update :props d/nilv {})
                    (update :context d/nilv {})
                    (assoc :source "backend")
                    (select-keys event-keys)
                    (check-event))]
      (db/run! cfg append-audit-entry event))))
