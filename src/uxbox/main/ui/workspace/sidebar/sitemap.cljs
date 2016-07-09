;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.state.project :as stpr]
            [uxbox.main.library :as library]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.dashboard.projects :refer (+layouts+)]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.data :refer (read-string parse-int)]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def pages-l
  (letfn [(getter [state]
            (let [project (get-in state [:workspace :project])]
              (stpr/project-pages state project)))]
    (-> (l/lens getter)
        (l/derive st/state))))

;; --- Component

(defn page-item-render
  [own page total active?]
  (letfn [(on-edit [event]
            (udl/open! :page-form {:page page}))

          (on-navigate [event]
            (rs/emit! (dp/go-to (:project page) (:id page))))

          (delete []
            (let [next #(rs/emit! (dp/go-to (:project page)))]
              (rs/emit! (udp/delete-page (:id page) next))))

          (on-delete [event]
            (dom/prevent-default event)
            (dom/stop-propagation event)
            (udl/open! :confirm {:on-accept delete}))]
    (html
     [:li {:class (when active? "selected")
           :on-click on-navigate}
      [:div.page-icon i/page]
      [:span (:name page)]
      [:div.page-actions
       [:a {:on-click on-edit} i/pencil]
       (if (> total 1)
         [:a {:on-click on-delete} i/trash])]])))

(def page-item
  (mx/component
   {:render page-item-render
    :name "page-item"
    :mixins [(mx/local) mx/static mx/reactive]}))

(defn sitemap-toolbox-render
  [own]
  (let [project (mx/react wb/project-l)
        pages (mx/react pages-l)
        current (mx/react wb/page-l)
        create #(udl/open! :page-form {:page {:project (:id project)}})
        close #(rs/emit! (dw/toggle-flag :sitemap))]
    (html
     [:div.sitemap.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/project-tree]
       [:span (tr "ds.sitemap")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:div.project-title
        [:span (:name project)]
        [:div.add-page {:on-click create} i/close]]
       [:ul.element-list
        (for [page pages
              :let [active? (= (:id page) (:id current))]]
          (-> (page-item page (count pages) active?)
              (rum/with-key (:id page))))]]])))

(def sitemap-toolbox
  (mx/component
   {:render sitemap-toolbox-render
    :name "sitemap-toolbox"
    :mixins [mx/static mx/reactive]}))

;; --- Lightbox

(def ^:const +page-defaults+
  {:width 1920
   :height 1080
   :layout :desktop})

(defn- layout-input
  [local page id]
  (let [layout (get +layouts+ id)
        size (select-keys layout [:width :height])
        change #(swap! local merge {:layout id} size)]
    (html
     [:div
      [:input {:type "radio"
               :key id :id id
               :name "project-layout"
               :value (:id layout)
               :checked (= id (:layout page))
               :on-change change}]
      [:label {:value (:id layout) :for id} (:name layout)]])))

(defn- page-form-lightbox-render
  [own local page]
  (let [edition? (:id page)
        page (merge page @local {:data nil})
        valid? (and (not (str/empty? (str/trim (:name page ""))))
                    (pos? (:width page))
                    (pos? (:height page)))]
    (letfn [(update-size [field e]
              (let [value (dom/event->value e)
                    value (parse-int value)]
                (swap! local assoc field value)))
            (update-name [e]
              (let [value (dom/event->value e)]
                (swap! local assoc :name value)))
            (toggle-sizes []
              (swap! local assoc
                     :width (:height page)
                     :height (:width page)))
            (cancel [e]
              (dom/prevent-default e)
              (udl/close!))
            (persist [e]
              (dom/prevent-default e)
              (udl/close!)
              (if edition?
                (rs/emit! (udp/update-page-metadata page))
                (rs/emit! (udp/create-page page))))]
      (html
       [:div.lightbox-body
        (if edition?
          [:h3 "Edit page"]
          [:h3 "New page"])
        [:form
         [:input#project-name.input-text
          {:placeholder "Page name"
           :type "text"
           :value (:name page "")
           :auto-focus true
           :on-change update-name}]
         [:div.project-size
          [:input#project-witdh.input-text
           {:placeholder "Width"
            :type "number"
            :min 0
            :max 4000
            :value (:width page)
            :on-change #(update-size :width %)}]
          [:a.toggle-layout {:on-click toggle-sizes} i/toggle]
          [:input#project-height.input-text
           {:placeholder "Height"
            :type "number"
            :min 0
            :max 4000
            :value (:height page)
            :on-change #(update-size :height %)}]]

         [:div.input-radio.radio-primary
          (layout-input local page "mobile")
          (layout-input local page "tablet")
          (layout-input local page "notebook")
          (layout-input local page "desktop")]

         (when valid?
           [:input#project-btn.btn-primary
            {:value "Go go go!"
             :on-click persist
             :type "button"}])]
        [:a.close {:on-click cancel} i/close]]))))

(def page-form-lightbox
  (mx/component
   {:render #(page-form-lightbox-render %1 (:rum/local %1) %2)
    :name "page-form-lightbox"
    :mixins [(mx/local)]}))

(defmethod lbx/render-lightbox :page-form
  [{:keys [page]}]
  (page-form-lightbox page))
