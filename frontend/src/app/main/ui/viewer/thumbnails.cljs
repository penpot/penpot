;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.thumbnails
  (:require
   [goog.events :as events]
   [goog.object :as gobj]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.common.data :as d]
   [app.main.store :as st]
   [app.main.data.viewer :as dv]
   [app.main.ui.components.dropdown :refer [dropdown']]
   [app.main.ui.shapes.frame :as frame]
   [app.main.exports :as exports]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.common.math :as mth]
   [app.util.router :as rt]
   [app.main.data.viewer :as vd])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defc thumbnails-content
  [{:keys [children expanded? total] :as props}]
  (let [container (mf/use-ref)
        width     (mf/use-var (.. js/document -documentElement -clientWidth))
        element-width (mf/use-var 152)

        offset (mf/use-state 0)

        on-left-arrow-click
        (fn [event]
          (swap! offset (fn [v]
                          (if (pos? v)
                            (dec v)
                            v))))

        on-right-arrow-click
        (fn [event]
          (let [visible (/ @width @element-width)
                max-val (- total visible)]
            (swap! offset (fn [v]
                            (if (< v max-val)
                              (inc v)
                              v)))))

        on-scroll
        (fn [event]
          (if (pos? (.. event -nativeEvent -deltaY))
            (on-right-arrow-click event)
            (on-left-arrow-click event)))

        on-mount
        (fn []
          (let [dom (mf/ref-val container)]
            (reset! width (gobj/get dom "clientWidth"))))]

    (mf/use-effect on-mount)
    (if expanded?
      [:div.thumbnails-content
       [:div.thumbnails-list-expanded children]]
      [:div.thumbnails-content
       [:div.left-scroll-handler {:on-click on-left-arrow-click} i/arrow-slide]
       [:div.right-scroll-handler {:on-click on-right-arrow-click} i/arrow-slide]
       [:div.thumbnails-list {:ref container :on-wheel on-scroll}
        [:div.thumbnails-list-inside {:style {:right (str (* @offset 152) "px")}}
         children]]])))

(mf/defc thumbnails-summary
  [{:keys [on-toggle-expand on-close total] :as props}]
  [:div.thumbnails-summary
   [:span.counter (str total " frames")]
   [:span.buttons
    [:span.btn-expand {:on-click on-toggle-expand} i/arrow-down]
    [:span.btn-close {:on-click on-close} i/close]]])

(mf/defc thumbnail-item
  [{:keys [selected? frame on-click index objects] :as props}]
  [:div.thumbnail-item {:on-click #(on-click % index)}
   [:div.thumbnail-preview
    {:class (classnames :selected selected?)}
    [:& exports/frame-svg {:frame frame :objects objects}]]
   [:div.thumbnail-info
    [:span.name (:name frame)]]])

(mf/defc thumbnails-panel
  [{:keys [data index] :as props}]
  (let [expanded? (mf/use-state false)
        container (mf/use-ref)
        page-id   (get-in data [:page :id])

        on-close #(st/emit! dv/toggle-thumbnails-panel)
        selected (mf/use-var false)

        on-mouse-leave
        (fn [event]
          (when @selected
            (on-close)))

        on-item-click
        (fn [event index]
          (compare-and-set! selected false true)
          (st/emit! (rt/nav :viewer {:page-id page-id} {:index index}))
          (when @expanded?
            (on-close)))]
    [:& dropdown' {:on-close on-close
                   :container container
                   :show true}
     [:section.viewer-thumbnails
      {:class (classnames :expanded @expanded?)
       :ref container
       :on-mouse-leave on-mouse-leave}

      [:& thumbnails-summary {:on-toggle-expand #(swap! expanded? not)
                              :on-close on-close
                              :total (count (:frames data))}]
      [:& thumbnails-content {:expanded? @expanded?
                              :total (count (:frames data))}
       (for [[i frame] (d/enumerate (:frames data))]
         [:& thumbnail-item {:key i
                             :index i
                             :frame frame
                             :objects (:objects data)
                             :on-click on-item-click
                             :selected? (= i index)}])]]]))
