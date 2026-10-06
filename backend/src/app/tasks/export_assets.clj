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
   [app.common.schema :as sm]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(def schema:export-item
  "One thing to render: where it lives (file, page, object), what to
  produce of it (type, scale) and what to call it. The same item shape
  the legacy `/api/export` surface received, frozen in the params of the
  job; the optional `share-id` marks the item as coming from a public
  share."
  [:map {:title "export-item" :closed true}
   [:file-id   ::sm/uuid]
   [:page-id   ::sm/uuid]
   [:object-id ::sm/uuid]
   [:type      [::sm/one-of #{:png :jpeg :webp :svg :pdf}]]
   [:name      ::sm/text]
   [:suffix    {:optional true} ::sm/text]
   [:scale     ::sm/number]
   [:share-id  {:optional true} ::sm/uuid]])

(def schema:params
  "Business params of an asset export: the items to render, frozen when
  the job was created, plus the options that shape the output. There is
  no `wait` here: a job is followed by its id, not waited on."
  [:map {:title "export-assets-params" :closed true}
   [:exports        [:vector {:min 1} schema:export-item]]
   [:name           {:optional true} ::sm/text]
   [:skip-children  {:optional true} ::sm/boolean]
   [:force-multiple {:optional true} ::sm/boolean]
   [:is-wasm        {:optional true} ::sm/boolean]])

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
   ::jobs/schema        schema:params
   ::jobs/handler       execute-export-assets
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)})
