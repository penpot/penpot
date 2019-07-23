;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.lightbox :as lbx]
   [uxbox.main.ui.workspace.sidebar.sitemap-pageform]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.dom.dnd :as dnd]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as r]))

(mf/defc page-item
  [{:keys [page deletable? selected?] :as props}]
  (let [local (mf/use-state {})
        body-classes (classnames
                      :selected selected?
                      :drag-active (:dragging @local)
                      :drag-top (= :top (:over @local))
                      :drag-bottom (= :bottom (:over @local))
                      :drag-inside (= :middle (:over @local)))
        li-classes (classnames
                    :selected selected?
                    :hide (:dragging @local))]
    (letfn [(on-edit [event]
              (udl/open! :page-form {:page page}))

            (on-navigate [event]
              (st/emit! (dp/go-to (:project page) (:id page))))

            (delete []
              (let [next #(st/emit! (dp/go-to (:project page)))]
                (st/emit! (udp/delete-page (:id page) next))))

            (on-delete [event]
              (dom/prevent-default event)
              (dom/stop-propagation event)
              (udl/open! :confirm {:on-accept delete}))

            (on-drag-start [event]
              (let [target (dom/event->target event)]
                (dnd/set-allowed-effect! event "move")
                (dnd/set-data! event (:id page))
                (dnd/set-image! event target 50 10)
                (swap! local assoc :dragging true)))
            (on-drag-end [event]
              (swap! local assoc :dragging false :over nil))
            (on-drop [event]
              (dom/stop-propagation event)
              (let [id (dnd/get-data event)
                    over (:over @local)]
                (case (:over @local)
                  :top (let [new-order (dec (get-in page [:metadata :order]))]
                         (st/emit! (udp/update-order id new-order)))
                  :bottom (let [new-order (inc (get-in page [:metadata :order]))]
                            (st/emit! (udp/update-order id new-order))))
                (swap! local assoc :dragging false :over nil)))
            (on-drag-over [event]
              (dom/prevent-default event)
              (dnd/set-drop-effect! event "move")
              (let [over (dnd/get-hover-position event false)]
                (swap! local assoc :over over)))
            (on-drag-enter [event]
              (swap! local assoc :over true))
            (on-drag-leave [event]
              (swap! local assoc :over false))]
      [:li {:class li-classes}
       [:div.element-list-body
        {:class body-classes
         :style {:opacity (if (:dragging @local)
                            "0.5"
                            "1")}
         :on-click on-navigate
         :on-double-click #(dom/stop-propagation %)
         :on-drag-start on-drag-start
         :on-drag-enter on-drag-enter
         :on-drag-leave on-drag-leave
         :on-drag-over on-drag-over
         :on-drag-end on-drag-end
         :on-drop on-drop
         :draggable true}

        [:div.page-icon i/page]
        [:span (:name page)]
        [:div.page-actions {}
         [:a {:on-click on-edit} i/pencil]
         (when deletable?
           [:a {:on-click on-delete} i/trash])]]])))

(mf/def sitemap-toolbox
  :mixins [mf/memo mf/reactive]

  :render
  (fn [own current-page-id]
    (let [project (mf/react refs/selected-project)
          pages (mf/react refs/selected-project-pages)
          create #(udl/open! :page-form {:page {:project (:id project)}})
          close #(st/emit! (dw/toggle-flag :sitemap))
          deletable? (> (count pages) 1)]
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
         (for [page pages]
           (let [selected? (= (:id page) current-page-id)]
             [:& page-item {:page page
                            :deletable? deletable?
                            :selected? selected?
                            :key (:id page)}]))]]])))
