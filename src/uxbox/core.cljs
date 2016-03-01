;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.core
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.router :as rt]
            [uxbox.rstore :as rs]
            [uxbox.ui :as ui]
            [uxbox.data.load :as dl]))

(enable-console-print!)

(defn- main
  []
  (let [lens (l/select-keys dl/+persistent-keys+)
        stream (->> (l/focus-atom lens st/state)
                    (rx/from-atom)
                    (rx/dedupe)
                    (rx/debounce 1000)
                    (rx/tap #(println "[save]")))]
    (rx/on-value stream #(dl/persist-state %))))

(define-once :setup
  (println "bootstrap")
  (st/init)
  (rt/init)
  (ui/init)

  (rs/emit! (dl/load-data))

  ;; During development, you can comment the
  ;; following call for disable temprary the
  ;; local persistence.
  (main))
