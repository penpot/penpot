;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.wasm
  (:require
   ["./renderer/renderer" :as renderer]
   ["./renderer/renderer.js$default" :as renderer-init]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn foo [] (renderer/print "Lorem ipsum"))

(defn init
  []
  (ptk/reify ::init
    ptk/WatchEvent
    (watch [_ _ _] ;; TODO: mirar la docu de potok
      (->> (rx/from (renderer-init))
           (rx/tap foo)
           (rx/ignore)))))