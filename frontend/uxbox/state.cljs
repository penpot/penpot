(ns uxbox.state
  (:require [uxbox.rstore :as rs]
            [beicon.core :as rx]))

(enable-console-print!)

(defonce state (atom {}))

(defonce stream
  (rs/init {:user {:fullname "Cirilla"
                   :avatar "http://lorempixel.com/50/50/"}
            :dashboard {:section :dashboard/projects}
            :workspace {}
            :projects-by-id {}
            :pages-by-id {}}))

(rx/to-atom stream state)
