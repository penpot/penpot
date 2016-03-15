(ns uxbox.ui.dashboard
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.rstore :as rs]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.data.dashboard :as dd]
            [uxbox.data.projects :as dp]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.library-bar :as ui.library-bar]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.ui.dashboard.projects :as projects]
            [uxbox.ui.dashboard.elements :as elements]
            [uxbox.ui.dashboard.icons :as icons]
            [uxbox.ui.dashboard.colors :as colors]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: projects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn projects-page-render
  [own]
  (html
   [:main.dashboard-main
    (uum/messages)
    (header)
    [:section.dashboard-content
     (projects/menu)
     (projects/grid)]]))

(defn projects-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/projects)
            (dp/load-projects)
            (dp/load-pages))
  own)

(defn projects-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/projects))
  state)

(def ^:static projects-page
  (mx/component
   {:render projects-page-render
    :will-mount projects-page-will-mount
    :transfer-state projects-page-transfer-state
    :name "projects-page"
    :mixins [rum/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: elements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn elements-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (ui.library-bar/library-bar)
     [:section.dashboard-grid.library
      (elements/page-title)
      (elements/grid)]]]))

(defn elements-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/elements))
  own)

(defn elements-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/elements))
  state)

(def ^:static elements-page
  (mx/component
   {:render elements-page-render
    :will-mount elements-page-will-mount
    :transfer-state elements-page-transfer-state
    :name "elements-page"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn icons-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (icons/nav)
     (icons/grid)]]))

(defn icons-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/icons))
  own)

(defn icons-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/icons))
  state)

(def ^:static icons-page
  (mx/component
   {:render icons-page-render
    :will-mount icons-page-will-mount
    :transfer-state icons-page-transfer-state
    :name "icons-page"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: colors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn colors-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     (colors/nav)
     (colors/grid)]]))

(defn colors-page-will-mount
  [own]
  (rs/emit! (dd/initialize :dashboard/colors))
  own)

(defn colors-page-transfer-state
  [old-state state]
  (rs/emit! (dd/initialize :dashboard/colors))
  state)

(def ^:static colors-page
  (mx/component
   {:render colors-page-render
    :will-mount colors-page-will-mount
    :transfer-state colors-page-transfer-state
    :name "colors"
    :mixins [mx/static]}))
