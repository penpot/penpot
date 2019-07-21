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
   [uxbox.main.ui.workspace.canvas :refer [viewport]]
   [uxbox.main.ui.workspace.colorpalette :refer [colorpalette]]
   [uxbox.main.ui.workspace.download]
   [uxbox.main.ui.workspace.header :refer [header]]
   [uxbox.main.ui.workspace.images]
   [uxbox.main.ui.workspace.rules :refer [horizontal-rule vertical-rule]]
   [uxbox.main.ui.workspace.scroll :as scroll]
   [uxbox.main.ui.workspace.shortcuts :refer [shortcuts-mixin]]
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
  [own event]
  (when (kbd/ctrl? event)
    (let [prev-zoom @refs/selected-zoom
          dom (mf/ref-node (::canvas own))
          scroll-position (scroll/get-current-position-absolute dom)
          mouse-point @refs/viewport-mouse-position]
      (dom/prevent-default event)
      (dom/stop-propagation event)
      (if (pos? (.-deltaY event))
        (st/emit! (dw/decrease-zoom))
        (st/emit! (dw/increase-zoom)))
      (scroll/scroll-to-point dom mouse-point scroll-position))))

(def ^:private workspace-page-ref
  (-> (l/key :page)
      (l/derive refs/workspace)))

(mf/def workspace
  :key-fn vector
  :mixins #{mf/memo
            mf/reactive
            shortcuts-mixin}

  :init
  (fn [own {:keys [project page] :as props}]
    (st/emit! (dw/initialize project page))
    (assoc own ::canvas (mf/create-ref)))

  :did-mount
  (fn [own]
    (let [{:keys [project page]} (::mf/props own)
          ;; dom (mf/ref-node own "workspace-canvas")
          dom (mf/ref-node (::canvas own))
          scroll-to-page-center #(scroll/scroll-to-page-center dom @refs/selected-page)
          sub (rx/subscribe streams/page-id-ref-s scroll-to-page-center)]
      (scroll-to-page-center)
      (st/emit! (udp/watch-page-changes page)
                (udu/watch-page-changes page))
      (assoc own ::sub sub)))

  :will-unmount
  (fn [own]
    (st/emit! ::udp/stop-page-watcher)
    (rx/cancel! (::sub own))
    (dissoc own ::sub))

  :render
  (fn [own props]
    (let [flags (mf/react refs/flags)
          project-id (get props :project)
          page-id (get props :page)
          left-sidebar? (not (empty? (keep flags [:layers :sitemap
                                                  :document-history])))
          right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                   :element-options])))
          classes (classnames
                   :no-tool-bar-right (not right-sidebar?)
                   :no-tool-bar-left (not left-sidebar?)
                   :scrolling (:viewport-positionig workspace))]
      [:*
       (messages-widget)
       (header)
       (colorpalette)

       [:main.main-content
        [:section.workspace-content
         {:class classes
          :on-scroll on-scroll
          :on-wheel (partial on-wheel own)}

         (history-dialog)

         ;; Rules
         (when (contains? flags :rules)
           (horizontal-rule))

         (when (contains? flags :rules)
           (vertical-rule))

         ;; Canvas
         [:section.workspace-canvas {:id "workspace-canvas"
                                     :ref (::canvas own)}
          (viewport)]]

        ;; Aside
        (when left-sidebar?
          (left-sidebar {:flags flags :page-id page-id}))
        (when right-sidebar?
          (right-sidebar {:flags flags :page-id page-id}))]])))
