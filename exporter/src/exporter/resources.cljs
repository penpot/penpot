;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.resources
  "The run's local resources: the artifact files the runner parks in
  the injected temp area until the settle moves them into the job's
  completion, zipped once a run packs more than one.

  Same operations as the legacy `app.handlers.resources`, adapted:
  the temp area arrives injected (no global), and the zip close is
  awaited instead of chained. The per-file deletion timers of the old
  shell are gone on purpose: the tmpdir registry owns every path and
  releases it on the settle."
  (:require
   ["archiver" :as arc]
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [cljs.core :as c]
   [cuerdas.core :as str]
   [exporter.util.mime :as mime]))

(defn- resource-path
  [tmpdir type id]
  (path/join tmpdir (str/concat "penpot.resource." (c/name type) "." id)))

(defn create
  "An ephemeral artifact descriptor of `type` named `name`, parked
  under the injected `tmpdir`."
  [tmpdir type name]
  (let [task-id (uuid/next)]
    {:path     (resource-path tmpdir type task-id)
     :mtype    (mime/get type)
     :name     name
     :filename (str/concat (str/replace name #"[\\/:*?\"<>|]" "_") (mime/get-extension type))
     :id       task-id}))

(defn create-zip
  [& {:keys [resource on-complete on-progress on-error]}]
  (let [^js zip  (new arc/ZipArchive)
        ^js out  (fs/createWriteStream (:path resource))
        on-complete (or on-complete (constantly nil))
        on-progress (or on-progress (constantly nil))
        on-error    (or on-error (constantly nil))
        progress (atom 0)]
    (.on zip "error" on-error)
    (.on zip "end" on-complete)
    (.on zip "entry" (fn [data]
                       (let [name (unchecked-get data "name")
                             num  (swap! progress inc)]
                         (on-progress {:done num :filename name}))))
    (.pipe zip out)
    ;; The stream belongs to the archiver: `abort-zip` needs it to close
    ;; the descriptor of a run that never reaches the happy path.
    (unchecked-set zip "__out" out)
    zip))

(defn add-to-zip
  [zip path name]
  (.file ^js zip path #js {:name name}))

(defn ^:async close-zip
  ([zip] (close-zip zip nil))
  ([zip {:keys [timeout-ms] :or {timeout-ms 30000}}]
   (await (js/Promise.
           (fn [resolve reject]
             (let [timer (js/setTimeout
                          (fn [] (reject (js/Error. "zip close timed out")))
                          timeout-ms)
                   done  (fn []
                           (js/clearTimeout timer)
                           (resolve nil))
                   fail  (fn [cause]
                           (js/clearTimeout timer)
                           (reject cause))]
               (.on ^js zip "close" (fn [] (done)))
               (.on ^js zip "error" (fn [cause] (fail cause)))
               (.finalize ^js zip)))))
   (unchecked-set zip "__closed" true)
   nil))

(defn abort-zip
  "Abort `zip` and destroy the write stream behind it, so a render that
  fails or is cancelled does not leave the descriptor open: releasing
  the temp path removes the name, not the handle. Idempotent, and safe
  after `close-zip` (the archiver is then already settled)."
  [zip]
  (when (and (some? zip) (not (unchecked-get zip "__closed")))
    (ex/ignoring (.abort ^js zip))
    (when-let [out (unchecked-get zip "__out")]
      (ex/ignoring (.destroy ^js out)))
    (unchecked-set zip "__closed" true))
  nil)
