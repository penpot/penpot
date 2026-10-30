;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.jobs
  "The local arm of the job life this process runs.

  The durable row of a job is the backend's business: the worker claims
  it, beats against it and settles it through the management API. This
  namespace keeps only the runtime bit that cannot live in a row — the
  cancel flag the parts of a run read between renders — keyed by the
  job id of the claimed job.

  This is deliberately smaller than the legacy `app.jobs`: the abort
  itself belongs to whoever holds the render (the per-lease interval of
  `exporter.wasm.scope` terminates its own worker when the check
  fails), so no terminate callbacks and no shared-array signal travel
  through here. The only surface toward the renderers is one zero-arg
  check constructor per job."
  (:require
   [app.common.exceptions :as ex]))

(defonce ^:private registry (atom {}))

(defn register
  "The runtime record of a job this process runs: one cancel flag,
  down until something marks it."
  [job-id]
  (swap! registry assoc (str job-id) {:cancelled? false})
  nil)

(defn cancelled?
  "True once the job has been marked cancelled in this process. The
  check between objects reads it; inside a render the leased worker
  reads its own abort instead."
  [job-id]
  (boolean (:cancelled? (get @registry (str job-id)))))

(defn mark-cancelled
  "Signals one job of this process as cancelled: the flag the checks
  read goes up. A job this process does not own, or a settled one, is
  a no-op. Returns true when this call marked it, nil otherwise."
  [job-id]
  (let [k  (str job-id)
        rt (get @registry k)]
    (when (some? rt)
      (when-not (:cancelled? rt)
        (swap! registry assoc-in [k :cancelled?] true)
        true))))

(defn release
  "Drops the local runtime entry once the job settled. The row stays in
  the backend until it is read once more or expires."
  [job-id]
  (swap! registry dissoc (str job-id))
  nil)

(defn check-cancelled
  "The zero-arg check of one job: silent while it lives, raising
  `:job-cancelled` once marked. The same fn travels into the render
  task and runs between units of work."
  [job-id]
  (fn []
    (when (cancelled? job-id)
      (throw (ex/error :type :internal
                       :code :job-cancelled
                       :hint "export job was cancelled")))))
