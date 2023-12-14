;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.helpers
  "A  main namespace for server repl."
  (:refer-clojure :exclude [parse-uuid])
  #_:clj-kondo/ignore
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as pmg]
   [app.common.files.repair :as repair]
   [app.common.files.validate :as validate]
   [app.common.logging :as l]
   [app.common.pprint :refer [pprint]]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as feat.fdata]
   [app.main :refer [system]]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as files-update]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.stacktrace :as strace]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [expound.alpha :as expound]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(def ^:dynamic *system* nil)

(defn println!
  [& params]
  (locking println
    (apply println params)))

(defn parse-uuid
  [v]
  (if (uuid? v)
    v
    (d/parse-uuid v)))

(defn reset-file-data!
  "Hardcode replace of the data of one file."
  [system id data]
  (db/tx-run! system (fn [system]
                       (db/update! system :file
                                   {:data data}
                                   {:id id}))))

(defn get-file
  "Get the migrated data of one file."
  [system id & {:keys [migrate?] :or {migrate? true}}]
  (db/run! system
           (fn [system]
             (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
               (-> (files/get-file system id :migrate? migrate?)
                   (update :data feat.fdata/process-pointers deref))))))

(defn validate
  "Validate structure, referencial integrity and semantic coherence of
    all contents of a file. Returns a list of errors."
  [system id]
  (db/tx-run! system
              (fn [{:keys [::db/conn] :as system}]
                (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
                  (let [id   (if (string? id) (parse-uuid id) id)
                        file (files/get-file system id)
                        libs (->> (files/get-file-libraries conn id)
                                  (into [file] (map (fn [{:keys [id]}]
                                                      (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
                                                        (-> (files/get-file system id :migrate? false)
                                                            (feat.fdata/process-pointers deref)
                                                            (pmg/migrate-file))))))
                                  (d/index-by :id))]
                    (validate/validate-file file libs))))))

