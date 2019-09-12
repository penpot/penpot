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
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.confirm]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.main.ui.workspace.viewport :refer [viewport]]
   [uxbox.main.ui.workspace.colorpalette :refer [colorpalette]]
   [uxbox.main.ui.workspace.download]
   [uxbox.main.ui.workspace.header :refer [header]]
   [uxbox.main.ui.workspace.images]
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
        (st/emit! (dw/decrease-zoom))
        (st/emit! (dw/increase-zoom)))
      (scroll/scroll-to-point dom mouse-point scroll-position))))

(defn- subscribe
  [canvas page]
  ;; (scroll/scroll-to-page-center (mf/ref-node canvas) page)
  (st/emit! (udp/watch-page-changes (:id page))
            (udu/watch-page-changes (:id page)))
  (let [sub (shortcuts/init)]
    #(do (st/emit! ::udp/stop-page-watcher)
         (rx/cancel! sub))))

(mf/defc workspace
  [{:keys [page] :as props}]
  (let [flags  (or (mf/deref refs/flags) #{})
        canvas (mf/use-ref nil)

        left-sidebar? (not (empty? (keep flags [:layers :sitemap
                                                :document-history])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?)
                 :scrolling (:viewport-positionig workspace))]

    (mf/use-effect #(subscribe canvas page)
                   #js [(:id page)])
    [:*
     [:& messages-widget]
     [:& header {:page page
                 :flags flags
                 :key (:id page)}]

     (when (:colorpalette flags)
       [:& colorpalette])

     [:main.main-content
      [:section.workspace-content
       {:class classes
        :on-scroll on-scroll
        :on-wheel #(on-wheel % canvas)}

       (history-dialog)

       ;; Rules
       (when (contains? flags :rules)
         [:& horizontal-rule])

       (when (contains? flags :rules)
         [:& vertical-rule])

       [:section.workspace-viewport {:id "workspace-viewport" :ref canvas}
        [:& viewport {:page page :key (:id page)}]]]

      ;; Aside
      (when left-sidebar?
        [:& left-sidebar {:page page :flags flags}])
      (when right-sidebar?
        [:& right-sidebar {:page page :flags flags}])]]))

(mf/defc workspace-page
  [{:keys [project-id page-id] :as props}]
  (let [page-iref (mf/use-memo {:deps #js [project-id page-id]
                                :fn #(-> (l/in [:pages page-id])
                                         (l/derive st/state))})
        page (mf/deref page-iref)]

    (mf/use-effect
     {:deps #js [project-id page-id]
      :fn #(st/emit! (dw/initialize project-id page-id))})

    ;; (prn "workspace-page.render" (:id page) props)

    [:> rdnd/provider {:backend rdnd/html5}
     (when page
       [:& workspace {:page page}])]))
