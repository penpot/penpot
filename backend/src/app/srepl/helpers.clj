;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.helpers
  "A  main namespace for server repl."
  #_:clj-kondo/ignore
  (:require
   [app.auth :refer [derive-password]]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.features :as ffeat]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.files.migrations :as pmg]
   [app.common.pprint :refer [pprint]]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :refer [system]]
   [app.rpc.commands.files :as files]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.stacktrace :as strace]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [expound.alpha :as expound]))

(def ^:dynamic *conn*)

(defn reset-password!
  "Reset a password to a specific one for a concrete user or all users
  if email is `:all` keyword."
  [system & {:keys [email password] :or {password "123123"} :as params}]
  (us/verify! (contains? params :email) "`email` parameter is mandatory")
  (db/with-atomic [conn (:app.db/pool system)]
    (let [password (derive-password password)]
      (if (= email :all)
        (db/exec! conn ["update profile set password=?" password])
        (let [email (str/lower email)]
          (db/exec! conn ["update profile set password=? where email=?" password email]))))))

(defn reset-file-data!
  "Hardcode replace of the data of one file."
  [system id data]
  (db/with-atomic [conn (:app.db/pool system)]
    (db/update! conn :file
                {:data data}
                {:id id})))

(defn get-file
  "Get the migrated data of one file."
  [system id]
  (db/with-atomic [conn (:app.db/pool system)]
    (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
      (-> (db/get-by-id conn :file id)
          (update :data blob/decode)
          (update :data pmg/migrate-data)
          (files/process-pointers deref)))))

(defn update-file!
  "Apply a function to the data of one file. Optionally save the changes or not.
  The function receives the decoded and migrated file data."
  [system & {:keys [update-fn id save? migrate? inc-revn?]
             :or {save? false migrate? true inc-revn? true}}]
  (db/with-atomic [conn (:app.db/pool system)]
    (let [file (-> (db/get-by-id conn :file id {::db/for-update? true})
                   (update :features db/decode-pgarray #{}))]
      (binding [*conn* conn
                pmap/*tracked* (atom {})
                pmap/*load-fn* (partial files/load-pointer conn id)
                ffeat/*wrap-with-pointer-map-fn*
                (if (contains? (:features file) "storage/pointer-map") pmap/wrap identity)
                ffeat/*wrap-with-objects-map-fn*
                (if (contains? (:features file) "storage/objectd-map") omap/wrap identity)]
        (let [file (-> file
                       (update :data blob/decode)
                       (cond-> migrate? (update :data pmg/migrate-data))
                       (update-fn)
                       (cond-> inc-revn? (update :revn inc)))]
          (when save?
            (let [features (db/create-array conn "text" (:features file))
                  data     (blob/encode (:data file))]
              (db/update! conn :file
                          {:data data
                           :revn (:revn file)
                           :features features}
                          {:id id})

              (when (contains? (:features file) "storage/pointer-map")
                (files/persist-pointers! conn id))))

          (dissoc file :data))))))

(def ^:private sql:retrieve-files-chunk
  "SELECT id, name, created_at, data FROM file
    WHERE created_at < ? AND deleted_at is NULL
    ORDER BY created_at desc LIMIT ?")

(defn analyze-files
  "Apply a function to all files in the database, reading them in
  batches. Do not change data.

  The `on-file` parameter should be a function that receives the file
  and the previous state and returns the new state."
  [system & {:keys [chunk-size max-items start-at on-file on-error on-end]
             :or {chunk-size 10 max-items Long/MAX_VALUE}}]
  (letfn [(get-chunk [conn cursor]
            (let [rows (db/exec! conn [sql:retrieve-files-chunk cursor chunk-size])]
              [(some->> rows peek :created-at) (seq rows)]))

          (get-candidates [conn]
            (->> (d/iteration (partial get-chunk conn)
                              :vf second
                              :kf first
                              :initk (or start-at (dt/now)))
                 (take max-items)
                 (map #(update % :data blob/decode))))

          (on-error* [file cause]
            (println "unexpected exception happened on processing file: " (:id file))
            (strace/print-stack-trace cause))]

    (db/with-atomic [conn (:app.db/pool system)]
      (loop [state {}
             files (get-candidates conn)]
        (if-let [file (first files)]
          (let [state' (try
                         (on-file file state)
                         (catch Throwable cause
                           (let [on-error (or on-error on-error*)]
                             (on-error file cause))))]
            (recur (or state' state) (rest files)))

          (if (fn? on-end)
            (on-end state)
            state))))))

(defn update-pages
  "Apply a function to all pages of one file. The function receives a page and returns an updated page."
  [data f]
  (update data :pages-index update-vals f))

(defn update-shapes
  "Apply a function to all shapes of one page The function receives a shape and returns an updated shape"
  [page f]
  (update page :objects update-vals f))
