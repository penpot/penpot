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
    [(:name data) type id]))

(mf/defc dashboard
  [{:keys [route] :as props}]
  (let [[section type id] (parse-route route)]
    [:main.dashboard-main
     [:& messages-widget]
     [:& header {:section section}]
     (case section
       :dashboard/icons
       [:& icons/icons-page {:type type :id id}]

       :dashboard/images
       [:& images/images-page {:type type :id id}]

       :dashboard/projects
       [:& projects/projects-page]

       :dashboard/colors
       [:& colors/colors-page {:type type :id id}])]))
