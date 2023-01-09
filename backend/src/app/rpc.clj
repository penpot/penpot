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
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as-alias actoken]
   [app.http.client :as-alias http.client]
   [app.http.session :as-alias session]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.metrics :as mtx]
   [app.msgbus :as-alias mbus]
   [app.rpc.climit :as climit]
   [app.rpc.cond :as cond]
   [app.rpc.helpers :as rph]
   [app.rpc.retry :as retry]
   [app.rpc.rlimit :as rlimit]
   [app.storage :as-alias sto]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

(s/def ::profile-id ::us/uuid)

(defn- default-handler
  [_]
  (p/rejected (ex/error :type :not-found)))

(defn- handle-response-transformation
  [response request mdata]
  (let [transform-fn (reduce (fn [res-fn transform-fn]
                               (fn [request response]
                                 (p/then (res-fn request response) #(transform-fn request %))))
                             (constantly response)
                             (::response-transform-fns mdata))]
    (transform-fn request response)))

(defn- handle-before-comple-hook
  [response mdata]
  (doseq [hook-fn (::before-complete-fns mdata)]
    (ex/ignoring (hook-fn)))
  response)

(defn- handle-response
  [request result]
  (if (fn? result)
    (p/wrap (result request))
    (let [mdata (meta result)]
      (p/-> (yrs/response {:status  (::http/status mdata 200)
                           :headers (::http/headers mdata {})
                           :body    (rph/unwrap result)})
            (handle-response-transformation request mdata)
            (handle-before-comple-hook mdata)))))

(defn- rpc-query-handler
  "Ring handler that dispatches query requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [params] :as request} respond raise]
  (let [type       (keyword (:type params))
        profile-id (or (::session/profile-id request)
                       (::actoken/profile-id request))

        data       (-> params
                       (assoc ::request-at (dt/now))
                       (assoc ::http/request request))
        data       (if profile-id
                     (-> data
                         (assoc :profile-id profile-id)
                         (assoc ::profile-id profile-id))
                     (dissoc data :profile-id ::profile-id))
        method     (get methods type default-handler)]

    (->> (method data)
         (p/mcat (partial handle-response request))
         (p/fnly (fn [response cause]
                   (if cause
                     (raise (ex/wrap-with-context cause {:profile-id profile-id}))
                     (respond response)))))))

(defn- rpc-mutation-handler
  "Ring handler that dispatches mutation requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [params] :as request} respond raise]
  (let [type       (keyword (:type params))
        profile-id (or (::session/profile-id request)
                       (::actoken/profile-id request))
        data       (-> params
                       (assoc ::request-at (dt/now))
                       (assoc ::http/request request))
        data       (if profile-id
                     (-> data
                         (assoc :profile-id profile-id)
                         (assoc ::profile-id profile-id))
                     (dissoc data :profile-id))
        method     (get methods type default-handler)]

    (->> (method data)
         (p/mcat (partial handle-response request))
         (p/fnly (fn [response cause]
                   (if cause
                     (raise (ex/wrap-with-context cause {:profile-id profile-id}))
                     (respond response)))))))

(defn- rpc-command-handler
  "Ring handler that dispatches cmd requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [params] :as request} respond raise]
  (let [type       (keyword (:type params))
        etag       (yrq/get-header request "if-none-match")
        profile-id (or (::session/profile-id request)
                       (::actoken/profile-id request))

        data       (-> params
                       (assoc ::request-at (dt/now))
                       (assoc ::http/request request)
                       (assoc ::cond/key etag)
                       (cond-> (uuid? profile-id)
                         (assoc ::profile-id profile-id)))

        method    (get methods type default-handler)]

    (binding [cond/*enabled* true]
      (->> (method data)
           (p/mcat (partial handle-response request))
           (p/fnly (fn [response cause]
                     (if cause
                       (raise (ex/wrap-with-context cause {:profile-id profile-id}))
                       (respond response))))))))

(defn- wrap-metrics
  "Wrap service method with metrics measurement."
  [{:keys [metrics ::metrics-id]} f mdata]
  (let [labels (into-array String [(::sv/name mdata)])]
    (fn [cfg params]
      (let [tp (dt/tpoint)]
        (->> (f cfg params)
             (p/fnly (fn [_ _]
                       (mtx/run! metrics
                                 :id metrics-id
                                 :val (inst-ms (tp))
                                 :labels labels))))))))

(defn- wrap-access-token
  "Wraps service method with access token validation."
  [_ f {:keys [::sv/name] :as mdata}]
  (if (contains? cf/flags :access-tokens)
    (fn [cfg params]
      (let [request (::http/request params)
            token   (::actoken/token request)]
        (if token
          (if (contains? (:perms token) name)
            (f cfg params)
            (ex/raise :type :authorization
                      :code :operation-not-allowed
                      :allowed (:perms token)))
          (f cfg params))))
    f))

(defn- wrap-dispatch
  "Wraps service method into async flow, with the ability to dispatching
  it to a preconfigured executor service."
  [{:keys [executor] :as cfg} f mdata]
  (with-meta
    (fn [cfg params]
      (->> (px/submit! executor (px/wrap-bindings #(f cfg params)))
           (p/mapcat p/wrap)
           (p/map rph/wrap)))
    mdata))

(defn- wrap-audit
  [cfg f mdata]
  (if-let [collector (::audit/collector cfg)]
    (letfn [(handle-audit [params result]
              (let [resultm    (meta result)
                    request    (::http/request params)

                    profile-id (or (::audit/profile-id resultm)
                                   (:profile-id result)
                                   (if (= (::type cfg) "command")
                                     (::profile-id params)
                                     (:profile-id params))
                                   uuid/zero)

                    props      (-> (or (::audit/replace-props resultm)
                                       (-> params
                                           (merge (::audit/props resultm))
                                           (dissoc :profile-id)
                                           (dissoc :type)))
                                   (d/without-qualified)
                                   (d/without-nils))

                    event      {:type (or (::audit/type resultm)
                                          (::type cfg))
                                :name (or (::audit/name resultm)
                                          (::sv/name mdata))
                                :profile-id profile-id
                                :ip-addr (some-> request audit/parse-client-ip)
                                :props props

                                ;; NOTE: for batch-key lookup we need the params as-is
                                ;; because the rpc api does not need to know the
                                ;; audit/webhook specific object layout.
                                ::params (dissoc params ::http/request)

                                ::webhooks/batch-key
                                (or (::webhooks/batch-key mdata)
                                    (::webhooks/batch-key resultm))

                                ::webhooks/batch-timeout
                                (or (::webhooks/batch-timeout mdata)
                                    (::webhooks/batch-timeout resultm))

                                ::webhooks/event?
                                (or (::webhooks/event? mdata)
                                    (::webhooks/event? resultm)
                                    false)}]

                (audit/submit! collector event)))

            (handle-request [cfg params]
              (->> (f cfg params)
                   (p/mcat (fn [result]
                             (->> (handle-audit params result)
                                  (p/map (constantly result)))))))]
      (if-not (::audit/skip mdata)
        (with-meta handle-request mdata)
        f))
    f))

(defn- wrap-all
  [cfg f mdata]
  (as-> f $
    (wrap-dispatch cfg $ mdata)
    (cond/wrap cfg $ mdata)
    (retry/wrap-retry cfg $ mdata)
    (wrap-metrics cfg $ mdata)
    (climit/wrap cfg $ mdata)
    (rlimit/wrap cfg $ mdata)
    (wrap-audit cfg $ mdata)
    (wrap-access-token cfg $ mdata)))

(defn- wrap
  [cfg f mdata]
  (let [spec  (or (::sv/spec mdata) (s/spec any?))
        auth? (::auth mdata true)
        f     (wrap-all cfg f mdata)]
    (l/debug :hint "register method" :name (::sv/name mdata))
    (with-meta
      (fn [params]
        ;; Raise authentication error when rpc method requires auth but
        ;; no profile-id is found in the request.
        (let [profile-id (::profile-id params)]
          (p/do!
           (if (and auth? (not (uuid? profile-id)))
             (ex/raise :type :authentication
                       :code :authentication-required
                       :hint "authentication required for this endpoint")
             (let [params (us/conform spec params)]
               (f cfg params))))))
      mdata)))

(defn- process-method
  [cfg vfn]
  (let [mdata (meta vfn)]
    [(keyword (::sv/name mdata))
     (wrap cfg vfn mdata)]))

(defn- resolve-query-methods
  [cfg]
  (let [cfg (assoc cfg ::type "query" ::metrics-id :rpc-query-timing)]
    (->> (sv/scan-ns
          'app.rpc.queries.projects
          'app.rpc.queries.files
          'app.rpc.queries.teams
          'app.rpc.queries.profile
          'app.rpc.queries.viewer
          'app.rpc.queries.fonts)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-mutation-methods
  [cfg]
  (let [cfg (assoc cfg ::type "mutation" ::metrics-id :rpc-mutation-timing)]
    (->> (sv/scan-ns
          'app.rpc.mutations.media
          'app.rpc.mutations.profile
          'app.rpc.mutations.files
          'app.rpc.mutations.projects
          'app.rpc.mutations.teams
          'app.rpc.mutations.fonts
          'app.rpc.mutations.share-link)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-command-methods
  [cfg]
  (let [cfg (assoc cfg ::type "command" ::metrics-id :rpc-command-timing)]
    (->> (sv/scan-ns
          'app.rpc.commands.access-token
          'app.rpc.commands.audit
          'app.rpc.commands.auth
          'app.rpc.commands.binfile
          'app.rpc.commands.comments
          'app.rpc.commands.demo
          'app.rpc.commands.files
          'app.rpc.commands.files.create
          'app.rpc.commands.files.temp
          'app.rpc.commands.files.update
          'app.rpc.commands.ldap
          'app.rpc.commands.management
          'app.rpc.commands.media
          'app.rpc.commands.profile
          'app.rpc.commands.search
          'app.rpc.commands.teams
          'app.rpc.commands.verify-token
          'app.rpc.commands.webhooks)
         (map (partial process-method cfg))
         (into {}))))

(s/def ::ldap (s/nilable map?))
(s/def ::msgbus ::mbus/msgbus)
(s/def ::climit (s/nilable ::climit/climit))
(s/def ::rlimit (s/nilable ::rlimit/rlimit))

(s/def ::public-uri ::us/not-empty-string)
(s/def ::sprops map?)

(defmethod ig/pre-init-spec ::methods [_]
  (s/keys :req [::audit/collector
                ::session/manager
                ::http.client/client
                ::db/pool
                ::ldap/provider
                ::wrk/executor]
          :req-un [::sto/storage
                   ::sprops
                   ::public-uri
                   ::msgbus
                   ::rlimit
                   ::climit
                   ::wrk/executor
                   ::mtx/metrics
                   ::db/pool]))

(defmethod ig/init-key ::methods
  [_ cfg]
  {:mutations (resolve-mutation-methods cfg)
   :queries   (resolve-query-methods cfg)
   :commands  (resolve-command-methods cfg)})

(s/def ::mutations
  (s/map-of keyword? fn?))

(s/def ::queries
  (s/map-of keyword? fn?))

(s/def ::commands
  (s/map-of keyword? fn?))

(s/def ::methods
  (s/keys :req-un [::mutations
                   ::queries
                   ::commands]))

(defmethod ig/pre-init-spec ::routes [_]
  (s/keys :req-un [::methods]))

(defmethod ig/init-key ::routes
  [_ {:keys [methods] :as cfg}]
  [["/rpc"
    ["/command/:type" {:handler (partial rpc-command-handler (:commands methods))}]
    ["/query/:type" {:handler (partial rpc-query-handler (:queries methods))}]
    ["/mutation/:type" {:handler (partial rpc-mutation-handler (:mutations methods))
                        :allowed-methods #{:post}}]]])

