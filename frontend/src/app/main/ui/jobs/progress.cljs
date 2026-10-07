;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.jobs.progress
  "How a milestone of a job is written in a row: the stage in course and
  its counter.

  The stage is a stable key of the vocabulary of the worker, never a
  display string, so it is resolved here with a `case` of literal `tr`
  calls: a stage this client does not know falls back to a generic label
  instead of breaking the row or showing the raw key."
  (:require
   [app.util.i18n :as i18n :refer [tr]]))

(defn- stage-label
  [stage]
  (case stage
    :files           (tr "jobs.progress.stage.files")
    :pages           (tr "jobs.progress.stage.pages")
    :media           (tr "jobs.progress.stage.media")
    :thumbnails      (tr "jobs.progress.stage.thumbnails")
    :components      (tr "jobs.progress.stage.components")
    :colors          (tr "jobs.progress.stage.colors")
    :typographies    (tr "jobs.progress.stage.typographies")
    :tokens          (tr "jobs.progress.stage.tokens")
    :storage-objects (tr "jobs.progress.stage.storage-objects")
    :relations       (tr "jobs.progress.stage.relations")
    :metadata        (tr "jobs.progress.stage.metadata")
    :manifest        (tr "jobs.progress.stage.manifest")
    :upload          (tr "jobs.progress.stage.upload")
    (tr "jobs.progress.stage.unknown")))

(defn- counter-label
  "The counter of one scope, `3/8` when the worker knows the total and the
  bare current when it does not."
  [{:keys [current total]}]
  (when (some? current)
    (if (some? total)
      (str current "/" total)
      (str current))))

(defn- upload-label
  "The upload of a file counts chunks, not units of work, so it is written
  as the percentage of the file already sent instead of a counter."
  [{:keys [current total]}]
  (when (and (some? current) (some? total) (pos? total))
    (str "(" (js/Math.round (* 100 (/ current total))) "%)")))

(defn milestone-text
  "The text of a milestone: the stage in course and its counter, and with
  `:file?` the file in course behind it, when the milestone carries its
  counter. The upload stage is the exception: it counts chunks, so it is
  written as a percentage of the file."
  [milestone & {:keys [file?]}]
  (when-some [stage (:stage milestone)]
    (let [counters (:counters milestone)
          own      (if (= :upload stage)
                     (upload-label (get counters :upload))
                     (counter-label (get counters stage)))]
      (str (stage-label stage)
           (when (some? own)
             (str " " own))
           (when-let [files (and file?
                                 (not= :files stage)
                                 (counter-label (get counters :files)))]
             (str " · " (stage-label :files) " " files))))))
