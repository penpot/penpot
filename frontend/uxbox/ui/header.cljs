(ns uxbox.ui.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.util :as util]
            [uxbox.router :as r]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.icons :as i]
            [uxbox.ui.users :as ui.u]))

(defn header-render
  [own]
  (html
   [:header#main-bar.main-bar
    [:div.main-logo
     (nav/link "/" i/logo)]
    [:ul.main-nav
     [:li
      [:a {:href "/#/"} "PROJECTS"]]
     [:li.current
      [:a {:href "/#/elements"} "ELEMENTS"]]
     [:li
      [:a {:href "/#/icons"} "ICONS"]]
     [:li
      [:a {:href "/#/colors"} "COLORS"]]]
    (ui.u/user)]))

(def ^:static header
  (util/component
   {:render header-render
    :name "header"
    :mixins [rum/static]}))
