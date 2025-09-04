;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.interactions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.page :as ctp]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- event-type-names
  []
  {:click (tr "workspace.options.interaction-on-click")
   ;; TODO: need more UX research
   ;; :mouse-over (tr "workspace.options.interaction-while-hovering")
   ;; :mouse-press (tr "workspace.options.interaction-while-pressing")
   :mouse-enter (tr "workspace.options.interaction-mouse-enter")
   :mouse-leave (tr "workspace.options.interaction-mouse-leave")
   :after-delay (tr "workspace.options.interaction-after-delay")})

(defn- event-type-name
  [interaction]
  (get (event-type-names) (:event-type interaction) "--"))

(defn- action-summary
  [interaction destination]
  (case (:action-type interaction)
    :navigate (tr "workspace.options.interaction-navigate-to-dest"
                  (get destination :name (tr "workspace.options.interaction-none")))
    :open-overlay (tr "workspace.options.interaction-open-overlay-dest"
                      (get destination :name (tr "workspace.options.interaction-none")))
    :toggle-overlay (tr "workspace.options.interaction-toggle-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :close-overlay (tr "workspace.options.interaction-close-overlay-dest"
                       (get destination :name (tr "workspace.options.interaction-self")))
    :prev-screen (tr "workspace.options.interaction-prev-screen")
    :open-url (tr "workspace.options.interaction-open-url")
    "--"))

(defn- get-frames-options
  [frames shape]
  (->> frames
       (filter #(and (not= (:id %) (:id shape)) ; A frame cannot navigate to itself
                     (not= (:id %) (:frame-id shape)))) ; nor a shape to its container frame
       (map (fn [frame]
              {:value (str (:id frame)) :label (:name frame)}))))

(defn- get-shared-frames-options
  [shared-frames]
  (map (fn [frame]
         {:value (str (:id frame)) :label (:name frame)}) shared-frames))

(def flow-for-rename-ref
  (l/derived (l/in [:workspace-local :flow-for-rename]) st/state))

(mf/defc flow-item
  [{:keys [flow]}]
  (let [editing?         (mf/use-state false)
        flow-for-rename  (mf/deref flow-for-rename-ref)
        name-ref         (mf/use-ref)

        start-edit (fn []
                     (reset! editing? true))

        accept-edit (fn []
                      (let [name-input (mf/ref-val name-ref)
                            name       (str/trim (dom/get-value name-input))]
                        (reset! editing? false)
                        (st/emit! (dwi/end-rename-flow)
                                  (when-not (str/empty? name)
                                    (dwi/rename-flow (:id flow) name)))))

        cancel-edit (fn []
                      (reset! editing? false)
                      (st/emit! (dwi/end-rename-flow)))

        on-key-down (fn [event]
                      (when (kbd/enter? event) (accept-edit))
                      (when (kbd/esc? event) (cancel-edit)))

        start-flow
        (mf/use-fn
         (mf/deps flow)
         #(st/emit! (dw/select-shape (:starting-frame flow))))

        ;; rename-flow
        ;; (mf/use-fn
        ;;  (mf/deps flow)
        ;;  #(st/emit! (dwi/start-rename-flow (:id flow))))

        remove-flow
        (mf/use-fn
         (mf/deps flow)
         #(st/emit! (dwi/remove-flow (:id flow))))]

    (mf/use-effect
     (fn []
       #(when editing?
          (cancel-edit))))

    (mf/use-effect
     (mf/deps flow-for-rename)
     #(when (and (= flow-for-rename (:id flow))
                 (not @editing?))
        (start-edit)))

    (mf/use-effect
     (mf/deps @editing?)
     #(when @editing?
        (let [name-input (mf/ref-val name-ref)]
          (dom/select-text! name-input))
        nil))

    [:div  {:class (stl/css :flow-element)}
     [:span {:class (stl/css :flow-info)}
      [:span {:class (stl/css :flow-name-wrapper)}
       [:button {:class (stl/css :start-flow-btn)
                 :on-click start-flow}
        [:span {:class (stl/css :button-icon)}
         deprecated-icon/play]]
       [:span {:class (stl/css :flow-input-wrapper)}
        [:input
         {:class (stl/css :flow-input)
          :type "text"
          :ref name-ref
          :on-blur accept-edit
          :on-key-down on-key-down
          :default-value (:name flow "")}]]]]

     [:> icon-button* {:variant "ghost"
                       :aria-label (tr "workspace.options.flows.remove-flow")
                       :on-click remove-flow
                       :icon i/remove}]]))

