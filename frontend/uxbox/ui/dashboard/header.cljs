(ns uxbox.ui.dashboard.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.util :as util]
            [uxbox.router :as r]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.icons :as i]
            [uxbox.ui.users :as ui.u]))

(defn header-render
  [own]
  (let [local (:rum/local own)
        projects? (= (:section local) :projects)
        elements? (= (:section local) :elements)
        icons? (= (:section local) :icons)
        colors? (= (:section local) :colores)]
    (html
     [:header#main-bar.main-bar
      [:div.main-logo
       (nav/link "/" i/logo)]
      [:ul.main-nav
       [:li {:class (when projects? "current")}
        (nav/link (r/route-for :dashboard/projects) "PROJECTS")]
       [:li {:class (when elements? "current")}
        (nav/link (r/route-for :dashboard/elements) "ELEMENTS")]
       [:li {:class (when icons? "current")}
        (nav/link (r/route-for :dashboard/icons) "ICONS")]
       [:li {:class (when colors? "current")}
        (nav/link (r/route-for :dashboard/colors) "COLORS")]]
      (ui.u/user)])))

(def ^:static header
  (util/component
   {:render header-render
    :name "header"
    :mixins [rum/static
             (rum/local {:section :projects})]}))
