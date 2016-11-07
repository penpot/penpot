;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.dashboard.projects :refer (+layouts+)]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.data :refer (deep-merge parse-int)]
            [uxbox.util.dom :as dom]))

;; --- Refs

(defn- resolve-pages
  [state]
  (let [project (get-in state [:workspace :project])]
    (->> (vals (:pages state))
         (filter #(= project (:project %)))
         (sort-by :created-at))))

(def pages-ref
  (-> (l/lens resolve-pages)
      (l/derive st/state)))

;; --- Component

(mx/defc page-item
  {:mixins [(mx/local) mx/static mx/reactive]}
  [page total active?]
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
    [:li {:class (when active? "selected")
          :on-click on-navigate}
     [:div.page-icon i/page]
     [:span (:name page)]
     [:div.page-actions
      [:a {:on-click on-edit} i/pencil]
      (if (> total 1)
        [:a {:on-click on-delete} i/trash])]]))

(mx/defc sitemap-toolbox
  {:mixins [mx/static mx/reactive]}
  []
  (let [project (mx/react wb/project-ref)
        pages (mx/react pages-ref)
        current (mx/react wb/page-ref)
        create #(udl/open! :page-form {:page {:project (:id project)}})
        close #(rs/emit! (dw/toggle-flag :sitemap))]
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
             (mx/with-key (:id page))))]]]))

;; --- Lightbox

(def +page-defaults+
  {:width 1920
   :height 1080
   :layout :desktop})

(mx/defc layout-input
  [local page id]
  (let [layout (get +layouts+ id)
        metadata (:metadata page)
        size (select-keys layout [:width :height])
        change #(swap! local update :metadata merge {:layout id} size)]
    [:div
     [:input {:type "radio"
              :key id :id id
              :name "project-layout"
              :value (:id layout)
              :checked (= id (:layout metadata))
              :on-change change}]
     [:label {:value (:id layout) :for id} (:name layout)]]))

(mx/defcs page-form-lightbox
  {:mixins [(mx/local)]}
  [own page]
  (let [local (:rum/local own)
        page (deep-merge page @local {:data nil})
        metadata (:metadata page)
        edition? (:id page)
        valid? (and (not (str/empty? (str/trim (:name page ""))))
                    (pos? (:width metadata))
                    (pos? (:height metadata)))]
    (letfn [(update-size [field e]
              (let [value (dom/event->value e)
                    value (parse-int value)]
                (swap! local assoc-in [:metadata field] value)))
            (update-name [e]
              (let [value (dom/event->value e)]
                (swap! local assoc :name value)))
            (toggle-sizes []
              (let [width (get-in page [:metadata :width])
                    height (get-in page [:metadata :height])]
                (swap! local update :metadata merge {:width height
                                                     :height width})))
            (cancel [e]
              (dom/prevent-default e)
              (udl/close!))
            (persist [e]
              (dom/prevent-default e)
              (udl/close!)
              (if edition?
                (rs/emit! (udp/update-page-metadata page))
                (rs/emit! (udp/create-page page))))]
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
         [:div.input-element.pixels
          [:input#project-witdh.input-text
           {:placeholder "Width"
            :type "number"
            :min 0
            :max 4000
            :value (:width metadata)
            :on-change #(update-size :width %)}]]
         [:a.toggle-layout {:on-click toggle-sizes} i/toggle]
         [:div.input-element.pixels
          [:input#project-height.input-text
           {:placeholder "Height"
            :type "number"
            :min 0
            :max 4000
            :value (:height metadata)
            :on-change #(update-size :height %)}]]]

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
       [:a.close {:on-click cancel} i/close]])))

(defmethod lbx/render-lightbox :page-form
  [{:keys [page]}]
  (page-form-lightbox page))
