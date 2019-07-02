;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.store
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]))

(def ^:dynamic *on-error* identity)

(defonce state (atom {}))
(defonce loader (atom false))
(defonce store (ptk/store {:on-error #(*on-error* %)}))
(defonce stream (ptk/input-stream store))

(def auth-ref
  (-> (l/key :auth)
      (l/derive state)))

(defn emit!
  ([event]
   (ptk/emit! store event))
  ([event & events]
   (apply ptk/emit! store (cons event events))))

(def initial-state
  {:route nil
   :project nil
   :pages nil
   :page nil
   :flags #{:sitemap}
   :shapes {}})

(defn init
  "Initialize the state materialization."
  []
  (emit! initial-state)
  (rx/to-atom store state))
