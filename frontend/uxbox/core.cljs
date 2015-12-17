(ns uxbox.core
  (:require [beicon.core :as rx]
            [uxbox.state :as s]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui :as ui]
            [uxbox.data.load :as dl]))

(enable-console-print!)

(defonce +setup+
  (do
    (println "bootstrap")
    (r/init)
    (ui/init)
    (rs/emit! (dl/load-data))
    (rx/on-value s/stream #(dl/persist-state %))))
