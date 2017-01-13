;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.history :as udh]
            [uxbox.main.data.undo :as udu]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.confirm]
            [uxbox.main.ui.workspace.images]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.workspace.scroll :as scroll]
            [uxbox.main.ui.workspace.download]
            [uxbox.main.ui.workspace.shortcuts :refer (shortcuts-mixin)]
            [uxbox.main.ui.workspace.header :refer (header)]
            [uxbox.main.ui.workspace.rules :refer (horizontal-rule vertical-rule)]
            [uxbox.main.ui.workspace.sidebar.history :refer (history-dialog)]
            [uxbox.main.ui.workspace.sidebar :refer (left-sidebar right-sidebar)]
            [uxbox.main.ui.workspace.colorpalette :refer (colorpalette)]
            [uxbox.main.ui.workspace.canvas :refer (viewport)]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (classnames)]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Workspace

(defn- workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/args own)]
    (st/emit! (dw/initialize projectid pageid))
    own))

(defn- workspace-did-mount
  [own]
  (let [[projectid pageid] (:rum/args own)
        dom (mx/ref-node own "workspace-canvas")
        scroll-to-page-center #(scroll/scroll-to-page-center dom @refs/selected-page)
        ;; sub1 (scroll/watch-scroll-interactions own)
        sub2 (rx/subscribe streams/page-id-ref-s scroll-to-page-center)]

    (scroll-to-page-center)

    (st/emit! (udp/watch-page-changes pageid)
              (udu/watch-page-changes pageid)
              (udh/watch-page-changes pageid))

    (assoc own
           ;; ::sub1 sub1
           ::sub2 sub2)))

(defn- workspace-will-unmount
  [own]
  (st/emit! ::udp/stop-page-watcher)
  ;; (.close (::sub1 own))
  (.close (::sub2 own))
  (dissoc own ::sub1 ::sub2))

(defn- workspace-did-remount
  [old-state state]
  (let [[projectid pageid] (:rum/args state)
        [oldprojectid oldpageid] (:rum/args old-state)]
    (when (not= pageid oldpageid)
      (st/emit! (dw/initialize projectid pageid)
                ::udp/stop-page-watcher
                (udp/watch-page-changes pageid)
                (udu/watch-page-changes pageid)
                (udh/watch-page-changes pageid)))
    state))

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (rx/push! streams/scroll-b (gpt/point left top))))

(defn- on-wheel
  [own event]
  (when (kbd/ctrl? event)
    (let [prev-zoom @refs/selected-zoom
          dom (mx/ref-node own "workspace-canvas")
          scroll-position (scroll/get-current-position-absolute dom)
          mouse-point @streams/mouse-viewport-a]
      (dom/prevent-default event)
      (dom/stop-propagation event)
      (if (pos? (.-deltaY event))
        (st/emit! (dw/increase-zoom))
        (st/emit! (dw/decrease-zoom)))
      (scroll/scroll-to-point dom mouse-point scroll-position))))

(mx/defcs workspace
  {:did-remount workspace-did-remount
   :will-mount workspace-will-mount
   :will-unmount workspace-will-unmount
   :did-mount workspace-did-mount
   :mixins [mx/static
            mx/reactive
            shortcuts-mixin
            (mx/local)]}
  [own]
  (let [{:keys [flags zoom page] :as workspace} (mx/react refs/workspace)
        left-sidebar? (not (empty? (keep flags [:layers :sitemap
                                                :document-history])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        local (:rum/local own)
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?)
                 :scrolling (:viewport-positionig workspace))]
    [:div
     (messages-widget)
     (header)
     (colorpalette)

     [:main.main-content

      [:section.workspace-content
       {:class classes
        :on-scroll on-scroll
        :on-wheel (partial on-wheel own)}


       (history-dialog page)

       ;; Rules
       (horizontal-rule zoom)
       (vertical-rule zoom)

       ;; Canvas
       [:section.workspace-canvas
        {:id "workspace-canvas"
         :ref "workspace-canvas"}
        (viewport)]]

      ;; Aside
      (when left-sidebar?
        (left-sidebar))
      (when right-sidebar?
        (right-sidebar))]]))
