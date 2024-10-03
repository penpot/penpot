(ns preload
  (:require
   [devtools.core :as devtools]))

;; Silence shadow-cljs devtools (ns reloading)
(devtools/set-pref! :dont-display-banner true)
(devtools/set-pref! :min-expandable-sequable-count-for-well-known-types 0)
