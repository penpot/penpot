(ns uxbox.core
  (:require [beicon.core :as rx]
            [uxbox.router]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.ui :as ui]
            [uxbox.data.load :as dl]))

(enable-console-print!)

(defonce +setup+
  (do
    (ui/init)
    (rs/emit! (dl/load-data))
    (rx/on-value s/stream #(dl/persist-state %))
    1))
