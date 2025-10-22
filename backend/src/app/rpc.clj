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
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as actoken]
   [app.http.client :as-alias http.client]
   [app.http.middleware :as mw]
   [app.http.security :as sec]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.msgbus :as-alias mbus]
   [app.redis :as rds]
   [app.rpc.climit :as climit]
   [app.rpc.cond :as cond]
   [app.rpc.doc :as doc]
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
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(s/def ::profile-id ::us/uuid)

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

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
                   (let [result  (rph/unwrap result)
                         status  (or (::http/status mdata)
                                     (if (nil? result)
                                       204
                                       200))
                         headers (cond-> (::http/headers mdata {})
                                   (yres/stream-body? result)
                                   (assoc "content-type" "application/octet-stream"))]
                     {::yres/status  status
                      ::yres/headers headers
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

(defn- make-rpc-handler
  "Ring handler that dispatches cmd requests and convert between
  internal async flow into ring async flow."
  [methods]
  (let [methods (update-vals methods peek)]
    (fn [{:keys [params path-params method] :as request}]
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

        ;; FIXME: why we have this cond enabled here, we need to move it outside this handler
        (binding [cond/*enabled* true]
          (let [response (handler-fn data)]
            (handle-response request response)))))))

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

(defn- wrap
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

(defn- wrap-management
  [cfg f mdata]
  (as-> f $
    (wrap-db-transaction cfg $ mdata)
    (retry/wrap-retry cfg $ mdata)
    (climit/wrap cfg $ mdata)
    (wrap-metrics cfg $ mdata)
    (wrap-audit cfg $ mdata)
    (wrap-spec-conform cfg $ mdata)
    (wrap-params-validation cfg $ mdata)
    (wrap-authentication cfg $ mdata)))

(defn- process-method
  [cfg module wrap-fn [f mdata]]
  (l/trc :hint "add method" :module module :name (::sv/name mdata))
  (let [f (wrap-fn cfg f mdata)
        k (keyword (::sv/name mdata))]
    [k [mdata (partial f cfg)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-methods
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
         (map (partial process-method cfg "rpc" wrap))
         (into {}))))

(def ^:private schema:methods-params
  [:map {:title "methods-params"}
   ::session/manager
   ::http.client/client
   ::db/pool
   ::rds/pool
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
    (resolve-methods cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MANAGEMENT METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-management-methods
  [cfg]
  (let [cfg (assoc cfg ::type "management" ::metrics-id :rpc-management-timing)]
    (->> (sv/scan-ns
          'app.rpc.management.subscription)
         (map (partial process-method cfg "management" wrap-management))
         (into {}))))

(def ^:private schema:management-methods-params
  [:map {:title "management-methods-params"}
   ::session/manager
   ::http.client/client
   ::db/pool
   ::rds/pool
   ::mbus/msgbus
   ::sto/storage
   ::mtx/metrics
   ::setup/props])

(defmethod ig/assert-key ::management-methods
  [_ params]
  (assert (sm/check schema:management-methods-params params)))

(defmethod ig/init-key ::management-methods
  [_ cfg]
  (let [cfg (d/without-nils cfg)]
    (resolve-management-methods cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROUTES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- redirect
  [href]
  (fn [_]
    {::yres/status 308
     ::yres/headers {"location" (str href)}}))

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
  (assert (valid-methods? (::methods params)) "expect valid methods map")
  (assert (valid-methods? (::management-methods params)) "expect valid methods map"))

(defmethod ig/init-key ::routes
  [_ {:keys [::methods ::management-methods] :as cfg}]

  (let [public-uri (cf/get :public-uri)]
    ["/api"


     ["/management"
      ["/methods/:type"
       {:middleware [[mw/shared-key-auth (cf/get :management-api-shared-key)]
                     [session/authz cfg]]
        :handler (make-rpc-handler management-methods)}]

      (doc/routes :methods management-methods
                  :label "management"
                  :base-uri (u/join public-uri "/api/management")
                  :description "MANAGEMENT API")]

     ["/main"
      ["/methods/:type"
       {:middleware [[mw/cors]
                     [sec/client-header-check]
                     [session/authz cfg]
                     [actoken/authz cfg]]
        :handler (make-rpc-handler methods)}]

      (doc/routes :methods methods
                  :label "main"
                  :base-uri (u/join public-uri "/api/main")
                  :description "MAIN API")]

     ;; BACKWARD COMPATIBILITY
     ["/_doc" {:handler (redirect (u/join public-uri "/api/main/doc"))}]
     ["/doc" {:handler (redirect (u/join public-uri "/api/main/doc"))}]
     ["/openapi" {:handler (redirect (u/join public-uri "/api/main/doc/openapi"))}]
     ["/openapi.join" {:handler (redirect (u/join public-uri "/api/main/doc/openapi.json"))}]

     ["/rpc/command/:type"
      {:middleware [[mw/cors]
                    [sec/client-header-check]
                    [session/authz cfg]
                    [actoken/authz cfg]]
       :handler (make-rpc-handler methods)}]]))
