;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.interactions
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.types.interactions :as cti]
   [app.common.types.page-options :as cto]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(defn- event-type-names
  []
  {:click (tr "workspace.options.interaction-on-click")
   ; TODO: need more UX research
   ;; :mouse-over (tr "workspace.options.interaction-while-hovering")
   ;; :mouse-press (tr "workspace.options.interaction-while-pressing")
   :mouse-enter (tr "workspace.options.interaction-mouse-enter")
   :mouse-leave (tr "workspace.options.interaction-mouse-leave")
   :after-delay (tr "workspace.options.interaction-after-delay")})

(defn- event-type-name
  [interaction]
  (get (event-type-names) (:event-type interaction) "--"))

(defn- action-type-names
  []
  {:navigate (tr "workspace.options.interaction-navigate-to")
   :open-overlay (tr "workspace.options.interaction-open-overlay")
   :toggle-overlay (tr "workspace.options.interaction-toggle-overlay")
   :close-overlay (tr "workspace.options.interaction-close-overlay")
   :prev-screen (tr "workspace.options.interaction-prev-screen")
   :open-url (tr "workspace.options.interaction-open-url")})

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

(defn- overlay-pos-type-names
  []
  {:manual (tr "workspace.options.interaction-pos-manual")
   :center (tr "workspace.options.interaction-pos-center")
   :top-left (tr "workspace.options.interaction-pos-top-left")
   :top-right (tr "workspace.options.interaction-pos-top-right")
   :top-center (tr "workspace.options.interaction-pos-top-center")
   :bottom-left (tr "workspace.options.interaction-pos-bottom-left")
   :bottom-right (tr "workspace.options.interaction-pos-bottom-right")
   :bottom-center (tr "workspace.options.interaction-pos-bottom-center")})

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
                            name       (dom/get-value name-input)]
                        (reset! editing? false)
                        (st/emit! (dwi/end-rename-flow)
                                  (when-not (str/empty? name)
                                    (dwi/rename-flow (:id flow) name)))))

        cancel-edit (fn []
                      (reset! editing? false)
                      (st/emit! (dwi/end-rename-flow)))

        on-key-down (fn [event]
                      (when (kbd/enter? event) (accept-edit))
                      (when (kbd/esc? event) (cancel-edit)))]

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

    [:div.flow-element
     [:div.flow-button {:on-click (st/emitf (dw/select-shape (:starting-frame flow)))}
      i/play]
     (if @editing?
       [:input.element-name
        {:type "text"
         :ref name-ref
         :on-blur accept-edit
         :on-key-down on-key-down
         :auto-focus true
         :default-value (:name flow "")}]
       [:span.element-label.flow-name
        {:on-double-click (st/emitf (dwi/start-rename-flow (:id flow)))}
        (:name flow)])
     [:div.add-page {:on-click (st/emitf (dwi/remove-flow (:id flow)))}
      i/minus]]))

(mf/defc page-flows
  [{:keys [flows]}]
  (when (seq flows)
    [:div.element-set.interactions-options
     [:div.element-set-title
      [:span (tr "workspace.options.flows.flow-starts")]]
     (for [flow flows]
       [:& flow-item {:flow flow :key (str (:id flow))}])]))

(mf/defc shape-flows
  [{:keys [flows shape]}]
  (when (= (:type shape) :frame)
    (let [flow (cto/get-frame-flow flows (:id shape))]
      [:div.element-set.interactions-options
       [:div.element-set-title
        [:span (tr "workspace.options.flows.flow-start")]]
       (if (nil? flow)
         [:div.flow-element
          [:span.element-label (tr "workspace.options.flows.add-flow-start")]
          [:div.add-page {:on-click (st/emitf (dwi/add-flow-selected-frame))}
           i/plus]]
         [:& flow-item {:flow flow :key (str (:id flow))}])])))

