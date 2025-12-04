;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.interactions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.page :as ctp]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- event-type-names
  []
  {:click       (tr "workspace.options.interaction-on-click")
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
    :navigate       (tr "workspace.options.interaction-navigate-to-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :open-overlay   (tr "workspace.options.interaction-open-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :toggle-overlay (tr "workspace.options.interaction-toggle-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :close-overlay  (tr "workspace.options.interaction-close-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-self")))
    :prev-screen    (tr "workspace.options.interaction-prev-screen")
    :open-url       (tr "workspace.options.interaction-open-url")
    "--"))

(defn- get-frames-options
  [frames shape]
  (->> frames
       (filter #(and (not= (:id %) (:id shape)) ; A frame cannot navigate to itself
                     (not= (:id %) (:frame-id shape)))) ; nor a shape to its container frame
       (map (fn [frame]
              {:value (str (:id frame))
               :label (:name frame)}))))

(defn- get-shared-frames-options
  [shared-frames]
  (map (fn [frame]
         {:value (str (:id frame))
          :label (:name frame)}) shared-frames))

(mf/defc prototype-pill*
  [{:keys [title description on-change is-editable
           left-button-icon-id left-button-tooltip on-left-button-click is-left-button-active
           right-button-icon-id right-button-tooltip on-right-button-click is-right-button-active]} external-ref]
  (let [local-ref (mf/use-ref)
        ref       (or external-ref local-ref)

        handle-focus
        (mf/use-fn
         (fn []
           (let [input-node (mf/ref-val ref)]
             (dom/select-text! input-node))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [input-node (mf/ref-val ref)
                 target     (dom/get-target event)
                 value      (-> target dom/get-value str/trim)]
             (when (or (kbd/esc? event) (kbd/enter? event))
               (dom/blur! input-node)
               (on-change value)))))

        handle-blur
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)
                 value  (-> target dom/get-value str/trim)]
             (on-change value))))]

    [:div {:class (stl/css-case :prototype-pill true
                                :double (some? description))}
     [:> icon-button* {:variant "secondary"
                       :class (stl/css :prototype-pill-button :left)
                       :aria-pressed is-left-button-active
                       :icon left-button-icon-id
                       :aria-label left-button-tooltip
                       :on-click on-left-button-click}]

     [:div {:class (stl/css :prototype-pill-main)}
      (if is-editable
        [:input {:type "text"
                 :class (stl/css :prototype-pill-input)
                 :ref ref
                 :default-value title
                 :on-focus handle-focus
                 :on-key-down handle-key-down
                 :on-blur handle-blur}]

        [:div {:class (stl/css :prototype-pill-center)}
         [:div {:class (stl/css :prototype-pill-info)}
          [:div {:class (stl/css :prototype-pill-name)} title]
          [:div {:class (stl/css :prototype-pill-description)} description]]])

      [:> icon-button* {:variant "secondary"
                        :class (stl/css :prototype-pill-button :right)
                        :aria-pressed is-right-button-active
                        :icon right-button-icon-id
                        :aria-label right-button-tooltip
                        :on-click on-right-button-click}]]]))

(mf/defc flow-item*
  [{:keys [flow]}]
  (let [start-flow
        (mf/use-fn
         (mf/deps flow)
         #(st/emit! (dcm/go-to-viewer {:section "interactions"
                                       :frame-id (:starting-frame flow)})))

        rename-flow
        (mf/use-fn
         (mf/deps flow)
         (fn [value]
           (when-not (str/empty? value)
             (st/emit! (dwi/rename-flow (:id flow) value)))))

        remove-flow
        (mf/use-fn
         (mf/deps flow)
         #(st/emit! (dwi/remove-flow (:id flow))))]

    [:> prototype-pill* {:title (:name flow "")
                         :is-editable true
                         :on-change rename-flow
                         :left-button-icon-id i/play
                         :left-button-tooltip (tr "workspace.options.flows.flow-start")
                         :on-left-button-click start-flow
                         :right-button-icon-id i/remove
                         :right-button-tooltip (tr "labels.remove")
                         :on-right-button-click remove-flow}]))

