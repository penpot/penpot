(ns uxbox.ui.dashboard.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.util :as util]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.icons :as i]
            [uxbox.ui.users :as ui.u]))

(def ^:static header-state
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ s/state)))


(defn- header-link
  [section content]
  (let [link (r/route-for section)
        on-click #(rs/emit! (dp/set-dashboard-section section))]
    (html
     [:a {:href (str "/#" link) :on-click on-click} content])))

(defn header-render
  [own]
  (let [local (rum/react header-state)
        projects? (= (:section local) :dashboard/projects)
        elements? (= (:section local) :dashboard/elements)
        icons? (= (:section local) :dashboard/icons)
        colors? (= (:section local) :dashboard/colors)]
    (html
     [:header#main-bar.main-bar
      [:div.main-logo
       (header-link :dashboard/projects i/logo)]
      [:ul.main-nav
       [:li {:class (when projects? "current")}
        (header-link :dashboard/projects "PROJECTS")]
       [:li {:class (when elements? "current")}
        (header-link :dashboard/elements "ELEMENTS")]
       [:li {:class (when icons? "current")}
        (header-link :dashboard/icons "ICONS")]
       [:li {:class (when colors? "current")}
        (header-link :dashboard/colors "COLORS")]]
      (ui.u/user)])))

(def ^:static header
  (util/component
   {:render header-render
    :name "header"
    :mixins [rum/static
             rum/reactive]}))