(mf/defc interaction-entry
  [{:keys [index shape interaction update-interaction remove-interaction]}]
  (let [objects     (deref refs/workspace-page-objects)
        destination (get objects (:destination interaction))
        frames      (mf/use-memo (mf/deps objects)
                                 #(cp/select-frames objects))

        overlay-pos-type     (:overlay-pos-type interaction)
        close-click-outside? (:close-click-outside interaction false)
        background-overlay?  (:background-overlay interaction false)
        preserve-scroll?     (:preserve-scroll interaction false)

        extended-open? (mf/use-state false)

        ext-delay-ref (mf/use-ref nil)

        select-text
        (fn [ref] (fn [_] (dom/select-text! (mf/ref-val ref))))

        change-event-type
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value d/read-string)]
            (update-interaction index #(cti/set-event-type % value shape))))

        change-action-type
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value d/read-string)]
            (update-interaction index #(cti/set-action-type % value))))

        change-delay
        (fn [value]
          (update-interaction index #(cti/set-delay % value)))

        change-destination
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value)
                value (when (not= value "") (uuid/uuid value))]
            (update-interaction index #(cti/set-destination % value))))

        change-preserve-scroll
        (fn [event]
          (let [value (-> event dom/get-target dom/checked?)]
            (update-interaction index #(cti/set-preserve-scroll % value))))

        change-url
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
                (update-interaction index #(cti/set-url % value)))
              (dom/add-class! target "error"))))

        change-overlay-pos-type
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value d/read-string)]
            (update-interaction index #(cti/set-overlay-pos-type % value shape objects))))

        toggle-overlay-pos-type
        (fn [pos-type]
          (update-interaction index #(cti/toggle-overlay-pos-type % pos-type shape objects)))

        change-close-click-outside
        (fn [event]
          (let [value (-> event dom/get-target dom/checked?)]
            (update-interaction index #(cti/set-close-click-outside % value))))

        change-background-overlay
        (fn [event]
          (let [value (-> event dom/get-target dom/checked?)]
            (update-interaction index #(cti/set-background-overlay % value))))]

    [:*
     [:div.element-set-options-group {:class (dom/classnames
                                               :open @extended-open?)}

      ; Summary
      [:div.element-set-actions-button {:on-click #(swap! extended-open? not)}
       i/actions]
      [:div.interactions-summary {:on-click #(swap! extended-open? not)}
       [:div.trigger-name (event-type-name interaction)]
       [:div.action-summary (action-summary interaction destination)]]
      [:div.element-set-actions {:on-click #(remove-interaction index)}
       [:div.element-set-actions-button i/minus]]

      (when @extended-open?
        [:div.element-set-content

         ; Trigger select
         [:div.interactions-element.separator
          [:span.element-set-subtitle.wide (tr "workspace.options.interaction-trigger")]
          [:select.input-select
           {:value (str (:event-type interaction))
            :on-change change-event-type}
           (for [[value name] (event-type-names)]
             (when-not (and (= value :after-delay)
                            (not= (:type shape) :frame))
               [:option {:value (str value)} name]))]]

         ; Delay
         (when (cti/has-delay interaction)
           [:div.interactions-element
            [:span.element-set-subtitle.wide (tr "workspace.options.interaction-delay")]
            [:div.input-element {:title (tr "workspace.options.interaction-ms")}
             [:> numeric-input {:ref ext-delay-ref
                                :on-click (select-text ext-delay-ref)
                                :on-change change-delay
                                :value (:delay interaction)
                                :title (tr "workspace.options.interaction-ms")}]
             [:span.after (tr "workspace.options.interaction-ms")]]])

         ; Action select
         [:div.interactions-element.separator
          [:span.element-set-subtitle.wide (tr "workspace.options.interaction-action")]
          [:select.input-select
           {:value (str (:action-type interaction))
            :on-change change-action-type}
           (for [[value name] (action-type-names)]
             [:option {:value (str value)} name])]]

         ; Destination
         (when (cti/has-destination interaction)
           [:div.interactions-element
            [:span.element-set-subtitle.wide (tr "workspace.options.interaction-destination")]
            [:select.input-select
             {:value (str (:destination interaction))
              :on-change change-destination}
             (if (= (:action-type interaction) :close-overlay)
               [:option {:value ""} (tr "workspace.options.interaction-self")]
               [:option {:value ""} (tr "workspace.options.interaction-none")])
             (for [frame frames]
               (when (and (not= (:id frame) (:id shape)) ; A frame cannot navigate to itself
                          (not= (:id frame) (:frame-id shape))) ; nor a shape to its container frame
                 [:option {:value (str (:id frame))} (:name frame)]))]])

         ; Preserve scroll
         (when (cti/has-preserve-scroll interaction)
           [:div.interactions-element
            [:div.input-checkbox
             [:input {:type "checkbox"
                      :id (str "preserve-" index)
                      :checked preserve-scroll?
                      :on-change change-preserve-scroll}]
             [:label {:for (str "preserve-" index)}
              (tr "workspace.options.interaction-preserve-scroll")]]])

         ; URL
         (when (cti/has-url interaction)
           [:div.interactions-element
            [:span.element-set-subtitle.wide (tr "workspace.options.interaction-url")]
            [:input.input-text {:type "url"
                                :placeholder "http://example.com"
                                :default-value (str (:url interaction))
                                :on-blur change-url}]])

         (when (cti/has-overlay-opts interaction)
           [:*
            ; Overlay position (select)
            [:div.interactions-element
             [:span.element-set-subtitle.wide (tr "workspace.options.interaction-position")]
             [:select.input-select
              {:value (str (:overlay-pos-type interaction))
               :on-change change-overlay-pos-type}
              (for [[value name] (overlay-pos-type-names)]
                [:option {:value (str value)} name])]]

            ; Overlay position (buttons)
            [:div.interactions-element.interactions-pos-buttons
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :center))
               :on-click #(toggle-overlay-pos-type :center)}
              i/position-center]
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :top-left))
               :on-click #(toggle-overlay-pos-type :top-left)}
              i/position-top-left]
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :top-right))
               :on-click #(toggle-overlay-pos-type :top-right)}
              i/position-top-right]
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :top-center))
               :on-click #(toggle-overlay-pos-type :top-center)}
              i/position-top-center]
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :bottom-left))
               :on-click #(toggle-overlay-pos-type :bottom-left)}
              i/position-bottom-left]
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :bottom-right))
               :on-click #(toggle-overlay-pos-type :bottom-right)}
              i/position-bottom-right]
             [:div.element-set-actions-button
              {:class (dom/classnames :active (= overlay-pos-type :bottom-center))
               :on-click #(toggle-overlay-pos-type :bottom-center)}
              i/position-bottom-center]]

            ; Overlay click outside
            [:div.interactions-element
             [:div.input-checkbox
              [:input {:type "checkbox"
                       :id (str "close-" index)
                       :checked close-click-outside?
                       :on-change change-close-click-outside}]
              [:label {:for (str "close-" index)}
               (tr "workspace.options.interaction-close-outside")]]]

            ; Overlay background
            [:div.interactions-element
             [:div.input-checkbox
              [:input {:type "checkbox"
                       :id (str "background-" index)
                       :checked background-overlay?
                       :on-change change-background-overlay}]
              [:label {:for (str "background-" index)}
               (tr "workspace.options.interaction-background")]]]])])]]))

