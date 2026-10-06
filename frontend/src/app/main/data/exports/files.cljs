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
   [app.main.data.event :as ev]
   [app.main.data.jobs :as dj]
   [app.main.data.modal :as modal]
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

(defn open-export-dialog
  [files]
  (let [files (check-export-files files)]
    (ptk/reify ::export-files
      ptk/WatchEvent
      (watch [_ state _]
        (let [team-id (get state :current-team-id)]
          (rx/merge
           (rx/of (ev/event {::ev/name "export-binary-files"
                             ::ev/origin "dashboard"
                             :format "binfile-v3"
                             :num-files (count files)}))
           (->> (rx/from files)
                (rx/mapcat
                 (fn [file]
                   (->> (rp/cmd! :has-file-libraries {:file-id (:id file)})
                        (rx/map #(assoc file :has-libraries %)))))
                (rx/reduce conj [])
                (rx/map (fn [files]
                          (modal/show {:type ::export-files
                                       :team-id team-id
                                       :files files}))))))))))

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
                {:params {:file-ids    #{(:id file)}
                          :export-type type}})
       (rx/mapcat (fn [{job-id :id}]
                    (when (fn? on-job)
                      (on-job {:job-id job-id :file-id (:id file)}))
                    (->> (dj/watch-job ws-conn job-id)
                         (rx/mapcat (fn [{:keys [kind status result error] :as emission}]
                                      (cond
                                        (= "completed" status)
                                        (rx/of {:file-id  (:id file)
                                                :uri      (:resource-uri result)
                                                :filename (:name file)})

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
       (rx/catch (fn [cause]
                   (rx/of {:file-id (:id file)
                           :error   (ex-data cause)})))))

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
