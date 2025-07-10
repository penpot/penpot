;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc
  (:require
   [app.auth.ldap :as-alias ldap]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as actoken]
   [app.http.client :as-alias http.client]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.msgbus :as-alias mbus]
   [app.rpc.climit :as climit]
   [app.rpc.cond :as cond]
   [app.rpc.helpers :as rph]
   [app.rpc.retry :as retry]
   [app.rpc.rlimit :as rlimit]
   [app.setup :as-alias setup]
   [app.storage :as-alias sto]
   [app.util.inet :as inet]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(s/def ::profile-id ::us/uuid)

(defn- default-handler
  [_]
  (p/rejected (ex/error :type :not-found)))

(defn- handle-response-transformation
  [response request mdata]
  (reduce (fn [response transform-fn]
            (transform-fn request response))
          response
          (::response-transform-fns mdata)))

(defn- handle-before-comple-hook
  [response mdata]
  (doseq [hook-fn (::before-complete-fns mdata)]
    (ex/ignoring (hook-fn)))
  response)

(defn- handle-response
  [request result]
  (let [mdata    (meta result)
        response (if (fn? result)
                   (result request)
                   (let [result (rph/unwrap result)]
                     {::yres/status  (::http/status mdata 200)
                      ::yres/headers (::http/headers mdata {})
                      ::yres/body    result}))]
    (-> response
        (handle-response-transformation request mdata)
        (handle-before-comple-hook mdata))))

(defn get-external-session-id
  [request]
  (when-let [session-id (yreq/get-header request "x-external-session-id")]
    (when-not (or (> (count session-id) 256)
                  (= session-id "null")
                  (str/blank? session-id))
      session-id)))

(defn- get-external-event-origin
  [request]
  (when-let [origin (yreq/get-header request "x-event-origin")]
    (when-not (or (> (count origin) 256)
                  (= origin "null")
                  (str/blank? origin))
      origin)))

