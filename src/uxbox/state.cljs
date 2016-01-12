(ns uxbox.state
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]))

(defonce state (atom {}))

(defonce stream
  (rs/init {:user {:fullname "Cirilla Fiona"
                   :avatar "http://lorempixel.com/50/50/"}
            :dashboard {}
            :workspace {}
            :shapes-by-id {}
            :elements-by-id {}
            :colors-by-id {}
            :icons-by-id {}
            :projects-by-id {}
            :pages-by-id {}}))


(defn init
  "Initialize the state materialization."
  []
  (rx/to-atom stream state))
