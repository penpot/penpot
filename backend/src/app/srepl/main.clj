;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.main
  "A collection of adhoc fixes scripts."
  #_:clj-kondo/ignore
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.pprint :as p]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [app.srepl.fixes :as f]
   [app.srepl.helpers :as h]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.pprint :refer [pprint print-table]]
   [cuerdas.core :as str]))

(defn print-available-tasks
  [system]
  (let [tasks (:app.worker/registry system)]
    (p/pprint (keys tasks) :level 200)))

(defn run-task!
  ([system name]
   (run-task! system name {}))
  ([system name params]
   (let [tasks (:app.worker/registry system)]
     (if-let [task-fn (get tasks name)]
       (task-fn params)
       (println (format "no task '%s' found" name))))))

(defn schedule-task!
  ([system name]
   (schedule-task! system name {}))
  ([system name props]
   (let [pool (:app.db/pool system)]
     (wrk/submit!
      ::wrk/conn pool
      ::wrk/task name
      ::wrk/props props))))

(defn send-test-email!
  [system destination]
  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (us/verify!
   :expr (string? destination)
   :hint "destination should be provided")

  (let [handler (:app.email/sendmail system)]
    (handler {:body "test email"
              :subject "test email"
              :to [destination]})))

(defn resend-email-verification-email!
  [system email]
  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (let [sprops  (:app.setup/props system)
        pool    (:app.db/pool system)
        profile (profile/get-profile-by-email pool email)]

    (auth/send-email-verification! pool sprops profile)
    :email-sent))

(defn mark-profile-as-active!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [system email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (db/get* conn :profile
                                {:email (str/lower email)}
                                {:columns [:id :email]})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-active true} {:id (:id profile)})
        :activated))))

(defn mark-profile-as-blocked!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [system email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (db/get* conn :profile
                                {:email (str/lower email)}
                                {:columns [:id :email]})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
        (db/delete! conn :http-session {:profile-id (:id profile)})
        :blocked))))


(defn enable-objects-map-feature-on-file!
  [system & {:keys [save? id]}]
  (letfn [(update-file [{:keys [features] :as file}]
            (if (contains? features "storage/objects-map")
              file
              (-> file
                  (update :data migrate)
                  (update :features conj "storage/objects-map"))))

          (migrate [data]
            (-> data
                (update :pages-index update-vals #(update % :objects omap/wrap))
                (update :components update-vals #(update % :objects omap/wrap))))]

    (h/update-file! system
                    :id id
                    :update-fn update-file
                    :save? save?)))

(defn enable-pointer-map-feature-on-file!
  [system & {:keys [save? id]}]
  (letfn [(update-file [{:keys [features] :as file}]
            (if (contains? features "storage/pointer-map")
              file
              (-> file
                  (update :data migrate)
                  (update :features conj "storage/pointer-map"))))

          (migrate [data]
            (-> data
                (update :pages-index update-vals pmap/wrap)
                (update :components pmap/wrap)))]

    (h/update-file! system
                    :id id
                    :update-fn update-file
                    :save? save?)))

(defn enable-storage-features-on-file!
  [system & {:as params}]
  (enable-objects-map-feature-on-file! system params)
  (enable-pointer-map-feature-on-file! system params))

(defn instrument-var
  [var]
  (alter-var-root var (fn [f]
                        (let [mf (meta f)]
                          (if (::original mf)
                            f
                            (with-meta
                              (fn [& params]
                                (tap> params)
                                (let [result (apply f params)]
                                  (tap> result)
                                  result))
                              {::original f}))))))

(defn uninstrument-var
  [var]
  (alter-var-root var (fn [f]
                        (or (::original (meta f)) f))))

(defn take-file-snapshot!
  "An internal helper that persist the file snapshot using non-gc
  collectable file-changes entry."
  [system & {:keys [file-id label]}]
  (let [label   (or label (str "Snapshot at " (dt/format-instant (dt/now) :rfc1123)))
        file-id (h/parse-uuid file-id)
        id      (uuid/next)]
    (db/tx-run! system
                (fn [{:keys [::db/conn]}]
                  (when-let [file (db/get* conn :file {:id file-id})]
                    (h/println! "=> persisting snapshot for" file-id)
                    (db/insert! conn :file-change
                                {:id id
                                 :revn (:revn file)
                                 :data (:data file)
                                 :features (:features file)
                                 :file-id (:id file)
                                 :label label})
                    id)))))

(defn restore-file-snapshot!
  [system & {:keys [file-id id label]}]
  (letfn [(restore-snapshot! [{:keys [::db/conn ::sto/storage]} file-id snapshot]
            (when (and (some? snapshot)
                       (some? (:data snapshot)))

              (h/println! "-> snapshot found:" (:id snapshot))
              (h/println! "-> restoring it on file:" file-id)
              (db/update! conn :file
                          {:data (:data snapshot)}
                          {:id file-id})

              ;; clean object thumbnails
              (let [sql (str "delete from file_object_thumbnail "
                             " where file_id=? returning media_id")
                    res (db/exec! conn [sql file-id])]
                (doseq [media-id (into #{} (keep :media-id) res)]
                  (sto/del-object! storage media-id)))))

          (execute [{:keys [::db/conn] :as cfg}]
            (let [file-id (h/parse-uuid file-id)
                  id      (h/parse-uuid id)
                  cfg     (update cfg ::sto/storage media/configure-assets-storage conn)]

              (cond
                (and (uuid? id) (uuid? file-id))
                (let [params   {:id id :file-id file-id}
                      options  {:columns [:id :data :revn]}
                      snapshot (db/get* conn :file-change params options)]
                  (restore-snapshot! cfg file-id snapshot))

                (uuid? file-id)
                (let [params   (cond-> {:file-id file-id}
                                 (string? label)
                                 (assoc :label label))
                      options  {:columns [:id :data :revn]}
                      snapshot (db/get* conn :file-change params options)]
                  (restore-snapshot! cfg file-id snapshot))

                :else
                (println "=> invalid parameters"))))]

    (db/tx-run! system execute)))

(defn list-file-snapshots!
  [system & {:keys [file-id limit chunk-size start-at]
             :or {chunk-size 10 limit Long/MAX_VALUE}}]

  (letfn [(get-chunk [ds cursor]
            (let [query   (str "select id, label, revn, created_at "
                               "  from file_change "
                               " where file_id = ? "
                               "   and created_at < ? "
                               "   and label is not null "
                               "   and data is not null "
                               " order by created_at desc "
                               "  limit ?")
                  file-id (if (string? file-id)
                            (d/parse-uuid file-id)
                            file-id)
                  rows    (db/exec! ds [query file-id cursor chunk-size])]
              [(some->> rows peek :created-at) (seq rows)]))

          (get-candidates [ds]
            (->> (d/iteration (partial get-chunk ds)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take limit)))]

    (db/tx-run! system (fn [system]
                         (->> (fsnap/get-file-snapshots
                              (map (fn [row]
                                     (update row :created-at dt/format-instant :rfc1123)))
                              (print-table [:id :revn :created-at :label]))))))
