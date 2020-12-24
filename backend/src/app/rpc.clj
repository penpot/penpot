;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.rpc
  (:require
   [app.common.exceptions :as ex]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(defn- default-handler
  [req]
  (ex/raise :type :not-found))

(defn- rpc-query-handler
  [methods {:keys [profile-id] :as request}]
  (let [type   (keyword (get-in request [:path-params :type]))
        data   (assoc (:params request) ::type type)
        data   (if profile-id
                 (assoc data :profile-id profile-id)
                 (dissoc data :profile-id))
        result ((get methods type default-handler) data)
        mdata  (meta result)]

    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata)) ((:transform-response mdata) request))))

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
      (fn? (:transform-response mdata)) ((:transform-response mdata) request))))

(defn- wrap-impl
  [f mdata cfg]
  (let [mreg  (get-in cfg [:metrics :registry])
        mobj  (mtx/create
               {:name (-> (str "rpc_" (::sv/name mdata) "_response_millis")
                          (str/replace "-" "_"))
                :registry mreg
                :type :summary
                :help (str/format "Service '%s' response time in milliseconds." (::sv/name mdata))})

        f     (mtx/wrap-summary f mobj)

        spec  (or (::sv/spec mdata) (s/spec any?))]

    (log/debugf "Registering '%s' command to rpc service." (::sv/name mdata))
    (fn [params]
      (when (and (:auth mdata true) (not (uuid? (:profile-id params))))
        (ex/raise :type :not-authenticated))
      (f cfg (us/conform spec params)))))

(defn- process-method
  [cfg vfn]
  (let [mdata (meta vfn)]
    [(keyword (::sv/name mdata))
     (wrap-impl (deref vfn) mdata cfg)]))

(defn- resolve-query-methods
  [cfg]
  (->> (sv/scan-ns 'app.rpc.queries.projects
                   'app.rpc.queries.files
                   'app.rpc.queries.teams
                   'app.rpc.queries.comments
                   'app.rpc.queries.profile
                   'app.rpc.queries.recent-files
                   'app.rpc.queries.viewer)
       (map (partial process-method cfg))
       (into {})))

(defn- resolve-mutation-methods
  [cfg]
  (->> (sv/scan-ns 'app.rpc.mutations.demo
                   'app.rpc.mutations.media
                   'app.rpc.mutations.profile
                   'app.rpc.mutations.files
                   'app.rpc.mutations.comments
                   'app.rpc.mutations.projects
                   'app.rpc.mutations.viewer
                   'app.rpc.mutations.verify-token)
       (map (partial process-method cfg))
       (into {})))

(s/def ::storage some?)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec ::rpc [_]
  (s/keys :req-un [::db/pool ::storage ::session ::tokens ::mtx/metrics]))

(defmethod ig/init-key ::rpc
  [_ cfg]
  (let [mq (resolve-query-methods cfg)
        mm (resolve-mutation-methods cfg)]
    {:methods {:query mq :mutation mm}
     :query-handler #(rpc-query-handler mq %)
     :mutation-handler #(rpc-mutation-handler mm %)}))
