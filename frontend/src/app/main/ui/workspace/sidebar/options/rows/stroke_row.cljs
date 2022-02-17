;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.rows.stroke-row
  (:require
   [app.common.data :as d]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(defn- width->string [width]
  (if (= width :multiple)
   ""
   (str (or width 1))))

(defn- enum->string [value]
  (if (= value :multiple)
    ""
    (pr-str value)))

(defn- stroke-cap-names []
  [[nil             (tr "workspace.options.stroke-cap.none")           false]
   [:line-arrow     (tr "workspace.options.stroke-cap.line-arrow")     true]
   [:triangle-arrow (tr "workspace.options.stroke-cap.triangle-arrow") false]
   [:square-marker  (tr "workspace.options.stroke-cap.square-marker")  false]
   [:circle-marker  (tr "workspace.options.stroke-cap.circle-marker")  false]
   [:diamond-marker (tr "workspace.options.stroke-cap.diamond-marker") false]
   [:round          (tr "workspace.options.stroke-cap.round")          true]
   [:square         (tr "workspace.options.stroke-cap.square")         false]])

(defn- value->img [value]
  (when (and value (not= value :multiple))
    (str "images/cap-" (name value) ".svg")))

(defn- value->name [value]
  (if (= value :multiple)
    "--"
    (-> (d/seek #(= (first %) value) (stroke-cap-names))
        (second))))

(mf/defc stroke-row
  [{:keys [index stroke title show-caps on-color-change on-reorder on-color-detach on-remove on-stroke-width-change on-stroke-style-change on-stroke-alignment-change open-caps-select close-caps-select on-stroke-cap-start-change on-stroke-cap-end-change on-stroke-cap-switch disable-drag select-all on-blur]}]
  (let [start-caps-state (mf/use-state {:open? false
                                        :top 0
                                        :left 0})
        end-caps-state   (mf/use-state {:open? false
                                        :top 0
                                        :left 0})
        on-drop
        (fn [_ data]
          (on-reorder (:index data)))

        [dprops dref] (if (some? on-reorder)
                        (h/use-sortable
                         :data-type "penpot/stroke-row"
                         :on-drop on-drop
                         :disabled @disable-drag
                         :detect-center? false
                         :data {:id (str "stroke-row-" index)
                                :index index
                                :name (str "Border row" index)})
                        [nil nil])]

    [:div.border-data {:class (dom/classnames
                   :dnd-over-top (= (:over dprops) :top)
                   :dnd-over-bot (= (:over dprops) :bot))
           :ref dref}
     ;; Stroke Color
     [:& color-row {:color {:color (:stroke-color stroke)
                            :opacity (:stroke-opacity stroke)
                            :id (:stroke-color-ref-id stroke)
                            :file-id (:stroke-color-ref-file stroke)
                            :gradient (:stroke-color-gradient stroke)}
                    :index index
                    :title title
                    :on-change (on-color-change index)
                    :on-detach (on-color-detach index)
                    :on-remove (on-remove index)
                    :disable-drag disable-drag
                    :select-all select-all
                    :on-blur on-blur}]

     ;; Stroke Width, Alignment & Style
     [:div.row-flex
      [:div.input-element
       {:class (dom/classnames :pixels (not= (:stroke-width stroke) :multiple))
        :title (tr "workspace.options.stroke-width")}

       [:> numeric-input
        {:min 0
         :value (-> (:stroke-width stroke) width->string)
         :precision 2
         :placeholder (tr "settings.multiple")
         :on-change (on-stroke-width-change index)
         :on-click select-all
         :on-blur on-blur}]]

      [:select#style.input-select {:value (enum->string (:stroke-alignment stroke))
                                   :on-change (on-stroke-alignment-change index)}
       (when (= (:stroke-alignment stroke) :multiple)
         [:option {:value ""} "--"])
       [:option {:value ":center"} (tr "workspace.options.stroke.center")]
       [:option {:value ":inner"} (tr "workspace.options.stroke.inner")]
       [:option {:value ":outer"} (tr "workspace.options.stroke.outer")]]

      [:select#style.input-select {:value (enum->string (:stroke-style stroke))
                                   :on-change (on-stroke-style-change index)}
       (when (= (:stroke-style stroke) :multiple)
         [:option {:value ""} "--"])
       [:option {:value ":solid"} (tr "workspace.options.stroke.solid")]
       [:option {:value ":dotted"} (tr "workspace.options.stroke.dotted")]
       [:option {:value ":dashed"} (tr "workspace.options.stroke.dashed")]
       [:option {:value ":mixed"} (tr "workspace.options.stroke.mixed")]]]

     ;; Stroke Caps
     (when show-caps
       [:div.row-flex
        [:div.cap-select {:tab-index 0 ;; tab-index to make the element focusable
                          :on-click (open-caps-select start-caps-state)}
         (value->name (:stroke-cap-start stroke))
         [:span.cap-select-button
          i/arrow-down]]
        [:& dropdown {:show (:open? @start-caps-state)
                      :on-close (close-caps-select start-caps-state)}
         [:ul.dropdown.cap-select-dropdown {:style {:top  (:top @start-caps-state)
                                                    :left (:left @start-caps-state)}}
          (for [[value label separator] (stroke-cap-names)]
            (let [img (value->img value)]
              [:li {:class (dom/classnames :separator separator)
                    :on-click #(on-stroke-cap-start-change index value)}
               (when img [:img {:src (value->img value)}])
               label]))]]

        [:div.element-set-actions-button {:on-click #(on-stroke-cap-switch index)}
         i/switch]

        [:div.cap-select {:tab-index 0
                          :on-click (open-caps-select end-caps-state)}
         (value->name (:stroke-cap-end stroke))
         [:span.cap-select-button
          i/arrow-down]]
        [:& dropdown {:show (:open? @end-caps-state)
                      :on-close (close-caps-select end-caps-state)}
         [:ul.dropdown.cap-select-dropdown {:style {:top  (:top @end-caps-state)
                                                    :left (:left @end-caps-state)}}
          (for [[value label separator] (stroke-cap-names)]
            (let [img (value->img value)]
              [:li {:class (dom/classnames :separator separator)
                    :on-click #(on-stroke-cap-end-change index value)}
               (when img [:img {:src (value->img value)}])
               label]))]]])]))
