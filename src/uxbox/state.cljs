(ns uxbox.state
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]))

(defonce state (atom {}))

(defonce stream
  (rs/init {:dashboard {:project-order :name
                        :project-filter ""}
            :route nil
            :auth nil
            :workspace nil
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
