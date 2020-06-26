;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.stroke
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.dom :as dom]
   [uxbox.util.object :as obj]
   [uxbox.util.i18n :as i18n :refer  [tr t]]
   [uxbox.common.math :as math]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]))

(defn- stroke-menu-memo-equals?
  [np op]
  (let [new-shape (obj/get np "shape")
        old-shape (obj/get op "shape")]
    (and (= (:id new-shape)
            (:id old-shape))
         (identical? (:stroke-style new-shape)
                     (:stroke-style old-shape))
         (identical? (:stroke-alignment new-shape)
                     (:stroke-alignment old-shape))
         (identical? (:stroke-width new-shape)
                     (:stroke-width old-shape))
         (identical? (:stroke-color new-shape)
                     (:stroke-color old-shape))
         (identical? (:stroke-opacity new-shape)
                     (:stroke-opacity old-shape)))))

(mf/defc stroke-menu
  {::mf/wrap [#(mf/memo' % stroke-menu-memo-equals?)]}
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)
        show-options (not= (:stroke-style shape) :none)

        on-stroke-style-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-style value}))))

        on-stroke-alignment-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-alignment value}))))

        on-stroke-width-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-width value}))))

        on-add-stroke
        (fn [event]
          (st/emit! (udw/update-shape (:id shape) {:stroke-style :solid
                                                   :stroke-color "#000000"
                                                   :stroke-opacity 1})))

        on-del-stroke
        (fn [event]
          (st/emit! (udw/update-shape (:id shape) {:stroke-style :none})))

        current-stroke-color {:value (:stroke-color shape)
                              :opacity (:stroke-opacity shape)}

        handle-change-stroke-color
        (fn [value opacity]
          (st/emit! (udw/update-shape (:id shape) {:stroke-color value
                                                   :stroke-opacity opacity})))]

    (if (not= :none (:stroke-style shape :none))
      [:div.element-set
       [:div.element-set-title
        [:span (t locale "workspace.options.stroke")]
        [:div.add-page {:on-click on-del-stroke} i/minus]]

       [:div.element-set-content
        ;; Stroke Color
        [:& color-row {:color current-stroke-color
                       :on-change handle-change-stroke-color}]

        ;; Stroke Width, Alignment & Style
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text {:type "number"
                              :min "0"
                              :value (str (-> (:stroke-width shape)
                                              (d/coalesce 1)
                                              (math/round)))
                              :on-change on-stroke-width-change}]]

         [:select#style.input-select {:value (pr-str (:stroke-alignment shape))
                                      :on-change on-stroke-alignment-change}
          [:option {:value ":center"} (t locale "workspace.options.stroke.center")]
          [:option {:value ":inner"} (t locale "workspace.options.stroke.inner")]
          [:option {:value ":outer"} (t locale "workspace.options.stroke.outer")]]

         [:select#style.input-select {:value (pr-str (:stroke-style shape))
                                      :on-change on-stroke-style-change}
          [:option {:value ":solid"} (t locale "workspace.options.stroke.solid")]
          [:option {:value ":dotted"} (t locale "workspace.options.stroke.dotted")]
          [:option {:value ":dashed"} (t locale "workspace.options.stroke.dashed")]
          [:option {:value ":mixed"} (t locale "workspace.options.stroke.mixed")]]]]]

      ;; NO STROKE
      [:div.element-set
       [:div.element-set-title
        [:span (t locale "workspace.options.stroke")]
        [:div.add-page {:on-click on-add-stroke} i/close]]])))