(mf/defc page-flows
  {::mf/props :obj}
  [{:keys [flows]}]
  (when flows
    [:div {:class (stl/css :interaction-options)}
     [:> title-bar* {:collapsable false
                     :title       (tr "workspace.options.flows.flow-starts")
                     :class       (stl/css :title-spacing-layout-flow)}]
     (for [[id flow] flows]
       [:& flow-item {:flow flow :key (dm/str id)}])]))

(mf/defc shape-flows
  {::mf/props :obj}
  [{:keys [flows shape]}]
  (when (cfh/frame-shape? shape)
    (let [flow     (ctp/get-frame-flow flows (:id shape))
          add-flow (mf/use-fn #(st/emit! (dwi/add-flow-selected-frame)))]

      [:div {:class (stl/css :element-set)}
       [:> title-bar* {:collapsable false
                       :title       (tr "workspace.options.flows.flow")
                       :class       (stl/css :title-spacing-layout-flow)}
        (when (nil? flow)
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.options.flows.add-flow-start")
                            :on-click add-flow
                            :icon i/add}])]

       (when (some? flow)
         [:& flow-item {:flow flow :key (dm/str (:id flow))}])])))

(def ^:private corner-center-icon
  (deprecated-icon/icon-xref :corner-center (stl/css :corner-icon)))
(def ^:private corner-bottom-icon
  (deprecated-icon/icon-xref :corner-bottom (stl/css :corner-icon)))
(def ^:private corner-bottomleft-icon
  (deprecated-icon/icon-xref :corner-bottom-left (stl/css :corner-icon)))
(def ^:private corner-bottomright-icon
  (deprecated-icon/icon-xref :corner-bottom-right (stl/css :corner-icon)))
(def ^:private corner-top-icon
  (deprecated-icon/icon-xref :corner-top (stl/css :corner-icon)))
(def ^:private corner-topleft-icon
  (deprecated-icon/icon-xref :corner-top-left (stl/css :corner-icon)))
(def ^:private corner-topright-icon
  (deprecated-icon/icon-xref :corner-top-right (stl/css :corner-icon)))

