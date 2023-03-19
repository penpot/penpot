;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.helpers
  (:require
   [app.auth]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.flags :as flags]
   [app.common.pages :as cp]
   [app.common.pprint :as pp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as main]
   [app.media :as-alias mtx]
   [app.media]
   [app.migrations]
   [app.msgbus :as-alias mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-create :as files.create]
   [app.rpc.commands.files-update :as files.update]
   [app.rpc.commands.teams :as teams]
   [app.rpc.helpers :as rph]
   [app.rpc.mutations.profile :as profile]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [environ.core :refer [env]]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [mockery.core :as mk]
   [promesa.core :as p]
   [yetti.request :as yrq])
  (:import
   java.util.UUID
   org.postgresql.ds.PGSimpleDataSource))

(def ^:dynamic *system* nil)
(def ^:dynamic *pool* nil)

(def defaults
  {:database-uri "postgresql://postgres/penpot_test"
   :redis-uri "redis://redis/1"})

(def config
  (->> (cf/read-env "penpot-test")
       (merge cf/defaults defaults)
       (us/conform ::cf/config)))

(def default-flags
  [:enable-secure-session-cookies
   :enable-email-verification
   :enable-smtp
   :enable-quotes])

(def test-init-sql
  ["alter table project_profile_rel set unlogged;\n"
   "alter table file_profile_rel set unlogged;\n"
   "alter table presence set unlogged;\n"
   "alter table presence set unlogged;\n"
   "alter table http_session set unlogged;\n"
   "alter table team_profile_rel set unlogged;\n"
   "alter table team_project_profile_rel set unlogged;\n"
   "alter table comment_thread_status set unlogged;\n"
   "alter table comment set unlogged;\n"
   "alter table comment_thread set unlogged;\n"
   "alter table profile_complaint_report set unlogged;\n"
   "alter table file_change set unlogged;\n"
   "alter table team_font_variant set unlogged;\n"
   "alter table share_link set unlogged;\n"
   "alter table usage_quote set unlogged;\n"
   "alter table access_token set unlogged;\n"
   "alter table profile set unlogged;\n"
   "alter table file_library_rel set unlogged;\n"
   "alter table file_thumbnail set unlogged;\n"
   "alter table file_object_thumbnail set unlogged;\n"
   "alter table file_media_object set unlogged;\n"
   "alter table file_data_fragment set unlogged;\n"
   "alter table file set unlogged;\n"
   "alter table project set unlogged;\n"
   "alter table team_invitation set unlogged;\n"
   "alter table webhook_delivery set unlogged;\n"
   "alter table webhook set unlogged;\n"
   "alter table team set unlogged;\n"
   ;; For some reason, modifying the task realted tables is very very
   ;; slow (5s); so we just don't alter them
   ;; "alter table task set unlogged;\n"
   ;; "alter table task_default set unlogged;\n"
   ;; "alter table task_completed set unlogged;\n"
   "alter table audit_log_default set unlogged ;\n"
   "alter table storage_object set unlogged;\n"
   "alter table server_error_report set unlogged;\n"
   "alter table server_prop set unlogged;\n"
   "alter table global_complaint_report set unlogged;\n"
])

