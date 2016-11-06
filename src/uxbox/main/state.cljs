;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.state
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.main.state.colors :as colors]
            [uxbox.util.rstore :as rs]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.storage :refer (storage)]))

(enable-console-print!)

(defonce state (atom {}))
(defonce loader (atom false))

(def auth-ref
  (-> (l/key :auth)
      (l/derive state)))

(defn initial-state
  []
  {:dashboard {:project-order :name
               :project-filter ""
               :images-order :name
               :images-filter ""}
   :route nil
   :auth (:uxbox/auth storage nil)
   :clipboard #queue []
   :undo {}
   :profile nil
   :workspace nil
   :image-colls-by-id nil
   :images-by-id nil
   :icon-colls-by-id nil
   :icons-by-id nil
   :shapes-by-id nil
   :color-collections colors/collections
   :projects-by-id nil
   :pages-by-id nil})

(defn init
  "Initialize the state materialization."
  ([] (init initial-state))
  ([& callbacks]
   (-> (reduce #(merge %1 (%2)) nil callbacks)
       (rs/init)
       (rx/to-atom state))))
