;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.state
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.common.rstore :as rs]
            [uxbox.common.i18n :refer (tr)]
            [uxbox.util.storage :refer (storage)]))

(enable-console-print!)

(defonce state (atom {}))
(defonce loader (atom false))

(def auth-l
  (-> (l/key :auth)
      (l/derive state)))

(defn initial-state
  []
  {:route nil
   :project nil
   :auth (:uxbox/auth storage nil)
   :shapes-by-id {}
   :pages-by-id {}})

(defn init
  "Initialize the state materialization."
  ([] (init initial-state))
  ([& callbacks]
   (-> (reduce #(merge %1 (%2)) nil callbacks)
       (rs/init)
       (rx/to-atom state))))
