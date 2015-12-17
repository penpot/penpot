(ns uxbox.core
  (:require [uxbox.ui :as ui]
            [uxbox.router]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [uxbox.data.load :as dl]
            [goog.dom :as dom]
            [beicon.core :as rx]))


(enable-console-print!)

(defonce +setup+
  (do
    (println "BOOTSTRAP")
    (ui/init)
    (rs/emit! (dl/load-data))
    (rx/on-value s/stream #(dl/persist-state %))))
