;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.color-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.color-bullet-new :as cbn]
   [app.main.ui.components.color-input :refer [color-input*]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

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
           disable-drag on-focus on-blur select-only select-on-focus]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        current-file-id (mf/use-ctx ctx/current-file-id)
        file-colors     (mf/deref refs/workspace-file-colors)
        shared-libs     (mf/deref refs/workspace-libraries)
        hover-detach    (mf/use-state false)
        on-change       (h/use-ref-callback on-change)
        src-colors      (if (= (:file-id color) current-file-id)
                          file-colors
                          (dm/get-in shared-libs [(:file-id color) :data :colors]))

        color-name       (dm/get-in src-colors [(:id color) :name])

        multiple-colors? (uc/multiple? color)
        library-color?    (and (:id color) color-name (not multiple-colors?))
        gradient-color? (and (not multiple-colors?)
                             (:gradient color)
                             (get-in color [:gradient :type]))

        editing-text*  (mf/use-state false)
        editing-text?  (deref editing-text*)

        on-focus
        (mf/use-fn
         (mf/deps on-focus)
         (fn [event]
           (reset! editing-text* true)
           (when on-focus
             (on-focus event))))

        on-blur
        (mf/use-fn
         (mf/deps on-blur)
         (fn [event]
           (reset! editing-text* false)
           (when on-blur
             (on-blur event))))
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
           (let [color (-> color
                           (assoc :color new-value)
                           (dissoc :gradient))]
             (st/emit! (dwl/add-recent-color color)
                       (on-change color)))))

        handle-opacity-change
        (mf/use-fn
         (mf/deps color on-change)
         (fn [value]
           (let [color (assoc color
                              :opacity (/ value 100)
                              :id nil
                              :file-id nil)]
             (st/emit! (dwl/add-recent-color color)
                       (on-change color)))))

        handle-click-color
        (mf/use-fn
         (mf/deps disable-gradient disable-opacity on-change on-close on-open)
         (fn [color event]
           (let [color (cond
                         multiple-colors?
                         {:color default-color
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
                        ;; on-change second parameter means if the source is the color-picker
                        :on-change #(on-change (merge uc/empty-color %) true)
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

    (if new-css-system
      [:div {:class (stl/css-case
                     :color-data true
                     :dnd-over-top (= (:over dprops) :top)
                     :dnd-over-bot (= (:over dprops) :bot))
             :ref dref}
       [:span {:class (stl/css :color-info)}
        [:span {:class (stl/css-case :color-name-wrapper true
                                     :library-name-wrapper library-color?
                                     :editing editing-text?
                                     :gradient-name-wrapper gradient-color?)}
         [:span {:class (stl/css :color-bullet-wrapper)}
          [:& cbn/color-bullet {:color (cond-> color
                                         (nil? color-name) (assoc
                                                            :id nil
                                                            :file-id nil))
                                :mini? true
                                :on-click handle-click-color}]]
        (cond
          ;; Rendering a color with ID
          library-color?
          [:*
           [:div {:class (stl/css :color-name)
                  :title (str color-name)}

            (str color-name)]
           (when on-detach
             [:button
              {:class (stl/css :detach-btn)
               :title (tr "settings.detach")
               :on-pointer-enter #(reset! hover-detach true)
               :on-pointer-leave #(reset! hover-detach false)
               :on-click detach-value}
              i/detach-refactor])]

          ;; Rendering a gradient
          gradient-color?
          [:*
           [:div {:class (stl/css :color-name)}
            (uc/gradient-type->string (get-in color [:gradient :type]))]]

          ;; Rendering a plain color
          :else
          [:span {:class (stl/css :color-input-wrapper)}
           [:> color-input* {:value (if multiple-colors?
                                      ""
                                      (-> color :color uc/remove-hash))
                             :placeholder (tr "settings.multiple")
                             :className   (stl/css :color-input)
                             :on-focus on-focus
                             :on-blur on-blur
                             :on-change handle-value-change}]])]

        (when (and (not gradient-color?)
                   (not multiple-colors?)
                   (not library-color?))

          [:div {:class (stl/css :opacity-element-wrapper)}
           [:span {:class (stl/css :icon-text)}
            "%"]
           [:> numeric-input* {:value (-> color :opacity opacity->string)
                               :className (stl/css :opacity-input)
                               :placeholder (tr "settings.multiple")
                               :select-on-focus select-on-focus
                               :on-focus on-focus
                               :on-blur on-blur
                               :on-change handle-opacity-change
                               :min 0
                               :max 100}]])]

       (when (some? on-remove)
         [:button {:class (stl/css :remove-btn)
                   :on-click on-remove} i/remove-refactor])
       (when select-only
         [:button {:class (stl/css :select-btn)
                   :on-click handle-select}
          i/move-refactor])]









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
         library-color?
         [:*
          [:div.color-info
           [:div.color-name (str color-name)]]
          (when on-detach
            [:div.element-set-actions-button
             {:on-pointer-enter #(reset! hover-detach true)
              :on-pointer-leave #(reset! hover-detach false)
              :on-click detach-value}
             (if @hover-detach i/unchain i/chain)])]

         ;; Rendering a gradient
         gradient-color?
         [:*
          [:div.color-info
           [:div.color-name (uc/gradient-type->string (get-in color [:gradient :type]))]]
          (when select-only
            [:div.element-set-actions-button {:on-click handle-select}
             i/pointer-inner])]

           ;; Rendering a plain color/opacity
         :else
         [:*
          [:div.color-info
           [:> color-input* {:value (if multiple-colors?
                                      ""
                                      (-> color :color uc/remove-hash))
                             :placeholder (tr "settings.multiple")
                             :on-focus on-focus
                             :on-blur on-blur
                             :on-change handle-value-change}]]

          (when (and (not disable-opacity)
                     (not (:gradient color)))
            [:div.input-element
             {:class (dom/classnames :percentail (not= (:opacity color) :multiple))}
             [:> numeric-input* {:value (-> color :opacity opacity->string)
                                 :placeholder (tr "settings.multiple")
                                 :select-on-focus select-on-focus
                                 :on-focus on-focus
                                 :on-blur on-blur
                                 :on-change handle-opacity-change
                                 :min 0
                                 :max 100}]])
          (when select-only
            [:div.element-set-actions-button {:on-click handle-select}
             i/pointer-inner])])
       (when (some? on-remove)
         [:div.element-set-actions-button.remove {:on-click on-remove} i/minus])])

    ))

