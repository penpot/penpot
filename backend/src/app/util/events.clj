;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.events
  "Event points of a run: the core marks what it does with `tap`, and
  whoever runs it decides where those marks go.

  The sink bound for the run decides how each event travels: a function
  is called inline, on the same thread, while a channel receives the
  event for a listener of its own. The jobs adapter binds a function
  that turns taps into heartbeats, so an `:interrupt` it raises aborts
  the run right there; the SSE stream binds a channel instead, and its
  listener writes what arrives to the response."
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(def ^:dynamic *sink* nil)

(defn tap
  "Mark an event point of the run in course with `[type data]`.

  With no sink bound nothing happens, so untracked runs pay a single
  nil check per event point. A function sink is called inline and any
  exception it raises propagates to the producer: that is how an
  `:interrupt` from a heartbeat stops the run at its next event point.
  A channel sink receives the event for its listener instead."
  [type data]
  (when-let [sink *sink*]
    (if (fn? sink)
      (sink [type data])
      (sp/put! sink [type data]))
    nil))

(defn spawn-listener
  "Consume the events of `channel` on a thread of its own, until it
  closes.

  `on-close` runs once the listener stops. A failure of `on-event` is
  logged and stops the listener."
  [channel on-event on-close]
  (assert (sp/chan? channel) "expected active events channel")

  (px/thread
    {:virtual true}
    (try
      (loop []
        (when-let [event (sp/take! channel)]
          (let [result (ex/try! (on-event event))]
            (if (ex/exception? result)
              (do
                (l/err :hint "unexpected exception on calling-on-event of spawn-listener"
                       :cause result)
                (sp/close! channel))
              (recur)))))
      (finally
        (try
          (on-close)
          (catch Exception cause
            (l/err :hint "unexpected exception on calling on-close of spawn-listener"
                   :cause cause)))))))

