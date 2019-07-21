(ns uxbox.main.ui.dashboard
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.dashboard.header :refer [header]]
   [uxbox.main.ui.dashboard.projects :as projects]
   ;; [uxbox.main.ui.dashboard.elements :as elements]
   [uxbox.main.ui.dashboard.icons :as icons]
   [uxbox.main.ui.dashboard.images :as images]
   [uxbox.main.ui.dashboard.colors :as colors]
   [uxbox.main.ui.messages :refer [messages-widget]]))

(def projects-page projects/projects-page)
;; (def elements-page elements/elements-page)
(def icons-page icons/icons-page)
(def images-page images/images-page)
(def colors-page colors/colors-page)

(mf/defc dashboard
  [props]
  [:main.dashboard-main
   (messages-widget)
   (header)
   (case (:section props)
     :icons (icons/icons-page props)
     :images (images/images-page props)
     :projects (projects/projects-page props)
     :colors (colors/colors-page props))])
