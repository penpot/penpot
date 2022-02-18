;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.debug
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc.mutations.files :as m.files]
   [app.rpc.queries.profile :as profile]
   [app.util.async :as async]
   [app.util.blob :as blob]
   [app.util.template :as tmpl]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [fipp.edn :as fpp]
   [integrant.core :as ig]
   [promesa.core :as p]))

;; (selmer.parser/cache-off!)

(defn authorized?
  [pool {:keys [profile-id]}]
  (or (= "devenv" (cf/get :host))
      (let [profile (ex/ignoring (profile/retrieve-profile-data pool profile-id))
            admins  (or (cf/get :admins) #{})]
        (contains? admins (:email profile)))))

(defn index
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  {:status 200
   :headers {"content-type" "text/html"}
   :body (-> (io/resource "templates/debug.tmpl")
             (tmpl/render {}))})


(def sql:retrieve-range-of-changes
  "select revn, changes from file_change where file_id=? and revn >= ? and revn <= ? order by revn")

(def sql:retrieve-single-change
  "select revn, changes, data from file_change where file_id=? and revn = ?")

(defn prepare-response
  [{:keys [params] :as request} body]
  (when-not body
    (ex/raise :type :not-found
              :code :enpty-data
              :hint "empty response"))

  (cond-> {:status 200
           :headers {"content-type" "application/transit+json"}
           :body body}
    (contains? params :download)
    (update :headers assoc "content-disposition" "attachment")))

(defn retrieve-file-data
  [{:keys [pool]} {:keys [params] :as request}]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  (let [file-id (some-> (get-in request [:params :file-id]) uuid/uuid)
        revn    (some-> (get-in request [:params :revn]) d/parse-integer)]
    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    (let [data (if (integer? revn)
                 (some-> (db/exec-one! pool [sql:retrieve-single-change file-id revn]) :data)
                 (some-> (db/get-by-id pool :file file-id) :data))]
      (if (contains? params :download)
        (-> (prepare-response request data)
            (update :headers assoc "content-type" "application/octet-stream"))
        (prepare-response request (some-> data blob/decode))))))

(defn upload-file-data
  [{:keys [pool]} {:keys [profile-id params] :as request}]
  (let [project-id (some-> (profile/retrieve-additional-data pool profile-id) :default-project-id)
        data       (some-> params :file :tempfile fs/slurp-bytes blob/decode)]

    (if (and data project-id)
      (let [fname (str "imported-file-" (dt/now))]
        (m.files/create-file pool {:id (uuid/next)
                                   :name fname
                                   :project-id project-id
                                   :profile-id profile-id
                                   :data data})
        {:status 200
         :body "OK"})
      {:status 500
       :body "error"})))

(defn retrieve-file-changes
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  (let [file-id (some-> (get-in request [:params :id]) uuid/uuid)
        revn    (or (get-in request [:params :revn]) "latest")]

    (when (or (not file-id) (not revn))
      (ex/raise :type :validation
                :code :invalid-arguments
                :hint "missing arguments"))

    (cond
      (d/num-string? revn)
      (let [item (db/exec-one! pool [sql:retrieve-single-change file-id (d/parse-integer revn)])]
        (prepare-response request (some-> item :changes blob/decode vec)))

      (str/includes? revn ":")
      (let [[start end] (->> (str/split revn #":")
                             (map str/trim)
                             (map d/parse-integer))
            items       (db/exec! pool [sql:retrieve-range-of-changes file-id start end])]
        (prepare-response request
                          (some->> items
                                   (map :changes)
                                   (map blob/decode)
                                   (mapcat identity)
                                   (vec))))
      :else
      (ex/raise :type :validation :code :invalid-arguments))))


(defn retrieve-error
  [{:keys [pool]} request]
  (letfn [(parse-id [request]
            (let [id (get-in request [:path-params :id])
                  id (us/uuid-conformer id)]
              (when (uuid? id)
                id)))

          (retrieve-report [id]
            (ex/ignoring
             (some-> (db/get-by-id pool :server-error-report id) :content db/decode-transit-pgobject)))

          (render-template [report]
            (let [context (dissoc report
                                  :trace :cause :params :data :spec-problems
                                  :spec-explain :spec-value :error :explain :hint)
                  params  {:context (with-out-str (fpp/pprint context {:width 300}))
                           :hint    (:hint report)
                           :spec-explain  (:spec-explain report)
                           :spec-problems (:spec-problems report)
                           :spec-value    (:spec-value report)
                           :data          (:data report)
                           :trace         (or (:trace report)
                                              (some-> report :error :trace))
                           :params        (:params report)}]
              (-> (io/resource "templates/error-report.tmpl")
                  (tmpl/render params))))
          ]

    (when-not (authorized? pool request)
      (ex/raise :type :authentication
                :code :only-admins-allowed))

    (let [result (some-> (parse-id request)
                         (retrieve-report)
                         (render-template))]
      (if result
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8"
                   "x-robots-tag" "noindex"}
         :body result}
        {:status 404
         :body "not found"}))))

(def sql:error-reports
  "select id, created_at from server_error_report order by created_at desc limit 100")

(defn retrieve-error-list
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))
  (let [items (db/exec! pool [sql:error-reports])
        items (map #(update % :created-at dt/format-instant :rfc1123) items)]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"
               "x-robots-tag" "noindex"}
     :body (-> (io/resource "templates/error-list.tmpl")
               (tmpl/render {:items items}))}))

(defn health-check
  "Mainly a task that performs a health check."
  [{:keys [pool]} _]
  (db/with-atomic [conn pool]
    (db/exec-one! conn ["select count(*) as count from server_prop;"])
    {:status 200 :body "Ok"}))

(defn- wrap-async
  [{:keys [executor] :as cfg} f]
  (fn [request respond raise]
    (-> (async/with-dispatch executor
          (f cfg request))
        (p/then respond)
        (p/catch raise))))

(defmethod ig/pre-init-spec ::handlers [_]
  (s/keys :req-un [::db/pool ::wrk/executor]))

(defmethod ig/init-key ::handlers
  [_ cfg]
  {:index (wrap-async cfg index)
   :health-check (wrap-async cfg health-check)
   :retrieve-file-data (wrap-async cfg retrieve-file-data)
   :retrieve-file-changes (wrap-async cfg retrieve-file-changes)
   :retrieve-error (wrap-async cfg retrieve-error)
   :retrieve-error-list (wrap-async cfg retrieve-error-list)
   :upload-file-data (wrap-async cfg upload-file-data)})
