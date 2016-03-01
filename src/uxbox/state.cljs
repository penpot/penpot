;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.state
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]))

(defonce state (atom {}))

(defonce stream
  (rs/init {:dashboard {:project-order :name
                        :project-filter ""}
            :route nil
            :auth {}
            :workspace nil
            :shapes-by-id {}
            :elements-by-id {}
            :colors-by-id {}
            :icons-by-id {}
            :projects-by-id {}
            :pages-by-id {}}))

(defn init
  "Initialize the state materialization."
  []
  (as-> stream $
    (rx/to-atom $ state)))
