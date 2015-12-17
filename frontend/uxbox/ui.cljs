(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [uxbox.ui.lightbox :as ui.lb]
            [uxbox.ui.users :as ui.users]
            [uxbox.ui.dashboard.projects :as ui.dashboard.projects]
            [uxbox.ui.dashboard.elements :as ui.dashboard.elements]
            [uxbox.ui.workspace :as ui.w]
            [uxbox.ui.util :as util]
            [uxbox.ui.mixins :as mx]))


(def ^:static state
  (as-> (l/select-keys [:location :location-params]) $
    (l/focus-atom $ s/state)))

(defn app-render
  [own]
  (let [{:keys [location location-params] :as state} (rum/react state)]
    (case location
      :auth/login (ui.users/login)
      :dashboard/projects (ui.dashboard.projects/projects)
      :dashboard/elements (ui.dashboard.elements/elements)
      :dashboard/icons (ui.dashboard.elements/icons)
      :dashboard/colors (ui.dashboard.elements/colors)
      :workspace/page (let [projectid (:project-uuid location-params)
                            pageid (:page-uuid location-params)]
                        (ui.w/workspace projectid pageid))
      nil
      )))

(def app
  (util/component {:render app-render
                   :mixins [rum/reactive]
                   :name "app"}))
(defn init
  []
  (let [app-dom (gdom/getElement "app")
        lb-dom (gdom/getElement "lightbox")]
    (util/mount (app) app-dom)
    (util/mount (ui.lb/lightbox) lb-dom)))
