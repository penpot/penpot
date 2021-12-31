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
   [app.rpc.queries.profile :as profile]
   [app.util.blob :as blob]
   [app.util.template :as tmpl]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.pprint :as ppr]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(def sql:retrieve-range-of-changes
  "select revn, changes from file_change where file_id=? and revn >= ? and revn <= ? order by revn")

(def sql:retrieve-single-change
  "select revn, changes, data from file_change where file_id=? and revn = ?")

(defn authorized?
  [pool {:keys [profile-id]}]
  (or (= "devenv" (cf/get :host))
      (let [profile (ex/ignoring (profile/retrieve-profile-data pool profile-id))
            admins  (or (cf/get :admins) #{})]
        (contains? admins (:email profile)))))

(defn prepare-response
  [body]
  (when-not body
    (ex/raise :type :not-found
              :code :enpty-data
              :hint "empty response"))

  {:status 200
   :headers {"content-type" "application/transit+json"}
   :body body})

(defn retrieve-file-data
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  (let [id    (some-> (get-in request [:path-params :id]) uuid/uuid)
        revn  (some-> (get-in request [:params :revn]) d/parse-integer)]
    (when-not id
      (ex/raise :type :validation
                :code :missing-arguments))

    (if (integer? revn)
      (let [fchange (db/exec-one! pool [sql:retrieve-single-change id revn])]
        (prepare-response (some-> fchange :data blob/decode)))

      (let [file (db/get-by-id pool :file id)]
        (prepare-response (some-> file :data blob/decode))))))

(defn retrieve-file-changes
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  (let [id    (some-> (get-in request [:path-params :id]) uuid/uuid)
        revn  (get-in request [:params :revn] "latest")]

    (when (or (not id) (not revn))
      (ex/raise :type :validation
                :code :invalid-arguments
                :hint "missing arguments"))

    (cond
      (d/num-string? revn)
      (let [item (db/exec-one! pool [sql:retrieve-single-change id (d/parse-integer revn)])]
        (prepare-response (some-> item :changes blob/decode vec)))

      (str/includes? revn ":")
      (let [[start end] (->> (str/split revn #":")
                             (map str/trim)
                             (map d/parse-integer))
            items       (db/exec! pool [sql:retrieve-range-of-changes id start end])]
        (prepare-response  (some->> items
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
            (binding [ppr/*print-right-margin* 300]
              (let [context (dissoc report :trace :cause :params :data :spec-prob :spec-problems :error :explain)
                    params  {:context (with-out-str (ppr/pprint context))
                             :data    (:data report)
                             :trace   (or (:cause report)
                                          (:trace report)
                                          (some-> report :error :trace))
                             :params  (:params report)}]
                (-> (io/resource "error-report.tmpl")
                    (tmpl/render params)))))
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
     :body (-> (io/resource "error-list.tmpl")
               (tmpl/render {:items items}))}))

(defmethod ig/init-key ::handlers
  [_ cfg]
  {:retrieve-file-data (partial retrieve-file-data cfg)
   :retrieve-file-changes (partial retrieve-file-changes cfg)
   :retrieve-error (partial retrieve-error cfg)
   :retrieve-error-list (partial retrieve-error-list cfg)})
