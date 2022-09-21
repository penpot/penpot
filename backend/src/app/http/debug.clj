;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.debug
  (:refer-clojure :exclude [error-handler])
  (:require
   [app.common.exceptions :as ex]
   [app.common.pprint :as pp]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.middleware :as mw]
   [app.rpc.commands.binfile :as binf]
   [app.rpc.mutations.files :refer [create-file]]
   [app.rpc.queries.profile :as profile]
   [app.util.blob :as blob]
   [app.util.template :as tmpl]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [emoji.core :as emj]
   [integrant.core :as ig]
   [markdown.core :as md]
   [markdown.transformers :as mdt]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

;; (selmer.parser/cache-off!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn authorized?
  [pool {:keys [profile-id]}]
  (or (= "devenv" (cf/get :host))
      (let [profile (ex/ignoring (profile/retrieve-profile-data pool profile-id))
            admins  (or (cf/get :admins) #{})]
        (contains? admins (:email profile)))))

(defn prepare-response
  [body]
  (let [headers {"content-type" "application/transit+json"}]
    (yrs/response :status 200 :body body :headers headers)))

(defn prepare-download-response
  [body filename]
  (let [headers {"content-disposition" (str "attachment; filename=" filename)
                 "content-type" "application/octet-stream"}]
    (yrs/response :status 200 :body body :headers headers)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INDEX
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index-handler
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))
  (yrs/response :status  200
                :headers {"content-type" "text/html"}
                :body    (-> (io/resource "app/templates/debug.tmpl")
                             (tmpl/render {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE CHANGES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:retrieve-range-of-changes
  "select revn, changes from file_change where file_id=? and revn >= ? and revn <= ? order by revn")

(def sql:retrieve-single-change
  "select revn, changes, data from file_change where file_id=? and revn = ?")

(defn- retrieve-file-data
  [{:keys [pool]} {:keys [params profile-id] :as request}]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  (let [file-id  (some-> params :file-id parse-uuid)
        revn     (some-> params :revn parse-long)
        filename (str file-id)]

    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    (let [data (if (integer? revn)
                 (some-> (db/exec-one! pool [sql:retrieve-single-change file-id revn]) :data)
                 (some-> (db/get-by-id pool :file file-id) :data))]

      (when-not data
        (ex/raise :type :not-found
                  :code :enpty-data
                  :hint "empty response"))
      (cond
        (contains? params :download)
        (prepare-download-response data filename)

        (contains? params :clone)
        (let [project-id (some-> (profile/retrieve-additional-data pool profile-id) :default-project-id)
              data       (some-> data blob/decode)]
          (create-file pool {:id (uuid/next)
                             :name (str "Cloned file: " filename)
                             :project-id project-id
                             :profile-id profile-id
                             :data data})
          (yrs/response 201 "OK CREATED"))

        :else
        (prepare-response (some-> data blob/decode))))))

(defn- is-file-exists?
  [pool id]
  (let [sql "select exists (select 1 from file where id=?) as exists;"]
    (-> (db/exec-one! pool [sql id]) :exists)))

(defn- upload-file-data
  [{:keys [pool]} {:keys [profile-id params] :as request}]
  (let [project-id (some-> (profile/retrieve-additional-data pool profile-id) :default-project-id)
        data       (some-> params :file :path io/read-as-bytes blob/decode)]

    (if (and data project-id)
      (let [fname      (str "Imported file *: " (dt/now))
            overwrite? (contains? params :overwrite?)
            file-id    (or (and overwrite? (ex/ignoring (-> params :file :filename parse-uuid)))
                           (uuid/next))]

        (if (and overwrite? file-id
                 (is-file-exists? pool file-id))
          (do
            (db/update! pool :file
                        {:data (blob/encode data)}
                        {:id file-id})
            (yrs/response 200 "OK UPDATED"))

          (do
            (create-file pool {:id file-id
                               :name fname
                               :project-id project-id
                               :profile-id profile-id
                               :data data})
            (yrs/response 201 "OK CREATED"))))

      (yrs/response 500 "ERROR"))))

(defn file-data-handler
  [cfg request]
  (case (yrq/method request)
    :get (retrieve-file-data cfg request)
    :post (upload-file-data cfg request)
    (ex/raise :type :http
              :code :method-not-found)))

(defn file-changes-handler
  [{:keys [pool]} {:keys [params] :as request}]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))

  (letfn [(retrieve-changes [file-id revn]
            (if (str/includes? revn ":")
              (let [[start end] (->> (str/split revn #":")
                                     (map str/trim)
                                     (map parse-long))]
                (some->> (db/exec! pool [sql:retrieve-range-of-changes file-id start end])
                         (map :changes)
                         (map blob/decode)
                         (mapcat identity)
                         (vec)))

              (if-let [revn (parse-long revn)]
                (let [item (db/exec-one! pool [sql:retrieve-single-change file-id revn])]
                  (some-> item :changes blob/decode vec))
                (ex/raise :type :validation :code :invalid-arguments))))]

    (let [file-id  (some-> params :id parse-uuid)
          revn     (or (some-> params :revn parse-long) "latest")
          filename (str file-id)]

      (when (or (not file-id) (not revn))
        (ex/raise :type :validation
                  :code :invalid-arguments
                  :hint "missing arguments"))

      (let [data (retrieve-changes file-id revn)]
        (if (contains? params :download)
          (prepare-download-response data filename)
          (prepare-response data))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ERROR BROWSER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn error-handler
  [{:keys [pool]} request]
  (letfn [(parse-id [request]
            (let [id (get-in request [:path-params :id])
                  id (parse-uuid id)]
              (when (uuid? id)
                id)))

          (retrieve-report [id]
            (ex/ignoring
             (some-> (db/get-by-id pool :server-error-report id) :content db/decode-transit-pgobject)))

          (render-template [report]
            (let [context (dissoc report
                                  :trace :cause :params :data :spec-problems :message
                                  :spec-explain :spec-value :error :explain :hint)
                  params  {:context       (pp/pprint-str context :width 200)
                           :hint          (:hint report)
                           :spec-explain  (:spec-explain report)
                           :spec-problems (:spec-problems report)
                           :spec-value    (:spec-value report)
                           :data          (:data report)
                           :trace         (or (:trace report)
                                              (some-> report :error :trace))
                           :params        (:params report)}]
              (-> (io/resource "app/templates/error-report.tmpl")
                  (tmpl/render params))))]

    (when-not (authorized? pool request)
      (ex/raise :type :authentication
                :code :only-admins-allowed))

    (let [result (some-> (parse-id request)
                         (retrieve-report)
                         (render-template))]
      (if result
        (yrs/response :status 200
                      :body result
                      :headers {"content-type" "text/html; charset=utf-8"
                                "x-robots-tag" "noindex"})
        (yrs/response 404 "not found")))))

(def sql:error-reports
  "select id, created_at from server_error_report order by created_at desc limit 100")

(defn error-list-handler
  [{:keys [pool]} request]
  (when-not (authorized? pool request)
    (ex/raise :type :authentication
              :code :only-admins-allowed))
  (let [items (db/exec! pool [sql:error-reports])
        items (map #(update % :created-at dt/format-instant :rfc1123) items)]
    (yrs/response :status 200
                  :body (-> (io/resource "app/templates/error-list.tmpl")
                            (tmpl/render {:items items}))
                  :headers {"content-type" "text/html; charset=utf-8"
                            "x-robots-tag" "noindex"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXPORT/IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export-handler
  [{:keys [pool] :as cfg} {:keys [params profile-id] :as request}]

  (let [file-ids (->> (:file-ids params)
                      (remove empty?)
                      (mapv parse-uuid))
        libs?    (contains? params :includelibs)
        clone?   (contains? params :clone)
        embed?   (contains? params :embedassets)]

    (when-not (seq file-ids)
      (ex/raise :type :validation
                :code :missing-arguments))

    (let [path (-> cfg
                   (assoc ::binf/file-ids file-ids)
                   (assoc ::binf/embed-assets? embed?)
                   (assoc ::binf/include-libraries? libs?)
                   (binf/export!))]
      (if clone?
        (let [project-id (some-> (profile/retrieve-additional-data pool profile-id) :default-project-id)]
          (binf/import!
           (assoc cfg
                  ::binf/input path
                  ::binf/overwrite? false
                  ::binf/ignore-index-errors? true
                  ::binf/profile-id profile-id
                  ::binf/project-id project-id))

          (yrs/response
           :status  200
           :headers {"content-type" "text/plain"}
           :body    "OK CLONED"))

        (yrs/response
         :status  200
         :headers {"content-type" "application/octet-stream"
                   "content-disposition" (str "attachmen; filename=" (first file-ids) ".penpot")}
         :body    (io/input-stream path))))))


(defn import-handler
  [{:keys [pool] :as cfg} {:keys [params profile-id] :as request}]
  (when-not (contains? params :file)
    (ex/raise :type :validation
              :code :missing-upload-file
              :hint "missing upload file"))

  (let [project-id (some-> (profile/retrieve-additional-data pool profile-id) :default-project-id)
        overwrite? (contains? params :overwrite)
        migrate? (contains? params :migrate)
        ignore-index-errors? (contains? params :ignore-index-errors)]

    (when-not project-id
      (ex/raise :type :validation
                :code :missing-project
                :hint "project not found"))

    (binf/import!
     (assoc cfg
            ::binf/input (-> params :file :path)
            ::binf/overwrite? overwrite?
            ::binf/migrate? migrate?
            ::binf/ignore-index-errors? ignore-index-errors?
            ::binf/profile-id profile-id
            ::binf/project-id project-id))

    (yrs/response
     :status  200
     :headers {"content-type" "text/plain"}
     :body    "OK")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OTHER SMALL VIEWS/HANDLERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn health-handler
  "Mainly a task that performs a health check."
  [{:keys [pool]} _]
  (db/with-atomic [conn pool]
    (db/exec-one! conn ["select count(*) as count from server_prop;"])
    (yrs/response 200 "OK")))

(defn changelog-handler
  [_ _]
  (letfn [(transform-emoji [text state]
            [(emj/emojify text) state])
          (md->html [text]
            (md/md-to-html-string text :replacement-transformers (into [transform-emoji] mdt/transformer-vector)))]
    (if-let [clog (io/resource "changelog.md")]
      (yrs/response :status 200
                    :headers {"content-type" "text/html; charset=utf-8"}
                    :body (-> clog slurp md->html))
      (yrs/response :status 404 :body "NOT FOUND"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def with-authorization
  {:compile
   (fn [& _]
     (fn [handler pool]
       (fn [request respond raise]
         (if (authorized? pool request)
           (handler request respond raise)
           (raise (ex/error :type :authentication
                            :code :only-admins-allowed))))))})


(s/def ::session map?)

(defmethod ig/pre-init-spec ::routes [_]
  (s/keys :req-un [::db/pool ::wrk/executor ::session]))

(defmethod ig/init-key ::routes
  [_ {:keys [session pool executor] :as cfg}]
  [["/readyz" {:middleware [[mw/with-dispatch executor]
                            [mw/with-config cfg]]
               :handler health-handler}]
   ["/dbg" {:middleware [[(:middleware session)]
                         [with-authorization pool]
                         [mw/with-dispatch executor]
                         [mw/with-config cfg]]}
    ["" {:handler index-handler}]
    ["/health" {:handler health-handler}]
    ["/changelog" {:handler changelog-handler}]
    ;; ["/error-by-id/:id" {:handler error-handler}]
    ["/error/:id" {:handler error-handler}]
    ["/error" {:handler error-list-handler}]
    ["/file/export" {:handler export-handler}]
    ["/file/import" {:handler import-handler}]
    ["/file/data" {:handler file-data-handler}]
    ["/file/changes" {:handler file-changes-handler}]]])
