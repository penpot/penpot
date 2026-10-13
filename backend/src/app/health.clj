;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.health
  "Instance availability state.

  A single flag tells whether this backend instance is willing to receive
  traffic. The readiness endpoint (`/readyz`) reports the instance as
  unavailable while the flag is off, so the load balancer removes it from
  rotation. Requests already in flight are still served.

  The state is a plain global atom and not an Integrant component because
  it must also exist on a bare REPL session, which is where the srepl
  helpers change it."
  (:require
   [app.common.logging :as l]))

(defonce state
  (atom true))

(defn online?
  "Returns true when this instance is able to receive traffic."
  []
  @state)

(defn set-offline
  "Marks the instance as offline. The `/readyz` endpoint answers 503 until
  `set-online` is called again.

  Use it to drain the instance from the load balancer before a restart:
  the load balancer stops sending new requests and the requests in flight
  finish on their own."
  []
  (l/inf :hint "instance set offline" :endpoint "/readyz")
  (swap! state (constantly false)))

(defn set-online
  "Marks the instance as online again, so the load balancer sends it
  traffic."
  []
  (l/inf :hint "instance set online" :endpoint "/readyz")
  (swap! state (constantly true)))
