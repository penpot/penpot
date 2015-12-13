(ns uxbox.state
  (:require [uxbox.rstore :as rs]
            [beicon.core :as rx]))

(enable-console-print!)

(defonce state (atom {}))

(def stream
  (rs/init {:user {:fullname "Cirilla"
                   :avatar "http://lorempixel.com/50/50/"}
            :projects-by-id {}
            :pages-by-id {}}))

(rx/to-atom stream state)
