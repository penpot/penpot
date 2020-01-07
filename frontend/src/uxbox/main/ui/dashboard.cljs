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

(mf/defc dashboard-projects
  [{:keys [route] :as props}]
  (let [id (get-in route [:params :query :project-id])
        id (when (uuid-str? id) (uuid id))]
    [:main.dashboard-main
     [:& messages-widget]
     [:& header {:section :dashboard-projects}]
     [:& projects/projects-page {:id id}]]))

(mf/defc dashboard-assets
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        {:keys [id type]} (get-in route [:params :query])
        id (cond
             ;; (str/digits? id) (parse-int id)
             (uuid-str? id) (uuid id)
             (str/empty-or-nil? id) nil
             :else id)
        type (if (str/alpha? type) (keyword type) :own)]
    [:main.dashboard-main
     [:& messages-widget]
     [:& header {:section section}]
     (case section
       :dashboard-icons
       [:& icons/icons-page {:type type :id id}]

       :dashboard-images
       [:& images/images-page {:type type :id id}]

       :dashboard-colors
       [:& colors/colors-page {:type type :id id}])]))
