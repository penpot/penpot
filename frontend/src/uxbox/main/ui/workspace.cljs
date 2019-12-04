;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace
  (:require
   [beicon.core :as rx]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.undo :as udu]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.confirm]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.main.ui.workspace.viewport :refer [viewport]]
   [uxbox.main.ui.workspace.colorpalette :refer [colorpalette]]
   ;; [uxbox.main.ui.workspace.download]
   [uxbox.main.ui.workspace.header :refer [header]]
   ;; [uxbox.main.ui.workspace.images]
   [uxbox.main.ui.workspace.rules :refer [horizontal-rule vertical-rule]]
   [uxbox.main.ui.workspace.scroll :as scroll]
   [uxbox.main.ui.workspace.shortcuts :as shortcuts]
   [uxbox.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [uxbox.main.ui.workspace.sidebar.history :refer [history-dialog]]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.rdnd :as rdnd]))

;; --- Workspace

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (st/emit! (uws/scroll-event (gpt/point left top)))))

(defn- on-wheel
  [event canvas]
  (when (kbd/ctrl? event)
    (let [prev-zoom @refs/selected-zoom
          dom (mf/ref-node canvas)
          scroll-position (scroll/get-current-position-absolute dom)
          mouse-point @uws/mouse-position]
      (dom/prevent-default event)
      (dom/stop-propagation event)
      (if (pos? (.-deltaY event))
        (st/emit! (udw/decrease-zoom))
        (st/emit! (udw/increase-zoom)))
      (scroll/scroll-to-point dom mouse-point scroll-position))))

(mf/defc workspace-content
  [{:keys [layout page] :as params}]
  (let [canvas (mf/use-ref nil)
        left-sidebar? (not (empty? (keep layout [:layers :sitemap
                                                :document-history])))
        right-sidebar? (not (empty? (keep layout [:icons :drawtools
                                                 :element-options])))
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?))]

     [:main.main-content
      [:section.workspace-content
       {:class classes
        :on-scroll on-scroll
        :on-wheel #(on-wheel % canvas)}

       [:& history-dialog]

       ;; Rules
       (when (contains? layout :rules)
         [:*
          [:& horizontal-rule]
          [:& vertical-rule]])

       [:section.workspace-viewport {:id "workspace-viewport" :ref canvas}
        [:& viewport {:page page}]]]

      ;; Aside
      (when left-sidebar?
        [:& left-sidebar {:page page :layout layout}])
      (when right-sidebar?
        [:& right-sidebar {:page page :layout layout}])]))

(mf/defc workspace
  [{:keys [page-id] :as props}]
  (let [layout (mf/deref refs/workspace-layout)
        flags  (mf/deref refs/selected-flags)
        page   (mf/deref refs/workspace-page)]

    [:*
     [:& messages-widget]
     [:& header {:page page :flags flags}]

     (when (:colorpalette flags)
       [:& colorpalette])

     (when (and layout page)
       [:& workspace-content {:layout layout :page page}])]))

(mf/defc workspace-page
  [{:keys [project-id page-id] :as props}]

  (mf/use-effect
   {:deps #js [page-id]
    :fn (fn []
          (let [sub (shortcuts/init)]
            (st/emit! (udw/initialize project-id page-id))
            #(rx/cancel! sub)))})

  [:> rdnd/provider {:backend rdnd/html5}
   [:& workspace {:page-id page-id :key page-id}]])
