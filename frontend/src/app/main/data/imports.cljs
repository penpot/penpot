;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.imports
  "Reading a penpot package and importing it as a job.

  Both steps run in the main thread: reading the manifest of the package
  is cheap (it scales with the number of entries, not with the size of the
  file) and the work itself belongs to the server, so the only thing the
  client has to be near is the websocket that reports the progress of the
  job."
  (:require
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.main.data.jobs :as dj]
   [app.main.data.uploads :as uploads]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.zip :as uz]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MESSAGES OF A FAILURE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-hint
  "The hint or the explanation of an error, trimmed, or nil."
  [error]
  (let [data (if (map? error) error (ex-data error))]
    (or (some-> (:hint data) str/trim not-empty)
        (some-> (:explain data) str/trim not-empty))))

(defn- cause-message
  "The message of a failure of the transport: the hint the server sent,
  then the explanation, then the message of the cause and finally the
  generic one. The wrapper of an aborted stream says nothing useful, so it
  is skipped."
  [cause default]
  (let [message (some-> (ex-message cause) str/trim)]
    (or (resolve-hint cause)
        (when-not (or (str/blank? message) (= message "stream exception"))
          message)
        default)))

(defn- job-message
  "The message of a job that failed."
  [error]
  (or (resolve-hint error) (tr "labels.error")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; READING A PACKAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- slurp-uri
  [uri response-type]
  (->> (http/send! {:uri uri :response-type response-type :method :get})
       (rx/map :body)))

(defn- parse-mtype
  "The mime type behind the first bytes of a file, guessed the same way the
  browser would: only a zip can be a penpot package of version 3."
  [ba]
  (let [u8 (js/Uint8Array. ba 0 4)
        sg (areduce u8 i ret "" (str ret (if (zero? i) "" " ") (.toString (aget u8 i) 8)))]
    (case sg
      "120 113 3 4" "application/zip"
      "1 13 32 206" "application/octet-stream"
      "other")))

(def ^:private schema:manifest
  [:map {:title "Manifest"}
   [:type :string]
   [:files
    [:vector
     [:map
      [:id ::sm/uuid]
      [:name :string]]]]])

(def ^:private decode-manifest
  (sm/decoder schema:manifest sm/json-transformer))

(defn- read-zip-manifest
  [zip-reader]
  (->> (rx/from (uz/get-entry zip-reader "manifest.json"))
       (rx/mapcat (fn [entry]
                    (if (nil? entry)
                      (rx/throw (ex/error :type :validation
                                          :code :invalid-penpot-file
                                          :hint "Not a valid Penpot file: manifest.json is missing"))
                      (uz/read-as-text entry))))
       (rx/map json/decode)))

(defn- analyze-file
  "The entries of one file the user dropped: one per file of a version 3
  package, one for a version 1 file, and an error for anything else."
  [{:keys [uri] :as file}]
  (let [stream (->> (slurp-uri uri :buffer)
                    (rx/merge-map
                     (fn [body]
                       (let [mtype (parse-mtype body)]
                         (cond
                           (= "application/zip" mtype)
                           (let [zip-reader (uz/reader body)]
                             (->> (read-zip-manifest zip-reader)
                                  (rx/map
                                   (fn [manifest]
                                     (if (= (:type manifest) "penpot/export-files")
                                       (let [manifest (decode-manifest manifest)]
                                         (assoc file :type :binfile-v3 :files (:files manifest)))
                                       (assoc file :type :unknown))))
                                  (rx/finalize (partial uz/close zip-reader))))

                           (= "application/octet-stream" mtype)
                           (rx/of (assoc file :type :binfile-v1))

                           :else
                           (rx/of (assoc file :type :unknown))))))
                    (rx/share))]

    (->> (rx/merge
          (->> stream
               (rx/filter (fn [entry] (= :binfile-v1 (:type entry))))
               (rx/map (fn [entry]
                         (let [file-id (uuid/next)]
                           (-> entry
                               (assoc :file-id file-id)
                               (assoc :name (:name file))
                               (assoc :status :success))))))

          (->> stream
               (rx/filter (fn [entry] (= :binfile-v3 (:type entry))))
               (rx/merge-map (fn [{:keys [files] :as entry}]
                               (->> (rx/from files)
                                    (rx/map (fn [file]
                                              (-> entry
                                                  (dissoc :files)
                                                  (assoc :name (:name file))
                                                  (assoc :file-id (:id file))
                                                  (assoc :status :success))))))))

          (->> stream
               (rx/filter (fn [data] (= :unknown (:type data))))
               (rx/map (fn [_]
                         {:uri    (:uri file)
                          :status :error
                          :error  (tr "dashboard.import.analyze-error")}))))

         (rx/catch (fn [cause]
                     (let [error (cause-message cause (tr "dashboard.import.analyze-error"))]
                       (rx/of (assoc file :error error :status :error))))))))

(defn analyze
  "Read the manifest of every file the user dropped and answer with the
  entries the wizard shows: one per file of a package."
  [files]
  (->> (rx/from files)
       (rx/merge-map analyze-file)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORTING A PACKAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- packages
  "The packages to import: a version 1 file is one package of one entry,
  and the entries of a version 3 file share their upload, which the uri
  of the dropped file identifies."
  [entries]
  (concat
   (->> entries
        (filter #(= :binfile-v1 (:type %)))
        (map (fn [entry]
               {:uri     (:uri entry)
                :version 1
                :name    (str/replace (:name entry) #".penpot$" "")
                :entries [entry]})))

   (->> entries
        (filter #(= :binfile-v3 (:type %)))
        (group-by :uri)
        (map (fn [[uri entries]]
               {:uri     uri
                :version 3
                :name    (:name (first entries))
                :entries entries})))))

(defn- entry-messages
  "The message of every entry of a package: the job works on the package,
  and each of its entries shows the same step."
  [entries message]
  (map (fn [entry] (assoc message :file-id (:file-id entry))) entries))

(defn- upload-milestone
  "The milestone of the upload phase: the chunks done out of the total.
  It is built on the client, never comes from the backend, and reuses
  the milestone shape so the wizard shows it like any other step."
  [{:keys [current total]}]
  {:stage :upload :counters {:upload {:current current :total total}}})

(defn- import-package
  "One package as a job: the blob is uploaded in chunks, the job is created
  with the upload and followed, and its outcome becomes the messages of
  the wizard.

  `on-job` is an optional callback invoked with the id of the created
  job, so the caller can cancel it while it runs."
  [ws-conn project-id resolutions {:keys [uri version name entries]} on-job]
  (let [upload-events (rx/subject)
        upload-ended? (atom false)
        finish-upload! (fn []
                         (when (compare-and-set! upload-ended? false true)
                           (rx/end! upload-events)))
        report-upload! (fn [progress]
                         (doseq [message (entry-messages entries {:status   :progress
                                                                  :progress (upload-milestone progress)})]
                           (rx/push! upload-events message)))]
    (rx/merge
     upload-events
     (->> (slurp-uri uri :blob)
          (rx/mapcat (fn [blob]
                       (->> (uploads/upload-blob-chunked blob :on-progress report-upload!)
                            (rx/tap (fn [_] (finish-upload!))))))
          (rx/mapcat (fn [{:keys [session-id]}]
                       (rp/cmd! :create-import-binfile-job
                                {:params    {:project-id project-id
                                             :name       name
                                             :version    version}
                                 :upload-id session-id})))
          (rx/mapcat (fn [{job-id :id}]
                       (when (fn? on-job)
                         (on-job job-id))
                       ;; the job exists but may wait for a worker: tell
                       ;; every entry it is queued before following it
                       (rx/concat
                        (rx/from (entry-messages entries {:status :queued}))
                        (->> (dj/watch-job ws-conn job-id)
                             (rx/mapcat (fn [{:keys [kind status result error] :as emission}]
                                          (cond
                                            (= "completed" status)
                                            (do
                                              (when-let [resolution (not-empty (:resolution result))]
                                                (vswap! resolutions merge resolution))
                                              (rx/from (entry-messages entries {:status :finish})))

                                            (= "failed" status)
                                            (rx/from (entry-messages entries {:status :error
                                                                              :error  (job-message error)}))

                                            (= "cancelled" status)
                                            (rx/from (entry-messages entries {:status :error
                                                                              :error  (tr "jobs.import-cancelled")}))

                                            (= :progress kind)
                                            (rx/from (entry-messages entries {:status   :progress
                                                                              :progress (:payload emission)}))

                                            ;; a worker picked it up (or will
                                            ;; retry it): no milestone yet
                                            (contains? #{:start :retry} kind)
                                            (rx/from (entry-messages entries {:status :started}))

                                            :else
                                            (rx/empty))))))))
          (rx/catch (fn [cause]
                      (finish-upload!)
                      (rx/from (entry-messages entries {:status :error
                                                        :error  (cause-message cause (tr "labels.error"))}))))))))

(defn import-files
  "Import the entries the wizard analyzed, one job per package.

  Answers with the messages the wizard already understands: the step of
  the job for every entry (progress, finish or error) and, once every
  package is over, the resolution of the libraries they brought.

  The optional `:on-job` callback is invoked with the id of every
  package job created, so the caller can cancel them while they run."
  [{:keys [project-id entries on-job]}]
  (let [ws-conn     (:ws-conn @st/state)
        resolutions (volatile! {})
        importing   (->> (rx/from (packages entries))
                         (rx/merge-map (fn [package]
                                         (import-package ws-conn project-id resolutions package on-job))))]
    (rx/concat importing
               (->> (rx/defer #(rx/of @resolutions))
                    (rx/map (fn [resolution]
                              {:libraries-resolution resolution}))))))
