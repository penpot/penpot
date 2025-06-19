;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.color-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as types.color]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.color-input :refer [color-input*]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.reorder-handler :refer [reorder-handler]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private detach-icon
  (i/icon-xref :detach (stl/css :detach-icon)))

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

(mf/defc color-row*
  [{:keys [index color class disable-gradient disable-opacity disable-image disable-picker hidden
           on-change on-reorder on-detach on-open on-close on-remove
           disable-drag on-focus on-blur select-only select-on-focus]}]
  (let [libraries        (mf/deref refs/files)
        hover-detach     (mf/use-state false)
        on-change        (h/use-ref-callback on-change)

        file-id          (or (:ref-file color) (:file-id color))
        color-id         (or (:ref-id color) (:id color))
        src-colors       (dm/get-in libraries [file-id :data :colors])
        color-name       (dm/get-in src-colors [color-id :name])

        multiple-colors? (uc/multiple? color)
        library-color?   (and (or (:id color) (:ref-id color)) color-name (not multiple-colors?))
        gradient-color?  (and (not multiple-colors?)
                              (:gradient color)
                              (dm/get-in color [:gradient :type]))
        image-color?     (and (not multiple-colors?)
                              (:image color))

        editing-text*    (mf/use-state false)
        editing-text?    (deref editing-text*)

        class            (if (some? class) (dm/str class " ") "")

        opacity?
        (and (not multiple-colors?)
             (not library-color?)
             (not disable-opacity))

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
         (fn [value]
           (let [color (-> color
                           (assoc :color value)
                           (dissoc :gradient)
                           (select-keys types.color/color-attrs))]
             (st/emit! (dwc/add-recent-color color)
                       (on-change color)))))

        handle-opacity-change
        (mf/use-fn
         (mf/deps color on-change)
         (fn [value]
           (let [color (-> color
                           (assoc :opacity (/ value 100))
                           (dissoc :ref-id :ref-file)
                           (select-keys types.color/color-attrs))]
             (st/emit! (dwc/add-recent-color color)
                       (on-change color)))))

        handle-click-color
        (mf/use-fn
         (mf/deps disable-gradient disable-opacity disable-image disable-picker on-change on-close on-open)
         (fn [color event]
           (let [color (cond
                         multiple-colors?
                         {:color default-color
                          :opacity 1}

                         (= :multiple (:opacity color))
                         (assoc color :opacity 1)

                         :else
                         color)

                 cpos  (dom/get-client-position event)
                 x     (dm/get-prop cpos :x)
                 y     (dm/get-prop cpos :y)

                 props {:x x
                        :y y
                        :disable-gradient disable-gradient
                        :disable-opacity disable-opacity
                        :disable-image disable-image
                        ;; on-change second parameter means if the source is the color-picker
                        :on-change #(on-change % true)
                        :on-close (fn [value opacity id file-id]
                                    (when on-close
                                      (on-close value opacity id file-id)))
                        :data color}]

             (when (fn? on-open)
               (on-open color))

             (when-not disable-picker
               (modal/show! :colorpicker props)))))

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

    (mf/with-effect [color prev-color disable-picker]
      (when (and (not disable-picker) (not= prev-color color))
        (modal/update-props! :colorpicker {:data (parse-color color)})))

    [:div {:class (dm/str
                   class
                   (stl/css-case
                    :color-data true
                    :hidden hidden
                    :dnd-over-top (= (:over dprops) :top)
                    :dnd-over-bot (= (:over dprops) :bot)))}

     ;; Drag handler
     (when (some? on-reorder)
       [:& reorder-handler {:ref dref}])

     [:div {:class (stl/css :color-info)}
      [:div {:class (stl/css-case :color-name-wrapper true
                                  :no-opacity (or disable-opacity
                                                  (not opacity?))
                                  :library-name-wrapper library-color?
                                  :editing editing-text?
                                  :gradient-name-wrapper gradient-color?)}
       [:div {:class (stl/css :color-bullet-wrapper)}
        [:& cb/color-bullet {:color (cond-> color
                                      (nil? color-name) (dissoc :ref-id :ref-file))
                             :mini true
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
             detach-icon])]

              ;; Rendering a gradient
         gradient-color?
         [:*
          [:div {:class (stl/css :color-name)}
           (uc/gradient-type->string (dm/get-in color [:gradient :type]))]]

              ;; Rendering an image
         image-color?
         [:*
          [:div {:class (stl/css :color-name)}
           (tr "media.image")]]

              ;; Rendering a plain color
         :else
         [:span {:class (stl/css :color-input-wrapper)}
          [:> color-input* {:value (if multiple-colors?
                                     ""
                                     (-> color :color cc/remove-hash))
                            :placeholder (tr "settings.multiple")
                            :class (stl/css :color-input)
                            :on-focus on-focus
                            :on-blur on-blur
                            :on-change handle-value-change}]])]

      (when opacity?
        [:div {:class (stl/css :opacity-element-wrapper)}
         [:span {:class (stl/css :icon-text)}
          "%"]
         [:> numeric-input* {:value (-> color :opacity opacity->string)
                             :className (stl/css :opacity-input)
                             :placeholder "--"
                             :select-on-focus select-on-focus
                             :on-focus on-focus
                             :on-blur on-blur
                             :on-change handle-opacity-change
                             :default 100
                             :min 0
                             :max 100}]])]

     (when (some? on-remove)
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "settings.remove-color")
                         :on-click on-remove
                         :icon "remove"}])
     (when select-only
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "settings.select-this-color")
                         :on-click handle-select
                         :icon "move"}])]))

