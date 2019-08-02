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
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.undo :as udu]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as streams]
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
   [uxbox.main.user-events :as uev]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]))

;; --- Workspace

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (st/emit! (uev/scroll-event (gpt/point left top)))))

(defn- on-wheel
  [event canvas]
  (when (kbd/ctrl? event)
    (let [prev-zoom @refs/selected-zoom
          dom (mf/ref-node canvas)
          scroll-position (scroll/get-current-position-absolute dom)
          mouse-point @refs/viewport-mouse-position]
      (dom/prevent-default event)
      (dom/stop-propagation event)
      (if (pos? (.-deltaY event))
        (st/emit! (dw/decrease-zoom))
        (st/emit! (dw/increase-zoom)))
      (scroll/scroll-to-point dom mouse-point scroll-position))))

(defn- subscibe
  [canvas page]
  (let [canvas-dom (mf/ref-node canvas)]
    ;; TODO: scroll stuff need to be refactored
    (scroll/scroll-to-page-center canvas-dom page)
    (st/emit! (udp/watch-page-changes (:id page))
              (udu/watch-page-changes (:id page)))

    (shortcuts/init)))

(defn- unsubscribe
  [shortcuts-subscription]
  (st/emit! ::udp/stop-page-watcher)
  (rx/cancel! shortcuts-subscription))

(mf/defc workspace
  [{:keys [page wst] :as props}]
  (let [flags (:flags wst)
        canvas (mf/use-ref* nil)
        left-sidebar? (not (empty? (keep flags [:layers :sitemap
                                                :document-history])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?)
                 :scrolling (:viewport-positionig workspace))]
    (mf/use-effect {:deps (:id page)
                    :init #(subscibe canvas page)
                    :end unsubscribe})
    [:*
     (messages-widget)
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
         [:& horizontal-rule {:zoom (:zoom wst)}])

       (when (contains? flags :rules)
         [:& vertical-rule {:zoom (:zoom wst)}])

       ;; Canvas
       [:section.workspace-canvas {:id "workspace-canvas"
                                   :ref canvas}
        [:& viewport {:page page
                      :wst wst
                      :key (:id page)}]]]

      ;; Aside
      (when left-sidebar?
        [:& left-sidebar {:wst wst :page page}])
      (when right-sidebar?
        [:& right-sidebar {:wst wst :page page}])]]))


;; TODO: consider using `derive-state` instead of `key` for
;; performance reasons

(mf/def workspace-page
  :mixins [mf/reactive]
  :init
  (fn [own {:keys [project-id page-id] :as props}]
    (st/emit! (dw/initialize project-id page-id))
    (assoc own
           ::page-ref (-> (l/in [:pages page-id])
                          (l/derive st/state))
           ::workspace-ref (-> (l/in [:workspace page-id])
                               (l/derive st/state))))
  :render
  (fn [own props]
    (let [wst (mf/react (::workspace-ref own))
          page (mf/react (::page-ref own))]
      (when page
        [:& workspace {:page page :wst wst}]))))
