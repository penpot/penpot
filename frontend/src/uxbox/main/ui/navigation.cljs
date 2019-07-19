(ns uxbox.main.ui.navigation
  (:require [rumext.alpha :refer-macros [html]]
            [goog.events :as events]
            [uxbox.util.dom :as dom]))

(defn link
  "Given an href and a component, return a link component that will navigate
  to the given URI withour reloading the page."
  [href component]
  (html
   [:a {:href (str "/#" href)} component]))
