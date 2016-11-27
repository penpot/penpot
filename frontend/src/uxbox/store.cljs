;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.store
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]))

(enable-console-print!)

(def ^:dynamic *on-error* identity)

(defonce state (atom {}))
(defonce loader (atom false))
(defonce store (ptk/store {:on-error #(*on-error* %)}))

(def auth-ref
  (-> (l/key :auth)
      (l/derive state)))

(defn emit!
  ([event]
   (ptk/emit! store event))
  ([event & events]
   (apply ptk/emit! store (cons event events))))

(defn init
  "Initialize the state materialization."
  [initial-state]
  (let [istate (if (fn? initial-state) (initial-state) initial-state)]
    (emit! (constantly istate))
    (rx/to-atom store state)))
