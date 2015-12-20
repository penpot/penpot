(ns uxbox.state
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]))

(enable-console-print!)

(defonce state (atom {}))

(defonce stream
  (rs/init {:user {:fullname "Cirilla"
                   :avatar "http://lorempixel.com/50/50/"}
            :dashboard {}
            :workspace {}
            :elements-by-id {}
            :colors-by-id {}
            :icons-by-id {}
            :projects-by-id {}
            :pages-by-id {}}))

(rx/to-atom stream state)