(mf/defc interactions-menu
  [{:keys [shape] :as props}]
  (let [interactions (get shape :interactions [])

        options (mf/deref refs/workspace-page-options)
        flows   (:flows options)

        add-interaction
        (fn []
          (st/emit! (dwi/add-new-interaction shape)))

        remove-interaction
        (fn [index]
          (st/emit! (dwi/remove-interaction shape index)))

        update-interaction
        (fn [index update-fn]
          (st/emit! (dwi/update-interaction shape index update-fn)))]
    [:*
     (if shape
       [:& shape-flows {:flows flows
                        :shape shape}]
       [:& page-flows {:flows flows}])

     [:div.element-set.interactions-options
      (when (and shape (not (cp/unframed-shape? shape)))
        [:div.element-set-title
         [:span (tr "workspace.options.interactions")]
         [:div.add-page {:on-click add-interaction}
          i/plus]])
      [:div.element-set-content
       (when (= (count interactions) 0)
         [:*
          (when (and shape (not (cp/unframed-shape? shape)))
            [:*
             [:div.interactions-help-icon i/plus]
             [:div.interactions-help.separator (tr "workspace.options.add-interaction")]])
          [:div.interactions-help-icon i/interaction]
          [:div.interactions-help (tr "workspace.options.select-a-shape")]
          [:div.interactions-help-icon i/play]
          [:div.interactions-help (tr "workspace.options.use-play-button")]])]
      [:div.groups
       (for [[index interaction] (d/enumerate interactions)]
         [:& interaction-entry {:index index
                                :shape shape
                                :interaction interaction
                                :update-interaction update-interaction
                                :remove-interaction remove-interaction}])]]]))

