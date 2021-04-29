;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.rlimits :as rlm]
   [app.util.logging :as l]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

(defn- run-hook
  [hook-fn response]
  (ex/ignoring (hook-fn))
  response)

(defn- rpc-query-handler
  [methods {:keys [profile-id] :as request}]
  (let [type   (keyword (get-in request [:path-params :type]))

        data   (d/merge (:params request)
                        (:body-params request)
                        (:uploads request))

        data   (if profile-id
                 (assoc data :profile-id profile-id)
                 (dissoc data :profile-id))

        result ((get methods type default-handler) data)
        mdata  (meta result)]

    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata))
      ((:transform-response mdata) request))))

(defn- rpc-mutation-handler
  [methods {:keys [profile-id] :as request}]
  (let [type   (keyword (get-in request [:path-params :type]))
        data   (d/merge (:params request)
                        (:body-params request)
                        (:uploads request))
        data   (if profile-id
                 (assoc data :profile-id profile-id)
                 (dissoc data :profile-id))
        result ((get methods type default-handler) data)
        mdata  (meta result)]
    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata))
      ((:transform-response mdata) request)

      (fn? (:before-complete mdata))
      (run-hook (:before-complete mdata)))))

(defn- wrap-with-metrics
  [cfg f mdata]
  (mtx/wrap-summary f (::mobj cfg) [(::sv/name mdata)]))

;; Wrap the rpc handler with a semaphore if it is specified in the
;; metadata asocciated with the handler.
(defn- wrap-with-rlimits
  [cfg f mdata]
  (if-let [key (:rlimit mdata)]
    (let [rlinst (get-in cfg [:rlimits key])]
      (when-not rlinst
        (ex/raise :type :internal
                  :code :rlimit-not-configured
                  :hint (str/fmt "%s rlimit not configured" key)))
      (l/trace :action "add rlimit"
               :handler (::sv/name mdata))
      (fn [cfg params]
        (rlm/execute rlinst (f cfg params))))
    f))

(defn- wrap-impl
  [cfg f mdata]
  (let [f     (wrap-with-rlimits cfg f mdata)
        f     (wrap-with-metrics cfg f mdata)
        spec  (or (::sv/spec mdata) (s/spec any?))]
    (l/trace :action "register"
             :name (::sv/name mdata))
    (fn [params]
      (when (and (:auth mdata true) (not (uuid? (:profile-id params))))
        (ex/raise :type :authentication
                  :code :authentication-required
                  :hint "authentication required for this endpoint"))
      (f cfg (us/conform spec params)))))

(defn- process-method
  [cfg vfn]
  (let [mdata (meta vfn)]
    [(keyword (::sv/name mdata))
     (wrap-impl cfg (deref vfn) mdata)]))

(defn- resolve-query-methods
  [cfg]
  (let [mobj (mtx/create
              {:name "rpc_query_timing"
               :labels ["name"]
               :registry (get-in cfg [:metrics :registry])
               :type :histogram
               :help "Timing of query services."})
        cfg  (assoc cfg ::mobj mobj)]
    (->> (sv/scan-ns 'app.rpc.queries.projects
                     'app.rpc.queries.files
                     'app.rpc.queries.teams
                     'app.rpc.queries.comments
                     'app.rpc.queries.profile
                     'app.rpc.queries.recent-files
                     'app.rpc.queries.viewer
                     'app.rpc.queries.fonts
                     'app.rpc.queries.svg)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-mutation-methods
  [cfg]
  (let [mobj (mtx/create
              {:name "rpc_mutation_timing"
               :labels ["name"]
               :registry (get-in cfg [:metrics :registry])
               :type :histogram
               :help "Timing of mutation services."})
        cfg  (assoc cfg ::mobj mobj)]
    (->> (sv/scan-ns 'app.rpc.mutations.demo
                     'app.rpc.mutations.media
                     'app.rpc.mutations.profile
                     'app.rpc.mutations.files
                     'app.rpc.mutations.comments
                     'app.rpc.mutations.projects
                     'app.rpc.mutations.viewer
                     'app.rpc.mutations.teams
                     'app.rpc.mutations.management
                     'app.rpc.mutations.ldap
                     'app.rpc.mutations.fonts
                     'app.rpc.mutations.verify-token)
         (map (partial process-method cfg))
         (into {}))))

(s/def ::storage some?)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec ::rpc [_]
  (s/keys :req-un [::db/pool ::storage ::session ::tokens ::mtx/metrics ::rlm/rlimits]))

(defmethod ig/init-key ::rpc
  [_ cfg]
  (let [mq (resolve-query-methods cfg)
        mm (resolve-mutation-methods cfg)]
    {:methods {:query mq :mutation mm}
     :query-handler #(rpc-query-handler mq %)
     :mutation-handler #(rpc-mutation-handler mm %)}))
