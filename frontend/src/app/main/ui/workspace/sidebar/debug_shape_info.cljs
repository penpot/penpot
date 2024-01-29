;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.debug-shape-info
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [debug :as dbg]
   [rumext.v2 :as mf]))

(def remove-attrs
  #{:id :name})

(def vertical-layout-attrs
  #{})

(def custom-renderer
  {:parent-id :shape-link
   :frame-id :shape-link
   :shapes :shape-list
   :shape-ref :shape-link
   :transform :matrix-render
   :transform-inverse :matrix-render
   :selrect :rect-render
   :points :points-render})

(mf/defc shape-link
  [{:keys [id objects]}]
  [:a {:class (stl/css :shape-link)
       :on-click #(st/emit! (dw/select-shape id))}
   (dm/str (dm/get-in objects [id :name]) " #" id)])

(mf/defc debug-shape-attr
  [{:keys [attr value objects]}]

  (case (get custom-renderer attr)
    :shape-link
    [:& shape-link {:id value :objects objects}]

    :shape-list
    [:div {:class (stl/css :shape-list)}
     (for [id value]
       [:& shape-link {:id id :objects objects}])]

    :matrix-render
    [:div (dm/str (gmt/format-precision value 2))]

    :rect-render
    [:div (dm/fmt "X:% Y:% W:% H:%" (:x value) (:y value) (:width value) (:height value))]

    :points-render
    [:div {:class (stl/css :point-list)}
     (for [point value]
       [:div (dm/fmt "(%, %)" (:x point) (:y point))])]

    [:div {:class (stl/css :attrs-container-value)} (str value)]))

(mf/defc debug-shape-info
  []
  (let [objects (mf/deref refs/workspace-page-objects)
        selected (->> (mf/deref refs/selected-shapes)
                      (map (d/getf objects)))]

    [:div {:class (stl/css :shape-info)}
     [:div {:class (stl/css :shape-info-title)}
      [:span "Debug"]
      [:div {:class (stl/css :close-button)
             :on-click #(dbg/disable! :shape-panel)}
       i/close-refactor]]

     (if (empty? selected)
       [:div {:class (stl/css :attrs-container)} "No shapes selected"]
       (for [[idx current] (d/enumerate selected)]
         [:div {:class (stl/css :attrs-container) :key (dm/str "shape" idx)}
          [:div {:class (stl/css :shape-title)}
           [:div {:class (stl/css :shape-name)} (:name current)]
           [:button {:on-click #(debug/dump-object (dm/str (:id current)))} "object"]
           [:button {:on-click #(debug/dump-subtree (dm/str (:id current)) true)} "tree"]]

          [:div {:class (stl/css :shape-attrs)}
           (let [attrs (->> (keys current)
                            (remove remove-attrs))
                 attrs (concat [:frame-id :parent-id :shapes]
                               (->> attrs (remove #{:frame-id :parent-id :shapes})))]
             (for [attr attrs]
               (when-let [value (get current attr)]
                 [:div {:class (stl/css-case :attrs-container-attr true
                                             :vertical-layout (contains? vertical-layout-attrs attr))
                        :key (dm/str "att-" idx "-" attr)}
                  [:div {:class (stl/css :attrs-container-name)} (d/name attr)]

                  [:& debug-shape-attr {:attr attr :value value :objects objects}]])))]]))]))
