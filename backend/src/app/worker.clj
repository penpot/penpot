;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker
  "Conventions shared by the job execution machinery (impl).

  Handlers that fail with a transient error can throw an exception with
  `{:type ::retry}` in ex-data (optionally `:delay` with a duration or
  milliseconds, and `:strategy ::noop` to retry without counting the
  attempt); the runner will schedule the job for retry (respecting
  max-retries) with exponential backoff. The public submit/invoke API
  lives in `app.jobs`."
  (:require
   [app.common.data :as d]))

(defn queue-key
  "The Redis list the payloads of a queue travel through:
  `penpot.worker.queue:<tenant>:<queue>`.

  The tenant prefix is what keeps two instances sharing one Redis from
  handing each other jobs, and `job.queue` stores the bare queue name, so
  the prefix has to be composed here. Three callers push or pop on it (the
  dispatcher, the runner and `app.jobs/request`), so the shape lives in
  one place."
  [tenant queue]
  (str "penpot.worker.queue:" tenant ":" (d/name queue)))