(defn repair!
  "Repair the list of errors detected by validation."
  [system id]
  (db/tx-run! system
              (fn [{:keys [::db/conn] :as system}]
                (binding [pmap/*tracked* (pmap/create-tracked)
                          pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
                  (let [id      (if (string? id) (parse-uuid id) id)
                        file    (files/get-file system id)
                        libs    (->> (files/get-file-libraries conn id)
                                     (into [file] (map (fn [{:keys [id]}]
                                                         (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
                                                           (-> (files/get-file system id :migrate? false)
                                                               (feat.fdata/process-pointers deref)
                                                               (pmg/migrate-file))))))
                                     (d/index-by :id))
                        errors  (validate/validate-file file libs)
                        changes (repair/repair-file file libs errors)

                        file    (-> file
                                    (update :revn inc)
                                    (update :data cpc/process-changes changes)
                                    (update :data blob/encode))]

                    (when (contains? (:features file) "fdata/pointer-map")
                      (feat.fdata/persist-pointers! system id))

                    (db/update! conn :file
                                {:revn (:revn file)
                                 :data (:data file)
                                 :data-backend nil
                                 :modified-at (dt/now)
                                 :has-media-trimmed false}
                                {:id (:id file)})
                    :repaired)))))

(defn update-file!
  "Apply a function to the data of one file. Optionally save the changes or not.
  The function receives the decoded and migrated file data."
  [system & {:keys [update-fn id rollback? migrate? inc-revn?]
             :or {rollback? true migrate? true inc-revn? true}}]
  (letfn [(process-file [{:keys [::db/conn] :as system} {:keys [features] :as file}]
            (binding [pmap/*tracked* (pmap/create-tracked)
                      pmap/*load-fn* (partial feat.fdata/load-pointer system id)
                      cfeat/*wrap-with-pointer-map-fn*
                      (if (contains? features "fdata/pointer-map") pmap/wrap identity)
                      cfeat/*wrap-with-objects-map-fn*
                      (if (contains? features "fdata/objectd-map") omap/wrap identity)]

              (let [file     (cond-> (update-fn file)
                               inc-revn? (update :revn inc))
                    features (db/create-array conn "text" (:features file))
                    data     (blob/encode (:data file))]

                (db/update! conn :file
                            {:data data
                             :revn (:revn file)
                             :features features}
                            {:id id}))

              (when (contains? (:features file) "fdata/pointer-map")
                (feat.fdata/persist-pointers! system id))

              (dissoc file :data)))]

    (db/tx-run! system
                (fn [system]
                  (binding [*system* system]
                    (try
                      (->> (files/get-file system id :migrate? migrate?)
                           (process-file system))
                      (finally
                        (when rollback?
                          (db/rollback! system)))))))))

(defn analyze-files
  "Apply a function to all files in the database, reading them in
  batches. Do not change data.

  The `on-file` parameter should be a function that receives the file
  and the previous state and returns the new state.

  Emits rollback at the end of operation."
  [system & {:keys [chunk-size max-items start-at on-file on-error on-end on-init with-libraries?]
             :or {chunk-size 10 max-items Long/MAX_VALUE}}]
  (letfn [(get-chunk [conn cursor]
            (let [sql  (str "SELECT id, created_at FROM file "
                            " WHERE created_at < ? AND deleted_at is NULL "
                            " ORDER BY created_at desc LIMIT ?")
                  rows (db/exec! conn [sql cursor chunk-size])]
              [(some->> rows peek :created-at) (map :id rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)))

          (on-error* [cause file]
            (println "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))

          (process-file [{:keys [::db/conn] :as system} file-id]
            (let [file (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system file-id)]
                         (-> (files/get-file system file-id)
                             (update :data feat.fdata/process-pointers deref)))

                  libs (when with-libraries?
                         (->> (files/get-file-libraries conn file-id)
                              (into [file] (map (fn [{:keys [id]}]
                                                  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer system id)]
                                                    (-> (files/get-file system id)
                                                        (update :data feat.fdata/process-pointers deref))))))
                              (d/index-by :id)))]
              (try
                (if with-libraries?
                  (on-file file libs)
                  (on-file file))
                (catch Throwable cause
                  ((or on-error on-error*) cause file)))))]

    (db/tx-run! system
                (fn [{:keys [::db/conn] :as system}]
                  (try
                    (binding [*system* system]
                      (when (fn? on-init) (on-init))
                      (run! (partial process-file system) (get-candidates conn)))
                    (finally
                      (when (fn? on-end)
                        (ex/ignoring (on-end)))
                      (db/rollback! system)))))))

(defn process-files!
  "Apply a function to all files in the database, reading them in
  batches."

  [system & {:keys [chunk-size
                    max-items
                    workers
                    start-at
                    on-file
                    on-error
                    on-end
                    on-init
                    rollback?]
             :or {chunk-size 10
                  max-items Long/MAX_VALUE
                  workers 1
                  rollback? true}}]
  (letfn [(get-chunk [conn cursor]
            (let [sql  (str "SELECT id, created_at FROM file "
                            " WHERE created_at < ? AND deleted_at is NULL "
                            " ORDER BY created_at desc LIMIT ?")
                  rows (db/exec! conn [sql cursor chunk-size])]
              [(some->> rows peek :created-at) (map :id rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)))

          (on-error* [cause file]
            (println! "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))

          (process-file [system file-id]
            (try
              (let [{:keys [features] :as file} (files/get-file system file-id)]
                (binding [pmap/*tracked* (pmap/create-tracked)
                          pmap/*load-fn* (partial feat.fdata/load-pointer system file-id)
                          cfeat/*wrap-with-pointer-map-fn*
                          (if (contains? features "fdata/pointer-map") pmap/wrap identity)
                          cfeat/*wrap-with-objects-map-fn*
                          (if (contains? features "fdata/objectd-map") omap/wrap identity)]

                  (on-file file)

                  (when (contains? features "fdata/pointer-map")
                    (feat.fdata/persist-pointers! system file-id))))

              (catch Throwable cause
                ((or on-error on-error*) cause file-id))))

          (run-worker [in index]
            (db/tx-run! system
                        (fn [system]
                          (binding [*system* system]
                            (loop [i 0]
                              (when-let [file-id (sp/take! in)]
                                (println! "=> worker: index:" index "| loop:" i "| file:" (str file-id) "|" (px/get-name))
                                (process-file system file-id)
                                (recur (inc i)))))

                          (when rollback?
                            (db/rollback! system)))))

          (run-producer [input]
            (db/tx-run! system (fn [{:keys [::db/conn]}]
                                 (doseq [file-id (get-candidates conn)]
                                   (println! "=> producer:" file-id "|" (px/get-name))
                                   (sp/put! input file-id))
                                 (sp/close! input))))]

    (when (fn? on-init) (on-init))

    (let [input    (sp/chan :buf chunk-size)
          producer (px/thread
                     {:name "penpot/srepl/producer"}
                     (run-producer input))
          threads  (->> (range workers)
                        (map (fn [index]
                               (px/thread
                                 {:name (str "penpot/srepl/worker/" index)}
                                 (run-worker input index))))
                        (cons producer)
                        (doall))]

      (run! p/await! threads)
      (when (fn? on-end) (on-end)))))
