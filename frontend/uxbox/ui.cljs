(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.util :as util]
            [uxbox.data.projects :as dp]
            [uxbox.ui.lightbox :as ui.lb]
            [uxbox.ui.users :as ui.users]
            [uxbox.ui.dashboard.projects :as ui.dashboard.projects]
            [uxbox.ui.dashboard.elements :as ui.dashboard.elements]
            [uxbox.ui.workspace :as ui.w]))

(def ^:static state
  (as-> (l/select-keys [:location :location-params]) $
    (l/focus-atom $ s/state)))

(defn app-render
  [own]
  (let [{:keys [location location-params] :as state} (rum/react state)]
    (html
     [:section
      (ui.lb/lightbox)
      (case location
        :auth/login (ui.users/login)
        :dashboard/projects (ui.dashboard.projects/projects)
        :dashboard/elements (ui.dashboard.elements/elements)
        :dashboard/icons (ui.dashboard.elements/icons)
        :dashboard/colors (ui.dashboard.elements/colors)
        :main/page (let [projectid (:project-uuid location-params)
                         pageid (:page-uuid location-params)]
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
