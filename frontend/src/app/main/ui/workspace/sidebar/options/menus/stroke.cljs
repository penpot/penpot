;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.stroke
  (:require
   [app.common.data :as d]
   [app.common.math :as math]
   [app.common.pages.spec :as spec]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def stroke-attrs
  [:stroke-style
   :stroke-alignment
   :stroke-width
   :stroke-color
   :stroke-color-ref-id
   :stroke-color-ref-file
   :stroke-opacity
   :stroke-color-gradient
   :stroke-cap-start
   :stroke-cap-end])

(defn- width->string [width]
  (if (= width :multiple)
   ""
   (str (-> width
            (d/coalesce 1)
            (math/round)))))

(defn- enum->string [value]
  (if (= value :multiple)
    ""
    (pr-str value)))

(mf/defc stroke-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "show-caps"]))]}
  [{:keys [ids type values show-caps] :as props}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-stroke")
                :group (tr "workspace.options.group-stroke")
                (tr "workspace.options.stroke"))

        show-options (not= (:stroke-style values :none) :none)

        show-caps (and show-caps (= (:stroke-alignment values) :center))

        current-stroke-color {:color (:stroke-color values)
                              :opacity (:stroke-opacity values)
                              :id (:stroke-color-ref-id values)
                              :file-id (:stroke-color-ref-file values)
                              :gradient (:stroke-color-gradient values)}

        handle-change-stroke-color
        (mf/use-callback
          (mf/deps ids)
          (fn [color]
            (let [remove-multiple (fn [[_ value]] (not= value :multiple))
                  color (into {} (filter remove-multiple) color)]
              (st/emit! (dc/change-stroke ids color)))))

        handle-detach
        (mf/use-callback
          (mf/deps ids)
          (fn []
            (st/emit! (dc/change-stroke ids (dissoc current-stroke-color :id :file-id)))))

        on-stroke-style-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (st/emit! (dch/update-shapes ids #(assoc % :stroke-style value)))))

        on-stroke-alignment-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (when-not (str/empty? value)
              (st/emit! (dch/update-shapes ids #(assoc % :stroke-alignment value))))))

        on-stroke-width-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (when-not (str/empty? value)
              (st/emit! (dch/update-shapes ids #(assoc % :stroke-width value))))))

        update-cap-attr
        (fn [& kvs]
          #(if (spec/has-caps? %)
             (apply (partial assoc %) kvs)
             %))

        on-stroke-cap-start-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (when-not (str/empty? value)
              (st/emit! (dch/update-shapes ids (update-cap-attr :stroke-cap-start value))))))

        on-stroke-cap-end-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (when-not (str/empty? value)
              (st/emit! (dch/update-shapes ids (update-cap-attr :stroke-cap-end value))))))

        on-stroke-cap-switch
        (fn [_]
          (let [stroke-cap-start (:stroke-cap-start values)
                stroke-cap-end   (:stroke-cap-end values)]
            (when (and (not= stroke-cap-start :multiple)
                       (not= stroke-cap-end :multiple))
              (st/emit! (dch/update-shapes ids (update-cap-attr
                                                 :stroke-cap-start stroke-cap-end
                                                 :stroke-cap-end stroke-cap-start))))))

        on-add-stroke
        (fn [_]
          (st/emit! (dch/update-shapes ids #(assoc %
                                                   :stroke-style :solid
                                                   :stroke-color "#000000"
                                                   :stroke-opacity 1
                                                   :stroke-width 1))))

        on-del-stroke
        (fn [_]
          (st/emit! (dch/update-shapes ids #(assoc % :stroke-style :none))))

        on-open-picker
        (mf/use-callback
         (mf/deps ids)
         (fn [_value _opacity _id _file-id]
           (st/emit! (dwu/start-undo-transaction))))

        on-close-picker
        (mf/use-callback
         (mf/deps ids)
         (fn [_value _opacity _id _file-id]
           (st/emit! (dwu/commit-undo-transaction))))]

    (if show-options
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-del-stroke} i/minus]]

       [:div.element-set-content
        ;; Stroke Color
        [:& color-row {:color current-stroke-color
                       :on-change handle-change-stroke-color
                       :on-detach handle-detach
                       :on-open on-open-picker
                       :on-close on-close-picker}]

        ;; Stroke Width, Alignment & Style
        [:div.row-flex
         [:div.input-element
          {:class (dom/classnames :pixels (not= (:stroke-width values) :multiple))}
          [:input.input-text {:type "number"
                              :min "0"
                              :value (-> (:stroke-width values) width->string)
                              :placeholder (tr "settings.multiple")
                              :on-change on-stroke-width-change}]]

         [:select#style.input-select {:value (enum->string (:stroke-alignment values))
                                      :on-change on-stroke-alignment-change}
          (when (= (:stroke-alignment values) :multiple)
            [:option {:value ""} "--"])
          [:option {:value ":center"} (tr "workspace.options.stroke.center")]
          [:option {:value ":inner"} (tr "workspace.options.stroke.inner")]
          [:option {:value ":outer"} (tr "workspace.options.stroke.outer")]]

         [:select#style.input-select {:value (enum->string (:stroke-style values))
                                      :on-change on-stroke-style-change}
          (when (= (:stroke-style values) :multiple)
            [:option {:value ""} "--"])
          [:option {:value ":solid"} (tr "workspace.options.stroke.solid")]
          [:option {:value ":dotted"} (tr "workspace.options.stroke.dotted")]
          [:option {:value ":dashed"} (tr "workspace.options.stroke.dashed")]
          [:option {:value ":mixed"} (tr "workspace.options.stroke.mixed")]]]

        ;; Stroke Caps
        (when show-caps
          [:div.row-flex
           [:select#style.input-select {:value (enum->string (:stroke-cap-start values))
                                        :on-change on-stroke-cap-start-change}
            (when (= (:stroke-cap-start values) :multiple)
              [:option {:value ""} "--"])
            [:option {:value ""} (tr "workspace.options.stroke-cap.none")]
            [:option {:value ":line-arrow"} (tr "workspace.options.stroke-cap.line-arrow")]
            [:option {:value ":triangle-arrow"} (tr "workspace.options.stroke-cap.triangle-arrow")]
            [:option {:value ":square-marker"} (tr "workspace.options.stroke-cap.square-marker")]
            [:option {:value ":circle-marker"} (tr "workspace.options.stroke-cap.circle-marker")]
            [:option {:value ":diamond-marker"} (tr "workspace.options.stroke-cap.diamond-marker")]
            [:option {:value ":round"} (tr "workspace.options.stroke-cap.round")]
            [:option {:value ":square"} (tr "workspace.options.stroke-cap.square")]]

           [:div.element-set-actions-button {:on-click on-stroke-cap-switch}
            i/switch]

           [:select#style.input-select {:value (enum->string (:stroke-cap-end values))
                                        :on-change on-stroke-cap-end-change}
            (when (= (:stroke-cap-end values) :multiple)
              [:option {:value ""} "--"])
            [:option {:value ""} (tr "workspace.options.stroke-cap.none")]
            [:option {:value ":line-arrow"} (tr "workspace.options.stroke-cap.line-arrow")]
            [:option {:value ":triangle-arrow"} (tr "workspace.options.stroke-cap.triangle-arrow")]
            [:option {:value ":square-marker"} (tr "workspace.options.stroke-cap.square-marker")]
            [:option {:value ":circle-marker"} (tr "workspace.options.stroke-cap.circle-marker")]
            [:option {:value ":diamond-marker"} (tr "workspace.options.stroke-cap.diamond-marker")]
            [:option {:value ":round"} (tr "workspace.options.stroke-cap.round")]
            [:option {:value ":square"} (tr "workspace.options.stroke-cap.square")]]])]]

      ;; NO STROKE
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-add-stroke} i/close]]])))

