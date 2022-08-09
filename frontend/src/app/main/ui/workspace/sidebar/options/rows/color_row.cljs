;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.rows.color-row
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages :as cp]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.color-input :refer [color-input]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.context :as ctx]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(defn opacity->string
  [opacity]
  (if (= opacity :multiple)
    ""
    (str (-> opacity
             (d/coalesce 1)
             (* 100)
             (fmt/format-number)))))

(defn remove-multiple
  [v]
  (if (= v :multiple) nil v))

(mf/defc color-row
  [{:keys [index color disable-gradient disable-opacity on-change
           on-reorder on-detach on-open on-close title on-remove
           disable-drag select-all on-blur select-only]}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)
        file-colors     (mf/deref refs/workspace-file-colors)
        shared-libs     (mf/deref refs/workspace-libraries)
        hover-detach    (mf/use-state false)
        on-change       (h/use-ref-callback on-change)
        src-colors      (if (= (:file-id color) current-file-id)
                          file-colors
                          (dm/get-in shared-libs [(:file-id color) :data :colors]))

        color-name       (dm/get-in src-colors [(:id color) :name])

        parse-color
        (mf/use-fn
         (fn [color]
           (update color :color #(or % (:value color)))))

        detach-value
        (mf/use-fn
         (mf/deps on-detach color)
         (fn []
           (when on-detach
             (on-detach color))))

        handle-select
        (mf/use-fn
         (mf/deps select-only color)
         (fn []
           (select-only color)))

        handle-value-change
        (mf/use-fn
         (mf/deps color on-change)
         (fn [new-value]
           (on-change (-> color
                          (assoc :color new-value)
                          (dissoc :gradient)))))

        handle-opacity-change
        (mf/use-fn
         (mf/deps color on-change)
         (fn [value]
           (on-change (assoc color
                             :opacity (/ value 100)
                             :id nil
                             :file-id nil))))

        handle-click-color
        (mf/use-fn
         (mf/deps disable-gradient disable-opacity on-change on-close on-open)
         (fn [color event]
           (let [color (cond
                         (uc/multiple? color)
                         {:color cp/default-color
                          :opacity 1}

                         (= :multiple (:opacity color))
                         (assoc color :opacity 1)

                         :else
                         color)

                 {:keys [x y]} (dom/get-client-position event)

                 props {:x x
                        :y y
                        :disable-gradient disable-gradient
                        :disable-opacity disable-opacity
                        :on-change #(on-change (merge uc/empty-color %))
                        :on-close (fn [value opacity id file-id]
                                    (when on-close
                                      (on-close value opacity id file-id)))
                        :data color}]

             (when on-open
               (on-open (merge uc/empty-color color)))

             (modal/show! :colorpicker props))))


        prev-color (h/use-previous color)

        on-drop
        (mf/use-fn
         (mf/deps on-reorder)
         (fn [_ data]
           (on-reorder (:index data))))

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

    (mf/with-effect [color prev-color]
      (when (not= prev-color color)
        (modal/update-props! :colorpicker {:data (parse-color color)})))

    [:div.row-flex.color-data {:title title
                               :class (dom/classnames
                                       :dnd-over-top (= (:over dprops) :top)
                                       :dnd-over-bot (= (:over dprops) :bot))
                               :ref dref}
     [:& cb/color-bullet {:color (cond-> color
                                   (nil? color-name) (assoc
                                                      :id nil
                                                      :file-id nil))
                          :on-click handle-click-color}]

     (cond
       ;; Rendering a color with ID
       (and (:id color) color-name (not (uc/multiple? color)))
       [:*
        [:div.color-info
         [:div.color-name (str color-name)]]
        (when on-detach
          [:div.element-set-actions-button
           {:on-mouse-enter #(reset! hover-detach true)
            :on-mouse-leave #(reset! hover-detach false)
            :on-click detach-value}
           (if @hover-detach i/unchain i/chain)])

        (when select-only
          [:div.element-set-actions-button {:on-click handle-select}
           i/pointer-inner])]

       ;; Rendering a gradient
       (and (not (uc/multiple? color))
            (:gradient color)
            (get-in color [:gradient :type]))
       [:*
        [:div.color-info
         [:div.color-name (cb/gradient-type->string (get-in color [:gradient :type]))]]
        (when select-only
          [:div.element-set-actions-button {:on-click handle-select}
           i/pointer-inner])]


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
                              :max 100}]])
        (when select-only
          [:div.element-set-actions-button {:on-click handle-select}
           i/pointer-inner])])
     (when (some? on-remove)
       [:div.element-set-actions-button.remove {:on-click on-remove} i/minus])]))

