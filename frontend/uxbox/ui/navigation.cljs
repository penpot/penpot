(ns uxbox.ui.navigation
  (:require [sablono.core :as html :refer-macros [html]]
            [goog.events :as events]
            [uxbox.ui.dom :as dom]))

(defn link
  "Given an href and a component, return a link component that will navigate
  to the given URI withour reloading the page."
  [href component]
  (html
   [:a {:href (str "/#" href)} component]))
