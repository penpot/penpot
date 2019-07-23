(ns uxbox.main.ui.dashboard
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.util.data :refer [parse-int uuid-str?]]
   [uxbox.main.ui.dashboard.header :refer [header]]
   [uxbox.main.ui.dashboard.projects :as projects]
   ;; [uxbox.main.ui.dashboard.elements :as elements]
   [uxbox.main.ui.dashboard.icons :as icons]
   [uxbox.main.ui.dashboard.images :as images]
   [uxbox.main.ui.dashboard.colors :as colors]
   [uxbox.main.ui.messages :refer [messages-widget]]))

(defn- parse-route
  [{:keys [params data] :as route}]
  (let [{:keys [id type]} (:query params)
        id (cond
             (str/digits? id) (parse-int id)
             (uuid-str? id) (uuid id)
             :else nil)
        type (when (str/alpha? type) (keyword type))]
    {:section (:name data)
     :type type
     :id id}))

(mf/defc dashboard
  {:wrap [mf/memo*]}
  [{:keys [route] :as props}]
  (let [{:keys [section] :as props} (parse-route route)]
    [:main.dashboard-main
     (messages-widget)
     [:& header props]
     (case section
       :dashboard/icons (mf/element icons/icons-page props)
       :dashboard/images (mf/element images/images-page props)
       :dashboard/projects (mf/element projects/projects-page props)
       :dashboard/colors (mf/element colors/colors-page props))]))
