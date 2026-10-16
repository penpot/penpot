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
   [cuerdas.core :as str]
   [promesa.core :as p]))

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

(defn- segment-of
  "The segment of a zip path that follows `prefix`, up to the next slash,
  or nil when the path does not start with it."
  [prefix path]
  (when (str/starts-with? path prefix)
    (let [rest (subs path (count prefix))
          idx  (str/index-of rest "/")]
      (if (some? idx) (subs rest 0 idx) rest))))

(defn- file-of-entry
  "The file of a container a zip entry belongs to. A container keeps every
  file of a package under its own prefix: `files/<id>.json` for its data
  and `files/<id>/…` for the rest."
  [path]
  (some-> (segment-of "files/" path)
          (str/replace #"\.json$" "")))

(defn- object-of-entry
  "The storage object a zip entry holds. A container keeps the media and
  the thumbnails of its files shared under `objects/`, and both the
  metadata (`objects/<id>.json`) and the binary (`objects/<id>.<ext>`)
  belong to the same object."
  [path]
  (when-some [segment (segment-of "objects/" path)]
    (let [dot (str/index-of segment ".")]
      (if (some? dot) (subs segment 0 dot) segment))))

(defn- reference-entry?
  "Whether an entry of a file is the metadata of one of the media or the
  thumbnails that file references, the only place that links a file to the
  objects it occupies."
  [path]
  (or (str/includes? path "/media/")
      (str/includes? path "/thumbnails/")))

(defn- index-container
  "Read the entries of a container in one pass: what every file of the
  package occupies by itself, what every storage object occupies, and the
  entries that say which objects a file references."
  [entries]
  (reduce
   (fn [index entry]
     (let [path    (.-filename ^js entry)
           size    (.-compressedSize ^js entry)
           file-id (file-of-entry path)]
       (cond
         (some? file-id)
         (cond-> (update-in index [:files file-id] (fnil + 0) size)
           (reference-entry? path)
           (update :references conj [file-id entry]))

         (some? (object-of-entry path))
         (update-in index [:objects (object-of-entry path)] (fnil + 0) size)

         :else
         index)))
   {:files {} :objects {} :references []}
   entries))

(defn- read-objects
  "The objects every file of a container references, read from the
  metadata of its media and its thumbnails."
  [references]
  (->> (map (fn [[file-id entry]]
              (->> (uz/read-as-text entry)
                   (p/fmap (fn [text] [file-id (json/decode text)]))))
            references)
       (p/all)
       (p/fmap
        (fn [decoded]
          (reduce (fn [result [file-id data]]
                    (->> [:media-id :thumbnail-id]
                         (keep #(get data %))
                         (reduce (fn [result object-id]
                                   (update result file-id (fnil conj #{}) object-id))
                                 result)))
                  {}
                  decoded)))))

(defn- file-sizes
  "How much every file of a container occupies inside it: its own entries
  plus the storage objects it references, which the container keeps shared
  and which are only linked from the file. The manifest carries neither a
  size, so the parts of a package are the only account of what it
  occupies."
  [entries]
  (let [{:keys [files objects references]} (index-container entries)]
    (->> (read-objects references)
         (p/fmap
          (fn [referenced]
            (reduce-kv (fn [sizes file-id object-ids]
                         (update sizes file-id (fnil + 0)
                                 (reduce + 0 (keep objects object-ids))))
                       files
                       referenced))))))

(defn- analyze-file
  "The entries of one file the user dropped: one per file of a version 3
  package, one for a version 1 file, and an error for anything else.

  A container carries its own weight and the weight of every file in it:
  the entry of a file of a version 3 package sizes the file inside its
  container, and keeps what the container itself occupies in
  `:container-size`, which is what the upload of the package is measured
  on."
  [{:keys [uri size] :as file}]
  (let [stream (->> (slurp-uri uri :buffer)
                    (rx/merge-map
                     (fn [body]
                       (let [mtype (parse-mtype body)]
                         (cond
                           (= "application/zip" mtype)
                           (let [zip-reader (uz/reader body)]
                             (->> (read-zip-manifest zip-reader)
                                  (rx/merge-map
                                   (fn [manifest]
                                     (if (= (:type manifest) "penpot/export-files")
                                       (->> (uz/get-entries zip-reader)
                                            (p/mcat file-sizes)
                                            (rx/from)
                                            (rx/map (fn [sizes]
                                                      (-> file
                                                          (assoc :type :binfile-v3)
                                                          (assoc :container-size size)
                                                          (assoc :files (:files (decode-manifest manifest)))
                                                          (assoc :sizes sizes)))))
                                       (rx/of (assoc file :type :unknown)))))
                                  (rx/finalize (partial uz/close zip-reader))))

                           (= "application/octet-stream" mtype)
                           (rx/of (assoc file :type :binfile-v1 :container-size size))

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
               (rx/merge-map (fn [{:keys [files sizes] :as entry}]
                               (->> (rx/from files)
                                    (rx/map (fn [file]
                                              (-> entry
                                                  (dissoc :files :sizes)
                                                  (assoc :name (:name file))
                                                  (assoc :file-id (:id file))
                                                  ;; the size the file itself
                                                  ;; occupies inside the
                                                  ;; container, never the
                                                  ;; container's
                                                  (assoc :size (get sizes (str (:id file)) (:size entry)))
                                                  (assoc :status :success))))))))

          (->> stream
               (rx/filter (fn [data] (= :unknown (:type data))))
               (rx/map (fn [_]
                         {:uri    (:uri file)
                          :name   (:name file)
                          :size   (:size file)
                          :container-size (:size file)
                          :type   :unknown
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

                                            ;; cancelled elsewhere (e.g. from
                                            ;; the admin panel): no outcome is
                                            ;; coming, fail the entries with
                                            ;; the cancelled message
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIZARD STEP, UPLOAD AND FILE SIZE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-file-size
  "Human size of a dropped file, like `234MB` or `512KB`.
  Returns nil when the size is unknown, so the row shows no subtitle."
  [size]
  (when (and (some? size) (number? size) (>= size 0))
    (let [kb 1024
          mb (* kb 1024)
          gb (* mb 1024)]
      (cond
        (< size kb)
        (str (js/Math.round size) "B")

        (< size mb)
        (let [v (/ size kb)]
          (str (if (< v 10)
                 (-> (.toFixed v 1)
                     (str/replace #"\.0$" ""))
                 (str (js/Math.round v)))
               "KB"))

        (< size gb)
        (let [v (/ size mb)]
          (str (if (< v 10)
                 (-> (.toFixed v 1)
                     (str/replace #"\.0$" ""))
                 (str (js/Math.round v)))
               "MB"))

        :else
        (let [v (/ size gb)]
          (str (if (< v 10)
                 (-> (.toFixed v 1)
                     (str/replace #"\.0$" ""))
                 (str (js/Math.round v)))
               "GB"))))))

(defn- upload-ratio
  "The fraction of the container of an entry already sent: the chunks
  uploaded over the chunks the file has, or nil while its upload has not
  started."
  [entry]
  (let [{:keys [current total]} (get-in entry [:progress :counters :upload])]
    (when (and (some? current) (some? total) (pos? total))
      (/ current total))))

(defn- uploaded-ratio
  "The fraction of the container of an entry already uploaded. A container
  is uploaded whole before its job exists, so from the moment its entries
  leave the upload stage they count as sent: only an entry still sending
  chunks, or one that has not started, says less than everything."
  [entry]
  (cond
    (= :upload (get-in entry [:progress :stage])) (or (upload-ratio entry) 0)
    (contains? #{:analyze :import-ready} (:status entry)) 0
    :else 1))

(defn upload-done?
  "Whether the container of an entry finished uploading: the chunks sent
  reached the chunks the file has."
  [entry]
  (let [{:keys [current total]} (get-in entry [:progress :counters :upload])]
    (and (some? current) (some? total) (= current total))))

(defn upload-pct
  "The percentage of the whole upload: the bytes already sent over the
  bytes of every container. The containers are weighted by their size,
  never averaged, because a `.penpot` is uploaded whole: a container of
  200MB moves the bar as much as four of 50MB."
  [entries]
  (let [containers (map (comp first :entries) (packages entries))
        total      (reduce + 0 (keep :container-size containers))]
    (when (pos? total)
      (let [sent (reduce + 0 (map (fn [{:keys [container-size] :as entry}]
                                    (* container-size (uploaded-ratio entry)))
                                  containers))]
        (js/Math.round (* 100 (/ sent total)))))))

(defn- files-done
  "How many files of a container are already rebuilt. A container that
  finished leaves all of its files behind; while it runs, its milestone
  says which file it is working on, one based, so the ones before it are
  done."
  [entries]
  (if (every? #(= :import-success (:status %)) entries)
    (count entries)
    (let [{:keys [current total]} (get-in (first entries) [:progress :counters :files])
          total (or total (count entries))]
      (-> (or current 1) dec (max 0) (min total)))))

(defn imported-count
  "How many of the files being imported are already rebuilt: the files of
  the containers that finished plus the ones a running container left
  behind. A container rebuilds its files one by one, so counting only the
  finished containers (or only their files) would leave the number still
  for the whole import."
  [entries]
  (reduce + 0 (map (comp files-done :entries) (packages entries))))

(defn waiting-slot?
  "Whether every file of the wizard waits for a free slot: the jobs of the
  containers exist, but no worker picked them up yet."
  [entries]
  (and (pos? (count entries))
       (every? #(= :import-queued (:status %)) entries)))

(defn- pending-upload?
  "Whether a file is still to be uploaded, or is being uploaded right now:
  the wizard is in its upload step while any file is pending."
  [entry]
  (or (= :import-ready (:status entry))
      (= :upload (get-in entry [:progress :stage]))))

(defn import-step
  "The wizard step the header paints: `:check` while the entries are
  analyzed, `:upload` until every file of every container is uploaded, and
  `:import` once the jobs run or the wizard reaches its outcome.

  A file leaves the upload behind when its job exists: the queued message
  carries no milestone, and the upload one disappears with it, so the
  presence of a milestone is not enough to keep the step in the upload."
  [status entries]
  (case status
    (:analyze :import-ready)
    :check

    :import-progress
    (if (some pending-upload? entries)
      :upload
      :import)

    (:import-success :import-error :library-resolution :library-summary)
    :import

    :check))

(defn awaiting-analysis?
  "Whether the wizard hides the rows and shows the checking placeholder
  instead: the dialog is still analyzing and no entry failed yet. Entries
  that fail during analyze take the error-summary path instead of rows."
  [status errors?]
  (and (= :analyze status) (not errors?)))

(defn ready-to-import?
  "Whether the wizard can offer its import step: the analysis is over and
  every file it found can be imported. A ready file is not enough on its
  own: until the analysis ends the wizard does not know how many files the
  dropped containers carry, and the ones still to arrive would never be
  imported."
  [entries analysis-ended?]
  (and analysis-ended?
       (pos? (count entries))
       (every? #(= :import-ready (:status %)) entries)))

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
