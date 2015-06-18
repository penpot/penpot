(ns uxbox.state
  (:require [uxbox.rstore :as rs]
            [beicon.core :as rx]))

(defonce stream
  (rs/init {}))

(defonce state (atom {}))
(rx/to-atom stream state)

(rs/emit! (rs/reset-state {:location :auth/login
                           :location-params nil
                           :projects-by-id {}
                           :pages-by-id {}}))
