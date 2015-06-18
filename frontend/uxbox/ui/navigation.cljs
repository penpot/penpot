(ns uxbox.ui.navigation
  (:require [goog.events :as events]))

(defn link
  "Given an href and a component, return a link component that will navigate
  to the given URI withour reloading the page."
  [href component]
  [:a
   {:href href
    :on-click #(do (.preventDefault %) (set-uri! href))}
   component])
