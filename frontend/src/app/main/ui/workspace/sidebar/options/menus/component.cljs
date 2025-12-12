;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.component
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.specialized-panel :as dwsp]
   [app.main.data.workspace.variants :as dwv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.reorder-handler :refer [reorder-handler*]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.product.input-with-meta :refer [input-with-meta*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.options.menus.variants-help-modal]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:annotations-state
  (l/derived :workspace-annotations st/state))

(mf/defc component-annotation*
  {::mf/private true}
  [{:keys [id shape component rerender-fn]}]
  (let [main-instance? (:main-instance shape)
        component-id   (:component-id shape)
        annotation     (:annotation component)
        shape-id       (:id shape)

        editing*       (mf/use-state false)
        editing?       (deref editing*)

        invalid-text*  (mf/use-state #(str/blank? annotation))
        invalid-text?  (deref invalid-text*)

        size*          (mf/use-state #(count annotation))
        size           (deref size*)

        textarea-ref   (mf/use-ref)

        state          (mf/deref ref:annotations-state)

        expanded?      (:expanded state)
        create-id      (:id-for-create state)
        creating?      (= id create-id)

        ;; hack to create an autogrowing textarea based on
        ;; https://css-tricks.com/the-cleanest-trick-for-autogrowing-textareas/
        adjust-textarea-size
        (mf/use-fn
         #(when-let [textarea (mf/ref-val textarea-ref)]
            (let [text (dom/get-value textarea)]
              (reset! invalid-text* (str/blank? text))
              (reset! size* (count text))
              (let [^js parent  (.-parentNode textarea)
                    ^js dataset (.-dataset parent)]
                (set! (.-replicatedValue dataset) text)))))

        on-toggle-expand
        (mf/use-fn
         (mf/deps expanded? editing? creating?)
         (fn [_]
           (st/emit! (dw/set-annotations-expanded (not expanded?)))))

        on-discard
        (mf/use-fn
         (mf/deps adjust-textarea-size creating?)
         (fn [event]
           (dom/stop-propagation event)
           (rerender-fn)
           (when-let [textarea (mf/ref-val textarea-ref)]
             (dom/set-value! textarea annotation)
             (reset! editing* false)
             (when creating?
               (st/emit! (dw/set-annotations-id-for-create nil)))
             (adjust-textarea-size)
             (rerender-fn))))

        on-edit
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (rerender-fn)
           (when ^boolean main-instance?
             (when-let [textarea (mf/ref-val textarea-ref)]
               (reset! editing* true)
               (dom/focus! textarea)
               (rerender-fn)))))

        on-save
        (mf/use-fn
         (mf/deps creating?)
         (fn [event]
           (dom/stop-propagation event)
           (rerender-fn)
           (when-let [textarea (mf/ref-val textarea-ref)]
             (let [text (dom/get-value textarea)]
               (when-not (str/blank? text)
                 (reset! editing* false)
                 (st/emit! (dw/update-component-annotation component-id text))
                 (when ^boolean creating?
                   (st/emit! (dw/set-annotations-id-for-create nil)))
                 (rerender-fn))))))

        on-delete-annotation
        (mf/use-fn
         (mf/deps shape-id component-id creating?)
         (fn [event]
           (dom/stop-propagation event)
           (let [on-accept (fn []
                             (rerender-fn)
                             (st/emit!
                              ;; (ptk/data-event {::ev/name "delete-component-annotation"})
                              (when creating?
                                (dw/set-annotations-id-for-create nil))
                              (dw/update-component-annotation component-id nil)
                              (rerender-fn)))]
             (st/emit! (modal/show {:type :confirm
                                    :title (tr "modals.delete-component-annotation.title")
                                    :message (tr "modals.delete-component-annotation.message")
                                    :accept-label (tr "ds.confirm-ok")
                                    :on-accept on-accept})))))]

    (mf/with-effect [shape-id state create-id creating?]
      (when-let [textarea (mf/ref-val textarea-ref)]
        (dom/set-value! textarea annotation)
        (adjust-textarea-size))

      ;; cleanup set-annotations-id-for-create if we aren't on the marked component
      (when (and (not creating?) (some? create-id))
        (st/emit! (dw/set-annotations-id-for-create nil)))

      ;; cleanup set-annotationsid-for-create on unload
      (fn []
        (when creating?
          (st/emit! (dw/set-annotations-id-for-create nil)))))

    (when (or creating? annotation)
      [:div {:class (stl/css-case :annotation true
                                  :editing editing?
                                  :creating creating?)}
       [:div {:class (stl/css-case :annotation-title true
                                   :expandeable (not (or editing? creating?))
                                   :expanded expanded?)
              :on-click on-toggle-expand}

        (if (or editing? creating?)
          [:span {:class (stl/css :annotation-title-name)}
           (if editing?
             (tr "workspace.options.component.edit-annotation")
             (tr "workspace.options.component.create-annotation"))]

          [:*
           [:> icon* {:icon-id (if expanded? i/arrow-down i/arrow-right)
                      :class (stl/css :annotation-title-icon-arrow)
                      :size "s"}]
           [:span {:class (stl/css :annotation-title-name)}
            (tr "workspace.options.component.annotation")]])

        [:div {:class (stl/css :annotation-title-actions)}
         (when (and ^boolean main-instance?
                    ^boolean expanded?)
           (if (or ^boolean editing?
                   ^boolean creating?)
             [:*
              [:div {:title (if ^boolean creating?
                              (tr "labels.create")
                              (tr "labels.save"))
                     :on-click on-save
                     :class (stl/css :annotation-title-icon-action)}
               [:> icon* {:icon-id i/tick
                          :class (stl/css-case :annotation-title-icon-ok true
                                               :disabled invalid-text?)}]]
              [:div {:class (stl/css :annotation-title-icon-action)
                     :title (tr "labels.discard")
                     :on-click on-discard}
               [:> icon* {:icon-id i/close
                          :class (stl/css :annotation-title-icon-nok)}]]]

             [:*
              [:div {:class (stl/css :annotation-title-icon-action)
                     :title (tr "labels.edit")
                     :on-click on-edit}
               [:> icon* {:icon-id i/curve
                          :class (stl/css :annotation-title-icon-ok)}]]
              [:div {:class (stl/css :annotation-title-icon-action)
                     :title (tr "labels.delete")
                     :on-click on-delete-annotation}
               [:> icon* {:icon-id i/delete
                          :class (stl/css :annotation-title-icon-nok)}]]]))]]

       [:div {:class (stl/css-case :annotation-body-hidden (not expanded?))}
        [:div {:class (stl/css :annotation-body)}
         [:textarea {:ref textarea-ref
                     :id "annotation-textarea"
                     :class (stl/css :annotation-textarea)
                     :data-debug annotation
                     :auto-focus (or editing? creating?)
                     :max-length 300
                     :on-input adjust-textarea-size
                     :default-value annotation
                     :read-only (not (or creating? editing?))}]]
        (when (or editing? creating?)
          [:div {:class (stl/css :annotation-counter)} (str size "/300")])]])))

