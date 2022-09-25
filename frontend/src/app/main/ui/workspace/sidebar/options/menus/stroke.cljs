;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.stroke
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.colors :as dc]
   [app.main.store :as st]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.stroke-row :refer [stroke-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def stroke-attrs
  [:strokes
   :stroke-style
   :stroke-alignment
   :stroke-width
   :stroke-color
   :stroke-color-ref-id
   :stroke-color-ref-file
   :stroke-opacity
   :stroke-color-gradient
   :stroke-cap-start
   :stroke-cap-end])

(mf/defc stroke-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "show-caps"]))]}
  [{:keys [ids type values show-caps disable-stroke-style] :as props}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-stroke")
                :group (tr "workspace.options.group-stroke")
                (tr "workspace.options.stroke"))

        handle-change-stroke-color
        (mf/use-callback
         (mf/deps ids)
         (fn [index]
           (fn [color]
             (st/emit! (dc/change-stroke ids color index)))))

        handle-remove
        (mf/use-callback
         (mf/deps ids)
         (fn [index]
           (fn []
             (st/emit! (dc/remove-stroke ids index)))))

        handle-remove-remove-all
        (fn [_]
          (st/emit! (dc/remove-all-strokes ids)))

        handle-detach
        (mf/use-callback
         (mf/deps ids)
         (fn [index]
           (fn [color]
             (let [color (-> color
                             (assoc :id nil :file-id nil))]
               (st/emit! (dc/change-stroke ids color index))))))

        handle-reorder
        (mf/use-callback
         (mf/deps ids)
         (fn [new-index]
           (fn [index]
             (st/emit! (dc/reorder-strokes ids index new-index)))))

        on-stroke-style-change
        (fn [index]
          (fn [event]
            (let [value (-> (dom/get-target event)
                            (dom/get-value)
                            (d/read-string))]
              (st/emit! (dc/change-stroke ids {:stroke-style value} index)))))

        on-stroke-alignment-change
        (fn [index]
          (fn [event]
            (let [value (-> (dom/get-target event)
                            (dom/get-value)
                            (d/read-string))]
              (when-not (str/empty? value)
                (st/emit! (dc/change-stroke ids {:stroke-alignment value} index))))))

        on-stroke-width-change
        (fn [index]
          (fn [value]
            (when-not (str/empty? value)
              (st/emit! (dc/change-stroke ids {:stroke-width value} index)))))

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
        (fn [index value]
          (st/emit! (dc/change-stroke ids {:stroke-cap-start value} index)))

        on-stroke-cap-end-change
        (fn [index value]
          (st/emit! (dc/change-stroke ids {:stroke-cap-end value} index)))

        on-stroke-cap-switch
        (fn [index]
          (let [stroke-cap-start (get-in values [:strokes index :stroke-cap-start])
                stroke-cap-end   (get-in values [:strokes index :stroke-cap-end])]
            (when (and (not= stroke-cap-start :multiple)
                       (not= stroke-cap-end :multiple))
              (st/emit! (dc/change-stroke ids {:stroke-cap-start stroke-cap-end
                                               :stroke-cap-end stroke-cap-start} index)))))
        on-add-stroke
        (fn [_]
          (st/emit! (dc/add-stroke ids {:stroke-style :solid
                                        :stroke-color clr/black
                                        :stroke-opacity 1
                                        :stroke-width 1})))

        disable-drag    (mf/use-state false)

        select-all (fn [event]
                     (when (not @disable-drag)
                       (dom/select-text! (dom/get-target event)))
                     (reset! disable-drag true))

        on-blur (fn [_]
                  (reset! disable-drag false))]

    [:div.element-set
     [:div.element-set-title
      [:span label]
      [:div.add-page {:on-click on-add-stroke} i/close]]

     [:div.element-set-content
      (cond
        (= :multiple (:strokes values))
        [:div.element-set-options-group
         [:div.element-set-label (tr "settings.multiple")]
         [:div.element-set-actions
          [:div.element-set-actions-button {:on-click handle-remove-remove-all}
           i/minus]]]


        (seq (:strokes values))
        [:& h/sortable-container {}
         (for [[index value] (d/enumerate (:strokes values []))]
           [:& stroke-row {:key (dm/str "stroke-" index)
                           :stroke value
                           :title (tr "workspace.options.stroke-color")
                           :index index
                           :show-caps show-caps
                           :on-color-change handle-change-stroke-color
                           :on-color-detach handle-detach
                           :on-stroke-width-change on-stroke-width-change
                           :on-stroke-style-change on-stroke-style-change
                           :on-stroke-alignment-change on-stroke-alignment-change
                           :open-caps-select open-caps-select
                           :close-caps-select close-caps-select
                           :on-stroke-cap-start-change on-stroke-cap-start-change
                           :on-stroke-cap-end-change on-stroke-cap-end-change
                           :on-stroke-cap-switch on-stroke-cap-switch
                           :on-remove handle-remove
                           :on-reorder (handle-reorder index)
                           :disable-drag disable-drag
                           :select-all select-all
                           :on-blur on-blur
                           :disable-stroke-style disable-stroke-style}])])]]))
