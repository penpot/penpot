;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.export-assets
  "The `:export-assets` job: the shapes and frames a caller froze at
  creation time, rendered as image files.

  This is an EXTERNAL job: the work runs on the exporter worker, a
  process outside the JVM, so the job is created with `::jobs/queue-name
  :exporter` (read by the creation command; the substrate never looks at
  it) and the backend ships no runner for that queue. The handler here
  is a guard, not a worker: it is never executed under a correct
  configuration, because the only runner that could claim the job is
  external, and `claim-job` does not look at the queue. Should a wrong
  configuration ever route the job to a JVM runner, it fails visibly
  here instead of \"completing\" empty.

  Progress vocabulary of the external worker (the substrate validates
  the shape, the worker owns the words): stages `:preparing`,
  `:rendering` and `:packaging`, with counters `objects` (one per
  exported shape) and `pages` (one per exported frame)."
  (:require
   [app.common.exceptions :as ex]
   [app.common.export :as export]
   [app.common.schema :as sm]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(defn- execute-export-assets
  "Plain handler, importable and testable without integrant.

  It only raises: the work of this job belongs to the external worker.
  See the namespace docstring."
  [_context _params]
  (ex/raise :type :internal
            :code :no-handler-for-external
            :hint "this job runs on the external exporter worker"))

(defmethod ig/init-key ::job-def
  [_ _cfg]
  {::jobs/name          :export-assets
   ::jobs/family        :export
   ::jobs/resource-role :output
   ;; the queue the creation command routes the job to: an external
   ;; worker consumes it, the backend ships no runner for it
   ::jobs/queue-name    :exporter
   ;; the item shapes live in app.common.export, the single source of
   ;; truth both runtimes validate and decode against
   ::jobs/schema        export/schema:params
   ::jobs/handler       execute-export-assets
   ::jobs/decoder       (sm/decoder export/schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator export/schema:params)})