(defn- get-variant-malformed-warning-message
  "Receive a list of booleans, one for each selected variant, indicating if that variant
   is malformed, and generate a warning message accordingly"
  [malformed-list]
  (cond
    (and (= (count malformed-list) 1) (some? (first malformed-list)))
    (tr "workspace.options.component.variant.malformed.single.one")

    (and (seq malformed-list) (every? some? malformed-list))
    (tr "workspace.options.component.variant.malformed.single.all")

    (and (seq malformed-list) (some some? malformed-list))
    (tr "workspace.options.component.variant.malformed.single.some")

    :else nil))

(defn- get-variant-duplicated-warning-message
  "Receive a list of booleans, one for each selected variant, indicating if that variant
   is duplicated, and generate a warning message accordingly"
  [duplicated-list]
  (cond
    (and (= (count duplicated-list) 1) (some? (first duplicated-list)))
    (tr "workspace.options.component.variant.duplicated.single.one")

    (and (seq duplicated-list) (every? some? duplicated-list))
    (tr "workspace.options.component.variant.duplicated.single.all")

    (and (seq duplicated-list) (some some? duplicated-list))
    (tr "workspace.options.component.variant.duplicated.single.some")

    :else nil))

(defn- get-components-with-duplicated-variant-props-and-values
  "Get a list of components whose property names and values are duplicated"
  [components]
  (let [duplicated-props (->> components
                              (map :variant-properties)
                              frequencies
                              (filter #(> (val %) 1))
                              keys
                              set)]
    (->> components
         (filter #(duplicated-props (:variant-properties %))))))

(defn- get-main-ids-with-duplicated-variant-props-and-values
  "Get a list of component main ids whose property names and values are duplicated"
  [components]
  (->> components
       get-components-with-duplicated-variant-props-and-values
       (map :main-instance-id)))

(defn- get-variant-options
  "Get variant options for a given property name"
  [prop-name prop-vals]
  (->> (filter #(= (:name %) prop-name) prop-vals)
       first
       :value
       (mapv (fn [val] {:id val
                        :label (if (str/blank? val) (str "(" (tr "labels.empty") ")") val)}))))

(mf/defc component-variant-property*
  [{:keys [pos prop options on-prop-name-blur on-prop-value-change on-reorder]}]
  (let [on-drop
        (mf/use-fn
         (fn [relative-pos data]
           (let [from-pos             (:from-pos data)
                 to-space-between-pos (if (= relative-pos :bot) (inc pos) pos)]
             (on-reorder from-pos to-space-between-pos))))


        on-prop-value-change
        (mf/use-fn
         (mf/deps on-prop-value-change pos)
         (fn [value]
           (on-prop-value-change pos value)))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/variant-property"
         :on-drop on-drop
         :draggable? true
         :data {:from-pos pos})]

    [:div {:class (stl/css-case :variant-property true
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))}
     (when (some? on-reorder)
       [:> reorder-handler* {:ref dref}])

     [:div {:class (stl/css :variant-property-container)}
      [:div {:class (stl/css :variant-property-name-wrapper)}
       [:> input-with-meta* {:value (:name prop)
                             :is-editing (:editing? (meta prop))
                             :max-length ctv/property-max-length
                             :data-position pos
                             :on-blur on-prop-name-blur}]]

      [:div {:class (stl/css :variant-property-value-wrapper)}
       (let [mixed-value? (= (:value prop) false)]
         [:> combobox* {:id (str "variant-prop-" pos)
                        :placeholder (if mixed-value? (tr "settings.multiple") "--")
                        :default-selected (if mixed-value? "" (:value prop))
                        :options options
                        :empty-to-end true
                        :max-length ctv/property-max-length
                        :on-change on-prop-value-change}])]]]))

(mf/defc component-variant*
  [{:keys [components shapes data]}]
  (let [component      (first components)

        variant-id     (:variant-id component)

        objects        (-> (dsh/get-page data (:main-instance-page component))
                           (get :objects))

        props-list     (map :variant-properties components)
        component-ids  (mf/with-memo [components]
                         (map :id components))
        properties     (if (> (count component-ids) 1)
                         (ctv/compare-properties props-list false)
                         (first props-list))

        malformed-list  (map :variant-error shapes)
        malformed-msg   (get-variant-malformed-warning-message malformed-list)

        duplicated-ids  (->> (cfv/find-variant-components data objects variant-id)
                             get-main-ids-with-duplicated-variant-props-and-values
                             set)
        duplicated-list (->> components
                             (map :main-instance-id)
                             (map duplicated-ids))
        duplicated-msg  (get-variant-duplicated-warning-message duplicated-list)

        prop-vals       (mf/with-memo [data objects variant-id]
                          (cfv/extract-properties-values data objects variant-id))

        get-options
        (mf/use-fn
         (mf/deps prop-vals)
         (fn [prop-name]
           (get-variant-options prop-name prop-vals)))

        update-property-value
        (mf/use-fn
         (mf/deps component-ids)
         (fn [pos value]
           (let [value (d/nilv (str/trim value) "")]
             (doseq [id component-ids]
               (st/emit!
                (ev/event {::ev/name "variant-edit-property-value" ::ev/origin "workspace:combo-design-tab"})
                (dwv/update-property-value id pos value))
               (st/emit! (dwv/update-error id))))))

        update-property-name
        (mf/use-fn
         (mf/deps variant-id)
         (fn [event]
           (let [value (str/trim (dom/get-target-val event))
                 pos   (-> (dom/get-current-target event)
                           (dom/get-data "position")
                           int)]
             (when (seq value)
               (st/emit!
                (dwv/update-property-name variant-id pos value {:trigger "workspace:design-tab-variant"}))))))

        reorder-properties
        (mf/use-fn
         (mf/deps variant-id)
         (fn [from-pos to-space-between-pos]
           (st/emit! (dwv/reorder-variant-poperties variant-id from-pos to-space-between-pos))))]

    [:*
     [:> h/sortable-container* {}
      [:div {:class (stl/css :variant-property-list)}
       (for [[pos prop] (map-indexed vector properties)]
         [:> component-variant-property* {:key (str variant-id "-" pos)
                                          :pos pos
                                          :prop prop
                                          :options (get-options (:name prop))
                                          :on-prop-name-blur update-property-name
                                          :on-prop-value-change update-property-value
                                          :on-reorder reorder-properties}])]]

     (if malformed-msg
       [:div {:class (stl/css :variant-warning)}
        [:> icon* {:icon-id i/msg-neutral
                   :class (stl/css :variant-warning-darken)}]
        [:div {:class (stl/css :variant-warning-highlight)}
         (str malformed-msg " " (tr "workspace.options.component.variant.malformed.structure.title"))]
        [:div {:class (stl/css :variant-warning-darken)}
         (tr "workspace.options.component.variant.malformed.structure.example")]]

       (when duplicated-msg
         [:div {:class (stl/css :variant-warning)}
          [:> icon* {:icon-id i/msg-neutral
                     :class (stl/css :variant-warning-darken)}]
          [:div {:class (stl/css :variant-warning-highlight)}
           (str duplicated-msg)]]))]))

(mf/defc component-variant-copy*
  [{:keys [components shapes component-file-data current-file-id]}]
  (let [component    (first components)
        shape        (first shapes)
        properties   (map :variant-properties components)
        props-first  (:variant-properties component)
        variant-id   (:variant-id component)
        component-page-objects (-> (dsh/get-page component-file-data (:main-instance-page component))
                                   (get :objects))
        variant-comps    (mf/with-memo [component-file-data component-page-objects variant-id]
                           (cfv/find-variant-components component-file-data component-page-objects variant-id))

        duplicated-comps (mf/with-memo [variant-comps]
                           (->> variant-comps
                                get-components-with-duplicated-variant-props-and-values))

        malformed-comps  (mf/with-memo [variant-comps]
                           (->> variant-comps
                                (filter #(->> (:main-instance-id %)
                                              (get component-page-objects)
                                              :variant-error))))

        prop-vals        (mf/with-memo [component-file-data component-page-objects variant-id]
                           (cfv/extract-properties-values component-file-data component-page-objects variant-id))

        get-options
        (mf/use-fn
         (mf/deps prop-vals)
         (fn [prop-name]
           (get-variant-options prop-name prop-vals)))

        select-duplicated-comps
        (mf/use-fn
         (mf/deps current-file-id shape duplicated-comps)
         #(let [ids (map :id duplicated-comps)]
            (if (= current-file-id (:component-file shape))
              (st/emit! (dwl/go-to-local-component {:id (first ids)
                                                    :additional-ids (rest ids)}))
              (st/emit! (dwl/go-to-component-file (:component-file shape)
                                                  (first duplicated-comps)
                                                  false)))))

        select-malformed-comps
        (mf/use-fn
         (mf/deps current-file-id shape malformed-comps)
         (fn []
           (let [ids (map :id malformed-comps)]
             (if (= current-file-id (:component-file shape))
               (st/emit! (dwl/go-to-local-component :id (first ids) :additional-ids (rest ids)))
               (st/emit! (dwl/go-to-component-file (:component-file shape) (first malformed-comps) false))))))

        ;; Used to force a remount after an error
        key*     (mf/use-state (uuid/next))
        key      (deref key*)
        mixed-label (tr "settings.multiple")

        switch-component
        (mf/use-fn
         (mf/deps shapes)
         (fn [pos val]
           (if (= val mixed-label)
             (reset! key* (uuid/next))
             (let [error-msg (if (> (count shapes) 1)
                               (tr "workspace.component.switch.loop-error-multi")
                               (tr "workspace.component.swap.loop-error"))

                   mdata     {:on-error #(do
                                           (reset! key* (uuid/next))
                                           (st/emit! (ntf/error error-msg)))}
                   params    {:shapes shapes :pos pos :val val}]
               (st/emit! (dwv/variants-switch (with-meta params mdata)))))))

        switch-component-toggle
        (mf/use-fn
         (mf/deps shapes)
         (fn [pos boolean-pair val]
           (let [inverted-boolean-pair (d/invert-map boolean-pair)
                 val                   (get inverted-boolean-pair val)]
             (switch-component pos val))))]

    [:*
     [:div {:class (stl/css :variant-property-list)}
      (for [[pos prop] (map-indexed vector props-first)]
        (let [mixed-value? (not-every? #(= (:value prop) (:value (nth % pos))) properties)
              options      (get-options (:name prop))
              boolean-pair (ctv/find-boolean-pair (mapv :id options))
              options      (cond-> options
                             mixed-value?
                             (conj {:id mixed-label :label mixed-label :dimmed true}))]

          [:div {:key (str pos mixed-value?)
                 :class (stl/css :variant-property-container)}

           [:div {:class (stl/css :variant-property-name-wrapper)
                  :title (:name prop)}
            [:div {:class (stl/css :variant-property-name)}
             (:name prop)]]

           (if boolean-pair
             [:div {:class (stl/css :variant-property-value-switch-wrapper)}
              [:> switch* {:default-checked (if mixed-value? nil (get boolean-pair (:value prop)))
                           :on-change (partial switch-component-toggle pos boolean-pair)
                           :key (str (:value prop) "-" key)}]]
             [:div {:class (stl/css :variant-property-value-wrapper)}
              [:> select* {:default-selected (if mixed-value? mixed-label (:value prop))
                           :options options
                           :empty-to-end true
                           :on-change (partial switch-component pos)
                           :key (str (:value prop) "-" key)}]])]))]

     (if (seq malformed-comps)
       [:div {:class (stl/css :variant-warning)}
        [:> icon* {:icon-id i/msg-neutral
                   :class (stl/css :variant-warning-darken)}]
        [:div {:class (stl/css :variant-warning-highlight)}
         (tr "workspace.options.component.variant.malformed.copy")]
        [:button {:class (stl/css :variant-warning-button)
                  :on-click select-malformed-comps}
         (tr "workspace.options.component.variant.malformed.locate")]]

       (when (seq duplicated-comps)
         [:div {:class (stl/css :variant-warning)}
          [:> icon* {:icon-id i/msg-neutral
                     :class (stl/css :variant-warning-darken)}]
          [:div {:class (stl/css :variant-warning-highlight)}
           (tr "workspace.options.component.variant.duplicated.copy.title")]
          [:button {:class (stl/css :variant-warning-button)
                    :on-click select-duplicated-comps}
           (tr "workspace.options.component.variant.duplicated.copy.locate")]]))]))

(mf/defc component-swap-item*
  [{:keys [item loop shapes file-id root-shape container component-id is-search listing-thumbs num-variants]}]
  (let [on-select
        (mf/use-fn
         (mf/deps shapes file-id item)
         #(when-not loop
            (st/emit!
             (dwl/component-multi-swap shapes file-id (:id item))
             (dwsp/clear-specialized-panel))))

        item-ref       (mf/use-ref)
        visible?       (h/use-visible item-ref :once? true)]
    [:button {:ref item-ref
              :key (str "swap-item-" (:id item))
              :class (stl/css-case :swap-item-list (not listing-thumbs)
                                   :swap-item-grid listing-thumbs
                                   :selected (= (:id item) component-id))
              :on-click on-select
              :disabled loop}
     (when visible?
       [:> cmm/component-item-thumbnail* {:file-id (:file-id item)
                                          :class (stl/css :swap-item-thumbnail)
                                          :root-shape root-shape
                                          :component item
                                          :container container}])
     [:span {:title (if is-search (:full-name item) (:name item))
             :class (stl/css :swap-item-name)}
      (if is-search (:full-name item) (:name item))]
     (when (ctk/is-variant? item)
       [:span {:class (stl/css :swap-item-variant-icon)
               :title (tr "workspace.assets.components.num-variants" num-variants)}
        [:> icon* {:icon-id i/variant
                   :size "s"}]])]))

(mf/defc component-swap-group-title*
  [{:keys [item on-enter-group]}]
  (let [group-name     (:name item)
        on-group-click #(on-enter-group group-name)]
    [:div {:class (stl/css :swap-group)
           :on-click on-group-click
           :title group-name}

     [:span {:class (stl/css :swap-group-name)}
      (cpn/last-path group-name)]

     [:> icon* {:class (stl/css :swap-group-icon)
                :variant "ghost"
                :icon-id i/arrow-right
                :size "s"}]]))

(defn- find-common-path
  ([components]
   (let [paths (map (comp cpn/split-path :path) components)]
     (find-common-path paths [] 0)))
  ([paths path n]
   (let [current (nth (first paths) n nil)]
     (if (or (nil? current)
             (not (every? #(= current (nth % n nil)) paths)))
       path
       (find-common-path paths (conj path current) (inc n))))))

(defn- same-component-file?
  [shape-a shape-b]
  (= (:component-file shape-a)
     (:component-file shape-b)))

(defn- same-component?
  [shape-a shape-b]
  (= (:component-id shape-a)
     (:component-id shape-b)))

(mf/defc component-swap*
  [{:keys [shapes]}]
  (let [single?             (= 1 (count shapes))
        shape               (first shapes)
        current-file-id     (mf/use-ctx ctx/current-file-id)

        libraries           (mf/deref refs/libraries)
        objects             (mf/deref refs/workspace-page-objects)

        ^boolean
        every-same-file?    (every? (partial same-component-file? shape) shapes)

        component-id        (if (every? (partial same-component? shape) shapes)
                              (:component-id shape)
                              nil)

        file-id             (if every-same-file?
                              (:component-file shape)
                              current-file-id)

        components          (map #(ctf/get-component libraries (:component-file %) (:component-id %)) shapes)

        path                (if single?
                              (:path (first components))
                              (cpn/join-path (if (not every-same-file?)
                                               ""
                                               (find-common-path components))))

        filters*            (mf/use-state
                             {:term ""
                              :file-id file-id
                              :path (or path "")
                              :listing-thumbs? false})

        filters             (deref filters*)

        search?             (not (str/blank? (:term filters)))

        current-library-id  (if (contains? libraries (:file-id filters))
                              (:file-id filters)
                              current-file-id)

        current-lib-name    (if (= current-library-id current-file-id)
                              (str/upper (tr "workspace.assets.local-library"))
                              (dm/get-in libraries [current-library-id :name]))

        current-lib-data    (mf/with-memo [libraries current-library-id]
                              (get-in libraries [current-library-id :data]))

        current-lib-counts  (mf/with-memo [current-lib-data]
                              (-> (group-by :variant-id
                                            (ctkl/components-seq current-lib-data))
                                  (update-vals count)))

        components          (->> current-lib-data
                                 :components
                                 vals
                                 (remove #(true? (:deleted %)))
                                 (remove #(cfv/is-secondary-variant? % current-lib-data))
                                 (map #(assoc % :full-name (cpn/merge-path-item-with-dot (:path %) (:name %)))))

        count-variants      (fn [component]
                              (get current-lib-counts (:variant-id component)))

        get-subgroups       (fn [path]
                              (let [split-path (cpn/split-path path)]
                                (reduce (fn [acc dir]
                                          (conj acc (str (last acc) " / " dir)))
                                        [(first split-path)] (rest split-path))))

        xform               (comp
                             (map :path)
                             (mapcat get-subgroups)
                             (remove str/empty?)
                             (remove nil?)
                             (distinct)
                             (filter #(= (cpn/butlast-path %) (:path filters))))

        groups              (when-not search?
                              (->> (sort (sequence xform components))
                                   (map (fn [name] {:name name}))))

        components          (if search?
                              (filter #(str/includes? (str/lower (:full-name %)) (str/lower (:term filters))) components)
                              (filter #(= (:path %) (:path filters)) components))

        items               (if (or search? (:listing-thumbs? filters))
                              (sort-by :full-name components)
                              (->> (concat groups components)
                                   (sort-by :name)))

        find-parent-components
        (mf/use-fn
         (mf/deps objects)
         (fn [shape]
           (->> (cfh/get-parents objects (:id shape))
                (map :component-id)
                (remove nil?))))

        ;; Get the ids of the components that are parents of the shapes, to avoid loops
        parent-components (mapcat find-parent-components shapes)

        libraries-options  (map (fn [library] {:value (:id library)
                                               :label (:name library)})
                                (vals libraries))

        on-library-change
        (mf/use-fn
         (fn [id]
           (swap! filters* assoc :file-id id :term "" :path "")))

        on-search-term-change
        (mf/use-fn
         (fn [term]
           (swap! filters* assoc :term term)))

        on-search-clear-click
        (mf/use-fn #(swap! filters* assoc :term ""))

        on-go-back
        (mf/use-fn
         (mf/deps (:path filters))
         #(swap! filters* assoc :path (cpn/butlast-path (:path filters))))

        on-enter-group
        (mf/use-fn #(swap! filters* assoc :path %))

        toggle-list-style
        (mf/use-fn
         (fn [style]
           (swap! filters* assoc :listing-thumbs? (= style "grid"))))

        filter-path-with-dots (->> (:path filters)
                                   (cpn/split-path)
                                   (cpn/join-path-with-dot))]

    [:div {:class (stl/css :swap)}
     [:div {:class (stl/css :swap-title)}
      [:span (tr "workspace.options.component.swap")]]
     [:div {:class (stl/css :swap-content)}
      [:div {:class (stl/css :swap-filters)}
       [:> search-bar* {:id "swap-component-search-filter"
                        :icon-id i/search
                        :value (:term filters)
                        :placeholder (str (tr "labels.search") " " (get-in libraries [current-library-id :name]))
                        :on-change on-search-term-change
                        :on-clear on-search-clear-click}]
       [:& select {:default-value current-library-id
                   :options libraries-options
                   :on-change on-library-change}]]

      [:div  {:class (stl/css :swap-library)}
       [:div {:class (stl/css :swap-library-title)}
        [:div {:class (stl/css :swap-library-name)} current-lib-name]
        [:& radio-buttons {:selected (if (:listing-thumbs? filters) "grid" "list")
                           :on-change toggle-list-style
                           :name "swap-listing-style"}
         [:& radio-button {:icon deprecated-icon/view-as-list
                           :value "list"
                           :id "swap-opt-list"}]
         [:& radio-button {:icon deprecated-icon/flex-grid
                           :value "grid"
                           :id "swap-opt-grid"}]]]

       (when-not (or search? (str/empty? (:path filters)))
         [:button {:class (stl/css :swap-library-back)
                   :on-click on-go-back
                   :title filter-path-with-dots}
          [:> icon* {:icon-id i/arrow-left
                     :size "s"}]
          [:span {:class (stl/css :swap-library-back-name)}
           filter-path-with-dots]])

       (when (empty? items)
         [:div {:class (stl/css :swap-library-empty)}
          (tr "workspace.options.component.swap.empty")]) ;;TODO review this empty space

       (when (:listing-thumbs? filters)
         [:div
          (for [item groups]
            [:> component-swap-group-title* {:item item
                                             :on-enter-group on-enter-group}])])

       [:div {:class (stl/css-case :swap-library-grid (:listing-thumbs? filters)
                                   :swap-library-list (not (:listing-thumbs? filters)))}
        ;; FIXME: This could be in the thousands. We need to think about paginate this
        (for [item items]
          (if (:id item)
            (let [data       (dm/get-in libraries [current-library-id :data])
                  container  (ctf/get-component-page data item)
                  root-shape (ctf/get-component-root data item)
                  components (->> (cfh/get-children-with-self (:objects container) (:id root-shape))
                                  (keep :component-id)
                                  set)
                  loop?      (some #(contains? components %) parent-components)]
              [:> component-swap-item* {:key (dm/str (:id item))
                                        :item item
                                        :loop loop?
                                        :shapes shapes
                                        :file-id current-library-id
                                        :root-shape root-shape
                                        :container container
                                        :component-id component-id
                                        :is-search search?
                                        :listing-thumbs (:listing-thumbs? filters)
                                        :num-variants (count-variants item)}])

            [:> component-swap-group-title* {:item item
                                             :key (:name item)
                                             :on-enter-group on-enter-group}]))]]]]))

(mf/defc component-pill*
  [{:keys [icon text subtext menu-entries disabled on-click]}]
  (let [menu-open* (mf/use-state false)
        menu-open? (deref menu-open*)

        menu-entries? (seq menu-entries)

        on-menu-click
        (mf/use-fn
         (mf/deps menu-open* menu-open?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (reset! menu-open* (not menu-open?))))

        on-menu-close
        (mf/use-fn
         (mf/deps menu-open*)
         #(reset! menu-open* false))

        do-action
        (fn [action event]
          (dom/stop-propagation event)
          (action)
          (on-menu-close))]

    [:div {:class (stl/css :pill)}
     [:button {:class (stl/css-case :pill-btn true
                                    :with-menu menu-entries?)
               :data-testid "component-pill-button"
               :on-click on-click
               :disabled disabled}

      [:div {:class (stl/css :pill-btn-icon)}
       [:> icon* {:size "s"
                  :icon-id icon}]]

      [:div {:class (stl/css :pill-btn-name)}
       [:div {:class (stl/css :pill-btn-text)}
        text]
       (when subtext
         [:div {:class (stl/css :pill-btn-subtext)}
          subtext])]]

     (when menu-entries?
       [:div {:class (stl/css :pill-actions)}
        [:button {:class (stl/css-case :pill-actions-btn true
                                       :selected menu-open?)
                  :on-click on-menu-click}
         [:> icon* {:icon-id i/menu}]]

        [:& dropdown {:show menu-open?
                      :on-close on-menu-close}
         [:ul {:class (stl/css-case :pill-actions-dropdown true
                                    :extended subtext)}
          (for [{:keys [title action]} menu-entries]
            (when (some? title)
              [:li {:key title
                    :class (stl/css :pill-actions-dropdown-item)
                    :on-click (partial do-action action)}
               [:span title]]))]]])]))

(mf/defc component-menu*
  [{:keys [shapes is-swap-opened]}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)

        libraries       (mf/deref refs/files)
        current-file    (get libraries current-file-id)

        state*          (mf/use-state
                         #(do {:show-content true
                               :menu-open false}))
        state           (deref state*)
        open?           (:show-content state)

        shapes          (filter ctk/instance-head? shapes)
        multi           (> (count shapes) 1)
        copies          (filter ctk/in-component-copy? shapes)
        can-swap?       (boolean (seq copies))

        all-main?       (every? ctk/main-instance? shapes)
        any-variant?    (some ctk/is-variant? shapes)

        components      (mapv #(ctf/resolve-component %
                                                      current-file
                                                      libraries
                                                      {:include-deleted? true}) shapes)
        same-variant?   (ctv/same-variant? components)

        ;; For when it's only one shape
        shape           (first shapes)
        id              (:id shape)
        shape-name      (:name shape)

        component       (first components)
        data            (dm/get-in libraries [(:component-file shape) :data])
        is-variant?     (ctk/is-variant? component)

        main-instance?  (ctk/main-instance? shape)

        toggle-content
        (mf/use-fn
         #(swap! state* update :show-content not))

        on-click-variant-title-help
        (mf/use-fn
         (fn []
           (modal/show! {:type :variants-help-modal})
           (modal/allow-click-outside!)))

        on-component-back
        (mf/use-fn
         #(st/emit! ::dwsp/interrupt))

        open-component-panel
        (mf/use-fn
         (mf/deps can-swap? shapes)
         (fn []
           (let [search-id "swap-component-search-filter"]
             (when can-swap? (st/emit! (dwsp/open-specialized-panel :component-swap)))
             (tm/schedule-on-idle #(dom/focus! (dom/get-element search-id))))))

        transform-into-variant
        (mf/use-fn
         (mf/deps id)
         #(st/emit! (dwv/transform-in-variant id)))

        create-variant
        (mf/use-fn
         (mf/deps id)
         #(st/emit!
           (ev/event {::ev/name "add-new-variant" ::ev/origin "workspace:button-design-tab-variant"})
           (dwv/add-new-variant id)))

        add-new-property
        (mf/use-fn
         (mf/deps shape)
         #(st/emit!
           (ev/event {::ev/name "add-new-property" ::ev/origin "workspace:button-design-tab-variant"})
           (dwv/add-new-property (:variant-id shape) {:property-value "Value 1"
                                                      :editing? true})))

        on-combine-as-variants
        (mf/use-fn
         #(st/emit!
           (dwv/combine-selected-as-variants {:trigger "workspace:button-design-tab"})))

        ;; NOTE: function needed for force rerender from the bottom
        ;; components. This is because `component-annotation`
        ;; component changes the component but that has no direct
        ;; reflection on shape which is passed on params. So for avoid
        ;; the need to modify the shape artificially we just pass a
        ;; rerender helper to it via react context mechanism
        rerender-fn
        (mf/use-fn
         (fn []
           (swap! state* update :render inc)))

        menu-entries (cmm/generate-components-menu-entries shapes {:for-design-tab? true})
        path         (->> component (:path) (cpn/split-path) (cpn/join-path-with-dot))]

    (when (seq shapes)
      [:div {:class (stl/css :component-section)}
       [:div {:class (stl/css :component-title)}
        (if is-swap-opened
          [:button {:class (stl/css :component-title-swap)
                    :on-click on-component-back}
           [:> icon* {:icon-id i/arrow-left
                      :size "s"}]
           [:span (tr "workspace.options.component")]]

          [:*
           [:> title-bar* {:collapsable  true
                           :collapsed    (not open?)
                           :on-collapsed toggle-content
                           :title        (tr "workspace.options.component")
                           :class        (stl/css :component-title-bar)
                           :title-class  (stl/css :component-title-bar-title)}
            [:span {:class (stl/css :component-title-bar-type)}
             (if main-instance?
               (if is-variant?
                 (tr "labels.variant")
                 (tr "workspace.options.component.main"))
               (tr "workspace.options.component.copy"))]]

           (when is-variant?
             [:> icon-button* {:variant "ghost"
                               :aria-label (tr "workspace.options.component.variants-help-modal.title")
                               :on-click on-click-variant-title-help
                               :icon i/help}])

           (when main-instance?
             [:> icon-button* {:variant "ghost"
                               :aria-label (tr "workspace.shape.menu.add-variant")
                               :on-click (if is-variant? create-variant transform-into-variant)
                               :icon i/variant}])])]

       (when open?
         [:div {:class (stl/css :component-content)}
          [:div {:class (stl/css :component-pill)}
           [:> component-pill* {:icon (if main-instance?
                                        (if is-variant? i/variant i/component)
                                        i/component-copy)
                                :text (if (and multi (not same-variant?))
                                        (tr "settings.multiple")
                                        (cpn/last-path shape-name))
                                :subtext (when (and can-swap? (or (not multi) same-variant?))
                                           (if (:deleted component)
                                             (tr "workspace.options.component.unlinked")
                                             (cpn/merge-path-item-with-dot path (:name component))))
                                :on-click open-component-panel
                                :disabled (or is-swap-opened (not can-swap?))
                                :menu-entries menu-entries}]
           (when (and is-variant? main-instance?)
             [:> icon-button* {:variant "ghost"
                               :aria-label (tr "workspace.shape.menu.add-variant-property")
                               :on-click add-new-property
                               :icon i/add}])]

          (when is-swap-opened
            [:> component-swap* {:shapes copies}])

          (when (and is-variant?
                     (not main-instance?)
                     (not (:deleted component))
                     (not is-swap-opened)
                     (or (not multi) same-variant?))
            [:> component-variant-copy* {:current-file-id current-file-id
                                         :components components
                                         :shapes shapes
                                         :component-file-data data}])

          (when (and is-variant? main-instance? same-variant? (not is-swap-opened))
            [:> component-variant* {:components components
                                    :shapes shapes
                                    :data data}])

          (when (and (not is-swap-opened) (not multi))
            [:> component-annotation* {:id id
                                       :shape shape
                                       :component component
                                       :rerender-fn rerender-fn}])

          (when (and multi all-main? (not any-variant?))
            [:> button* {:variant "secondary"
                         :type "button"
                         :class (stl/css :component-combine)
                         :on-click on-combine-as-variants}
             (tr "workspace.shape.menu.combine-as-variants")])

          (when (dbg/enabled? :display-touched)
            [:div ":touched " (str (:touched shape))])])])))

(defn- move-empty-items-to-end
  "Creates a new vector with the empty items at the end"
  [v]
  (-> []
      (into (remove empty?) v)
      (into (filter empty?) v)))

(mf/defc component-variant-main-property*
  [{:keys [pos property is-remove-disabled on-remove on-blur on-reorder]}]
  (let [values (->> (:value property)
                    (move-empty-items-to-end)
                    (replace {"" "--"})
                    (str/join ", "))

        on-drop
        (mf/use-fn
         (fn [relative-pos data]
           (let [from-pos             (:from-pos data)
                 to-space-between-pos (if (= relative-pos :bot) (inc pos) pos)]
             (on-reorder from-pos to-space-between-pos))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/variant-main-property"
         :on-drop on-drop
         :draggable? true
         :data {:from-pos pos})]

    [:div {:class (stl/css-case :variant-property true
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))}
     (when (some? on-reorder)
       [:> reorder-handler* {:ref dref}])

     [:div {:class (stl/css :variant-property-row)}
      [:> input-with-meta* {:value (:name property)
                            :data-position pos
                            :meta values
                            :is-editing (:editing? (meta property))
                            :max-length ctv/property-max-length
                            :on-blur on-blur}]
      [:> icon-button* {:variant "ghost"
                        :icon i/remove
                        :data-position pos
                        :aria-label (if is-remove-disabled
                                      (tr "workspace.shape.menu.remove-variant-property.last-property")
                                      (tr "workspace.shape.menu.remove-variant-property"))
                        :on-click on-remove
                        :disabled is-remove-disabled}]]]))

(mf/defc component-variant-main*
  [{:keys [shapes]}]
  (let [multi?             (> (count shapes) 1)

        shape              (first shapes)
        shape-name         (:name shape)

        libraries          (deref refs/libraries)
        current-file-id    (mf/use-ctx ctx/current-file-id)
        current-page-id    (mf/use-ctx ctx/current-page-id)
        data               (get-in libraries [current-file-id :data])

        objects            (-> (dsh/get-page data current-page-id)
                               (get :objects))

        variants           (mapv #(get objects %) (:shapes shape))
        variant-id         (:variant-id (first variants))
        variant-components (cfv/find-variant-components data objects variant-id)

        malformed-ids      (->> variants
                                (filterv #(some? (:variant-error %)))
                                (mapv :id))
        malformed?         (d/not-empty? malformed-ids)

        duplicated-ids     (->> variant-components
                                get-main-ids-with-duplicated-variant-props-and-values)
        duplicated?        (d/not-empty? duplicated-ids)

        properties         (mf/with-memo [data objects variant-id]
                             (cfv/extract-properties-values data objects (:id shape)))
        single-property?   (= (count properties) 1)

        open*              (mf/use-state true)
        open?              (deref open*)

        show-in-assets-panel
        (mf/use-fn
         (mf/deps variants)
         #(st/emit! (dw/show-component-in-assets (:component-id (first variants)))))

        create-variant
        (mf/use-fn
         (mf/deps shape)
         (fn [trigger]
           (st/emit! (ev/event {::ev/name "add-new-variant" ::ev/origin trigger})
                     (dwv/add-new-variant (:id shape)))))

        add-new-property
        (mf/use-fn
         (mf/deps variant-id)
         (fn [trigger]
           (st/emit!
            (ev/event {::ev/name "add-new-property" ::ev/origin trigger})
            (dwv/add-new-property variant-id {:property-value "Value 1"
                                              :editing? true}))))

        menu-entries [{:title (tr "workspace.shape.menu.show-in-assets")
                       :action show-in-assets-panel}
                      {:title (tr "workspace.shape.menu.add-variant")
                       :action (partial create-variant "workspace:design-tab-menu-component")}
                      {:title (tr "workspace.shape.menu.add-variant-property")
                       :action (partial add-new-property "workspace:design-tab-menu-component")}]

        toggle-content
        (mf/use-fn
         #(swap! open* not))

        on-click-variant-title-help
        (mf/use-fn
         (fn []
           (modal/show! {:type :variants-help-modal})
           (modal/allow-click-outside!)))

        update-property-name
        (mf/use-fn
         (mf/deps variant-id)
         (fn [event]
           (let [value (dom/get-target-val event)
                 pos   (-> (dom/get-current-target event)
                           (dom/get-data "position")
                           int)]
             (when (seq value)
               (st/emit!
                (dwv/update-property-name variant-id pos value {:trigger "workspace:design-tab-component"}))))))

        remove-property
        (mf/use-fn
         (mf/deps variant-id properties)
         (fn [event]
           (let [pos (-> (dom/get-current-target event)
                         (dom/get-data "position")
                         int)]
             (when (> (count properties) 1)
               (st/emit!
                (ev/event {::ev/name "variant-remove-property" ::ev/origin "workspace:button-design-tab"})
                (dwv/remove-property variant-id pos))))))

        reorder-properties
        (mf/use-fn
         (mf/deps variant-id)
         (fn [from-pos to-space-between-pos]
           (st/emit! (dwv/reorder-variant-poperties variant-id from-pos to-space-between-pos))))

        select-shapes-with-malformed
        (mf/use-fn
         (mf/deps malformed-ids)
         #(st/emit! (dw/select-shapes (into (d/ordered-set) malformed-ids))))

        select-shapes-with-duplicated
        (mf/use-fn
         (mf/deps duplicated-ids)
         #(st/emit! (dw/select-shapes (into (d/ordered-set) duplicated-ids))))]

    (when (seq shapes)
      [:div {:class (stl/css :component-section)}
       [:div {:class (stl/css :component-title)}

        [:*
         [:> title-bar* {:collapsable  true
                         :collapsed    (not open?)
                         :on-collapsed toggle-content
                         :title        (tr "workspace.options.component")
                         :class        (stl/css :component-title-bar)
                         :title-class  (stl/css :component-title-bar-title)}
          [:span {:class (stl/css :component-title-bar-type)}
           (tr "workspace.options.component.main")]]

         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.component.variants-help-modal.title")
                           :on-click on-click-variant-title-help
                           :icon i/help}]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.shape.menu.add-variant")
                           :on-click (partial create-variant "workspace:button-design-tab-component")
                           :icon i/variant}]]]

       (when open?
         [:div {:class (stl/css :component-content)}
          [:div {:class (stl/css :component-pill)}
           [:> component-pill* {:icon i/component
                                :text (if multi?
                                        (tr "settings.multiple")
                                        (cpn/last-path shape-name))
                                :disabled true
                                :menu-entries menu-entries}]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.shape.menu.add-variant-property")
                             :on-click (partial add-new-property "workspace:button-design-tab-component")
                             :icon i/add}]]

          (when-not multi?
            [:> h/sortable-container* {}
             [:div {:class (stl/css :variant-property-list)}
              (for [[pos property] (map-indexed vector properties)]
                [:> component-variant-main-property* {:key (str (:id shape) pos)
                                                      :pos pos
                                                      :property property
                                                      :is-remove-disabled single-property?
                                                      :on-remove remove-property
                                                      :on-blur update-property-name
                                                      :on-reorder reorder-properties}])]])

          (if malformed?
            [:div {:class (stl/css :variant-warning)}
             [:> icon* {:icon-id i/msg-neutral
                        :class (stl/css :variant-warning-darken)}]
             [:div {:class (stl/css :variant-warning-highlight)}
              (tr "workspace.options.component.variant.malformed.group.title")]
             [:button {:class (stl/css :variant-warning-button)
                       :on-click select-shapes-with-malformed}
              (tr "workspace.options.component.variant.malformed.group.locate")]]

            (when duplicated?
              [:div {:class (stl/css :variant-warning)}
               [:> icon* {:icon-id i/msg-neutral
                          :class (stl/css :variant-warning-darken)}]
               [:div {:class (stl/css :variant-warning-highlight)}
                (tr "workspace.options.component.variant.duplicated.group.title")]
               [:button {:class (stl/css :variant-warning-button)
                         :on-click select-shapes-with-duplicated}
                (tr "workspace.options.component.variant.duplicated.group.locate")]]))])])))
