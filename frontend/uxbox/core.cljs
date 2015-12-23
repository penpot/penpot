(ns uxbox.core
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.router :as rt]
            [uxbox.rstore :as rs]
            [uxbox.ui :as ui]
            [uxbox.data.load :as dl]))

(enable-console-print!)

(defn main
  "Initialize the storage subsystem."
  []
  (let [lens (l/select-keys [:pages-by-id
                             :projects-by-id])
        stream (->> (l/focus-atom lens st/state)
                    (rx/from-atom)
                    (rx/debounce 1000)
                    (rx/tap #(println "[save]")))]
    (rx/on-value stream #(dl/persist-state %))))

(defonce +setup+
  (do
    (println "bootstrap")

    (st/init)
    (rt/init)
    (ui/init)

    (rs/emit! (dl/load-data))
    (main)))
