;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.constants :refer [right-sidebar-default-width right-sidebar-default-max-width]]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.inspect.left-sidebar :refer [left-sidebar]]
   [app.main.ui.inspect.render :refer [render-frame-svg]]
   [app.main.ui.inspect.right-sidebar :refer [right-sidebar*]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn handle-select-frame
  [event]
  (let [frame-id (-> (dom/get-current-target event)
                     (dom/get-data "value")
                     (d/read-string))
        origin (dom/get-target event)
        over-section? (dom/class? origin "inspect-svg-container")
        layout (dom/get-element "viewer-layout")
        has-force? (dom/class? layout "force-visible")]

    (dom/prevent-default event)
    (dom/stop-propagation event)
    (st/emit! (dv/select-shape frame-id))
    (when over-section?
      (if has-force?
        (dom/remove-class! layout "force-visible")
        (dom/add-class! layout "force-visible")))))

(mf/defc viewport
  [{:keys [local file page frame index viewer-pagination size share-id]}]
  (let [inspect-svg-container-ref (mf/use-ref nil)
        current-section* (mf/use-state :info)
        current-section  (deref current-section*)

        can-be-expanded? (= current-section :code)

        on-mouse-wheel
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
              (events/unlistenByKey key1))))

        {:keys [on-pointer-down on-lost-pointer-capture on-pointer-move]
         set-right-size :set-size
         right-size :size}
        (use-resize-hook :code right-sidebar-default-width right-sidebar-default-width right-sidebar-default-max-width :x true :right)

        handle-change-section
        (mf/use-callback
         (fn [section]
           (reset! current-section* section)))

        handle-expand
        (mf/use-callback
         (mf/deps right-size)
         (fn []
           (set-right-size (if (> right-size right-sidebar-default-width) right-sidebar-default-width right-sidebar-default-max-width))))]

    (mf/use-effect on-mount)

    (mf/use-effect
     (mf/deps (:id frame))
     (fn []
       (st/emit! (dv/select-shape (:id frame)))))

    [:*
     [:& left-sidebar {:frame frame
                       :local local
                       :page page}]
     [:div#inspect-svg-wrapper {:class (stl/css :inspect-svg-wrapper)
                                :data-value (pr-str (:id frame))
                                :on-click handle-select-frame}
      [:& viewer-pagination {:index index :num-frames (count (:frames page)) :left-bar true :right-bar true}]
      [:div#inspect-svg-container {:class (stl/css :inspect-svg-container)
                                   :ref inspect-svg-container-ref}
       [:& render-frame-svg {:frame frame :page page :local local :size size}]]]

     [:div {:class (stl/css-case :sidebar-container true
                                 :not-expand (not can-be-expanded?)
                                 :expanded can-be-expanded?)

            :style #js {"--right-sidebar-width" (when can-be-expanded? (dm/str right-size "px"))}}
      (when can-be-expanded?
        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-pointer-down
               :on-lost-pointer-capture on-lost-pointer-capture
               :on-pointer-move on-pointer-move}])
      [:> right-sidebar* {:frame frame
                          :selected (:selected local)
                          :page page
                          :file file
                          :on-change-section handle-change-section
                          :on-expand handle-expand
                          :share-id share-id}]]]))