(defn state-init
  [next]
  (with-redefs [app.config/flags (flags/parse flags/default default-flags)
                app.config/config config
                app.loggers.audit/submit! (constantly nil)
                app.auth/derive-password identity
                app.auth/verify-password (fn [a b] {:valid (= a b)})]

    (let [templates [{:id "test"
                      :name "test"
                      :file-uri "test"
                      :thumbnail-uri "test"
                      :path (-> "backend_tests/test_files/template.penpot" io/resource fs/path)}]
          system (-> (merge main/system-config main/worker-config)
                     (assoc-in [:app.redis/redis :app.redis/uri] (:redis-uri config))
                     (assoc-in [::db/pool ::db/uri] (:database-uri config))
                     (assoc-in [::db/pool ::db/username] (:database-username config))
                     (assoc-in [::db/pool ::db/password] (:database-password config))
                     (assoc-in [:app.rpc/methods :templates] templates)
                     (dissoc :app.srepl/server
                             :app.http/server
                             :app.http/router
                             :app.auth.oidc/google-provider
                             :app.auth.oidc/gitlab-provider
                             :app.auth.oidc/github-provider
                             :app.auth.oidc/generic-provider
                             :app.setup/builtin-templates
                             :app.auth.oidc/routes
                             :app.http.oauth/handler
                             :app.notifications/handler
                             :app.loggers.mattermost/reporter
                             :app.loggers.database/reporter
                             :app.worker/cron
                             :app.worker/dispatcher
                             [:app.main/default :app.worker/worker]
                             [:app.main/webhook :app.worker/worker]))
          _      (ig/load-namespaces system)
          system (-> (ig/prep system)
                     (ig/init))]
      (try
        (binding [*system* system
                  *pool*   (:app.db/pool system)]
          (db/with-atomic [conn *pool*]
            (doseq [sql test-init-sql]
              (db/exec! conn [sql])))
          (next))
        (finally
          (ig/halt! system))))))

