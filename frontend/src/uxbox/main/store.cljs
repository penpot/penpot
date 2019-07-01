;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.store
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.builtins.colors :as colors]
            [uxbox.util.storage :refer [storage]]))
(enable-console-print!)

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
  {:dashboard {:project-order :name
               :project-filter ""
               :images-order :name
               :images-filter ""}
   :route nil
   :router nil
   :auth (:auth storage nil)
   :clipboard #queue []
   :undo {}
   :profile nil
   :workspace nil
   :images-collections nil
   :images nil
   :icons-collections nil
   :icons nil
   :colors-collections colors/collections
   :shapes nil
   :projects nil
   :pages nil})

(defn init
  "Initialize the state materialization."
  ([] (init {}))
  ([props]
   (emit! #(merge % initial-state props))
   (rx/to-atom store state)))