(mf/defc interaction-entry
  [{:keys [index shape interaction update-interaction remove-interaction]}]
  (let [objects              (deref refs/workspace-page-objects)
        destination          (get objects (:destination interaction))

        frames               (mf/with-memo [objects] (ctt/get-viewer-frames objects {:all-frames? true}))
        shape-parent-ids     (mf/with-memo [objects] (cfh/get-parent-ids objects (:id shape)))
        shape-parents        (mf/with-memo [frames shape] (filter (comp (set shape-parent-ids) :id) frames))

        overlay-pos-type     (:overlay-pos-type interaction)
        close-click-outside? (:close-click-outside interaction false)
        background-overlay?  (:background-overlay interaction false)
        preserve-scroll?     (:preserve-scroll interaction false)

        way                  (-> interaction :animation :way)
        direction            (-> interaction :animation :direction)

        state*               (mf/use-state false)
        extended-open?       (deref state*)

        toggle-extended      (mf/use-fn  #(swap! state* not))

        ext-delay-ref        (mf/use-ref nil)
        ext-duration-ref     (mf/use-ref nil)

        change-event-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-event-type % value shape)))))

        change-action-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-action-type % value)))))

        change-delay
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [value]
           (update-interaction index #(ctsi/set-delay % value))))

        change-destination
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value event
                 value (when (not= value "") (uuid/parse value))]
             (update-interaction index #(ctsi/set-destination % value)))))

        change-position-relative-to
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (uuid/parse* event)]
             (update-interaction index #(ctsi/set-position-relative-to % value)))))

        change-preserve-scroll
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-preserve-scroll % value)))))

        change-url
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [target      (dom/get-target event)
                 value       (dom/get-value target)
                 has-prefix? (or (str/starts-with? value "http://")
                                 (str/starts-with? value "https://"))
                 value       (if has-prefix?
                               value
                               (str "http://" value))]
             (when-not has-prefix?
               (dom/set-value! target value))
             (if (dom/valid? target)
               (do
                 (dom/remove-class! target "error")
                 (update-interaction index #(ctsi/set-url % value)))
               (dom/add-class! target "error")))))

        change-overlay-pos-type
        (mf/use-fn
         (mf/deps shape update-interaction)
         (fn [value]
           (let [shape-id (:id shape)]
             (update-interaction index #(ctsi/set-overlay-pos-type % value shape objects))
             (when (= value :manual)
               (update-interaction index #(ctsi/set-position-relative-to % shape-id))))))

        toggle-overlay-pos-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [pos-type (-> (dom/get-current-target event)
                              (dom/get-data "value")
                              (keyword))]
             (update-interaction index #(ctsi/toggle-overlay-pos-type % pos-type shape objects)))))

        change-close-click-outside
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-close-click-outside % value)))))

        change-background-overlay
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-background-overlay % value)))))

        change-animation-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (if (= "" event)
                         nil
                         (keyword event))]
             (update-interaction index #(ctsi/set-animation-type % value)))))

        change-duration
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [value]
           (update-interaction index #(ctsi/set-duration % value))))

        change-easing
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-easing % value)))))

        change-way
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-way % value)))))

        change-direction
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-direction % value)))))

        change-offset-effect
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-offset-effect % value)))))


        event-type-options   (-> [{:value :click :label (tr "workspace.options.interaction-on-click")}
                                  ;; TODO: need more UX research
                                  ;; :mouse-over (tr "workspace.options.interaction-while-hovering")
                                  ;; :mouse-press (tr "workspace.options.interaction-while-pressing")
                                  {:value :mouse-enter :label (tr "workspace.options.interaction-mouse-enter")}
                                  {:value :mouse-leave :label (tr "workspace.options.interaction-mouse-leave")}]
                                 (cond-> (cfh/frame-shape? shape)
                                   (conj {:value :after-delay :label (tr "workspace.options.interaction-after-delay")})))

        action-type-options [{:value :navigate :label (tr "workspace.options.interaction-navigate-to")}
                             {:value :open-overlay :label (tr "workspace.options.interaction-open-overlay")}
                             {:value :toggle-overlay :label (tr "workspace.options.interaction-toggle-overlay")}
                             {:value :close-overlay :label (tr "workspace.options.interaction-close-overlay")}
                             {:value :prev-screen :label (tr "workspace.options.interaction-prev-screen")}
                             {:value :open-url :label (tr "workspace.options.interaction-open-url")}]

        frames-opts         (get-frames-options frames shape)

        default-opts        [(if (= (:action-type interaction) :close-overlay)
                               {:value "" :label (tr "workspace.options.interaction-self")}
                               {:value "" :label (tr "workspace.options.interaction-none")})]
        destination-options
        (mf/with-memo [frames-opts default-opts]
          (let [sorted-frames-opts (sort-by :label frames-opts)]
            (d/concat-vec default-opts sorted-frames-opts)))

        shape-parents-opts (get-shared-frames-options shape-parents)

        relative-to-opts
        (mf/with-memo [shape-parents-opts]
          (if (not= (:overlay-pos-type interaction) :manual)
            (d/concat-vec [{:value "" :label (tr "workspace.options.interaction-auto")}]
                          shape-parents-opts
                          [{:value (str (:id shape)) :label (str (:name shape) " (" (tr "workspace.options.interaction-self") ")")}])
            [{:value (str (:id shape)) :label (str (:name shape) " (" (tr "workspace.options.interaction-self") ")")}]))

        overlay-position-opts [{:value :manual :label (tr "workspace.options.interaction-pos-manual")}
                               {:value :center :label  (tr "workspace.options.interaction-pos-center")}
                               {:value :top-left  :label (tr "workspace.options.interaction-pos-top-left")}
                               {:value :top-right  :label (tr "workspace.options.interaction-pos-top-right")}
                               {:value :top-center  :label (tr "workspace.options.interaction-pos-top-center")}
                               {:value :bottom-left  :label (tr "workspace.options.interaction-pos-bottom-left")}
                               {:value :bottom-right :label  (tr "workspace.options.interaction-pos-bottom-right")}
                               {:value :bottom-center  :label (tr "workspace.options.interaction-pos-bottom-center")}]

        basic-animation-opts [{:value "" :label  (tr "workspace.options.interaction-animation-none")}
                              {:value :dissolve :label  (tr "workspace.options.interaction-animation-dissolve")}
                              {:value :slide :label  (tr "workspace.options.interaction-animation-slide")}]

        animation-opts
        (mf/with-memo [basic-animation-opts]
          (if (ctsi/allow-push? (:action-type interaction))
            (d/concat-vec basic-animation-opts [{:value :push :label (tr "workspace.options.interaction-animation-push")}])
            basic-animation-opts))

        easing-options [{:icon  :easing-linear :value :linear :label (tr "workspace.options.interaction-easing-linear")}
                        {:icon  :easing-ease :value :ease :label (tr "workspace.options.interaction-easing-ease")}
                        {:icon  :easing-ease-in :value :ease-in :label (tr "workspace.options.interaction-easing-ease-in")}
                        {:icon  :easing-ease-out :value :ease-out :label (tr "workspace.options.interaction-easing-ease-out")}
                        {:icon  :easing-ease-in-out :value :ease-in-out :label (tr "workspace.options.interaction-easing-ease-in-out")}]]


    [:div {:class (stl/css-case  :element-set-options-group true
                                 :element-set-options-group-open extended-open?)}
          ; Summary
     [:div {:class (stl/css :interactions-summary)}
      [:button {:class (stl/css-case :extend-btn true
                                     :extended extended-open?)
                :on-click toggle-extended}
       deprecated-icon/menu]

      [:div {:class (stl/css :interactions-info)
             :on-click toggle-extended}
       [:div {:class (stl/css :trigger-name)} (event-type-name interaction)]
       [:div {:class (stl/css :action-summary)} (action-summary interaction destination)]]
      [:button {:class (stl/css :remove-btn)
                :data-value index
                :on-click #(remove-interaction index)}
       deprecated-icon/remove-icon]]

     (when extended-open?
       [:div {:class (stl/css :extended-options)}
              ;; Trigger select
        [:div {:class (stl/css :property-row)}
         [:span {:class (stl/css :interaction-name)}
          (tr "workspace.options.interaction-trigger")]
         [:div {:class (stl/css :select-wrapper)}
          [:& select {:class (stl/css :interaction-type-select)
                      :default-value (:event-type interaction)
                      :options event-type-options
                      :on-change change-event-type}]]]

             ;; Delay
        (when (ctsi/has-delay interaction)
          [:div {:class (stl/css :property-row)}
           [:span {:class (stl/css :interaction-name)}
            (tr "workspace.options.interaction-delay")]
           [:div {:class (stl/css :input-element-wrapper)
                  :title (tr "workspace.options.interaction-ms")}
            [:span.after (tr "workspace.options.interaction-ms")]
            [:> numeric-input* {:ref ext-delay-ref
                                :className (stl/css :numeric-input)
                                :on-change change-delay
                                :value (:delay interaction)
                                :title (tr "workspace.options.interaction-ms")}]]])

              ;; Action select
        [:div {:class (stl/css :property-row)}
         [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-action")]
         [:div {:class (stl/css :select-wrapper)}
          [:& select {:class (stl/css :interaction-type-select)
                      :default-value (:action-type interaction)
                      :options action-type-options
                      :on-change change-action-type}]]]

              ;; Destination
        (when (ctsi/has-destination interaction)
          [:div {:class (stl/css :property-row)}
           [:span  {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-destination")]
           [:div {:class (stl/css :select-wrapper)}
            [:& select {:class (stl/css :interaction-type-select)
                        :default-value (str (:destination interaction))
                        :options destination-options
                        :on-change change-destination}]]])

              ;; Preserve scroll
        (when (ctsi/has-preserve-scroll interaction)
          [:div {:class (stl/css :property-row)}
           [:div {:class (stl/css :checkbox-option)}
            [:label {:for (str "preserve-" index)
                     :class (stl/css-case  :global/checked preserve-scroll?)}
             [:span {:class (stl/css-case :global/checked preserve-scroll?)}
              (when preserve-scroll?
                deprecated-icon/status-tick)]
             (tr "workspace.options.interaction-preserve-scroll")
             [:input {:type "checkbox"
                      :id (str "preserve-" index)
                      :checked preserve-scroll?
                      :on-change change-preserve-scroll}]]]])

              ;; URL
        (when (ctsi/has-url interaction)
          [:div {:class (stl/css :property-row)}
           [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-url")]
           [:div {:class (stl/css :input-element-wrapper)}
            [:input {:class (stl/css :input-text)
                     :type "url"
                     :placeholder "http://example.com"
                     :default-value (str (:url interaction))
                     :on-blur change-url}]]])

        (when (ctsi/has-overlay-opts interaction)
          [:*
                 ;; Overlay position relative-to (select)
           [:div {:class (stl/css :property-row)}
            [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-relative-to")]
            [:div {:class (stl/css :select-wrapper)}
             [:& select {:class (stl/css :interaction-type-select)
                         :default-value  (str (:position-relative-to interaction))
                         :options relative-to-opts
                         :on-change change-position-relative-to}]]]

                 ;; Overlay position (select)
           [:div {:class (stl/css :property-row)}
            [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-position")]
            [:div {:class (stl/css :select-wrapper)}
             [:& select {:class (stl/css :interaction-type-select)
                         :default-value (:overlay-pos-type interaction)
                         :options overlay-position-opts
                         :on-change change-overlay-pos-type}]]]

                 ;; Overlay position (buttons)
           [:div {:class (stl/css-case :property-row true
                                       :big-row true)}
            [:div {:class (stl/css :position-btns-wrapper)}
             [:button {:class (stl/css-case :direction-btn true
                                            :center-btn true
                                            :active (= overlay-pos-type :center))
                       :data-value "center"
                       :on-click toggle-overlay-pos-type}
              corner-center-icon]
             [:button {:class (stl/css-case :direction-btn true
                                            :top-left-btn true
                                            :active (= overlay-pos-type :top-left))
                       :data-value "top-left"
                       :on-click toggle-overlay-pos-type}
              corner-topleft-icon]
             [:button {:class (stl/css-case :direction-btn true
                                            :top-right-btn true
                                            :active (= overlay-pos-type :top-right))
                       :data-value "top-right"
                       :on-click toggle-overlay-pos-type}
              corner-topright-icon]

             [:button {:class (stl/css-case :direction-btn true
                                            :top-center-btn true
                                            :active (= overlay-pos-type :top-center))
                       :data-value "top-center"
                       :on-click toggle-overlay-pos-type}
              corner-top-icon]

             [:button {:class (stl/css-case :direction-btn true
                                            :bottom-left-btn true
                                            :active (= overlay-pos-type :bottom-left))
                       :data-value "bottom-left"
                       :on-click toggle-overlay-pos-type}
              corner-bottomleft-icon]
             [:button {:class (stl/css-case :direction-btn true
                                            :bottom-right-btn true
                                            :active (= overlay-pos-type :bottom-right))
                       :data-value "bottom-right"
                       :on-click toggle-overlay-pos-type}
              corner-bottomright-icon]
             [:button {:class (stl/css-case :direction-btn true
                                            :bottom-center-btn true
                                            :active (= overlay-pos-type :bottom-center))
                       :data-value "bottom-center"
                       :on-click toggle-overlay-pos-type}
              corner-bottom-icon]]]

                 ;; Overlay click outside

           [:ul {:class (stl/css :property-list)}
            [:li {:class (stl/css :property-row)}
             [:div {:class (stl/css :checkbox-option)}
              [:label {:for (str "close-" index)
                       :class (stl/css-case  :global/checked close-click-outside?)}
               [:span {:class (stl/css-case :global/checked close-click-outside?)}
                (when close-click-outside?
                  deprecated-icon/status-tick)]
               (tr "workspace.options.interaction-close-outside")
               [:input {:type "checkbox"
                        :id (str "close-" index)
                        :checked close-click-outside?
                        :on-change change-close-click-outside}]]]]

                ;; Overlay background
            [:li {:class (stl/css :property-row)}
             [:div {:class (stl/css :checkbox-option)}
              [:label {:for (str "background-" index)
                       :class (stl/css-case  :global/checked background-overlay?)}
               [:span {:class (stl/css-case :global/checked background-overlay?)}
                (when background-overlay?
                  deprecated-icon/status-tick)]
               (tr "workspace.options.interaction-background")
               [:input {:type "checkbox"
                        :id (str "background-" index)
                        :checked background-overlay?
                        :on-change change-background-overlay}]]]]]])

        (when (ctsi/has-animation? interaction)
          [:*
                 ;; Animation select
           [:div {:class (stl/css :property-row)}
            [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-animation")]
            [:div {:class (stl/css :select-wrapper)}
             [:& select {:class (stl/css :animation-select)
                         :default-value (or (-> interaction :animation :animation-type) "")
                         :options animation-opts
                         :on-change change-animation-type}]]]

           ;; Direction
           (when (ctsi/has-way? interaction)
             [:div {:class (stl/css :property-row)}
              [:div {:class (stl/css :inputs-wrapper)}

               [:& radio-buttons {:selected (d/name way)
                                  :on-change change-way
                                  :name "animation-way"}
                [:& radio-button {:value "in"
                                  :id "animation-way-in"}]
                [:& radio-button {:id "animation-way-out"
                                  :value "out"}]]]])

           ;; Direction
           (when (ctsi/has-direction? interaction)
             [:div {:class (stl/css :property-row)}
              [:div {:class (stl/css :buttons-wrapper)}
               [:& radio-buttons {:selected (d/name direction)
                                  :on-change change-direction
                                  :name "animation-direction"}
                [:& radio-button {:icon deprecated-icon/column
                                  :icon-class (stl/css :right)
                                  :value "right"
                                  :id "animation-right"}]
                [:& radio-button {:icon deprecated-icon/column
                                  :icon-class (stl/css :left)
                                  :id "animation-left"
                                  :value "left"}]
                [:& radio-button {:icon deprecated-icon/column
                                  :icon-class (stl/css :down)
                                  :id "animation-down"
                                  :value "down"}]
                [:& radio-button {:icon deprecated-icon/column
                                  :icon-class (stl/css :up)
                                  :id "animation-up"
                                  :value "up"}]]]])

           ;; Duration
           (when (ctsi/has-duration? interaction)
             [:div {:class (stl/css :property-row)}
              [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-duration")]
              [:div {:class (stl/css :input-element-wrapper)
                     :title (tr "workspace.options.interaction-ms")}
               [:span {:class (stl/css :after)}
                (tr "workspace.options.interaction-ms")]
               [:> numeric-input* {:ref ext-duration-ref
                                   :on-change change-duration
                                   :value (-> interaction :animation :duration)
                                   :title (tr "workspace.options.interaction-ms")}]]])

           ;; Easing
           (when (ctsi/has-easing? interaction)
             [:div {:class (stl/css :property-row)}
              [:span {:class (stl/css :interaction-name)} (tr "workspace.options.interaction-easing")]
              [:div {:class (stl/css :select-wrapper)}
               [:& select {:class (stl/css :easing-select)
                           :dropdown-class (stl/css :dropdown-upwards)
                           :default-value (-> interaction :animation :easing)
                           :options easing-options
                           :on-change change-easing}]]])

           ;; Offset effect
           (when (ctsi/has-offset-effect? interaction)
             [:div {:class (stl/css :property-row)}
              [:div {:class (stl/css :checkbox-option)}
               [:label {:for (str "offset-effect-" index)
                        :class (stl/css-case  :global/checked (-> interaction :animation :offset-effect))}
                [:span {:class (stl/css-case :global/checked (-> interaction :animation :offset-effect))}
                 (when (-> interaction :animation :offset-effect)
                   deprecated-icon/status-tick)]
                (tr "workspace.options.interaction-offset-effect")
                [:input {:type "checkbox"
                         :id (str "offset-effect-" index)
                         :checked (-> interaction :animation :offset-effect)
                         :on-change change-offset-effect}]]]])])])]))

(mf/defc interactions-menu
  {::mf/props :obj}
  [{:keys [shape]}]
  (let [interactions
        (get shape :interactions [])

        flows (mf/deref refs/workspace-page-flows)

        add-interaction
        (fn []
          (st/emit! (dwi/add-new-interaction shape)))

        remove-interaction
        (fn [index]
          (st/emit! (dwi/remove-interaction shape index)))

        update-interaction
        (fn [index update-fn]
          (st/emit! (dwi/update-interaction shape index update-fn)))]
    [:div {:class (stl/css :interactions-content)}
     (if shape
       [:& shape-flows {:flows flows
                        :shape shape}]
       [:& page-flows {:flows flows}])
     [:div {:class (stl/css :interaction-options)}
      (when (and shape (not (cfh/unframed-shape? shape)))
        [:div {:class (stl/css :element-title)}
         [:> title-bar* {:collapsable false
                         :title       (tr "workspace.options.interactions")
                         :class       (stl/css :title-spacing-layout-interactions)}

          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.options.interactions.add-interaction")
                            :on-click add-interaction
                            :icon i/add}]]])

      (when (= (count interactions) 0)
        [:div {:class (stl/css :help-content)}
         (when (and shape (not (cfh/unframed-shape? shape)))
           [:div {:class (stl/css :help-group)}
            [:div {:class (stl/css :interactions-help-icon)} deprecated-icon/add]
            [:div {:class (stl/css :interactions-help)}
             (tr "workspace.options.add-interaction")]])
         [:div {:class (stl/css :help-group)}
          [:div {:class (stl/css :interactions-help-icon)} deprecated-icon/interaction]
          [:div {:class (stl/css :interactions-help)}
           (tr "workspace.options.select-a-shape")]]
         [:div {:class (stl/css :help-group)}
          [:div {:class (stl/css :interactions-help-icon)} deprecated-icon/play]
          [:div {:class (stl/css :interactions-help)}
           (tr "workspace.options.use-play-button")]]])
      [:div {:class (stl/css :groups)}
       (for [[index interaction] (d/enumerate interactions)]
         [:& interaction-entry {:key (dm/str (:id shape) "-" index)
                                :index index
                                :shape shape
                                :interaction interaction
                                :update-interaction update-interaction
                                :remove-interaction remove-interaction}])]]]))

