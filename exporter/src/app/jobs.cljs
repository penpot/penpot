;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs
  "The local arm of the job life this process runs.

  The durable row of a job is the backend's business: the worker claims
  it, beats against it and settles it through the management API. This
  namespace keeps only the runtime bits that cannot live in a row -- the
  cancel callbacks that terminate a render worker, the cancel signal
  shared with that render worker, and the flag the parts of a run read
  between renders -- keyed by the job id of the claimed job."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]))

(def ^:private terminal-states #{"ended" "error" "cancelled"})

(defonce ^:private registry (atom {}))

(defn- now-ms
  []
  (inst-ms (ct/now)))

(defn- runtime
  [job-id]
  (get @registry (str job-id)))

(defn terminal?
  [job]
  (contains? terminal-states (:state job)))

(defn cancelled?
  "True when the job has been marked cancelled in this process. The
  check between objects reads it; inside a render the thread reads the
  signal instead."
  [job-id]
  (boolean (:cancelled? (runtime job-id))))

(defn cancel-signal
  "Int32Array over a SharedArrayBuffer, readable from a worker thread: 0 while
  the job is live, 1 once it has been cancelled. Nil once the job has been
  released -- writing the signal back would leave an entry for a settled job in
  the registry that nothing would ever remove."
  [job-id]
  (let [k (str job-id)]
    (when-let [rt (get @registry k)]
      (or (:cancel-signal rt)
          (let [signal (js/Int32Array. (js/SharedArrayBuffer. 4))]
            (swap! registry update k (fn [rt] (some-> rt (assoc :cancel-signal signal))))
            (when (cancelled? job-id)
              (js/Atomics.store signal 0 1))
            signal)))))

(defn on-cancel
  "Registers a callback used to abort the job's in-flight work (terminating a
  render worker). A job fans out over several renders, so callbacks
  accumulate. One registered for a job that already settled is dropped: keeping
  it would revive that job's registry entry for good."
  [job-id f]
  (swap! registry update (str job-id)
         (fn [rt] (some-> rt (update :cancel-fns (fnil conj []) f)))))

(defn release!
  "Drops the local runtime entry once the job settled. The row stays in
  the backend until it is read once more or expires."
  [job-id]
  (swap! registry dissoc (str job-id)))

(defn register!
  "The runtime record of a job this process runs. The local cancel arm
  reads it (`mark-cancelled`), so the in-flight render of a claimed job
  is terminable without the jobs substrate: the row is the backend's
  business, this record is only what a terminate needs."
  [job-id & {:keys [total]}]
  (swap! registry assoc (str job-id)
         {:job {:id job-id :state "running" :total total}
          :cancelled? false}))

(defn- check-cancel-callback!
  [job-id f]
  (try
    (f)
    (catch :default cause
      (l/warn :hint "error on job cancel callback" :job-id (str job-id) :cause cause))))

(defn mark-cancelled
  "Signals one job of this process as cancelled: raises the cancel flag
  (the check between objects sees it), marks the local record terminal
  (the row of the job is already terminal in the backend), arms the
  shared-array signal (the wasm worker sees it inside a render) and
  runs the cancel callbacks (the terminate of the render worker). The
  mark happens before the callbacks run: one of them releases the job,
  and a second mark for the same job must change nothing.

  A job this process does not own, or a settled one, is a no-op."
  [job-id]
  (let [k  (str job-id)
        rt (get @registry k)]
    (when-let [_job (and rt (:job rt))]
      (when-not (terminal? (:job rt))
        (let [callbacks (:cancel-fns rt)]
          (swap! registry update k
                 (fn [e] (some-> e
                                 (assoc :cancelled? true)
                                 (update :job assoc :state "cancelled" :ended-at (now-ms)))))
          (when-let [signal (:cancel-signal (get @registry k))]
            (js/Atomics.store signal 0 1))
          (doseq [f callbacks]
            (check-cancel-callback! job-id f)))
        true))))
