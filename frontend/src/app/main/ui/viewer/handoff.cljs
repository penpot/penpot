;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.handoff
  (:require
   [app.main.data.viewer :as dv]
   [app.main.store :as st]
   [app.main.ui.viewer.handoff.left-sidebar :refer [left-sidebar]]
   [app.main.ui.viewer.handoff.render :refer [render-frame-svg]]
   [app.main.ui.viewer.handoff.right-sidebar :refer [right-sidebar]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [rumext.alpha :as mf])
  (:import goog.events.EventType))

(defn handle-select-frame
  [frame]
  (fn [event]
    (dom/prevent-default event)
    (dom/stop-propagation event)
    (st/emit! (dv/select-shape (:id frame)))))

(mf/defc viewport
  [{:keys [local file page frame index viewer-pagination size]}]
  (let [on-mouse-wheel
        (fn [event]
          (when (kbd/mod? event)
            (dom/prevent-default event)
            (let [event (.getBrowserEvent ^js event)
                  delta (+ (.-deltaY ^js event)
                           (.-deltaX ^js event))]
              (if (pos? delta)
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

    (mf/use-effect
     (mf/deps (:id frame))
     (fn []
       (st/emit! (dv/select-shape (:id frame)))))

    [:*
     [:& left-sidebar {:frame frame
                       :local local
                       :page page}]
     [:div.handoff-svg-wrapper {:on-click (handle-select-frame frame)}
      [:& viewer-pagination {:index index :num-frames (count (:frames page)) :left-bar true :right-bar true}]
      [:div.handoff-svg-container
       [:& render-frame-svg {:frame frame :page page :local local :size size}]]]

     [:& right-sidebar {:frame frame
                        :selected (:selected local)
                        :page page
                        :file file}]]))
