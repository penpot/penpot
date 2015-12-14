(ns uxbox.ui.dashboard
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.util :as util]
            [uxbox.router :as r]
            [uxbox.ui.icons :as i]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.header :as ui.h]
            [uxbox.ui.lightbox :as lightbox]
            ;; [uxbox.ui.activity :refer [timeline]]
            [uxbox.ui.icons.dashboard :as icons]
            ;; [uxbox.projects.queries :as q]
            ;; [uxbox.projects.actions :as actions]
            [uxbox.time :refer [ago]]))

;; Config
;; TODO: i18nized names
(def project-orderings {:project/name "name"
                        :project/last-updated "date updated"
                        :project/created "date created"})

(def project-layouts {:mobile {:name "Mobile"
                               :id "mobile"
                               :width 320
                               :height 480}
                      :tablet {:name "Tablet"
                               :id "tablet"
                               :width 1024
                               :height 768}
                      :notebook {:name "Notebook"
                                 :id "notebook"
                                 :width 1366
                                 :height 768}
                      :desktop {:name "Desktop"
                                :id "desktop"
                                :width 1920
                                :height 1080}})

(def new-project-defaults {:name ""
                           :width 1920
                           :height 1080
                           :layout :desktop})

(def name->order (into {} (for [[k v] project-orderings] [v k])))

;; Views

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn layout-input
  [local layout-id]
  (let [layout (get-in project-layouts [layout-id])
        id (:id layout)
        name (:name layout)
        width (:width layout)
        height (:height layout)]
    (html
     [:input
      {:type "radio"
       :key id
       :id id
       :name "project-layout"
       :value name
       :checked (= layout-id (:layout @local))
       :on-change #(swap! local merge {:layout layout-id :width width :height height})}]
     [:label {:value (:name @local) :for id} name])))

(defn- layout-selector
  [local]
  (html
   [:div.input-radio.radio-primary
    (vec (cons :div.input-radio.radio-primary
               (mapcat #(layout-input local %) (keys project-layouts))))]))

(defn- new-project-lightbox-render
  [own]
  (let [local (:rum/local own)
        name (:name @local)
        width (:width @local)
        height (:height @local)]
   (html
    [:div.lightbox-body
     [:h3 "New project"]
     [:form {:on-submit (constantly nil)}
      [:input#project-name.input-text
        {:placeholder "New project name"
         :type "text"
         :value name
         :auto-focus true
         :on-change #(swap! local assoc :name (.-value (.-target %)))}]
      [:div.project-size
       [:input#project-witdh.input-text
        {:placeholder "Width"
         :type "number"
         :min 0 ;;TODO check this value
         :max 666666 ;;TODO check this value
         :value width
         :on-change #(swap! local assoc :width (.-value (.-target %)))}]
       [:a.toggle-layout
        {:href "#"
         :on-click #(swap! local assoc :width width :height height)}
        i/toggle]
       [:input#project-height.input-text
        {:placeholder "Height"
         :type "number"
         :min 0 ;;TODO check this value
         :max 666666 ;;TODO check this value
         :value height
         :on-change #(swap! local assoc :height (.-value (.-target %)))}]]
      ;; Layout selector
      (layout-selector local)
      ;; Submit
      (when-not (empty? (str/trim name))
        [:input#project-btn.btn-primary
         {:value "Go go go!"
          :type "submit"}])]
     [:a.close
      {:href "#"
       :on-click #(lightbox/close!)}
      i/close]])))

                    ;; (.preventDefault e)
                    ;; (let [new-project-attributes {:name (trim name)
                    ;;                               :width (int width)
                    ;;                               :height (int height)
                    ;;                               :layout layout}]
                    ;;  ;; (actions/create-project conn new-project-attributes)
                    ;;  (close-lightbox!)))}

(def new-project-lightbox
  (util/component
   {:render new-project-lightbox-render
    :name "new-project-lightbox"
    :mixins [(rum/local new-project-defaults)]}))

(defmethod lightbox/render-lightbox :new-project
  [_]
  (new-project-lightbox))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rum/defc project-sort-selector < rum/reactive
  [sort-order]
  (let [sort-name (get project-orderings (rum/react sort-order))]
    [:select.input-select
     {:on-change #(reset! sort-order (name->order (.-value (.-target %))))
      :value sort-name}
     (for [order (keys project-orderings)
           :let [name (get project-orderings order)]]
       [:option {:key name} name])]))

(rum/defc project-count < rum/static
  [n]
  [:span.dashboard-projects n " projects"])

(defn menu-render
  []
  (html
   [:section#dashboard-bar.dashboard-bar
    [:div.dashboard-info
     (project-count 0)
     [:span "Sort by"]
     (project-sort-selector (atom :name))]
    [:div.dashboard-search
     icons/search]]))

(def menu
  (util/component
   {:render menu-render
    :name "dashboard-menu"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Project Item
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn project-render
  [own project]
  (html
   [:div.grid-item.project-th {:on-click (constantly nil)
                               :key (:uuid project)}
    [:h3 (:name project)]
    [:span.project-th-update
     (str "Updated " (ago (:last-update project)))]
    [:div.project-th-actions
     [:div.project-th-icon.pages
      icons/page
      [:span 0]]
     [:div.project-th-icon.comments
      i/chat
      [:span 0]]
     [:div.project-th-icon.delete
      {:on-click #(do
                    (dom/stop-propagation %)
                    ;; (actions/delete-project conn uuid)
                    %)}
      icons/trash]]]))

(def project
  (util/component
   {:render project-render
    :name "project"
    :mixins [rum/static]}))

;; (defn sorted-projects
;;   [projects sort-order]
;;   (let [project-cards (map (partial project-card conn) (sort-by sort-order projects))]
;;     (if (= sort-order :project/name)
;;       project-cards
;;       (reverse project-cards))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own]
  (letfn [(on-click [e]
            (dom/prevent-default e)
            (lightbox/set! :new-project))]
    (html
     [:section.dashboard-grid
      [:h2 "Your projects"]
      [:div.dashboard-grid-content
       [:div.dashboard-grid-content
        [:div.grid-item.add-project {:on-click on-click}
         [:span "+ New project"]]]]])))

(def grid
  (util/component
   {:render grid-render
    :name "grid"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dashboard-render
  [own]
  (html
   [:main.dashboard-main
    (ui.h/header)
    [:section.dashboard-content
     (menu)
     (grid)]
    #_(timeline conn)]))


(def dashboard
  (util/component {:render dashboard-render
                   :mixins [rum/reactive]
                   :name "dashboard"}))

;; (rum/defc dashboard
;;   [conn]
;;   [:div
;;    (dashboard* conn)
;;    #_(lightbox conn)])
