(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.util :as util]
            [uxbox.data.projects :as dp]
            [uxbox.ui.lightbox :as ui.lb]
            [uxbox.ui.users :as ui.u]
            [uxbox.ui.workspace :as ui.w]
            [uxbox.ui.dashboard :as ui.d]))

(def ^:static state
  (as-> (l/select-keys [:location :location-params]) $
    (l/focus-atom $ s/state)))

(defn app-render
  [own]
  (let [{:keys [location location-params] :as state} (rum/react state)]
    (println 1111 location location-params)
    (html
     [:section
      (ui.lb/lightbox)
      (case location
        :auth/login (ui.u/login)
        ;; :auth/register (u/register)
        ;; :auth/recover (u/recover-password)
        :main/dashboard (ui.d/dashboard)
        ;; :main/project (ui.w/workspace (:project-uuid location-params))
        :main/page (let [projectid (:project-uuid location-params)
                         pageid (:page-uuid location-params)]
                     (rs/emit! (dp/initialize-workspace projectid pageid))
                     (ui.w/workspace projectid pageid))
        nil
        )])))

(def app
  (util/component {:render app-render
                   :mixins [rum/reactive]
                   :name "app"}))
(defn mount!
  [el]
  (rum/mount (app) el))
