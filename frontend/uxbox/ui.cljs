(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.util :as util]
            [uxbox.ui.lightbox :as ui.lb]
            [uxbox.ui.users :as ui.u]
            [uxbox.ui.dashboard :as ui.d]))

(def ^:private ^:static state
  (as-> (l/select-keys [:location :location-params]) $
    (l/focus-atom $ s/state)))

(defn app-render
  [own]
  (let [{:keys [location location-params] :as state} (rum/react state)]
    (html
     [:section
      (ui.lb/lightbox)
      (case location
        :auth/login (ui.u/login)
        ;; :auth/register (u/register)
        ;; :auth/recover (u/recover-password)
        :main/dashboard (ui.d/dashboard)
        ;; :main/project (w/workspace conn location-params)
        ;; :main/page (w/workspace conn location-params))))
        nil
        )])))

(def app
  (util/component {:render app-render
                   :mixins [rum/reactive]
                   :name "app"}))
(defn mount!
  [el]
  (rum/mount (app) el))
