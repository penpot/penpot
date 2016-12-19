;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [uxbox.main.constants :as c]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.history :as udh]
            [uxbox.main.data.undo :as udu]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (classnames)]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.ui.confirm]
            [uxbox.main.ui.workspace.images]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.workspace.scroll :as scroll]
            [uxbox.main.ui.workspace.download]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.shortcuts :refer (shortcuts-mixin)]
            [uxbox.main.ui.workspace.header :refer (header)]
            [uxbox.main.ui.workspace.rules :refer (horizontal-rule vertical-rule)]
            [uxbox.main.ui.workspace.sidebar.history :refer (history-dialog)]
            [uxbox.main.ui.workspace.sidebar :refer (left-sidebar right-sidebar)]
            [uxbox.main.ui.workspace.colorpalette :refer (colorpalette)]
            [uxbox.main.ui.workspace.canvas :refer (viewport)]))

;; --- Workspace

(defn- workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/args own)]
    (st/emit! (dw/initialize projectid pageid))
    own))

(defn- workspace-did-mount
  [own]
  (let [[projectid pageid] (:rum/args own)
        sub1 (scroll/watch-scroll-interactions own)
        dom (mx/ref-node own "workspace-canvas")]

    (st/emit! (udp/watch-page-changes pageid)
              (udu/watch-page-changes pageid)
              (udh/watch-page-changes pageid))

    ;; Set initial scroll position
    (set! (.-scrollLeft dom) (* c/canvas-start-scroll-x @wb/zoom-ref))
    (set! (.-scrollTop dom) (* c/canvas-start-scroll-y @wb/zoom-ref))

    (assoc own ::sub1 sub1)))

(defn- workspace-will-unmount
  [own]
  (st/emit! ::udp/stop-page-watcher)
  (.close (::sub1 own))
  (dissoc own ::sub1))

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
    (rx/push! wb/scroll-b (gpt/point left top))))

(defn- on-wheel
  [own event]
  (when (kbd/ctrl? event)
    (let [prev-zoom @wb/zoom-ref]
      (dom/prevent-default event)
      (dom/stop-propagation event)
      (if (pos? (.-deltaY event))
        (st/emit! (dw/increase-zoom))
        (st/emit! (dw/decrease-zoom)))

      (let [
          dom (mx/ref-node own "workspace-canvas")
          scroll-position (gpt/divide @wb/scroll-a prev-zoom)
          mouse-position (gpt/divide @wb/mouse-viewport-a prev-zoom)
          viewport-offset-x (* (- (:x mouse-position) (:x scroll-position)) prev-zoom)
          viewport-offset-y (* (- (:y mouse-position) (:y scroll-position)) prev-zoom)
          new-scroll-position-x (- (* (:x mouse-position) @wb/zoom-ref) viewport-offset-x)
          new-scroll-position-y (- (* (:y mouse-position) @wb/zoom-ref) viewport-offset-y)
        ]
        (set! (.-scrollLeft dom) new-scroll-position-x )
        (set! (.-scrollTop dom) new-scroll-position-y)))))

(defn- workspace-render
  [own]
  (let [{:keys [flags zoom page] :as workspace} (mx/react wb/workspace-ref)
        left-sidebar? (not (empty? (keep flags [:layers :sitemap
                                                :document-history])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        local (:rum/local own)
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?)
                 :scrolling (:scrolling @local false))]
    (html
     [:div
      (header)
      (colorpalette)
      (uum/messages)

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
         {:ref "workspace-canvas"}
         (viewport)]]

       ;; Aside
       (when left-sidebar?
         (left-sidebar))
       (when right-sidebar?
         (right-sidebar))]])))

(def workspace
  (mx/component
   {:render workspace-render
    :did-remount workspace-did-remount
    :will-mount workspace-will-mount
    :will-unmount workspace-will-unmount
    :did-mount workspace-did-mount
    :name "workspace"
    :mixins [mx/static
             mx/reactive
             shortcuts-mixin
             (mx/local)]}))
