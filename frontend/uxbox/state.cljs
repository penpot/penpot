(ns uxbox.state
  (:require [uxbox.rstore :as rs]
            [beicon.core :as rx]))

(enable-console-print!)

(defonce state (atom {}))

(defonce stream
  (rs/init {:user {:fullname "Cirilla"
                   :avatar "http://lorempixel.com/50/50/"}
            :projects []
            :pages []
            :projects-by-id {}
            :pages-by-id {}}))

(defonce +setup-stuff+
  (do
    (rx/to-atom stream state)))

