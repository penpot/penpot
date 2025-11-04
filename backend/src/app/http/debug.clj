;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.debug
  (:refer-clojure :exclude [error-handler])
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-migrations :as feat.fmig]
   [app.http.session :as session]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.files-create :refer [create-file]]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.setup :as-alias setup]
   [app.setup.clock :as clock]
   [app.srepl.main :as srepl]
   [app.storage :as-alias sto]
   [app.storage.tmp :as tmp]
   [app.util.blob :as blob]
   [app.util.template :as tmpl]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [emoji.core :as emj]
   [integrant.core :as ig]
   [markdown.core :as md]
   [markdown.transformers :as mdt]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

;; (selmer.parser/cache-off!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INDEX
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index-handler
  [_cfg _request]
  (let [{:keys [clock offset]} @clock/current]
    {::yres/status  200
     ::yres/headers {"content-type" "text/html"}
     ::yres/body    (-> (io/resource "app/templates/debug.tmpl")
                        (tmpl/render {:version (:full cf/version)
                                      :current-clock  (str clock)
                                      :current-offset (if offset
                                                        (ct/format-duration offset)
                                                        "NO OFFSET")
                                      :current-time  (ct/format-inst (ct/now) :http)
                                      :supported-features cfeat/supported-features}))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE CHANGES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-resolved-file
  [cfg file-id]
  (some-> (bfc/get-file cfg file-id :migrate? false)
          (update :data blob/encode)))

(defn prepare-download
  [file filename]
  {::yres/status 200
   ::yres/headers
   {"content-disposition" (str "attachment; filename=" filename ".json")
    "content-type" "application/octet-stream"}
   ::yres/body
   (t/encode file {:type :json-verbose})})

(def sql:retrieve-range-of-changes
  "select revn, changes from file_change where file_id=? and revn >= ? and revn <= ? order by revn")

(def sql:retrieve-single-change
  "select revn, changes, data from file_change where file_id=? and revn = ?")

(defn- download-file-data
  [cfg {:keys [params ::session/profile-id] :as request}]
  (let [file-id  (some-> params :file-id parse-uuid)
        filename (str file-id)]

    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    (if-let [file (get-resolved-file cfg file-id)]
      (cond
        (contains? params :download)
        (prepare-download file filename)

        (contains? params :clone)
        (db/tx-run! cfg
                    (fn [{:keys [::db/conn] :as cfg}]
                      (let [profile    (profile/get-profile conn profile-id)
                            project-id (:default-project-id profile)
                            file       (-> (create-file cfg {:id (uuid/next)
                                                             :name (str "Cloned: " (:name file))
                                                             :features (:features file)
                                                             :project-id project-id
                                                             :profile-id profile-id})
                                           (assoc :data (:data file))
                                           (assoc :migrations (:migrations file)))]

                        (feat.fmig/reset-migrations! conn file)
                        (db/update! conn :file
                                    {:data (:data file)}
                                    {:id (:id file)}
                                    {::db/return-keys false})


                        {::yres/status 201
                         ::yres/body "OK CLONED"})))

        :else
        (ex/raise :type :validation
                  :code :invalid-params
                  :hint "invalid button"))

      (ex/raise :type :not-found
                :code :enpty-data
                :hint "empty response"))))

(defn- is-file-exists?
  [pool id]
  (let [sql "select exists (select 1 from file where id=?) as exists;"]
    (-> (db/exec-one! pool [sql id]) :exists)))

(defn- upload-file-data
  [{:keys [::db/pool] :as cfg} {:keys [::session/profile-id params] :as request}]
  (let [profile    (profile/get-profile pool profile-id)
        project-id (:default-project-id profile)
        file       (some-> params :file :path io/read* t/decode)]

    (if (and file project-id)
      (let [fname     (str "Imported: " (:name file) "(" (ct/now) ")")
            reuse-id? (contains? params :reuseid)
            file-id   (or (and reuse-id? (ex/ignoring (-> params :file :filename parse-uuid)))
                          (uuid/next))]

        (if (and reuse-id? file-id
                 (is-file-exists? pool file-id))
          (db/tx-run! cfg
                      (fn [{:keys [::db/conn] :as cfg}]
                        (db/update! conn :file
                                    {:data (:data file)
                                     :features (into-array (:features file))
                                     :deleted-at nil}
                                    {:id file-id}
                                    {::db/return-keys false})
                        (feat.fmig/reset-migrations! conn file)
                        {::yres/status 200
                         ::yres/body "OK UPDATED"}))

          (db/tx-run! cfg
                      (fn [{:keys [::db/conn] :as cfg}]
                        (let [file (-> (create-file cfg {:id file-id
                                                         :name fname
                                                         :features (:features file)
                                                         :project-id project-id
                                                         :profile-id profile-id})
                                       (assoc :data (:data file))
                                       (assoc :migrations (:migrations file)))]

                          (db/update! conn :file
                                      {:data (:data file)}
                                      {:id file-id}
                                      {::db/return-keys false})
                          (feat.fmig/reset-migrations! conn file)
                          {::yres/status 201
                           ::yres/body "OK CREATED"})))))

      (ex/raise :type :validation
                :code :invalid-params
                :hint "invalid file uploaded"))))

(defn raw-export-import-handler
  [cfg request]
  (case (yreq/method request)
    :get (download-file-data cfg request)
    :post (upload-file-data cfg request)
    (ex/raise :type :http
              :code :method-not-found)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ERROR BROWSER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn error-handler
  [{:keys [::db/pool]} request]
  (letfn [(get-report [{:keys [path-params]}]
            (ex/ignoring
             (let [report-id (some-> path-params :id parse-uuid)]
               (some-> (db/get-by-id pool :server-error-report report-id)
                       (update :content db/decode-transit-pgobject)))))

          (render-template-v1 [{:keys [content]}]
            (let [context (dissoc content
                                  :trace :cause :params :data :spec-problems :message
                                  :spec-explain :spec-value :error :explain :hint)
                  params  {:context       (pp/pprint-str context :width 200)
                           :hint          (:hint content)
                           :spec-explain  (:spec-explain content)
                           :spec-problems (:spec-problems content)
                           :spec-value    (:spec-value content)
                           :data          (:data content)
                           :trace         (or (:trace content)
                                              (some-> content :error :trace))
                           :params        (:params content)}]
              (-> (io/resource "app/templates/error-report.tmpl")
                  (tmpl/render params))))

          (render-template-v2 [{report :content}]
            (-> (io/resource "app/templates/error-report.v2.tmpl")
                (tmpl/render report)))

          (render-template-v3 [{:keys [content id created-at]}]
            (-> (io/resource "app/templates/error-report.v3.tmpl")
                (tmpl/render (-> content
                                 (assoc :id id)
                                 (assoc :created-at (ct/format-inst created-at :rfc1123))))))]

    (if-let [report (get-report request)]
      (let [result (case (:version report)
                     1 (render-template-v1 report)
                     2 (render-template-v2 report)
                     3 (render-template-v3 report))]
        {::yres/status 200
         ::yres/body result
         ::yres/headers {"content-type" "text/html; charset=utf-8"
                         "x-robots-tag" "noindex"}})
      {::yres/status 404
       ::yres/body "not found"})))

(def sql:error-reports
  "SELECT id, created_at,
          content->>'~:hint' AS hint
     FROM server_error_report
    ORDER BY created_at DESC
    LIMIT 200")

(defn error-list-handler
  [{:keys [::db/pool]} _request]
  (let [items (->> (db/exec! pool [sql:error-reports])
                   (map #(update % :created-at ct/format-inst :rfc1123)))]
    {::yres/status 200
     ::yres/body (-> (io/resource "app/templates/error-list.tmpl")
                     (tmpl/render {:items items}))
     ::yres/headers {"content-type" "text/html; charset=utf-8"
                     "x-robots-tag" "noindex"}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXPORT/IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export-handler
  [{:keys [::db/pool] :as cfg} {:keys [params ::session/profile-id] :as request}]

  (let [file-ids (into #{}
                       (comp (remove empty?)
                             (map parse-uuid))
                       (:file-ids params))
        libs?    (contains? params :includelibs)
        clone?   (contains? params :clone)
        embed?   (contains? params :embedassets)]

    (when-not (seq file-ids)
      (ex/raise :type :validation
                :code :missing-arguments))

    (let [path (tmp/tempfile :prefix "penpot.export." :min-age "30m")]
      (with-open [output (io/output-stream path)]
        (-> cfg
            (assoc ::bfc/ids file-ids)
            (assoc ::bfc/embed-assets embed?)
            (assoc ::bfc/include-libraries libs?)
            (bf.v3/export-files! output)))

      (if clone?
        (let [profile    (profile/get-profile pool profile-id)
              project-id (:default-project-id profile)
              cfg        (assoc cfg
                                ::bfc/overwrite false
                                ::bfc/profile-id profile-id
                                ::bfc/project-id project-id
                                ::bfc/input path)]
          (bf.v3/import-files! cfg)
          {::yres/status  200
           ::yres/headers {"content-type" "text/plain"}
           ::yres/body    "OK CLONED"})

        {::yres/status  200
         ::yres/body    (io/input-stream path)
         ::yres/headers {"content-type" "application/octet-stream"
                         "content-disposition" (str "attachmen; filename=" (first file-ids) ".penpot")}}))))


(defn import-handler
  [{:keys [::db/pool] :as cfg} {:keys [params ::session/profile-id] :as request}]
  (when-not (contains? params :file)
    (ex/raise :type :validation
              :code :missing-upload-file
              :hint "missing upload file"))

  (let [profile    (profile/get-profile pool profile-id)
        project-id (:default-project-id profile)
        team       (teams/get-team pool
                                   :profile-id profile-id
                                   :project-id project-id)]

    (when-not project-id
      (ex/raise :type :validation
                :code :missing-project
                :hint "project not found"))

    (let [path   (-> params :file :path)
          format (bfc/parse-file-format path)
          cfg    (assoc cfg
                        ::bfc/profile-id profile-id
                        ::bfc/project-id project-id
                        ::bfc/input path
                        ::bfc/features (cfeat/get-team-enabled-features cf/flags team))]

      (if (= format :binfile-v3)
        (bf.v3/import-files! cfg)
        (bf.v1/import-files! cfg))

      {::yres/status  200
       ::yres/headers {"content-type" "text/plain"}
       ::yres/body    "OK"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resend-email-notification
  [cfg {:keys [params] :as request}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (when-not (contains? params :force)
                      (ex/raise :type :validation
                                :code :missing-force
                                :hint "missing force checkbox"))

                    (let [profile (some->> params
                                           :email
                                           (profile/clean-email)
                                           (profile/get-profile-by-email conn))]

                      (when-not profile
                        (ex/raise :type :validation
                                  :code :missing-profile
                                  :hint "unable to find profile by email"))

                      (cond
                        (contains? params :block)
                        (do
                          (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
                          (db/delete! conn :http-session {:profile-id (:id profile)})

                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    (str/ffmt "PROFILE '%' BLOCKED" (:email profile))})

                        (contains? params :unblock)
                        (do
                          (db/update! conn :profile {:is-blocked false} {:id (:id profile)})
                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    (str/ffmt "PROFILE '%' UNBLOCKED" (:email profile))})

                        (contains? params :resend)
                        (if (:is-blocked profile)
                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    "PROFILE ALREADY BLOCKED"}
                          (do
                            (#'auth/send-email-verification! cfg profile)
                            {::yres/status  200
                             ::yres/headers {"content-type" "text/plain"}
                             ::yres/body    (str/ffmt "RESENDED FOR '%'" (:email profile))}))

                        :else
                        (do
                          (db/update! conn :profile {:is-active true} {:id (:id profile)})
                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    (str/ffmt "PROFILE '%' ACTIVATED" (:email profile))}))))))

(defn- handle-team-features
  [cfg {:keys [params] :as request}]
  (let [team-id    (some-> params :team-id d/parse-uuid)
        feature    (some-> params :feature str)
        action     (some-> params :action)
        skip-check (contains? params :skip-check)]

    (when (nil? team-id)
      (ex/raise :type :validation
                :code :invalid-team-id
                :hint "provided invalid team id"))

    (if (= action "show")
      (let [team (db/run! cfg teams/get-team-info {:id team-id})]
        {::yres/status 200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body (apply str "Team features:\n"
                            (->> (:features team)
                                 (map (fn [feature]
                                        (str "- " feature "\n")))))})

      (do
        (when-not (contains? params :force)
          (ex/raise :type :validation
                    :code :missing-force
                    :hint "missing force checkbox"))

        (cond
          (= action "enable")
          (srepl/enable-team-feature! team-id feature :skip-check skip-check)

          (= action "disable")
          (srepl/disable-team-feature! team-id feature :skip-check skip-check)

          :else
          (ex/raise :type :validation
                    :code :invalid-action
                    :hint (str "invalid action: " action)))


        {::yres/status  200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body    "OK"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VIRTUAL CLOCK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-virtual-clock
  [_ {:keys [params] :as request}]
  (let [offset (some-> params :offset str/trim not-empty ct/duration)
        reset? (contains? params :reset)]
    (if (= "production" (cf/get :tenant))
      {::yres/status 501
       ::yres/body "OPERATION NOT ALLOWED"}
      (do
        (if (or reset? (zero? (inst-ms offset)))
          (clock/set-offset! nil)
          (clock/set-offset! offset))
        {::yres/status 302
         ::yres/headers {"location" "/dbg"}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OTHER SMALL VIEWS/HANDLERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn health-handler
  "Mainly a task that performs a health check."
  [{:keys [::db/pool]} _]
  (try
    (db/exec-one! pool ["select count(*) as count from server_prop;"])
    {::yres/status 200
     ::yres/body "OK"}
    (catch Throwable cause
      (l/warn :hint "unable to execute query on health handler"
              :cause cause)
      {::yres/status 503
       ::yres/body "KO"})))

(defn changelog-handler
  [_ _]
  (letfn [(transform-emoji [text state]
            [(emj/emojify text) state])
          (md->html [text]
            (md/md-to-html-string text :replacement-transformers (into [transform-emoji] mdt/transformer-vector)))]
    (if-let [clog (io/resource "changelog.md")]
      {::yres/status 200
       ::yres/headers {"content-type" "text/html; charset=utf-8"}
       ::yres/body (-> clog slurp md->html)}
      {::yres/status 404
       ::yres/body "NOT FOUND"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn authorized?
  [pool {:keys [::session/profile-id]}]
  (or (= "devenv" (cf/get :host))
      (let [profile (ex/ignoring (profile/get-profile pool profile-id))
            admins  (or (cf/get :admins) #{})]
        (contains? admins (:email profile)))))

(def with-authorization
  {:compile
   (fn [& _]
     (fn [handler pool]
       (fn [request]
         (if (authorized? pool request)
           (handler request)
           (ex/raise :type :authentication
                     :code :only-admins-allowed)))))})

(def errors
  (letfn [(handle-error [cause]
            (when-let [data (ex-data cause)]
              (when (= :validation (:type data))
                (str "Error: " (or (:hint data) (ex-message cause)) "\n"))))]
    {:name ::errors
     :compile
     (fn [& _params]
       (fn [handler]
         (fn [request]
           (try
             (handler request)
             (catch Throwable cause
               (let [body (or (handle-error cause)
                              (ex/format-throwable cause))]
                 {::yres/status 400
                  ::yres/headers {"content-type" "text/plain"}
                  ::yres/body body}))))))}))

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (session/manager? (::session/manager params)) "expected a valid session manager"))

(defmethod ig/init-key ::routes
  [_ {:keys [::db/pool] :as cfg}]
  [["/readyz" {:handler (partial health-handler cfg)}]
   ["/dbg" {:middleware [[session/authz cfg]
                         [with-authorization pool]]}
    ["" {:handler (partial index-handler cfg)}]
    ["/health" {:handler (partial health-handler cfg)}]
    ["/changelog" {:handler (partial changelog-handler cfg)}]
    ["/error/:id" {:handler (partial error-handler cfg)}]
    ["/error" {:handler (partial error-list-handler cfg)}]
    ["/actions" {:middleware [[errors]]}
     ["/set-virtual-clock"
      {:handler (partial set-virtual-clock cfg)}]
     ["/resend-email-verification"
      {:handler (partial resend-email-notification cfg)}]
     ["/handle-team-features"
      {:handler (partial handle-team-features cfg)}]
     ["/file-export" {:handler (partial export-handler cfg)}]
     ["/file-import" {:handler (partial import-handler cfg)}]
     ["/file-raw-export-import" {:handler (partial raw-export-import-handler cfg)}]]]])

