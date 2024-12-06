;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.thumbnails
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.main.data.viewer :as dv]
   [app.main.render :as render]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(mf/defc thumbnails-content
  [{:keys [children expanded? total] :as props}]
  (let [container (mf/use-ref)
        width     (mf/use-var (.. js/document -documentElement -clientWidth))
        element-width (mf/use-var 152)

        offset (mf/use-state 0)

        on-left-arrow-click
        (fn [_]
          (swap! offset (fn [v]
                          (if (pos? v)
                            (dec v)
                            v))))

        on-right-arrow-click
        (fn [_]
          (let [visible (/ @width @element-width)
                max-val (- total visible)]
            (swap! offset (fn [v]
                            (if (< v max-val)
                              (inc v)
                              v)))))

        on-scroll
        (fn [event]
          (let [event (dom/event->native-event event)]
            (if (pos? (.-deltaY ^js event))
              (on-right-arrow-click event)
              (on-left-arrow-click event))))

        on-mount
        (fn []
          (let [dom (mf/ref-val container)]
            (reset! width (obj/get dom "clientWidth"))))]

    (mf/use-effect on-mount)
    (if expanded?
      [:div {:class (stl/css :thumbnails-content)}
       [:div {:class (stl/css :thumbnails-list-expanded)} children]]

      [:div {:class (stl/css :thumbnails-content)}
       [:button {:class (stl/css :left-scroll-handler)
                 :on-click on-left-arrow-click} i/arrow]
       [:button {:class (stl/css :right-scroll-handler)
                 :on-click on-right-arrow-click} i/arrow]

       [:div {:class (stl/css :thumbnails-list)
              :ref container
              :on-wheel on-scroll}
        [:div {:class (stl/css :thumbnails-list-inside)
               :style {:right (str (* @offset 152) "px")}}
         children]]])))

(mf/defc thumbnails-summary
  [{:keys [on-toggle-expand on-close total] :as props}]
  [:div {:class (stl/css :thumbnails-summary)}
   [:span {:class (stl/css :counter)}
    (tr "labels.num-of-frames" (i18n/c total))]
   [:span {:class (stl/css :actions)}
    [:button {:class (stl/css :expand-btn)
              :on-click on-toggle-expand} i/arrow]
    [:button {:class (stl/css :close-btn)
              :on-click on-close} i/close]]])

(mf/defc thumbnail-item
  {::mf/wrap [mf/memo
              #(mf/deferred % ts/idle-then-raf)]}
  [{:keys [selected? frame on-click index objects page-id thumbnail-data]}]

  (let [children-ids (cfh/get-children-ids objects (:id frame))
        children-bounds (gsh/shapes->rect (concat [frame] (->> children-ids (keep (d/getf objects)))))]

    [:button {:class (stl/css :thumbnail-item)
              :on-click #(on-click % index)}
     [:div {:class (stl/css-case :thumbnail-preview true
                                 :selected selected?)}
      [:& render/frame-svg {:frame (-> frame
                                       (assoc :thumbnail (get thumbnail-data (dm/str page-id (:id frame))))
                                       (assoc :children-bounds children-bounds))
                            :objects objects
                            :use-thumbnails true}]]
     [:div {:class (stl/css :thumbnail-info)
            :title (:name frame)}
      (:name frame)]]))

(mf/defc thumbnails-panel
  [{:keys [frames page index show? thumbnail-data] :as props}]
  (let [expanded-state (mf/use-state false)
        expanded? (deref expanded-state)
        container (mf/use-ref)

        objects   (:objects page)
        on-close  #(st/emit! dv/toggle-thumbnails-panel)
        selected  (mf/use-var false)

        on-item-click
        (mf/use-fn
         (mf/deps expanded?)
         (fn [_ index]
           (compare-and-set! selected false true)
           (st/emit! (dv/go-to-frame-by-index index))
           (when expanded?
             (on-close))))

        toggle-expand
        (mf/use-fn
         #(swap! expanded-state not))]
    [:section {:class (stl/css-case :viewer-thumbnails true
                                    :expanded expanded?)
               ;; This is better as an inline-style so it won't make a reflow of every frame inside
               :style {:display (when (not show?) "none")}
               :ref container}

     [:& thumbnails-summary {:on-toggle-expand toggle-expand
                             :on-close on-close
                             :total (count frames)}]
     [:& thumbnails-content {:expanded? expanded?
                             :total (count frames)}
      (for [[i frame] (d/enumerate frames)]
        [:& thumbnail-item {:index i
                            :key (dm/str (:id frame) "-" i)
                            :frame frame
                            :page-id (:id page)
                            :objects objects
                            :on-click on-item-click
                            :selected? (= i index)
                            :thumbnail-data thumbnail-data}])]]))
