;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [goog.object :as gobj]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.common.exceptions :as ex]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.viewer.header :refer [header]]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.main.ui.viewer.shapes :refer [frame-svg]]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]])
  (:import goog.events.EventType))

(mf/defc main-panel
  [{:keys [data local index]}]
  (let [locale  (mf/deref i18n/locale)
        frames  (:frames data [])
        objects (:objects data)
        frame   (get frames index)]
    [:section.viewer-preview
     (cond
       (empty? frames)
       [:section.empty-state
        [:span (t locale "viewer.empty-state")]]

       (nil? frame)
       [:section.empty-state
        [:span (t locale "viewer.frame-not-found")]]

       :else
       [:& frame-svg {:frame frame
                      :show-interactions? (:show-interactions? local)
                      :zoom (:zoom local)
                      :objects objects}])]))

(mf/defc viewer-content
  [{:keys [data local index] :as props}]
  (let [container (mf/use-ref)

        [toggle-fullscreen fullscreen?] (hooks/use-fullscreen container)

        on-click
        (fn [event]
          (dom/stop-propagation event)
          (let [mode (get local :interactions-mode)]
            (when (= mode :show-on-click)
              (st/emit! dv/flash-interactions))))

        on-mouse-wheel
        (fn [event]
          (when (kbd/ctrl? event)
            (dom/prevent-default event)
            (let [event (.getBrowserEvent ^js event)]
              (if (pos? (.-deltaY ^js event))
                (st/emit! dv/decrease-zoom)
                (st/emit! dv/increase-zoom)))))

        on-mount
        (fn []
          ;; bind with passive=false to allow the event to be cancelled
          ;; https://stackoverflow.com/a/57582286/3219895
          (let [key1 (events/listen goog/global EventType.WHEEL
                                    on-mouse-wheel #js {"passive" false})]
            (fn []
              (events/unlistenByKey key1))))]

    (mf/use-effect on-mount)
    (hooks/use-shortcuts dv/shortcuts)

    [:div.viewer-layout {:class (classnames :fullscreen fullscreen?)
                         :ref container}

     [:& header {:data data
                 :toggle-fullscreen toggle-fullscreen
                 :fullscreen? fullscreen?
                 :local local
                 :index index}]
     [:div.viewer-content {:on-click on-click}
      (when (:show-thumbnails local)
        [:& thumbnails-panel {:index index
                              :data data}])
      [:& main-panel {:data data
                      :local local
                      :index index}]]]))


;; --- Component: Viewer Page

(mf/defc viewer-page
  [{:keys [page-id index token] :as props}]
  (mf/use-effect
   (mf/deps page-id token)
   #(st/emit! (dv/initialize page-id token)))

  (let [data (mf/deref refs/viewer-data)
        local (mf/deref refs/viewer-local)]
    (when data
      [:& viewer-content {:index index
                          :local local
                          :data data}])))
