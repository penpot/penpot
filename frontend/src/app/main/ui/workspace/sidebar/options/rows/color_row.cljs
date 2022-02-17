;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.rows.color-row
  (:require
   [app.common.data :as d]
   [app.common.math :as math]
   [app.common.pages :as cp]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.color-input :refer [color-input]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(defn color-picker-callback
  [color disable-gradient disable-opacity handle-change-color handle-open handle-close]
  (fn [event]
    (let [color
          (cond
            (uc/multiple? color)
            {:color cp/default-color
             :opacity 1}

            (= :multiple (:opacity color))
            (assoc color :opacity 1)

            :else
            color)

          x (.-clientX event)
          y (.-clientY event)
          props {:x x
                 :y y
                 :disable-gradient disable-gradient
                 :disable-opacity disable-opacity
                 :on-change handle-change-color
                 :on-close handle-close
                 :data color}]
      (handle-open)
      (modal/show! :colorpicker props))))

(defn opacity->string [opacity]
  (if (= opacity :multiple)
    ""
    (str (-> opacity
             (d/coalesce 1)
             (* 100)
             (math/round)))))

(defn remove-multiple [v]
  (if (= v :multiple) nil v))

(mf/defc color-row
  [{:keys [index color disable-gradient disable-opacity on-change on-reorder on-detach on-open on-close title on-remove disable-drag select-all on-blur]}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)
        file-colors     (mf/deref refs/workspace-file-colors)
        shared-libs     (mf/deref refs/workspace-libraries)
        hover-detach    (mf/use-state false)

        get-color-name (fn [{:keys [id file-id]}]
                         (let [src-colors (if (= file-id current-file-id)
                                            file-colors
                                            (get-in shared-libs [file-id :data :colors]))]
                           (get-in src-colors [id :name])))

        parse-color (fn [color]
                      (-> color
                          (update :color #(or % (:value color)))))

        detach-value (fn []
                       (when on-detach (on-detach color)))

        change-value (fn [new-value]
                       (when on-change (on-change (-> color
                                                      (assoc :color new-value)
                                                      (dissoc :gradient)))))

        change-opacity (fn [new-opacity]
                         (when on-change (on-change (assoc color
                                                           :opacity new-opacity
                                                           :id nil
                                                           :file-id nil))))

        handle-pick-color (fn [color]
                            (when on-change (on-change (merge uc/empty-color color))))

        handle-open (fn [] (when on-open (on-open)))

        handle-close (fn [value opacity id file-id]
                       (when on-close (on-close value opacity id file-id)))

        handle-value-change (fn [new-value]
                              (-> new-value
                                  change-value))

        handle-opacity-change (fn [value]
                                (change-opacity (/ value 100)))

        handle-click-color (mf/use-callback
                            (mf/deps color)
                            (color-picker-callback color
                                                   disable-gradient
                                                   disable-opacity
                                                   handle-pick-color
                                                   handle-open
                                                   handle-close))

        prev-color (h/use-previous color)

        on-drop
        (fn [_ data]
          (on-reorder (:index data)))

        [dprops dref] (if (some? on-reorder)
                        (h/use-sortable
                         :data-type "penpot/color-row"
                         :on-drop on-drop
                         :disabled @disable-drag
                         :detect-center? false
                         :data {:id (str "color-row-" index)
                                :index index
                                :name (str "Color row" index)})
                        [nil nil])]

    (mf/use-effect
     (mf/deps color prev-color)
     (fn []
       (when (not= prev-color color)
         (modal/update-props! :colorpicker {:data (parse-color color)}))))

    [:div.row-flex.color-data {:title title
                               :class (dom/classnames
                                       :dnd-over-top (= (:over dprops) :top)
                                       :dnd-over-bot (= (:over dprops) :bot))
                               :ref dref}
     [:& cb/color-bullet {:color color
                          :on-click handle-click-color}]

     (cond
       ;; Rendering a color with ID
       (and (:id color) (not (uc/multiple? color)))
       [:*
        [:div.color-info
         [:div.color-name (str (get-color-name color))]]
        (when on-detach
          [:div.element-set-actions-button
           {:on-mouse-enter #(reset! hover-detach true)
            :on-mouse-leave #(reset! hover-detach false)
            :on-click detach-value}
           (if @hover-detach i/unchain i/chain)])]

       ;; Rendering a gradient
       (and (not (uc/multiple? color))
            (:gradient color)
            (get-in color [:gradient :type]))
       [:div.color-info
        [:div.color-name (cb/gradient-type->string (get-in color [:gradient :type]))]]

       ;; Rendering a plain color/opacity
       :else
       [:*
        [:div.color-info
         [:> color-input {:value (if (uc/multiple? color)
                                   ""
                                   (-> color :color uc/remove-hash))
                          :placeholder (tr "settings.multiple")
                          :on-click select-all
                          :on-blur on-blur
                          :on-change handle-value-change}]]

        (when (and (not disable-opacity)
                   (not (:gradient color)))
          [:div.input-element
           {:class (dom/classnames :percentail (not= (:opacity color) :multiple))}
           [:> numeric-input {:value (-> color :opacity opacity->string)
                              :placeholder (tr "settings.multiple")
                              :on-click select-all
                              :on-blur on-blur
                              :on-change handle-opacity-change
                              :min 0
                              :max 100}]])])
     (when (some? on-remove)
       [:div.element-set-actions-button.remove {:on-click on-remove} i/minus])]))

