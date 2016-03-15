;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.state
  (:require [hodgepodge.core :refer [local-storage]]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]))

(def +storage+ local-storage)
(def ^:const ^:private +persistent-keys+
  [:auth])

(defonce state (atom {}))

(defonce stream
  (rs/init {:dashboard {:project-order :name
                        :project-filter ""}
            :route nil
            :auth (::auth +storage+)
            :workspace nil
            :shapes-by-id {}
            :elements-by-id {}
            :colors-by-id {}
            :icons-by-id {}
            :projects-by-id {}
            :pages-by-id {}}))

(defn- persist-state!
  [state]
  (assoc! +storage+ ::auth (:auth state)))

(defn init
  "Initialize the state materialization."
  []
  (rx/to-atom stream state)
  (let [lens (l/select-keys +persistent-keys+)
        stream (->> (l/focus-atom lens state)
                    (rx/from-atom)
                    (rx/dedupe)
                    (rx/debounce 1000)
                    (rx/tap #(println "[save]")))]
    (rx/on-value stream #(persist-state! %))))