(mf/defc interaction-item*
  [{:keys [index shape interaction update-interaction remove-interaction]}]
  (let [objects              (deref refs/workspace-page-objects)
        destination          (get objects (:destination interaction))

        frames               (mf/with-memo [objects]
                               (ctt/get-viewer-frames objects {:all-frames? true}))
        shape-parent-ids     (mf/with-memo [objects]
                               (cfh/get-parent-ids objects (:id shape)))
        shape-parents        (mf/with-memo [frames shape]
                               (filter (comp (set shape-parent-ids) :id) frames))

        overlay-pos-type     (:overlay-pos-type interaction)
        close-click-outside? (:close-click-outside interaction false)
        background-overlay?  (:background-overlay interaction false)
        preserve-scroll?     (:preserve-scroll interaction false)

        way                  (-> interaction :animation :way)
        direction            (-> interaction :animation :direction)

        open-extended*       (mf/use-state false)
        open-extended?       (deref open-extended*)

        ext-delay-ref        (mf/use-ref nil)
        ext-duration-ref     (mf/use-ref nil)

        toggle-extended
        (mf/use-fn
         #(swap! open-extended* not))

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


        event-type-options   (-> [{:value :click       :label (tr "workspace.options.interaction-on-click")}
                                  ;; TODO: need more UX research
                                  ;; :mouse-over (tr "workspace.options.interaction-while-hovering")
                                  ;; :mouse-press (tr "workspace.options.interaction-while-pressing")
                                  {:value :mouse-enter :label (tr "workspace.options.interaction-mouse-enter")}
                                  {:value :mouse-leave :label (tr "workspace.options.interaction-mouse-leave")}]
                                 (cond-> (cfh/frame-shape? shape)
                                   (conj {:value :after-delay :label (tr "workspace.options.interaction-after-delay")})))

        action-type-options [{:value :navigate       :label (tr "workspace.options.interaction-navigate-to")}
                             {:value :open-overlay   :label (tr "workspace.options.interaction-open-overlay")}
                             {:value :toggle-overlay :label (tr "workspace.options.interaction-toggle-overlay")}
                             {:value :close-overlay  :label (tr "workspace.options.interaction-close-overlay")}
                             {:value :prev-screen    :label (tr "workspace.options.interaction-prev-screen")}
                             {:value :open-url       :label (tr "workspace.options.interaction-open-url")}]

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

        overlay-position-opts [{:value :manual        :label (tr "workspace.options.interaction-pos-manual")}
                               {:value :center        :label (tr "workspace.options.interaction-pos-center")}
                               {:value :top-left      :label (tr "workspace.options.interaction-pos-top-left")}
                               {:value :top-right     :label (tr "workspace.options.interaction-pos-top-right")}
                               {:value :top-center    :label (tr "workspace.options.interaction-pos-top-center")}
                               {:value :bottom-left   :label (tr "workspace.options.interaction-pos-bottom-left")}
                               {:value :bottom-right  :label (tr "workspace.options.interaction-pos-bottom-right")}
                               {:value :bottom-center :label (tr "workspace.options.interaction-pos-bottom-center")}]

        basic-animation-opts [{:value ""        :label (tr "workspace.options.interaction-animation-none")}
                              {:value :dissolve :label (tr "workspace.options.interaction-animation-dissolve")}
                              {:value :slide    :label (tr "workspace.options.interaction-animation-slide")}]

        animation-opts
        (mf/with-memo [basic-animation-opts]
          (if (ctsi/allow-push? (:action-type interaction))
            (d/concat-vec basic-animation-opts [{:value :push :label (tr "workspace.options.interaction-animation-push")}])
            basic-animation-opts))

        easing-options [{:icon :easing-linear      :value :linear      :label (tr "workspace.options.interaction-easing-linear")}
                        {:icon :easing-ease        :value :ease        :label (tr "workspace.options.interaction-easing-ease")}
                        {:icon :easing-ease-in     :value :ease-in     :label (tr "workspace.options.interaction-easing-ease-in")}
                        {:icon :easing-ease-out    :value :ease-out    :label (tr "workspace.options.interaction-easing-ease-out")}
                        {:icon :easing-ease-in-out :value :ease-in-out :label (tr "workspace.options.interaction-easing-ease-in-out")}]]

    [:div {:class (stl/css :interaction-item)}
     [:> prototype-pill* {:title (event-type-name interaction)
                          :description (action-summary interaction destination)
                          :left-button-icon-id i/hsva
                          :left-button-tooltip (tr "labels.options")
                          :is-left-button-active open-extended?
                          :on-left-button-click toggle-extended
                          :right-button-icon-id i/remove
                          :right-button-tooltip (tr "labels.remove")
                          :on-right-button-click #(remove-interaction index)}]

     (when open-extended?
       [:*
        ;; Trigger select
        [:div {:class (stl/css :interaction-row)}
         [:label {:class (stl/css :interaction-row-label)}
          [:div {:class (stl/css :interaction-row-name)}
           (tr "workspace.options.interaction-trigger")]]
         [:div {:class (stl/css :interaction-row-select)}
          [:& select {:default-value (:event-type interaction)
                      :options event-type-options
                      :on-change change-event-type}]]]

        ;; Delay
        (when (ctsi/has-delay interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-delay")]]
           [:div {:class (stl/css :interaction-row-input)}
            [:> numeric-input* {:ref ext-delay-ref
                                :icon i/character-m
                                :property (tr "workspace.options.interaction-ms")
                                :on-change change-delay
                                :value (:delay interaction)}]]])

        ;; Action select
        [:div {:class (stl/css :interaction-row)}
         [:div {:class (stl/css :interaction-row-label)}
          [:div {:class (stl/css :interaction-row-name)}
           (tr "workspace.options.interaction-action")]]
         [:div {:class (stl/css :interaction-row-select)}
          [:& select {:default-value (:action-type interaction)
                      :options action-type-options
                      :on-change change-action-type}]]]

        ;; Destination
        (when (ctsi/has-destination interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-destination")]]
           [:div {:class (stl/css :interaction-row-select)}
            [:& select {:default-value (str (:destination interaction))
                        :options destination-options
                        :on-change change-destination}]]])

        ;; Preserve scroll
        (when (ctsi/has-preserve-scroll interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-checkbox)}
            [:> checkbox* {:id (str "preserve-" index)
                           :label (tr "workspace.options.interaction-preserve-scroll")
                           :checked preserve-scroll?
                           :on-change change-preserve-scroll}]]])

        ;; URL
        (when (ctsi/has-url interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-url")]]
           [:div {:class (stl/css :interaction-row-input)}
            [:> input* {:type "url"
                        :placeholder "http://example.com"
                        :default-value (:url interaction)
                        :on-blur change-url}]]])

        (when (ctsi/has-overlay-opts interaction)
          [:*
           ;; Overlay position relative-to (select)
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-relative-to")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value  (str (:position-relative-to interaction))
                         :options relative-to-opts
                         :on-change change-position-relative-to}]]]

           ;; Overlay position (select)
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-position")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value (:overlay-pos-type interaction)
                         :options overlay-position-opts
                         :on-change change-overlay-pos-type}]]]

           ;; Overlay position (buttons)
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-position)}
             [:div {:class (stl/css :center)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :center)
                                :data-value "center"
                                :icon i/corner-center
                                :aria-label (tr "workspace.options.interaction-pos-center")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :top-left)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :top-left)
                                :data-value "top-left"
                                :icon i/corner-top-left
                                :aria-label (tr "workspace.options.interaction-pos-top-left")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :top-right)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :top-right)
                                :data-value "top-right"
                                :icon i/corner-top-right
                                :aria-label (tr "workspace.options.interaction-pos-top-right")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :top-center)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :top-center)
                                :data-value "top-center"
                                :icon i/corner-top
                                :aria-label (tr "workspace.options.interaction-pos-top-center")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :bottom-left)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :bottom-left)
                                :data-value "bottom-left"
                                :icon i/corner-bottom-left
                                :aria-label (tr "workspace.options.interaction-pos-bottom-left")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :bottom-right)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :bottom-right)
                                :data-value "bottom-right"
                                :icon i/corner-bottom-right
                                :aria-label (tr "workspace.options.interaction-pos-bottom-right")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :bottom-center)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :bottom-center)
                                :data-value "bottom-center"
                                :icon i/corner-bottom
                                :aria-label (tr "workspace.options.interaction-pos-bottom-center")
                                :on-click toggle-overlay-pos-type}]]]]

           ;; Overlay click outside
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-checkbox)}
             [:> checkbox* {:id (str "close-" index)
                            :label (tr "workspace.options.interaction-close-outside")
                            :checked close-click-outside?
                            :on-change change-close-click-outside}]]]

           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-checkbox)}
             [:> checkbox* {:id (str "background-" index)
                            :label (tr "workspace.options.interaction-background")
                            :checked background-overlay?
                            :on-change change-background-overlay}]]]])

        (when (ctsi/has-animation? interaction)
          [:*
           ;; Animation select
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-animation")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:class (stl/css :animation-select)
                         :default-value (or (-> interaction :animation :animation-type) "")
                         :options animation-opts
                         :on-change change-animation-type}]]]

           ;; Direction
           (when (ctsi/has-way? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-radio)}
               [:& radio-buttons {:selected (d/name way)
                                  :on-change change-way
                                  :name "animation-way"}
                [:& radio-button {:value "in"
                                  :id "animation-way-in"}]
                [:& radio-button {:id "animation-way-out"
                                  :value "out"}]]]])

           ;; Direction
           (when (ctsi/has-direction? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-radio)}
               [:& radio-buttons {:selected (d/name direction)
                                  :on-change change-direction
                                  :name "animation-direction"}
                [:& radio-button {:icon i/row
                                  :icon-class (stl/css :right)
                                  :value "right"
                                  :id "animation-right"}]
                [:& radio-button {:icon i/row-reverse
                                  :icon-class (stl/css :left)
                                  :id "animation-left"
                                  :value "left"}]
                [:& radio-button {:icon i/column
                                  :icon-class (stl/css :down)
                                  :id "animation-down"
                                  :value "down"}]
                [:& radio-button {:icon i/column-reverse
                                  :icon-class (stl/css :up)
                                  :id "animation-up"
                                  :value "up"}]]]])

           ;; Duration
           (when (ctsi/has-duration? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-duration")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:> numeric-input* {:ref ext-duration-ref
                                   :icon i/character-m
                                   :property (tr "workspace.options.interaction-ms")
                                   :on-change change-duration
                                   :value (-> interaction :animation :duration)}]]])

           ;; Easing
           (when (ctsi/has-easing? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-easing")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:& select {:class (stl/css :easing-select)
                           :dropdown-class (stl/css :dropdown-upwards)
                           :default-value (-> interaction :animation :easing)
                           :options easing-options
                           :on-change change-easing}]]])

           ;; Offset effect
           (when (ctsi/has-offset-effect? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-checkbox)}
               [:> checkbox* {:id (str "offset-effect-" index)
                              :label (tr "workspace.options.interaction-offset-effect")
                              :checked (-> interaction :animation :offset-effect)
                              :on-change change-offset-effect}]]])])])]))

