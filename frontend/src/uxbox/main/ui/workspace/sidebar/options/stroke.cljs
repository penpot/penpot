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
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.object :as obj]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.common.math :as math]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]))

(def stroke-attrs [:stroke-style :stroke-alignment :stroke-width :stroke-color :stroke-opacity])

(defn- stroke-menu-memo-equals?
  [np op]
  (let [new-ids    (obj/get np "ids")
        old-ids    (obj/get op "ids")
        new-values (obj/get np "values")
        old-values (obj/get op "values")]
    (and (= new-ids old-ids)
         (identical? (:stroke-style new-values)
                     (:stroke-style old-values))
         (identical? (:stroke-alignment new-values)
                     (:stroke-alignment old-values))
         (identical? (:stroke-width new-values)
                     (:stroke-width old-values))
         (identical? (:stroke-color new-values)
                     (:stroke-color old-values))
         (identical? (:stroke-opacity new-values)
                     (:stroke-opacity old-values)))))

(defn width->string [width]
  (if (= width :multiple)
   ""
   (str (-> width
            (d/coalesce 1)
            (math/round)))))

(defn enum->string [value]
  (if (= value :multiple)
    ""
    (pr-str value)))

(mf/defc stroke-menu
  {::mf/wrap [#(mf/memo' % stroke-menu-memo-equals?)]}
  [{:keys [ids values] :as props}]
  (let [locale (i18n/use-locale)
        show-options (not= (:stroke-style values :none) :none)

        current-stroke-color {:value (:stroke-color values)
                              :opacity (:stroke-opacity values)}

        handle-change-stroke-color
        (fn [value opacity]
          (let [change #(cond-> %
                         value (assoc :stroke-color value)
                         opacity (assoc :stroke-opacity opacity))]
            (st/emit! (dwc/update-shapes ids change))))

        on-stroke-style-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (st/emit! (dwc/update-shapes ids #(assoc % :stroke-style value)))))

        on-stroke-alignment-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (when-not (str/empty? value)
              (st/emit! (dwc/update-shapes ids #(assoc % :stroke-alignment value))))))

        on-stroke-width-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (when-not (str/empty? value)
              (st/emit! (dwc/update-shapes ids #(assoc % :stroke-width value))))))

        on-add-stroke
        (fn [event]
          (st/emit! (dwc/update-shapes ids #(assoc %
                                                   :stroke-style :solid
                                                   :stroke-color "#000000"
                                                   :stroke-opacity 1))))

        on-del-stroke
        (fn [event]
          (st/emit! (dwc/update-shapes ids #(assoc % :stroke-style :none))))]

    (if show-options
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
         [:div.input-element
          {:class (classnames :pixels (not= (:stroke-width values) :multiple))}
          [:input.input-text {:type "number"
                              :min "0"
                              :value (-> (:stroke-width values) width->string)
                              :placeholder (t locale "settings.multiple")
                              :on-change on-stroke-width-change}]]

         [:select#style.input-select {:value (enum->string (:stroke-alignment values))
                                      :on-change on-stroke-alignment-change}
          (when (= (:stroke-alignment values) :multiple)
            [:option {:value ""} "--"])
          [:option {:value ":center"} (t locale "workspace.options.stroke.center")]
          [:option {:value ":inner"} (t locale "workspace.options.stroke.inner")]
          [:option {:value ":outer"} (t locale "workspace.options.stroke.outer")]]

         [:select#style.input-select {:value (enum->string (:stroke-style values))
                                      :on-change on-stroke-style-change}
          (when (= (:stroke-style values) :multiple)
            [:option {:value ""} "--"])
          [:option {:value ":solid"} (t locale "workspace.options.stroke.solid")]
          [:option {:value ":dotted"} (t locale "workspace.options.stroke.dotted")]
          [:option {:value ":dashed"} (t locale "workspace.options.stroke.dashed")]
          [:option {:value ":mixed"} (t locale "workspace.options.stroke.mixed")]]]]]

      ;; NO STROKE
      [:div.element-set
       [:div.element-set-title
        [:span (t locale "workspace.options.stroke")]
        [:div.add-page {:on-click on-add-stroke} i/close]]])))

