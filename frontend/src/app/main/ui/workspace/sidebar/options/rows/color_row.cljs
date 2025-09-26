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
   [app.common.types.color :as clr]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.color-input :refer [color-input*]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.reorder-handler :refer [reorder-handler*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
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


(mf/defc color-token-row*
  [{:keys [active-tokens color-token color on-swatch-click-token detach-token open-modal-from-token color-name]}]
  (let [color-tokens (:color active-tokens)
        token (some #(when (= (:name %) color-token) %) color-tokens)
        has-errors (some? (:errors token))
        token-name (:name token)
        resolved (:resolved-value token)
        not-active (and (some? color-tokens) (nil? token))
        ;;  value (dwta/value->color resolved)
        id (dm/str (:id token) "-name")
        swatch-tooltip-content (cond
                         not-active
                         "This token is not in any active set or has an invalid value"
                         has-errors
                         "This token does not exists or has been deleted."
                         :else
                         (tr "workspace.tokens.resolved-value" resolved))
        name-tooltip-content (cond
                               not-active
                               "This token is not in any active set or has an invalid value"
                               has-errors
                               "This token does not exists or has been deleted."
                               :else
                               (mf/html
                                [:*
                                 [:div
                                  [:span (dm/str (tr "workspace.tokens.token-name") ": ")]
                                  [:span {:class (stl/css :token-name-tooltip)} color-token]]
                                 [:div (tr "workspace.tokens.resolved-value" resolved)]]))]
    [:div {:class (stl/css :color-info)}
     [:div {:class (stl/css-case :token-color-wrapper true
                                 :token-color-with-errors has-errors
                                 :token-color-not-active not-active)}
      [:div {:class (stl/css :color-bullet-wrapper)}
       (when (or has-errors not-active)
         [:div {:class (stl/css :error-dot)}])
       [:> swatch* {:background (cond-> color
                                  (nil? color-name) (dissoc :ref-id :ref-file))
                    :tooltip-content swatch-tooltip-content
                    :on-click on-swatch-click-token
                    :has-errors (or has-errors not-active)
                    :size "small"}]]
      [:> tooltip* {:content name-tooltip-content
                    :id id
                    :class (stl/css :token-tooltip)}
       [:div {:class (stl/css :token-name)
              :aria-labelledby id}
        (or token-name color-token)]]
      [:div {:class (stl/css :token-actions)}
       [:> icon-button*
        {:variant "action"
         :class (stl/css :detach-btn2)
         :aria-label "Detach-token"
         :on-click #(detach-token token)
         :icon i/detach}]
       [:> icon-button*
        {:variant "action"
         :class (stl/css :detach-btn2)
         :aria-label "Open colorpicker"
         :on-click open-modal-from-token
         :icon i/tokens}]]]]))


(mf/defc library-color-row*
  [{:keys  [color color-name detach-value handle-click-color]}]
  [:div {:class (stl/css :color-info)}
   [:div {:class (stl/css-case :color-name-wrapper true
                               :library-name-wrapper true)}
    [:div {:class (stl/css :color-bullet-wrapper)}
     [:& cb/color-bullet {:color (cond-> color
                                   (nil? color-name) (dissoc :ref-id :ref-file))
                          :mini true
                          :on-click handle-click-color}]]
    [:*
     [:div {:class (stl/css :color-name)
            :title (str color-name)}
      (str color-name)]
     [:> icon-button*
      {:variant "ghost"
       :class (stl/css :detach-btn)
       :aria-label (tr "settings.detach")
       :on-click detach-value
       :icon i/detach}]]]])

(mf/defc color-row*
  [{:keys [index color class disable-gradient disable-opacity disable-image disable-picker hidden
           on-change on-reorder on-detach on-open on-close on-remove origin on-detach-token
           disable-drag on-focus on-blur select-only select-on-focus on-token-change color-token]}]
  (let [libraries        (mf/deref refs/files)
        on-change        (h/use-ref-callback on-change)
        on-token-change  (h/use-ref-callback on-token-change)

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

        active-tokens*    (mf/use-ctx ctx/active-tokens-by-type)
        ;; TODO Review this
        active-tokens     (if active-tokens*
                            @active-tokens*
                            {})

        opacity?
        (and (not multiple-colors?)
             (not library-color?)
             (not disable-opacity))

        on-focus'
        (mf/use-fn
         (mf/deps on-focus)
         (fn [_]
           (reset! editing-text* true)
           (when on-focus
             (on-focus))))

        on-blur'
        (mf/use-fn
         (mf/deps on-blur)
         (fn [_]
           (reset! editing-text* false)
           (when on-blur
             (on-blur))))

        parse-color
        (mf/use-fn
         (fn [color]
           (update color :color #(or % (:value color)))))

        detach-value
        (mf/use-fn
         (mf/deps on-detach index)
         (fn [_]
           (when on-detach
             (on-detach index))))

        handle-select
        (mf/use-fn
         (mf/deps select-only color)
         (fn []
           (select-only color)))

        on-color-change
        (mf/use-fn
         (mf/deps color index on-change)
         (fn [value _event]
           (let [color (-> color
                           (assoc :color value)
                           (dissoc :gradient)
                           (select-keys clr/color-attrs))]
             (st/emit! (dwc/add-recent-color color)
                       (on-change color index)))))

        on-opacity-change
        (mf/use-fn
         (mf/deps color index on-change)
         (fn [value]
           (let [color (-> color
                           (assoc :opacity (/ value 100))
                           (dissoc :ref-id :ref-file)
                           (select-keys clr/color-attrs))]
             (st/emit! (dwc/add-recent-color color)
                       (on-change color index)))))

        open-modal
        (mf/use-fn
         (mf/deps disable-gradient disable-opacity disable-image disable-picker on-change on-close on-open active-tokens)
         (fn [color pos tab]
           (let [color (cond
                         multiple-colors?
                         {:color default-color
                          :opacity 1}

                         (= :multiple (:opacity color))
                         (assoc color :opacity 1)

                         :else
                         color)
                 props {:x (:x pos)
                        :y (:y pos)
                        :disable-gradient disable-gradient
                        :disable-opacity disable-opacity
                        :disable-image disable-image
                        ;; on-change second parameter means if the source is the color-picker
                        :on-change #(on-change % index)
                        :on-token-change on-token-change
                        :on-close (fn [value opacity id file-id]
                                    (when on-close
                                      (on-close value opacity id file-id)))
                        :active-tokens active-tokens
                        :color-origin origin
                        :tab tab
                        :origin :sidebar
                        :data color}]

             (when (fn? on-open)
               (on-open color))

             (when-not disable-picker
               (modal/show! :colorpicker props)))))

        handle-click-color
        (mf/use-fn
         (mf/deps open-modal)
         (fn [color event]
           (let [cpos  (dom/get-client-position event)]
             (open-modal color cpos nil))))
        
        open-modal-from-token
        (mf/use-fn
         (mf/deps open-modal color)
         (fn [event]
           (let [cpos  (dom/get-client-position event)
                 x     (:x cpos)
                 y     (:y cpos)
                 pos {:x (- x 215)
                      :y y}]
             (open-modal color pos :token-color))))

        on-swatch-click-token
        (mf/use-fn
         (mf/deps open-modal)
         (fn [color event]
           (let [cpos  (dom/get-client-position event)]
             (open-modal color cpos :token-color))))

        detach-token
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (when on-detach-token
             (on-detach-token token))))

        on-remove'
        (mf/use-fn
         (mf/deps index)
         (fn [_]
           (when on-remove
             (on-remove index))))

        prev-color
        (h/use-previous color)

        on-drop
        (mf/use-fn
         (mf/deps on-reorder index)
         (fn [_ data]
           (on-reorder index (:index data))))

        [dprops dref]
        (if (some? on-reorder)
          (h/use-sortable
           :data-type "penpot/color-row"
           :on-drop on-drop
           :disabled disable-drag
           :detect-center? false
           :data {:id (str "color-row-" index)
                  :index index
                  :name (str "Color row" index)})
          [nil nil])


        row-class
        (stl/css-case :color-data true
                      :hidden hidden
                      :dnd-over-top (= (:over dprops) :top)
                      :dnd-over-bot (= (:over dprops) :bot))]

    (mf/with-effect [color prev-color disable-picker]
      (when (and (not disable-picker) (not= prev-color color))
        (modal/update-props! :colorpicker {:data (parse-color color)})))

    [:div {:class [class row-class]}

     ;; Drag handler
     (when (some? on-reorder)
       [:> reorder-handler* {:ref dref}])

     (if color-token
       [:> color-token-row* {:active-tokens active-tokens
                             :color-token color-token
                             :color color 
                             :on-swatch-click-token  on-swatch-click-token 
                             :detach-token detach-token 
                             :open-modal-from-token open-modal-from-token 
                             :color-name color-name}]

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
              [:> icon-button*
               {:variant "ghost"
                :class (stl/css :detach-btn)
                :aria-label (tr "settings.detach")
                :on-click detach-value
                :icon i/detach}])]

         ;; Rendering a gradient
           gradient-color?
           [:div {:class (stl/css :color-name)}
            (uc/gradient-type->string (dm/get-in color [:gradient :type]))]

         ;; Rendering an image
           image-color?
           [:div {:class (stl/css :color-name)}
            (tr "media.image")]

           ;; Rendering a plain color
           :else
           [:span {:class (stl/css :color-input-wrapper)}
            [:> color-input* {:value (if multiple-colors?
                                       ""
                                       (-> color :color clr/remove-hash))
                              :placeholder (tr "settings.multiple")
                              :data-index index
                              :class (stl/css :color-input)
                              :on-focus on-focus'
                              :on-blur on-blur'
                              :on-change on-color-change}]])]

        (when opacity?
          [:div {:class (stl/css :opacity-element-wrapper)}
           [:span {:class (stl/css :icon-text)} "%"]
           [:> numeric-input* {:value (-> color :opacity opacity->string)
                               :class (stl/css :opacity-input)
                               :placeholder "--"
                               :select-on-focus select-on-focus
                               :on-focus on-focus'
                               :on-blur on-blur'
                               :on-change on-opacity-change
                               :data-testid "opacity-input"
                               :default 100
                               :min 0
                               :max 100}]])])

     (when (some? on-remove)
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "settings.remove-color")
                         :on-click on-remove'
                         :icon i/remove}])
     (when select-only
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "settings.select-this-color")
                         :on-click handle-select
                         :icon i/move}])]))

