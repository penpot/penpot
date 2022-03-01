;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.stroke
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.colors :as dc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
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

(defn- value->name [value]
  (if (= value :multiple)
    "--"
    (-> (d/seek #(= (first %) value) (stroke-cap-names))
        (second))))

(defn- value->img [value]
  (when (and value (not= value :multiple))
    (str "images/cap-" (name value) ".svg")))

(mf/defc stroke-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "show-caps"]))]}
  [{:keys [ids type values show-caps] :as props}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-stroke")
                :group (tr "workspace.options.group-stroke")
                (tr "workspace.options.stroke"))

        show-options (not= (or (:stroke-style values) :none) :none)
        show-caps    (and show-caps
                          (not (#{:inner :outer} (:stroke-alignment values))))

        start-caps-state (mf/use-state {:open? false
                                        :top 0
                                        :left 0})
        end-caps-state   (mf/use-state {:open? false
                                        :top 0
                                        :left 0})

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
            (let [remove-multiple (fn [[_ value]] (not= value :multiple))
                  current-stroke-color (-> (into {} (filter remove-multiple) current-stroke-color)
                                           (assoc :id nil :file-id nil))]
              (st/emit! (dc/change-stroke ids current-stroke-color)))))

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
        (fn [value]
          (when-not (str/empty? value)
            (st/emit! (dch/update-shapes ids #(assoc % :stroke-width value)))))

        update-cap-attr
        (fn [& kvs]
          #(if (= :path (:type %))
             (apply (partial assoc %) kvs)
             %))

        open-caps-select
        (fn [caps-state]
          (fn [event]
            (let [window-size (dom/get-window-size)

                  target (dom/get-current-target event)
                  rect   (dom/get-bounding-rect target)

                  top (if (< (+ (:bottom rect) 320) (:height window-size))
                        (+ (:bottom rect) 5)
                        (- (:height window-size) 325))

                  left (if (< (+ (:left rect) 200) (:width window-size))
                         (:left rect)
                         (- (:width window-size) 205))]
              (swap! caps-state assoc :open? true
                                      :left left
                                      :top top))))

        close-caps-select
        (fn [caps-state]
          (fn [_]
            (swap! caps-state assoc :open? false)))

        on-stroke-cap-start-change
        (fn [value]
          (st/emit! (dch/update-shapes ids (update-cap-attr :stroke-cap-start value))))

        on-stroke-cap-end-change
        (fn [value]
          (st/emit! (dch/update-shapes ids (update-cap-attr :stroke-cap-end value))))

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
                                                   :stroke-color clr/black
                                                   :stroke-opacity 1
                                                   :stroke-width 1))))

        on-del-stroke
        (fn [_]
          (st/emit! (dch/update-shapes ids #(assoc % :stroke-style :none))))]

    (if show-options
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-del-stroke} i/minus]]

       [:div.element-set-content
        ;; Stroke Color
        [:& color-row {:color current-stroke-color
                       :title (tr "workspace.options.stroke-color")
                       :on-change handle-change-stroke-color
                       :on-detach handle-detach}]

        ;; Stroke Width, Alignment & Style
        [:div.row-flex
         [:div.input-element
          {:class (dom/classnames :pixels (not= (:stroke-width values) :multiple))
           :title (tr "workspace.options.stroke-width")}

          [:> numeric-input
           {:min 0
            :value (-> (:stroke-width values) width->string)
            :precision 2
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
           [:div.cap-select {:tab-index 0 ;; tab-index to make the element focusable
                             :on-click (open-caps-select start-caps-state)}
            (value->name (:stroke-cap-start values))
            [:span.cap-select-button
             i/arrow-down]]
           [:& dropdown {:show (:open? @start-caps-state)
                         :on-close (close-caps-select start-caps-state)}
            [:ul.dropdown.cap-select-dropdown {:style {:top  (:top @start-caps-state)
                                                       :left (:left @start-caps-state)}}
             (for [[value label separator] (stroke-cap-names)]
               (let [img (value->img value)]
                 [:li {:class (dom/classnames :separator separator)
                       :on-click #(on-stroke-cap-start-change value)}
                  (when img [:img {:src (value->img value)}])
                  label]))]]

           [:div.element-set-actions-button {:on-click on-stroke-cap-switch}
            i/switch]

           [:div.cap-select {:tab-index 0
                             :on-click (open-caps-select end-caps-state)}
            (value->name (:stroke-cap-end values))
            [:span.cap-select-button
             i/arrow-down]]
           [:& dropdown {:show (:open? @end-caps-state)
                         :on-close (close-caps-select end-caps-state)}
            [:ul.dropdown.cap-select-dropdown {:style {:top  (:top @end-caps-state)
                                                       :left (:left @end-caps-state)}}
             (for [[value label separator] (stroke-cap-names)]
               (let [img (value->img value)]
                 [:li {:class (dom/classnames :separator separator)
                       :on-click #(on-stroke-cap-end-change value)}
                  (when img [:img {:src (value->img value)}])
                  label]))]]])]]

      ;; NO STROKE
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-add-stroke} i/close]]])))

