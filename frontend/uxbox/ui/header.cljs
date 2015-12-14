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
    (ui.u/user)]))

(def ^:static header
  (util/component
   {:render header-render
    :name "header"
    :mixins [rum/static]}))



