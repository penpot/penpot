;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.viewer.thumbnails
  (:require
   [goog.events :as events]
   [goog.object :as gobj]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.main.store :as st]
   [uxbox.main.data.viewer :as dv]
   [uxbox.main.ui.components.dropdown :refer [dropdown']]
   [uxbox.main.ui.shapes.frame :as frame]
   [uxbox.main.exports :as exports]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt]
   [uxbox.main.data.viewer :as vd])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defc thumbnails-content
  [{:keys [children expanded? total] :as props}]
  (let [container (mf/use-ref)
        width (mf/use-var (.. js/document -documentElement -clientWidth))
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

(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom] :or {zoom 1} :as props}]
  (let [childs (mapv #(get objects %) (:shapes frame))
        modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))
        frame (assoc frame :displacement-modifier modifier)

        transform (str "scale(" zoom ")")]


    [:svg {:view-box (str "0 0 " (:width frame 0) " " (:height frame 0))
           :width (:width frame)
           :height (:height frame)
           :transform transform
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& exports/frame-shape {:shape frame :childs childs}]]))

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
    [:& frame-svg {:frame frame :objects objects}]]
   [:div.thumbnail-info
    [:span.name (:name frame)]]])

(mf/defc thumbnails-panel
  [{:keys [data index] :as props}]
  (let [expanded? (mf/use-state false)
        container (mf/use-ref)
        page-id   (get-in data [:page :id])

        on-close #(st/emit! dv/toggle-thumbnails-panel)

        on-item-click
        (fn [event index]
          (st/emit! (rt/nav :viewer {:page-id page-id
                                     :index index})))]
    [:& dropdown' {:on-close on-close
                   :container container
                   :show true}
     [:section.viewer-thumbnails {:class (classnames :expanded @expanded?)
                                  :ref container}
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
