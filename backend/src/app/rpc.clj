;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.metrics :as mtx]
   [app.rpc.retry :as retry]
   [app.rpc.rlimit :as rlimit]
   [app.util.async :as async]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]))

(defn- default-handler
  [_]
  (p/rejected (ex/error :type :not-found)))

(defn- handle-response-transformation
  [response request mdata]
  (if-let [transform-fn (:transform-response mdata)]
    (transform-fn request response)
    response))

(defn- handle-before-comple-hook
  [response mdata]
  (when-let [hook-fn (:before-complete mdata)]
    (ex/ignoring (hook-fn)))
  response)

(defn- rpc-query-handler
  "Ring handler that dispatches query requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [profile-id session-id] :as request} respond raise]
  (letfn [(handle-response [result]
            (let [mdata (meta result)]
              (-> {:status 200 :body result}
                  (handle-response-transformation request mdata))))]

    (let [type   (keyword (get-in request [:path-params :type]))
          data   (merge (:params request)
                        (:body-params request)
                        (:uploads request)
                        {::request request})

          data   (if profile-id
                   (assoc data :profile-id profile-id ::session-id session-id)
                   (dissoc data :profile-id))

          ;; Get the method from methods registry and if method does
          ;; not exists asigns it to the default handler.
          method (get methods type default-handler)]

      (-> (method data)
          (p/then #(respond (handle-response %)))
          (p/catch raise)))))

(defn- rpc-mutation-handler
  "Ring handler that dispatches mutation requests and convert between
  internal async flow into ring async flow."
  [methods {:keys [profile-id session-id] :as request} respond raise]
  (letfn [(handle-response [result]
            (let [mdata (meta result)]
              (-> {:status 200 :body result}
                  (handle-response-transformation request mdata)
                  (handle-before-comple-hook mdata))))]

    (let [type   (keyword (get-in request [:path-params :type]))
          data   (merge (:params request)
                        (:body-params request)
                        (:uploads request)
                        {::request request})

          data   (if profile-id
                   (assoc data :profile-id profile-id ::session-id session-id)
                   (dissoc data :profile-id))

          method (get methods type default-handler)]

      (-> (method data)
          (p/then #(respond (handle-response %)))
          (p/catch raise)))))

(defn- wrap-metrics
  "Wrap service method with metrics measurement."
  [{:keys [metrics ::metrics-id]} f mdata]
  (let [labels (into-array String [(::sv/name mdata)])]
    (fn [cfg params]
      (let [start (System/nanoTime)]
        (p/finally
          (f cfg params)
          (fn [_ _]
            (mtx/run! metrics
                      {:id metrics-id
                       :val (/ (- (System/nanoTime) start) 1000000)
                       :labels labels})))))))

(defn- wrap-dispatch
  "Wraps service method into async flow, with the ability to dispatching
  it to a preconfigured executor service."
  [{:keys [executors] :as cfg} f mdata]
  (let [dname (::async/dispatch mdata :none)]
    (if (= :none dname)
      (with-meta
        (fn [cfg params]
          (try
            (p/wrap (f cfg params))
            (catch Throwable cause
              (p/rejected cause))))
        mdata)

      (let [executor (get executors dname)]
        (when-not executor
          (ex/raise :type :internal
                    :code :executor-not-configured
                    :hint (format "executor %s not configured" dname)))
        (with-meta
          (fn [cfg params]
            (-> (px/submit! executor #(f cfg params))
                (p/bind p/wrap)))
          mdata)))))

(defn- wrap-audit
  [{:keys [audit] :as cfg} f mdata]
  (if audit
    (with-meta
      (fn [cfg {:keys [::request] :as params}]
        (p/finally (f cfg params)
                   (fn [result _]
                     (when result
                       (let [resultm    (meta result)
                             profile-id (or (:profile-id params)
                                            (:profile-id result)
                                            (::audit/profile-id resultm))
                             props      (d/merge params (::audit/props resultm))]
                         (audit :cmd :submit
                                :type (or (::audit/type resultm)
                                          (::type cfg))
                                :name (or (::audit/name resultm)
                                          (::sv/name mdata))
                                :profile-id profile-id
                                :ip-addr (audit/parse-client-ip request)
                                :props (dissoc props ::request)))))))
      mdata)
    f))

(defn- wrap
  [cfg f mdata]
  (let [f     (as-> f $
                (wrap-dispatch cfg $ mdata)
                (rlimit/wrap-rlimit cfg $ mdata)
                (retry/wrap-retry cfg $ mdata)
                (wrap-audit cfg $ mdata)
                (wrap-metrics cfg $ mdata)
                )

        spec  (or (::sv/spec mdata) (s/spec any?))
        auth? (:auth mdata true)]

    (l/trace :action "register" :name (::sv/name mdata))
    (with-meta
      (fn [{:keys [::request] :as params}]
        ;; Raise authentication error when rpc method requires auth but
        ;; no profile-id is found in the request.
        (when (and auth? (not (uuid? (:profile-id params))))
          (ex/raise :type :authentication
                    :code :authentication-required
                    :hint "authentication required for this endpoint"))

        (let [params (us/conform spec (dissoc params ::request))]
          (f cfg (assoc params ::request request))))
      mdata)))

(defn- process-method
  [cfg vfn]
  (let [mdata (meta vfn)]
    [(keyword (::sv/name mdata))
     (wrap cfg (deref vfn) mdata)]))

(defn- resolve-query-methods
  [cfg]
  (let [cfg (assoc cfg ::type "query" ::metrics-id :rpc-query-timing)]
    (->> (sv/scan-ns 'app.rpc.queries.projects
                     'app.rpc.queries.files
                     'app.rpc.queries.teams
                     'app.rpc.queries.comments
                     'app.rpc.queries.profile
                     'app.rpc.queries.viewer
                     'app.rpc.queries.fonts)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-mutation-methods
  [cfg]
  (let [cfg (assoc cfg ::type "mutation" ::metrics-id :rpc-mutation-timing)]
    (->> (sv/scan-ns 'app.rpc.mutations.demo
                     'app.rpc.mutations.media
                     'app.rpc.mutations.profile
                     'app.rpc.mutations.files
                     'app.rpc.mutations.comments
                     'app.rpc.mutations.projects
                     'app.rpc.mutations.teams
                     'app.rpc.mutations.management
                     'app.rpc.mutations.ldap
                     'app.rpc.mutations.fonts
                     'app.rpc.mutations.share-link
                     'app.rpc.mutations.verify-token)
         (map (partial process-method cfg))
         (into {}))))

(s/def ::storage some?)
(s/def ::session map?)
(s/def ::tokens fn?)
(s/def ::audit (s/nilable fn?))
(s/def ::executors (s/map-of keyword? ::wrk/executor))

(defmethod ig/pre-init-spec ::rpc [_]
  (s/keys :req-un [::storage ::session ::tokens ::audit
                   ::executors ::mtx/metrics ::db/pool]))

(defmethod ig/init-key ::rpc
  [_ cfg]
  (let [mq (resolve-query-methods cfg)
        mm (resolve-mutation-methods cfg)]
    {:methods {:query mq :mutation mm}
     :query-handler (partial rpc-query-handler mq)
     :mutation-handler (partial rpc-mutation-handler mm)}))