(mf/defc page-flows*
  [{:keys [flows]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (> (count flows) 0)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.flows.flow-starts")
                      :class        (stl/css :title-bar)}]]
     (when show-content?
       [:div {:class (stl/css :content)}
        (for [[id flow] flows]
          [:> flow-item* {:key id
                          :flow flow}])])]))

(mf/defc shape-flows*
  [{:keys [flows shape]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        flow          (ctp/get-frame-flow flows (:id shape))

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))

        add-flow
        (mf/use-fn
         #(st/emit! (dwi/add-flow-selected-frame)))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (some? flow)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.flows.flow")
                      :class        (stl/css :title-bar)}
       (when (nil? flow)
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.flows.add-flow-start")
                           :on-click add-flow
                           :icon i/add}])]]
     (when (and show-content? (some? flow))
       [:div {:class (stl/css :content)}
        [:> flow-item* {:key (:id flow)
                        :flow flow}]])]))

(mf/defc interactions*
  [{:keys [interactions shape]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))

        add-interaction
        (mf/use-fn
         (mf/deps shape)
         #(st/emit! (dwi/add-new-interaction shape)))

        remove-interaction
        (mf/use-fn
         (mf/deps shape)
         (fn [index]
           (st/emit! (dwi/remove-interaction shape index))))

        update-interaction
        (mf/use-fn
         (mf/deps shape)
         (fn [index update-fn]
           (st/emit! (dwi/update-interaction shape index update-fn))))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (> (count interactions) 0)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.interactions")
                      :class        (stl/css :title-bar)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.interactions.add-interaction")
                         :on-click add-interaction
                         :icon i/add}]]]

     (when show-content?
       [:div {:class (stl/css :content :content-interactions)}
        (for [[index interaction] (d/enumerate interactions)]
          [:> interaction-item* {:key (str (:id shape) "-" index)
                                 :index index
                                 :shape shape
                                 :interaction interaction
                                 :update-interaction update-interaction
                                 :remove-interaction remove-interaction}])])]))

(mf/defc interactions-menu*
  [{:keys [shape]}]
  (let [interactions  (get shape :interactions [])
        flows         (mf/deref refs/workspace-page-flows)
        framed-shape? (and shape (not (cfh/unframed-shape? shape)))]

    [:div {:class (stl/css :wrapper)}
     (if shape
       (when (cfh/frame-shape? shape)
         [:> shape-flows* {:flows flows
                           :shape shape}])
       (when flows
         [:> page-flows* {:flows flows}]))

     (when framed-shape?
       [:> interactions* {:interactions interactions
                          :shape shape}])

     (when (= (count interactions) 0)
       [:div {:class (stl/css :section)}
        [:div {:class (stl/css :content)}
         [:div {:class (stl/css :empty)}
          (when framed-shape?
            [:> empty-state* {:icon i/add
                              :text (tr "workspace.options.add-interaction")}])
          [:> empty-state* {:icon i/interaction
                            :text (tr "workspace.options.select-a-shape")}]
          [:> empty-state* {:icon i/play
                            :text (tr "workspace.options.use-play-button")}]]]])]))
