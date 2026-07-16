;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.files
  "The file exportation API and events"
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.jobs :as dj]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dps]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def valid-types
  (d/ordered-set :include-libraries :merge-libraries :detach-libraries :link-later))

(def valid-formats
  #{:binfile-v1 :binfile-v3})

(def ^:private schema:export-file-param
  [:map {:title "FileParam"}
   [:id ::sm/uuid]
   [:name :string]
   [:project-id ::sm/uuid]
   [:is-shared ::sm/boolean]
   #_[:has-libraries ::sm/boolean]])

(def ^:private schema:export-files
  [:sequential {:title "Files"} schema:export-file-param])

(def check-export-files
  (sm/check-fn schema:export-files))

;; Longest wait for the pending changes of the downloaded files to be saved.
(def save-wait-timeout-ms 10000)

(defn- export-variant
  "The layout of the package the backend writes, or nil for the default one.
  `:compact` and `:compact-zstd` are behind flags because a package in those
  layouts only opens in an importer that reads them; the zstd layout wins when
  both flags are on."
  []
  (cond
    (contains? cf/flags :binfile-v3-compact-zstd) :compact-zstd
    (contains? cf/flags :binfile-v3-compact)      :compact))

(defn- export-params
  "The params of one export job. The variant is only sent when a flag asks for
  another layout: the default is the backend's own, and the params schema of an
  older deployment is closed and does not know the key, so sending it always
  would break every export with the flags off during a mixed deployment."
  [type file]
  (let [variant (export-variant)]
    (cond-> {:file-ids    #{(:id file)}
             :export-type type}
      (some? variant)
      (assoc :variant variant))))

(defn- show-export-dialog
  "Opens the download dialog, flagging whether the files have changes not
  saved yet."
  [team-id files]
  (ptk/reify ::show-export-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (modal/show {:type ::export-files
                          :team-id team-id
                          :files files
                          :unsaved (dps/unsaved-changes? state (into #{} (map :id) files))})))))

(defn open-export-dialog
  [files]
  (let [files    (check-export-files files)
        file-ids (into #{} (map :id) files)]
    (ptk/reify ::export-files
      ptk/WatchEvent
      (watch [_ state _]
        (let [team-id (get state :current-team-id)]
          (rx/merge
           (rx/of (ev/event {::ev/name "export-binary-files"
                             ::ev/origin "dashboard"
                             :format "binfile-v3"
                             :num-files (count files)}))
           ;; The download is built from the server copy: send the pending
           ;; changes first.
           (rx/concat
            (dps/force-persist-files-and-wait file-ids save-wait-timeout-ms)
            (->> (rx/from files)
                 (rx/mapcat
                  (fn [file]
                    (->> (rp/cmd! :has-file-libraries {:file-id (:id file)})
                         (rx/map #(assoc file :has-libraries %)))))
                 (rx/reduce conj [])
                 (rx/map (partial show-export-dialog team-id))))))))))

(defn- export-file
  "One file, one job: the file is exported on its own, so a failure does
  not touch the others, and the artifact is read from the result of the
  job once it is over.

  Every step of the job becomes a message for the caller: the milestone
  under `:progress`, the artifact as `:uri` when the job completed, and
  the public error of the job when it failed. The queue of a file is
  announced by `export-files` before any job exists and only ends with a
  milestone: a worker picking the job up is not one.

  `on-job` is an optional callback invoked with `{:job-id ... :file-id
  ...}` for the created job, so the caller can track it per file and
  cancel it while it runs."
  [ws-conn type file on-job]
  (->> (rp/cmd! :create-export-binfile-job
                {:params (export-params type file)})
       (rx/mapcat (fn [{job-id :id}]
                    (when (fn? on-job)
                      (on-job {:job-id job-id :file-id (:id file)}))
                    (->> (dj/watch-job ws-conn job-id)
                         (rx/mapcat (fn [{:keys [kind status result error] :as emission}]
                                      (cond
                                        (= "completed" status)
                                        (rx/of {:file-id  (:id file)
                                                :uri      (:resource-uri result)
                                                :filename (:name file)
                                                :size     (:size result)})

                                        (= "failed" status)
                                        (rx/of {:file-id (:id file)
                                                :error   error})

                                        (= "cancelled" status)
                                        (rx/of {:file-id   (:id file)
                                                :cancelled true})

                                        (= :progress kind)
                                        (rx/of {:file-id  (:id file)
                                                :progress (:payload emission)})

                                        :else
                                        (rx/empty)))))))
       ;; a transport failure carries no job error: a nil `ex-data` would
       ;; make the dialog drop the message and leave the row hanging, so a
       ;; synthetic error takes its place and the row reports it
       (rx/catch (fn [cause]
                   (rx/of {:file-id (:id file)
                           :error (or (ex-data cause)
                                      {:type :internal
                                       :code :unknown-error})})))))

(defn export-files
  "Start files exportation process.

  Every file is queued up front, even the ones whose job is only created
  once the previous one is over, so a multiple export shows all of them
  waiting from its first moment.

  The optional `:on-job` callback is invoked with `{:job-id ...
  :file-id ...}` for every file job created, so the caller can track
  them per file and cancel them while they run."
  [& {:keys [type files on-job]}]
  (assert (check-export-files files) "expected a sequence of files")
  (assert (valid-types type) "expected valid export type")

  (let [ws-conn (:ws-conn @st/state)]
    (rx/concat
     (rx/from (map (fn [file] {:file-id (:id file) :queued true}) files))
     (->> (rx/from files)
          (rx/mapcat (fn [file]
                       (export-file ws-conn type file on-job)))))))

;;;;;;;;;;;;;;;;;;;;;;
;; Team Request
;;;;;;;;;;;;;;;;;;;;;;

(defn create-team-access-request
  [params]
  (ptk/reify ::create-team-access-request
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-team-access-request params)
             (rx/tap on-success)
             (rx/catch on-error))))))
