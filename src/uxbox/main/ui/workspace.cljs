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
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.history :as udh]
            [uxbox.util.dom :as dom]
            [uxbox.main.geom.point :as gpt]
            [uxbox.util.data :refer (classnames)]
            [uxbox.util.mixins :as mx]
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
  (let [[projectid pageid] (:rum/props own)]
    (rs/emit! (dw/initialize projectid pageid)
              (udh/watch-page-changes))
    own))

(defn- workspace-did-mount
  [own]
  (let [[projectid pageid] (:rum/props own)
        sub1 (scroll/watch-scroll-interactions own)
        sub2 (udp/watch-page-changes pageid)
        dom (mx/get-ref-dom own "workspace-canvas")]

    ;; Set initial scroll position
    (set! (.-scrollLeft dom) (* c/canvas-start-scroll-x @wb/zoom-l))
    (set! (.-scrollTop dom) (* c/canvas-start-scroll-y @wb/zoom-l))

    (assoc own ::sub1 sub1 ::sub2 sub2)))

(defn- workspace-will-unmount
  [own]
  (rs/emit! (udh/clean-page-history))

  ;; Close subscriptions
  (.close (::sub1 own))
  (.close (::sub2 own))

  (dissoc own ::sub1 ::sub2))

(defn- workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)
        [oldprojectid oldpageid] (:rum/props old-state)]
    (if (not= pageid oldpageid)
      (do
        (rs/emit! (dw/initialize projectid pageid))
        (.close (::sub2 old-state))
        (assoc state
               ::sub1 (::sub1 old-state)
               ::sub2 (udp/watch-page-changes pageid)))
      (assoc state
             ::sub1 (::sub1 old-state)
             ::sub2 (::sub2 old-state)))))

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (rx/push! wb/scroll-b (gpt/point left top))))

(defn- on-wheel
  [own event]
  (when (kbd/ctrl? event)
    (dom/prevent-default event)
    (dom/stop-propagation event)
    (if (pos? (.-deltaY event))
      (rs/emit! (dw/increase-zoom))
      (rs/emit! (dw/decrease-zoom)))

    (let [dom (mx/get-ref-dom own "workspace-canvas")]
      (set! (.-scrollLeft dom) (* c/canvas-start-scroll-x @wb/zoom-l))
      (set! (.-scrollTop dom) (* c/canvas-start-scroll-y @wb/zoom-l)))))

(defn- workspace-render
  [own projectid]
  (let [{:keys [flags zoom page] :as workspace} (rum/react wb/workspace-l)
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
    :transfer-state workspace-transfer-state
    :will-mount workspace-will-mount
    :will-unmount workspace-will-unmount
    :did-mount workspace-did-mount
    :name "workspace"
    :mixins [mx/static
             rum/reactive
             shortcuts-mixin
             (mx/local)]}))