(defn- rpc-handler
  "Ring handler that dispatches cmd requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [params path-params method] :as request}]
  (let [handler-name (:type path-params)
        etag         (yreq/get-header request "if-none-match")
        profile-id   (or (::session/profile-id request)
                         (::actoken/profile-id request))

        ip-addr      (inet/parse-request request)
        session-id   (get-external-session-id request)
        event-origin (get-external-event-origin request)

        data         (-> params
                         (assoc ::handler-name handler-name)
                         (assoc ::ip-addr ip-addr)
                         (assoc ::request-at (ct/now))
                         (assoc ::external-session-id session-id)
                         (assoc ::external-event-origin event-origin)
                         (assoc ::session/id (::session/id request))
                         (assoc ::cond/key etag)
                         (cond-> (uuid? profile-id)
                           (assoc ::profile-id profile-id)))

        data         (vary-meta data assoc ::http/request request)
        handler-fn   (get methods (keyword handler-name) default-handler)]

    (when (and (or (= method :get)
                   (= method :head))
               (not (str/starts-with? handler-name "get-")))
      (ex/raise :type :restriction
                :code :method-not-allowed
                :hint "method not allowed for this request"))

    (binding [cond/*enabled* true]
      (let [response (handler-fn data)]
        (handle-response request response)))))

(defn- wrap-metrics
  "Wrap service method with metrics measurement."
  [{:keys [::mtx/metrics ::metrics-id]} f mdata]
  (let [labels (into-array String [(::sv/name mdata)])]
    (fn [cfg params]
      (let [tp (ct/tpoint)]
        (try
          (f cfg params)
          (finally
            (mtx/run! metrics
                      :id metrics-id
                      :val (inst-ms (tp))
                      :labels labels)))))))

(defn- wrap-authentication
  [_ f mdata]
  (fn [cfg params]
    (let [profile-id (::profile-id params)]
      (if (and (::auth mdata true) (not (uuid? profile-id)))
        (ex/raise :type :authentication
                  :code :authentication-required
                  :hint "authentication required for this endpoint")
        (f cfg params)))))

(defn- wrap-db-transaction
  [_ f mdata]
  (if (::db/transaction mdata)
    (fn [cfg params]
      (db/tx-run! cfg f params))
    f))

(defn- wrap-audit
  [_ f mdata]
  (if (or (contains? cf/flags :webhooks)
          (contains? cf/flags :audit-log))
    (if-not (::audit/skip mdata)
      (fn [cfg params]
        (let [result (f cfg params)]
          (->> (audit/prepare-event cfg mdata params result)
               (audit/submit! cfg))
          result))
      f)
    f))

(defn- wrap-spec-conform
  [_ f mdata]
  ;; NOTE: skip spec conform operation on rpc methods that already
  ;; uses malli validation mechanism.
  (if (contains? mdata ::sm/params)
    f
    (if-let [spec (ex/ignoring (s/spec (::sv/spec mdata)))]
      (fn [cfg params]
        (f cfg (us/conform spec params)))
      f)))

(defn- wrap-params-validation
  [_ f mdata]
  (if-let [schema (::sm/params mdata)]
    (let [validate (sm/validator schema)
          explain  (sm/explainer schema)
          decode   (sm/decoder schema sm/json-transformer)
          encode   (sm/encoder schema sm/json-transformer)]
      (fn [cfg params]
        (let [params (decode params)]
          (if (validate params)
            (let [result (f cfg params)]
              (if (instance? clojure.lang.IObj result)
                (vary-meta result assoc :encode/json encode)
                result))
            (let [params (d/without-qualified params)]
              (ex/raise :type :validation
                        :code :params-validation
                        ::sm/explain (explain params)))))))
    f))

(defn- wrap-all
  [cfg f mdata]
  (as-> f $
    (wrap-db-transaction cfg $ mdata)
    (cond/wrap cfg $ mdata)
    (retry/wrap-retry cfg $ mdata)
    (climit/wrap cfg $ mdata)
    (wrap-metrics cfg $ mdata)
    (rlimit/wrap cfg $ mdata)
    (wrap-audit cfg $ mdata)
    (wrap-spec-conform cfg $ mdata)
    (wrap-params-validation cfg $ mdata)
    (wrap-authentication cfg $ mdata)))

(defn- wrap
  [cfg f mdata]
  (l/trc :hint "register method" :name (::sv/name mdata))
  (let [f (wrap-all cfg f mdata)]
    (partial f cfg)))

(defn- process-method
  [cfg [vfn mdata]]
  [(keyword (::sv/name mdata)) [mdata (wrap cfg vfn mdata)]])

(defn- resolve-command-methods
  [cfg]
  (let [cfg (assoc cfg ::type "command" ::metrics-id :rpc-command-timing)]
    (->> (sv/scan-ns
          'app.rpc.commands.access-token
          'app.rpc.commands.audit
          'app.rpc.commands.auth
          'app.rpc.commands.feedback
          'app.rpc.commands.fonts
          'app.rpc.commands.binfile
          'app.rpc.commands.comments
          'app.rpc.commands.demo
          'app.rpc.commands.files
          'app.rpc.commands.files-create
          'app.rpc.commands.files-share
          'app.rpc.commands.files-update
          'app.rpc.commands.files-snapshot
          'app.rpc.commands.files-thumbnails
          'app.rpc.commands.ldap
          'app.rpc.commands.management
          'app.rpc.commands.media
          'app.rpc.commands.profile
          'app.rpc.commands.projects
          'app.rpc.commands.search
          'app.rpc.commands.teams
          'app.rpc.commands.teams-invitations
          'app.rpc.commands.verify-token
          'app.rpc.commands.viewer
          'app.rpc.commands.webhooks)
         (map (partial process-method cfg))
         (into {}))))

(def ^:private schema:methods-params
  [:map {:title "methods-params"}
   ::session/manager
   ::http.client/client
   ::db/pool
   ::mbus/msgbus
   ::sto/storage
   ::mtx/metrics
   [::ldap/provider [:maybe ::ldap/provider]]
   [::climit [:maybe ::climit]]
   [::rlimit [:maybe ::rlimit]]
   ::setup/props])

(defmethod ig/assert-key ::methods
  [_ params]
  (assert (sm/check schema:methods-params params)))

(defmethod ig/init-key ::methods
  [_ cfg]
  (let [cfg (d/without-nils cfg)]
    (resolve-command-methods cfg)))

(def ^:private schema:methods
  [:map-of :keyword [:tuple :map ::sm/fn]])

(sm/register! ::methods schema:methods)

(def ^:private valid-methods?
  (sm/validator schema:methods))

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool")
  (assert (some? (::setup/props params)))
  (assert (session/manager? (::session/manager params)) "expect valid session manager")
  (assert (valid-methods? (::methods params)) "expect valid methods map"))

(defmethod ig/init-key ::routes
  [_ {:keys [::methods] :as cfg}]
  (let [methods (update-vals methods peek)]
    [["/rpc" {:middleware [[session/authz cfg]
                           [actoken/authz cfg]]}
      ["/command/:type" {:handler (partial rpc-handler methods)}]]]))
