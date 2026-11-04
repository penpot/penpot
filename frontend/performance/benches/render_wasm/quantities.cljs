;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.quantities
  "Portable descriptions of raw measured quantities.")

(def schema:description
  [:map {:closed true}
   [:unit keyword?]
   [:description {:optional true} string?]])

(def descriptions
  {:command/elapsed
   {:unit :ms :description "Command core start through its completion endpoint."}
   :renderer/call-elapsed
   {:unit :ms :description "Elapsed time of each synchronous renderer call."}
   :renderer/cache-call-elapsed
   {:unit :ms :description "Elapsed time of each synchronous cached renderer call."}
   :camera/finalization-to-full
   {:unit :ms :description "Camera finalization through the renderer return at Full."}})