(defn database-reset
  [next]
  (let [sql (str "SELECT table_name "
                 "  FROM information_schema.tables "
                 " WHERE table_schema = 'public' "
                 "   AND table_name != 'migrations';")]
    (db/with-atomic [conn *pool*]
      (let [result (->> (db/exec! conn [sql])
                        (map :table-name)
                        (remove #(= "task" %)))
            sql    (str "TRUNCATE "
                        (apply str (interpose ", " result))
                        " CASCADE;")]
        (doseq [table result]
          (db/exec! conn [(str "delete from " table ";")]))))

    (next)))

(defn clean-storage
  [next]
  (let [path (fs/path "/tmp/penpot")]
    (when (fs/exists? path)
      (fs/delete (fs/path "/tmp/penpot")))
    (next)))

(defn serial
  [& funcs]
  (fn [next]
    (loop [f   (first funcs)
           fs  (rest funcs)]
      (when f
        (let [prm (promise)]
          (f #(deliver prm true))
          (deref prm)
          (recur (first fs)
                 (rest fs)))))
    (next)))

(defn mk-uuid
  [prefix & args]
  (UUID/nameUUIDFromBytes (-> (apply str prefix args)
                              (.getBytes "UTF-8"))))
;; --- FACTORIES

(defn create-profile*
  ([i] (create-profile* *pool* i {}))
  ([i params] (create-profile* *pool* i params))
  ([pool i params]
   (let [params (merge {:id (mk-uuid "profile" i)
                        :fullname (str "Profile " i)
                        :email (str "profile" i ".test@nodomain.com")
                        :password "123123"
                        :is-demo false}
                       params)]
     (dm/with-open [conn (db/open pool)]
       (->> params
            (cmd.auth/create-profile! conn)
            (cmd.auth/create-profile-rels! conn))))))

(defn create-project*
  ([i params] (create-project* *pool* i params))
  ([pool i {:keys [profile-id team-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? team-id)
   (dm/with-open [conn (db/open pool)]
     (->> (merge {:id (mk-uuid "project" i)
                  :name (str "project" i)}
                 params)
          (#'teams/create-project conn)))))

(defn create-file*
  ([i params]
   (create-file* *pool* i params))
  ([pool i {:keys [profile-id project-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? project-id)
   (dm/with-open [conn (db/open pool)]
     (files.create/create-file conn
                               (merge {:id (mk-uuid "file" i)
                                       :name (str "file" i)
                                       :components-v2 true}
                                      params)))))

(defn mark-file-deleted*
  ([params] (mark-file-deleted* *pool* params))
  ([conn {:keys [id] :as params}]
   (#'files/mark-file-deleted conn {:id id})))

(defn create-team*
  ([i params] (create-team* *pool* i params))
  ([pool i {:keys [profile-id] :as params}]
   (us/assert uuid? profile-id)
   (dm/with-open [conn (db/open pool)]
     (let [id   (mk-uuid "team" i)]
       (teams/create-team conn {:id id
                                :profile-id profile-id
                                :name (str "team" i)})))))

(defn create-file-media-object*
  ([params] (create-file-media-object* *pool* params))
  ([pool {:keys [name width height mtype file-id is-local media-id]
          :or {name "sample" width 100 height 100 mtype "image/svg+xml" is-local true}}]

   (dm/with-open [conn (db/open pool)]
     (db/insert! conn :file-media-object
                 {:id (uuid/next)
                  :file-id file-id
                  :is-local is-local
                  :name name
                  :media-id media-id
                  :width  width
                  :height height
                  :mtype  mtype}))))

(defn link-file-to-library*
  ([params] (link-file-to-library* *pool* params))
  ([pool {:keys [file-id library-id] :as params}]
   (dm/with-open [conn (db/open pool)]
     (#'files/link-file-to-library conn {:file-id file-id :library-id library-id}))))

(defn create-complaint-for
  [pool {:keys [id created-at type]}]
  (dm/with-open [conn (db/open pool)]
    (db/insert! conn :profile-complaint-report
                {:profile-id id
                 :created-at (or created-at (dt/now))
                 :type (name type)
                 :content (db/tjson {})})))

(defn create-global-complaint-for
  [pool {:keys [email type created-at]}]
  (dm/with-open [conn (db/open pool)]
    (db/insert! conn :global-complaint-report
                {:email email
                 :type (name type)
                 :created-at (or created-at (dt/now))
                 :content (db/tjson {})})))

(defn create-team-role*
  ([params] (create-team-role* *pool* params))
  ([pool {:keys [team-id profile-id role] :or {role :owner}}]
   (dm/with-open [conn (db/open pool)]
     (#'teams/create-team-role conn {:team-id team-id
                                     :profile-id profile-id
                                     :role role}))))

(defn create-project-role*
  ([params] (create-project-role* *pool* params))
  ([pool {:keys [project-id profile-id role] :or {role :owner}}]
   (dm/with-open [conn (db/open pool)]
     (#'teams/create-project-role conn {:project-id project-id
                                           :profile-id profile-id
                                           :role role}))))

(defn create-file-role*
  ([params] (create-file-role* *pool* params))
  ([pool {:keys [file-id profile-id role] :or {role :owner}}]
   (dm/with-open [conn (db/open pool)]
     (files.create/create-file-role! conn {:file-id file-id
                                           :profile-id profile-id
                                           :role role}))))

(defn update-file*
  ([params] (update-file* *pool* params))
  ([pool {:keys [file-id changes session-id profile-id revn]
          :or {session-id (uuid/next) revn 0}}]
   (dm/with-open [conn (db/open pool)]
     (let [features #{"components/v2"}
           cfg      (-> (select-keys *system* [::mbus/msgbus ::mtx/metrics])
                        (assoc ::db/conn conn))]
       (files.update/update-file cfg
                                 {:id file-id
                                  :revn revn
                                  :features features
                                  :changes changes
                                  :session-id session-id
                                  :profile-id profile-id})))))

(defn create-webhook*
  ([params] (create-webhook* *pool* params))
  ([pool {:keys [team-id id uri mtype is-active]
          :or {is-active true
               mtype "application/json"
               uri "http://example.com/webhook"}}]
   (db/insert! pool :webhook
               {:id (or id (uuid/next))
                :team-id team-id
                :uri uri
                :is-active is-active
                :mtype mtype})))

;; --- RPC HELPERS

(defn handle-error
  [^Throwable err]
  (if (instance? java.util.concurrent.ExecutionException err)
    (handle-error (.getCause err))
    err))

(defmacro try-on!
  [expr]
  `(try
     (let [result# ~expr
           result# (cond-> result# (rph/wrapped? result#) deref)]
       {:error nil
        :result result#})
     (catch Exception e#
       {:error (handle-error e#)
        :result nil})))

(defn command!
  [{:keys [::type] :as data}]
  (let [[mdata method-fn] (get-in *system* [:app.rpc/methods :commands type])]
    (when-not method-fn
      (ex/raise :type :assertion
                :code :rpc-method-not-found
                :hint (str/ffmt "rpc method '%' not found" (name type))))

    ;; (app.common.pprint/pprint (:app.rpc/methods *system*))
    (try-on! (method-fn (-> data
                            (dissoc ::type)
                            (assoc :app.rpc/request-at (dt/now)))))))

(defn mutation!
  [{:keys [::type profile-id] :as data}]
  (let [[mdata method-fn] (get-in *system* [:app.rpc/methods :mutations type])]
    (try-on! (method-fn (-> data
                            (dissoc ::type)
                            (assoc ::rpc/profile-id profile-id)
                            (d/without-nils))))))

(defn query!
  [{:keys [::type profile-id] :as data}]
  (let [[mdata method-fn] (get-in *system* [:app.rpc/methods :queries type])]
    (try-on! (method-fn (-> data
                            (dissoc ::type)
                            (assoc ::rpc/profile-id profile-id)
                            (d/without-nils))))))


(defn run-task!
  ([name]
   (run-task! name {}))
  ([name params]
   (let [tasks (:app.worker/registry *system*)]
     (let [task-fn (get tasks (d/name name))]
       (task-fn params)))))

;; --- UTILS

(defn print-error!
  [error]
  (let [data (ex-data error)]
    (cond
      (= :spec-validation (:code data))
      (println
       (us/pretty-explain data))

      (= :service-error (:type data))
      (print-error! (.getCause ^Throwable error))

      :else
      (.printStackTrace ^Throwable error))))

(defn print-result!
  [{:keys [error result]}]
  (if error
    (do
      (println "====> START ERROR")
      (print-error! error)
      (println "====> END ERROR"))
    (do
      (println "====> START RESPONSE")
      (pp/pprint result)
      (println "====> END RESPONSE"))))

(defn exception?
  [v]
  (instance? Throwable v))

(defn ex-info?
  [v]
  (ex/error? v))

(defn ex-type
  [e]
  (:type (ex-data e)))

(defn ex-code
  [e]
  (:code (ex-data e)))

(defn ex-of-type?
  [e type]
  (let [data (ex-data e)]
    (= type (:type data))))

(defn ex-of-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

(defn ex-with-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

(defn success?
  [{:keys [result error]}]
  (nil? error))

(defn tempfile
  [source]
  (let [rsc (io/resource source)
        tmp (fs/create-tempfile)]
    (io/copy (io/file rsc)
             (io/file tmp))
    tmp))

(defn pause
  []
  (let [^java.io.Console cnsl (System/console)]
    (println "[waiting RETURN]")
    (.readLine cnsl)
    nil))

(defn db-exec!
  [sql]
  (db/exec! *pool* sql))

(defn db-insert!
  [& params]
  (apply db/insert! *pool* params))

(defn db-query
  [& params]
  (apply db/query *pool* params))

(defn db-get
  [& params]
  (apply db/get* *pool* params))

(defn sleep
  [ms-or-duration]
  (Thread/sleep (inst-ms (dt/duration ms-or-duration))))

(defn config-get-mock
  [data]
  (fn
    ([key]
     (get data key (get cf/config key)))
    ([key default]
     (get data key (get cf/config key default)))))

(defn reset-mock!
  [m]
  (swap! m (fn [m]
             (-> m
                 (assoc :called? false)
                 (assoc :call-count 0)
                 (assoc :return-list [])
                 (assoc :call-args nil)
                 (assoc :call-args-list [])))))
