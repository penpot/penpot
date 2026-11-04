;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.job
  "The wire shapes of the job queue, shared by the process that pushes a
  payload (the backend dispatcher) and the process that pops it (the
  exporter worker). One definition on purpose: a drift between the two
  fails at push time instead of at claim time."
  (:require
   [app.common.schema :as sm]))

(def schema:queue-payload
  "The two-element JSON array the dispatcher pushes and the consumer
  pops: the job id as text and the `scheduled-at` of the row when it was
  pushed, in `ct/format-inst` shape. Fewer than two items, other types
  or a different container is not a payload: the consumer drops it
  instead of claiming an invalid job."
  [:tuple :string :string])

(def valid-queue-payload?
  (sm/validator schema:queue-payload))

(def decode-queue-payload
  (sm/decoder schema:queue-payload sm/string-transformer))
