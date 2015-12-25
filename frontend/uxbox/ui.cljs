(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [uxbox.ui.lightbox :as ui.lb]
            [uxbox.ui.users :as users]
            [uxbox.ui.dashboard :as dashboard]
            [uxbox.ui.workspace :refer (workspace)]
            [uxbox.ui.util :as util]
            [uxbox.ui.mixins :as mx]))

(def ^:static state
  (as-> (l/select-keys [:location :location-params]) $
    (l/focus-atom $ s/state)))

(defn app-render
  [own]
  (let [{:keys [location location-params] :as state} (rum/react state)]
    (case location
      :auth/login (users/login)
      :dashboard/projects (dashboard/projects-page)
      :dashboard/elements (dashboard/elements-page)
      :dashboard/icons (dashboard/icons-page)
      :dashboard/colors (dashboard/colors-page)
      :workspace/page (let [projectid (:project-uuid location-params)
                            pageid (:page-uuid location-params)]
                        (workspace projectid pageid))
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
    (rum/mount (app) app-dom)
    (rum/mount (ui.lb/lightbox) lb-dom)))
