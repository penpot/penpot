;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [goog.events :as events]
   [okulary.core :as l]
   [app.common.exceptions :as ex]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.viewer.header :refer [header]]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.main.ui.viewer.handoff.render :refer [render-frame-svg]]
   [app.main.ui.viewer.handoff.left-sidebar :refer [left-sidebar]]
   [app.main.ui.viewer.handoff.right-sidebar :refer [right-sidebar]])
  (:import goog.events.EventType))

(defn handle-select-frame [frame]
  #(do (dom/prevent-default %)
       (dom/stop-propagation %)
       (st/emit! (dv/select-shape (:id frame)))))

(mf/defc render-panel
  [{:keys [data local index]}]
  (let [locale  (mf/deref i18n/locale)
        frames  (:frames data [])
        objects (:objects data)
        frame   (get frames index)]

    (mf/use-effect
     (mf/deps index)
     (fn []
       (st/emit! (dv/set-current-frame (:id frame))
                 (dv/select-shape (:id frame)))))

    [:section.viewer-preview
     (cond
       (empty? frames)
       [:section.empty-state
        [:span (t locale "viewer.empty-state")]]

       (nil? frame)
       [:section.empty-state
        [:span (t locale "viewer.frame-not-found")]]

       :else
       [:*
        [:& left-sidebar {:frame frame}]
        [:div.handoff-svg-wrapper {:on-click (handle-select-frame frame)}
         [:& render-frame-svg {:frame-id (:id frame)
                               :zoom (:zoom local)
                               :objects objects}]]
        [:& right-sidebar {:frame frame}]])]))

(mf/defc handoff-content
  [{:keys [data local index] :as props}]
  (let [container (mf/use-ref)

        [toggle-fullscreen fullscreen?] (hooks/use-fullscreen container)

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

    [:div.handoff-layout {:class (classnames :fullscreen fullscreen?)
                         :ref container}
     [:& header {:data data
                 :toggle-fullscreen toggle-fullscreen
                 :fullscreen? fullscreen?
                 :local local
                 :index index
                 :screen :handoff}]
     [:div.viewer-content
      (when (:show-thumbnails local)
        [:& thumbnails-panel {:index index
                              :data data
                              :screen :handoff}])
      [:& render-panel {:data data
                        :local local
                        :index index}]]]))

(mf/defc handoff
  [{:keys [file-id page-id index] :as props}]
  (mf/use-effect
   (mf/deps file-id page-id)
   (fn []
     (st/emit! (dv/initialize props))))

  (let [data (mf/deref refs/viewer-data)
        local (mf/deref refs/viewer-local)]
    (when data
      [:& handoff-content {:index index
                           :local local
                           :data data}])))
