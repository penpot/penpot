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
   [app.common.types.token :as tk]
   [app.config :as cfg]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
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

(mf/defc color-info-wrapper*
  {::mf/private true}
  [{:keys [color class handle-click-color children select-on-focus opacity on-focus on-blur on-opacity-change]}]
  [:div {:class (stl/css :color-info)}
   [:div {:class class}
    [:div {:class (stl/css :color-bullet-wrapper)}
     [:> swatch* {:background color
                  :on-click handle-click-color
                  :size "small"}]]
    children]
   (when opacity
     [:div {:class (stl/css :opacity-element-wrapper)}
      [:span {:class (stl/css :icon-text)} "%"]
      [:> numeric-input* {:value (-> color :opacity opacity->string)
                          :class (stl/css :opacity-input)
                          :placeholder "--"
                          :select-on-focus select-on-focus
                          :on-focus on-focus
                          :on-blur on-blur
                          :on-change on-opacity-change
                          :data-testid "opacity-input"
                          :default 100
                          :min 0
                          :max 100}]])])

(mf/defc color-token-row*
  {::mf/private true}
  [{:keys [active-tokens color-token color on-swatch-click-token detach-token open-modal-from-token]}]
  (let [;; `active-tokens` may be provided as a `delay` (lazy computation).
        ;; In that case we must deref it (`@active-tokens`) to force evaluation
        ;; and obtain the actual value. If it’s already realized (not a delay),
        ;; we just use it directly.
        active-tokens (if (delay? active-tokens)
                        @active-tokens
                        active-tokens)

        color-tokens (:color active-tokens)

        token (some #(when (= (:name %) color-token) %) color-tokens)

        on-detach-token
        (mf/use-fn
         (mf/deps detach-token token)
         #(detach-token token))

        has-errors (some? (:errors token))
        token-name (:name token)
        resolved (:resolved-value token)
        not-active (and (some? active-tokens) (nil? token))
        id (dm/str (:id token) "-name")
        swatch-tooltip-content (cond
                                 not-active
                                 (tr "ds.inputs.token-field.no-active-token-option")
                                 has-errors
                                 (tr "color-row.token-color-row.deleted-token")
                                 :else
                                 (tr "workspace.tokens.resolved-value" resolved))
        name-tooltip-content (cond
                               not-active
                               (tr "ds.inputs.token-field.no-active-token-option")
                               has-errors
                               (tr "color-row.token-color-row.deleted-token")
                               :else
                               #(mf/html
                                 [:div
                                  [:span (dm/str (tr "workspace.tokens.token-name") ": ")]
                                  [:span {:class (stl/css :token-name-tooltip)} color-token]]))]

    [:div {:class (stl/css :color-info)}
     [:div {:class (stl/css-case :token-color-wrapper true
                                 :token-color-with-errors has-errors
                                 :token-color-not-active not-active)}
      [:div {:class (stl/css :color-bullet-wrapper)}
       (when (or has-errors not-active)
         [:div {:class (stl/css :error-dot)}])
       [:> swatch* {:background color
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
         :aria-label (tr "ds.inputs.token-field.detach-token")
         :on-click on-detach-token
         :icon i/detach}]
       [:> icon-button*
        {:variant "action"
         :aria-label (tr "ds.inputs.numeric-input.open-token-list-dropdown")
         :on-click open-modal-from-token
         :icon i/tokens}]]]]))

(mf/defc color-row*
  [{:keys [index color class disable-gradient disable-opacity disable-image disable-picker hidden
           on-change on-reorder on-detach on-open on-close on-remove origin on-detach-token
           disable-drag on-focus on-blur select-only select-on-focus on-token-change applied-token]}]
  (let [token-color      (contains? cfg/flags :token-color)
        libraries        (mf/deref refs/files)
        on-change        (h/use-ref-callback on-change)
        on-token-change  (h/use-ref-callback on-token-change)
        color-without-hash (mf/use-memo
                            (mf/deps color)
                            #(-> color :color clr/remove-hash))

        file-id          (or (:ref-file color) (:file-id color))
        color-id         (or (:ref-id color) (:id color))
        src-colors       (dm/get-in libraries [file-id :data :colors])
        color-name       (dm/get-in src-colors [color-id :name])

        has-multiple-colors (uc/multiple? color)
        library-color?   (and (or (:id color) (:ref-id color)) color-name (not ^boolean has-multiple-colors))
        gradient-color?  (and (not ^boolean has-multiple-colors)
                              (:gradient color)
                              (dm/get-in color [:gradient :type]))
        image-color?     (and (not ^boolean has-multiple-colors)
                              (:image color))

        editing-text*    (mf/use-state false)
        is-editing-text    (deref editing-text*)

        active-tokens*    (mf/use-ctx ctx/active-tokens-by-type)

        tokens (mf/with-memo [active-tokens* origin]
                 (delay
                   (-> (deref active-tokens*)
                       (select-keys (get tk/tokens-by-input origin))
                       (not-empty))))

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
         (mf/deps disable-gradient disable-opacity disable-image disable-picker on-change on-close on-open tokens)
         (fn [color pos tab]
           (let [color (cond
                         ^boolean has-multiple-colors
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
                        :active-tokens tokens
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
         (fn [relative-pos data]
           (let [from-pos             (:index data)
                 to-space-between-pos (if (= relative-pos :bot) (inc index) index)]
             (on-reorder from-pos to-space-between-pos))))

        [dprops dref]
        (if (some? on-reorder)
          (h/use-sortable
           :data-type "penpot/color-row"
           :on-drop on-drop
           :disabled disable-drag
           :detect-center? false
           :data {:index index})
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
     (cond
       (and token-color applied-token)
       [:> color-token-row* {:active-tokens tokens
                             :color-token applied-token
                             :color (dissoc color :ref-id :ref-file)
                             :on-swatch-click-token  on-swatch-click-token
                             :detach-token detach-token
                             :open-modal-from-token open-modal-from-token}]

       library-color?
       [:> color-info-wrapper* {:class (stl/css-case :color-name-wrapper true
                                                     :library-name-wrapper true)
                                :handle-click-color handle-click-color
                                :opacity false
                                :color color}
        [:*
         [:div {:class (stl/css :color-name)
                :title (str color-name)}
          (str color-name)]
         [:> icon-button*
          {:variant "ghost"
           :class (stl/css :detach-btn)
           :aria-label (tr "settings.detach")
           :on-click detach-value
           :icon i/detach}]]]

       gradient-color?
       [:> color-info-wrapper* {:class (stl/css-case :color-name-wrapper true
                                                     :no-opacity ^boolean disable-opacity
                                                     :gradient-name-wrapper true)
                                :handle-click-color handle-click-color
                                :color color
                                :opacity (not ^boolean disable-opacity)
                                :select-on-focus select-on-focus
                                :on-focus on-focus'
                                :on-blur on-blur'
                                :on-opacity-change on-opacity-change}
        [:div {:class (stl/css :color-name)}
         (uc/gradient-type->string (dm/get-in color [:gradient :type]))]]

       image-color?
       [:> color-info-wrapper* {:class (stl/css-case :color-name-wrapper true
                                                     :no-opacity ^boolean disable-opacity)
                                :handle-click-color handle-click-color
                                :color color
                                :opacity (not ^boolean disable-opacity)
                                :select-on-focus select-on-focus
                                :on-focus on-focus'
                                :on-blur on-blur'
                                :on-opacity-change on-opacity-change}
        [:div {:class (stl/css :color-name)}
         (tr "media.image")]]

       :else
       [:> color-info-wrapper* {:class (stl/css-case :color-name-wrapper true
                                                     :no-opacity (or ^boolean disable-opacity
                                                                     ^boolean has-multiple-colors)
                                                     :editing is-editing-text)
                                :handle-click-color handle-click-color
                                :color color
                                :opacity (not (or ^boolean disable-opacity
                                                  ^boolean has-multiple-colors))
                                :select-on-focus select-on-focus
                                :on-focus on-focus'
                                :on-blur on-blur'
                                :on-opacity-change on-opacity-change}

        [:span {:class (stl/css :color-input-wrapper)}
         [:> color-input* {:value (if ^boolean has-multiple-colors
                                    ""
                                    color-without-hash)
                           :placeholder (tr "settings.multiple")
                           :data-index index
                           :class (stl/css :color-input)
                           :on-focus on-focus'
                           :on-blur on-blur'
                           :on-change on-color-change}]]])

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
