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
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.controls.select :refer [select*]]
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
             (st/emit! (modal/show
                        {:type :confirm
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
      [:div {:class (stl/css-case
                     :component-annotation true
                     :editing editing?
                     :creating creating?)}
       [:div {:class (stl/css-case
                      :annotation-title true
                      :expandeable (not (or editing? creating?))
                      :expanded expanded?)
              :on-click on-toggle-expand}

        (if (or editing? creating?)
          [:span {:class (stl/css :annotation-text)}
           (if editing?
             (tr "workspace.options.component.edit-annotation")
             (tr "workspace.options.component.create-annotation"))]

          [:*
           [:span {:class (stl/css-case
                           :icon-arrow true
                           :expanded expanded?)}
            deprecated-icon/arrow]
           [:span {:class (stl/css :annotation-text)}
            (tr "workspace.options.component.annotation")]])

        [:div {:class (stl/css :icons-wrapper)}
         (when (and ^boolean main-instance?
                    ^boolean expanded?)
           (if (or ^boolean editing?
                   ^boolean creating?)
             [:*
              [:div {:title (if ^boolean creating?
                              (tr "labels.create")
                              (tr "labels.save"))
                     :on-click on-save
                     :class (stl/css-case
                             :icon true
                             :icon-tick true
                             :invalid invalid-text?)}
               deprecated-icon/tick]
              [:div {:class (stl/css :icon :icon-cross)
                     :title (tr "labels.discard")
                     :on-click on-discard}
               deprecated-icon/close]]

             [:*
              [:div {:class (stl/css :icon :icon-edit)
                     :title (tr "labels.edit")
                     :on-click on-edit}
               deprecated-icon/curve]
              [:div {:class (stl/css :icon :icon-trash)
                     :title (tr "labels.delete")
                     :on-click on-delete-annotation}
               deprecated-icon/delete]]))]]

       [:div {:class (stl/css-case :hidden (not expanded?))}
        [:div {:class (stl/css :grow-wrap)}
         [:div {:class (stl/css :texarea-copy)}]
         [:textarea
          {:ref textarea-ref
           :id "annotation-textarea"
           :data-debug annotation
           :auto-focus (or editing? creating?)
           :maxLength 300
           :on-input adjust-textarea-size
           :default-value annotation
           :read-only (not (or creating? editing?))}]]
        (when (or editing? creating?)
          [:div {:class (stl/css  :counter)} (str size "/300")])]])))

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

(mf/defc component-variant-main-instance*
  [{:keys [components shapes data]}]
  (let [component      (first components)

        variant-id     (:variant-id component)

        objects        (-> (dsh/get-page data (:main-instance-page component))
                           (get :objects))

        props-list     (map :variant-properties components)
        component-ids  (map :id components)
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
                (dwv/update-property-name variant-id pos value {:trigger "workspace:design-tab-variant"}))))))]

    [:*
     [:div {:class (stl/css :variant-property-list)}
      (for [[pos prop] (map-indexed vector properties)]
        [:div {:key (str variant-id "-" pos)
               :class (stl/css :variant-property-container)}

         [:div {:class (stl/css :variant-property-name-wrapper)}
          [:> input-with-meta* {:value (:name prop)
                                :is-editing (:editing? (meta prop))
                                :max-length ctv/property-max-length
                                :data-position pos
                                :on-blur update-property-name}]]

         [:div {:class (stl/css :variant-property-value-wrapper)}
          (let [mixed-value? (= (:value prop) false)]
            [:> combobox* {:id (str "variant-prop-" variant-id "-" pos)
                           :placeholder (if mixed-value? (tr "settings.multiple") "--")
                           :default-selected (if mixed-value? "" (:value prop))
                           :options (get-options (:name prop))
                           :empty-to-end true
                           :max-length ctv/property-max-length
                           :on-change (partial update-property-value pos)}])]])]

     (if malformed-msg
       [:div {:class (stl/css :variant-warning-wrapper)}
        [:> icon* {:icon-id i/msg-neutral
                   :class (stl/css :variant-warning-darken)}]
        [:div {:class (stl/css :variant-warning-highlight)}
         (str malformed-msg " " (tr "workspace.options.component.variant.malformed.structure.title"))]
        [:div {:class (stl/css :variant-warning-darken)}
         (tr "workspace.options.component.variant.malformed.structure.example")]]

       (when duplicated-msg
         [:div {:class (stl/css :variant-warning-wrapper)}
          [:> icon* {:icon-id i/msg-neutral
                     :class (stl/css :variant-warning-darken)}]
          [:div {:class (stl/css :variant-warning-highlight)}
           (str duplicated-msg)]]))]))

(mf/defc component-variant-copy*
  [{:keys [component shape data current-file-id]}]
  (let [page-objects (mf/deref refs/workspace-page-objects)
        component-id (:id component)
        properties   (:variant-properties component)
        variant-id   (:variant-id component)
        objects      (-> (dsh/get-page data (:main-instance-page component))
                         (get :objects))
        variant-comps    (mf/with-memo [data objects variant-id]
                           (cfv/find-variant-components data objects variant-id))

        duplicated-comps (mf/with-memo [variant-comps]
                           (->> variant-comps
                                get-components-with-duplicated-variant-props-and-values))

        malformed-comps  (mf/with-memo [variant-comps]
                           (->> variant-comps
                                (filter #(->> (:main-instance-id %)
                                              (get objects)
                                              :variant-error))))

        prop-vals        (mf/with-memo [data objects variant-id]
                           (cfv/extract-properties-values data objects variant-id))

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

        switch-component
        (mf/use-fn
         (mf/deps shape component component-id variant-comps)
         (fn [pos val]
           (when (not= val (dm/get-in component [:variant-properties pos :value]))
             (let [target-props (-> (:variant-properties component)
                                    (update pos assoc :value val))
                   valid-comps  (->> variant-comps
                                     (remove #(= (:id %) component-id))
                                     (filter #(= (dm/get-in % [:variant-properties pos :value]) val))
                                     (reverse))
                   nearest-comp (apply min-key #(ctv/distance target-props (:variant-properties %)) valid-comps)
                   parents (cfh/get-parents-with-self page-objects (:parent-id shape))
                   children (cfh/get-children-with-self objects (:main-instance-id nearest-comp))
                   comps-nesting-loop? (seq? (cfh/components-nesting-loop? children parents))]

               (when nearest-comp
                 (if comps-nesting-loop?
                   (do
                     (st/emit! (ntf/error (tr "workspace.component.swap.loop-error")))
                     (reset! key* (uuid/next)))
                   (st/emit! (dwl/component-swap shape (:component-file shape) (:id nearest-comp) true))))))))]

    [:*
     [:div {:class (stl/css :variant-property-list)}
      (for [[pos prop] (map vector (range) properties)]
        [:div {:key (str (:id shape) pos)
               :class (stl/css :variant-property-container)}

         [:div {:class (stl/css :variant-property-name-wrapper)
                :title (:name prop)}
          [:div {:class (stl/css :variant-property-name)}
           (:name prop)]]

         [:div {:class (stl/css :variant-property-value-wrapper)}
          [:> select* {:default-selected (:value prop)
                       :options (get-options (:name prop))
                       :empty-to-end true
                       :on-change (partial switch-component pos)
                       :key (str (:value prop) "-" key)}]]])]

     (if (seq malformed-comps)
       [:div {:class (stl/css :variant-warning-wrapper)}
        [:> icon* {:icon-id i/msg-neutral
                   :class (stl/css :variant-warning-darken)}]
        [:div {:class (stl/css :variant-warning-highlight)}
         (tr "workspace.options.component.variant.malformed.copy")]
        [:button {:class (stl/css :variant-warning-button)
                  :on-click select-malformed-comps}
         (tr "workspace.options.component.variant.malformed.locate")]]

       (when (seq duplicated-comps)
         [:div {:class (stl/css :variant-warning-wrapper)}
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
    [:div {:ref item-ref
           :class (stl/css-case :component-item (not listing-thumbs)
                                :grid-cell listing-thumbs
                                :selected (= (:id item) component-id)
                                :disabled loop)
           :key (str "swap-item-" (:id item))
           :on-click on-select}
     (when visible?
       [:> cmm/component-item-thumbnail*
        {:file-id (:file-id item)
         :class (stl/css :component-img)
         :root-shape root-shape
         :component item
         :container container}])
     [:span {:title (if is-search (:full-name item) (:name item))
             :class (stl/css-case :component-name true
                                  :selected (= (:id item) component-id))}
      (if is-search (:full-name item) (:name item))]
     (when (ctk/is-variant? item)
       [:span {:class (stl/css-case :variant-mark-cell listing-thumbs
                                    :variant-icon true)
               :title (tr "workspace.assets.components.num-variants" num-variants)}
        [:> icon* {:icon-id i/variant
                   :size "s"}]])]))

(mf/defc component-group-item*
  [{:keys [item on-enter-group]}]
  (let [group-name (:name item)
        on-group-click #(on-enter-group group-name)]
    [:div {:class (stl/css :component-group)
           :on-click on-group-click
           :title group-name}

     [:span {:class (stl/css :component-group-name)}
      (cfh/last-path group-name)]

     [:> icon* {:class (stl/css :component-group-icon)
                :variant "ghost"
                :icon-id i/arrow-right
                :size "s"}]]))

(defn- find-common-path
  ([components]
   (let [paths (map (comp cfh/split-path :path) components)]
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
                              (cfh/join-path (if (not every-same-file?)
                                               ""
                                               (find-common-path components))))

        filters*            (mf/use-state
                             {:term ""
                              :file-id file-id
                              :path (or path "")
                              :listing-thumbs? false})

        filters             (deref filters*)

        is-search?          (not (str/blank? (:term filters)))

        current-library-id  (if (contains? libraries (:file-id filters))
                              (:file-id filters)
                              current-file-id)

        current-lib-name    (if (= current-library-id current-file-id)
                              (str/upper (tr "workspace.assets.local-library"))
                              (dm/get-in libraries [current-library-id :name]))

        current-lib-data    (get-in libraries [current-library-id :data])

        components          (->> (get-in libraries [current-library-id :data :components])
                                 vals
                                 (remove #(true? (:deleted %)))
                                 (remove #(cfv/is-secondary-variant? % current-lib-data))
                                 (map #(assoc % :full-name (cfh/merge-path-item-with-dot (:path %) (:name %)))))

        count-variants      (fn [component]
                              (->> (ctkl/components-seq current-lib-data)
                                   (filterv #(= (:variant-id component) (:variant-id %)))
                                   count))

        get-subgroups       (fn [path]
                              (let [split-path (cfh/split-path path)]
                                (reduce (fn [acc dir]
                                          (conj acc (str (last acc) " / " dir)))
                                        [(first split-path)] (rest split-path))))

        xform               (comp
                             (map :path)
                             (mapcat get-subgroups)
                             (remove str/empty?)
                             (remove nil?)
                             (distinct)
                             (filter #(= (cfh/butlast-path %) (:path filters))))

        groups              (when-not is-search?
                              (->> (sort (sequence xform components))
                                   (map (fn [name] {:name name}))))

        components          (if is-search?
                              (filter #(str/includes? (str/lower (:full-name %)) (str/lower (:term filters))) components)
                              (filter #(= (:path %) (:path filters)) components))

        items               (if (or is-search? (:listing-thumbs? filters))
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

        libraries-options  (map (fn [library] {:value (:id library) :label (:name library)})
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
         #(swap! filters* assoc :path (cfh/butlast-path (:path filters))))

        on-enter-group
        (mf/use-fn #(swap! filters* assoc :path %))

        toggle-list-style
        (mf/use-fn
         (fn [style]
           (swap! filters* assoc :listing-thumbs? (= style "grid"))))
        filter-path-with-dots (->> (:path filters) (cfh/split-path) (cfh/join-path-with-dot))]

    [:div {:class (stl/css :component-swap)}
     [:div {:class (stl/css :element-set-title)}
      [:span (tr "workspace.options.component.swap")]]
     [:div {:class (stl/css :component-swap-content)}
      [:div {:class (stl/css :fields-wrapper)}
       [:div {:class (stl/css :search-field)}
        [:> search-bar* {:on-change on-search-term-change
                         :on-clear on-search-clear-click
                         :class (stl/css :search-wrapper)
                         :id "swap-component-search-filter"
                         :value (:term filters)
                         :placeholder (str (tr "labels.search") " " (get-in libraries [current-library-id :name]))
                         :icon-id i/search}]]

       [:& select {:class (stl/css :select-library)
                   :default-value current-library-id
                   :options libraries-options
                   :on-change on-library-change}]]

      [:div  {:class (stl/css :swap-wrapper)}
       [:div {:class (stl/css :library-name-wrapper)}
        [:div {:class (stl/css :library-name)} current-lib-name]

        [:div {:class (stl/css :listing-options-wrapper)}
         [:& radio-buttons {:class (stl/css :listing-options)
                            :selected (if (:listing-thumbs? filters) "grid" "list")
                            :on-change toggle-list-style
                            :name "swap-listing-style"}
          [:& radio-button {:icon deprecated-icon/view-as-list
                            :value "list"
                            :id "swap-opt-list"}]
          [:& radio-button {:icon deprecated-icon/flex-grid
                            :value "grid"
                            :id "swap-opt-grid"}]]]]

       (when-not (or is-search? (str/empty? (:path filters)))
         [:button {:class (stl/css :component-path)
                   :on-click on-go-back
                   :title filter-path-with-dots}
          [:> icon* {:icon-id i/arrow-left
                     :size "s"}]
          [:span {:class (stl/css :path-name)}
           filter-path-with-dots]])

       (when (empty? items)
         [:div {:class (stl/css :component-list-empty)}
          (tr "workspace.options.component.swap.empty")]) ;;TODO review this empty space

       (when (:listing-thumbs? filters)
         [:div {:class (stl/css :component-list)}
          (for [item groups]
            [:> component-group-item* {:item item :on-enter-group on-enter-group}])])

       [:div {:class (stl/css-case :component-grid (:listing-thumbs? filters)
                                   :component-list (not (:listing-thumbs? filters)))}
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
                                        :is-search is-search?
                                        :listing-thumbs (:listing-thumbs? filters)
                                        :num-variants (count-variants item)}])

            [:> component-group-item* {:item item
                                       :key (:name item)
                                       :on-enter-group on-enter-group}]))]]]]))

(mf/defc component-ctx-menu*
  [{:keys [menu-entries on-close show main-instance]}]
  (let [do-action
        (fn [action event]
          (dom/stop-propagation event)
          (action)
          (on-close))]
    [:& dropdown {:show show :on-close on-close}
     [:ul {:class (stl/css-case :custom-select-dropdown true
                                :not-main (not main-instance))}
      (for [{:keys [title action]} menu-entries]
        (when (some? title)
          [:li {:key title
                :class (stl/css :dropdown-element)
                :on-click (partial do-action action)}
           [:span {:class (stl/css :dropdown-label)} title]]))]]))

(mf/defc component-menu
  {::mf/props :obj}
  [{:keys [shapes swap-opened?]}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)

        libraries       (mf/deref refs/files)
        current-file    (get libraries current-file-id)

        state*          (mf/use-state
                         #(do {:show-content true
                               :menu-open false}))
        state           (deref state*)
        open?           (:show-content state)
        menu-open?      (:menu-open state)

        shapes          (filter ctk/instance-head? shapes)
        multi           (> (count shapes) 1)
        copies          (filter ctk/in-component-copy? shapes)
        can-swap?       (boolean (seq copies))

        all-main?       (every? ctk/main-instance? shapes)
        any-variant?    (some ctk/is-variant? shapes)

        ;; For when it's only one shape
        shape           (first shapes)
        id              (:id shape)
        shape-name      (:name shape)

        component       (ctf/resolve-component shape
                                               current-file
                                               libraries
                                               {:include-deleted? true})
        data            (dm/get-in libraries [(:component-file shape) :data])
        is-variant?     (ctk/is-variant? component)

        main-instance?  (ctk/main-instance? shape)

        components      (mapv #(ctf/resolve-component %
                                                      current-file
                                                      libraries
                                                      {:include-deleted? true}) shapes)
        same-variant?   (ctv/same-variant? components)

        toggle-content
        (mf/use-fn #(swap! state* update :show-content not))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (swap! state* update :menu-open not)))

        on-menu-close
        (mf/use-fn
         #(swap! state* assoc :menu-open false))

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
        show-menu?   (seq menu-entries)
        path         (->> component (:path) (cfh/split-path) (cfh/join-path-with-dot))]

    (when (seq shapes)
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        (if swap-opened?
          [:button {:class (stl/css :title-back)
                    :on-click on-component-back}
           [:> icon* {:icon-id i/arrow-left
                      :size "s"}]
           [:span (tr "workspace.options.component")]]

          [:*
           [:> title-bar* {:collapsable  true
                           :collapsed    (not open?)
                           :on-collapsed toggle-content
                           :title        (tr "workspace.options.component")
                           :class        (stl/css :title-spacing-component)
                           :title-class  (stl/css :title-bar-variant)}
            [:span {:class (stl/css :copy-text)}
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
         [:div {:class (stl/css :element-content)}
          [:div {:class (stl/css :component-line)}

           [:div {:class (stl/css :component-wrapper)}

            [:button {:class (stl/css-case :component-name-wrapper true
                                           :without-menu (not show-menu?))
                      :data-testid "swap-component-btn"
                      :on-click open-component-panel
                      :disabled (or swap-opened? (not can-swap?))}

             [:div {:class (stl/css :component-icon)}
              [:> icon* {:size "s"
                         :icon-id (if main-instance?
                                    (if is-variant? i/variant i/component)
                                    i/component-copy)}]]

             [:div {:class (stl/css :component-name-outside)}
              [:div {:class (stl/css :component-name)}
               [:span {:class (stl/css :component-name-inside)}
                (if (and multi (not same-variant?))
                  (tr "settings.multiple")
                  (cfh/last-path shape-name))]]

              (when (and can-swap? (not multi))
                [:div {:class (stl/css :component-parent-name)}
                 (if (:deleted component)
                   (tr "workspace.options.component.unlinked")
                   (cfh/merge-path-item-with-dot path (:name component)))])]]

            (when show-menu?
              [:div {:class (stl/css :component-actions)}
               [:button {:class (stl/css-case :component-menu-btn true
                                              :selected menu-open?)
                         :on-click on-menu-click}
                [:> icon* {:icon-id i/menu}]]

               [:> component-ctx-menu* {:show menu-open?
                                        :on-close on-menu-close
                                        :menu-entries menu-entries
                                        :main-instance main-instance?}]])]

           (when (and is-variant? main-instance?)
             [:> icon-button* {:variant "ghost"
                               :aria-label (tr "workspace.shape.menu.add-variant-property")
                               :on-click add-new-property
                               :icon i/add}])]

          (when swap-opened?
            [:> component-swap* {:shapes copies}])

          (when (and is-variant?
                     (not main-instance?)
                     (not (:deleted component))
                     (not swap-opened?)
                     (not multi))
            [:> component-variant-copy* {:current-file-id current-file-id
                                         :component component
                                         :shape shape
                                         :data data}])

          (when (and is-variant? main-instance? same-variant? (not swap-opened?))
            [:> component-variant-main-instance* {:components components
                                                  :shapes shapes
                                                  :data data}])

          (when (and (not swap-opened?) (not multi))
            [:> component-annotation* {:id id
                                       :shape shape
                                       :component component
                                       :rerender-fn rerender-fn}])

          (when (and multi all-main? (not any-variant?))
            [:button {:class (stl/css :combine-variant-button)
                      :on-click on-combine-as-variants}
             [:span (tr "workspace.shape.menu.combine-as-variants")]])

          (when (dbg/enabled? :display-touched)
            [:div ":touched " (str (:touched shape))])])])))

(defn- move-empty-items-to-end
  "Creates a new vector with the empty items at the end"
  [v]
  (-> []
      (into (remove empty?) v)
      (into (filter empty?) v)))

(mf/defc variant-menu*
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

        open*              (mf/use-state true)
        open?              (deref open*)

        menu-open*         (mf/use-state false)
        menu-open?         (deref menu-open*)

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

        select-shapes-with-malformed
        (mf/use-fn
         (mf/deps malformed-ids)
         #(st/emit! (dw/select-shapes (into (d/ordered-set) malformed-ids))))

        select-shapes-with-duplicated
        (mf/use-fn
         (mf/deps duplicated-ids)
         #(st/emit! (dw/select-shapes (into (d/ordered-set) duplicated-ids))))]

    (when (seq shapes)
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}

        [:*
         [:> title-bar* {:collapsable  true
                         :collapsed    (not open?)
                         :on-collapsed toggle-content
                         :title        (tr "workspace.options.component")
                         :class        (stl/css :title-spacing-component)
                         :title-class  (stl/css :title-bar-variant)}
          [:span {:class (stl/css :copy-text)}
           (tr "workspace.options.component.main")]]

         [:div {:class (stl/css :title-actions)}
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.options.component.variants-help-modal.title")
                            :on-click on-click-variant-title-help
                            :icon i/help}]
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.shape.menu.add-variant")
                            :on-click (partial create-variant "workspace:button-design-tab-component")
                            :icon i/variant}]]]]

       (when open?
         [:div {:class (stl/css :element-content)}
          [:div {:class (stl/css :component-line)}

           [:div {:class (stl/css :component-wrapper)}

            [:button {:class (stl/css :component-name-wrapper)
                      :disabled true}

             [:div {:class (stl/css :component-icon)}
              [:> icon* {:size "s"
                         :icon-id i/component}]]

             [:div {:class (stl/css :component-name-outside)}
              [:div {:class (stl/css :component-name)}
               [:span {:class (stl/css :component-name-inside)}
                (if multi?
                  (tr "settings.multiple")
                  (cfh/last-path shape-name))]]]]

            (when-not multi?
              [:div {:class (stl/css :component-actions)}

               [:button {:class (stl/css-case :component-menu-btn true
                                              :selected menu-open?)
                         :on-click on-menu-click}
                [:> icon* {:icon-id i/menu}]]

               [:> component-ctx-menu* {:show menu-open?
                                        :on-close on-menu-close
                                        :menu-entries menu-entries
                                        :main-instance true}]])]

           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.shape.menu.add-variant-property")
                             :on-click (partial add-new-property "workspace:button-design-tab-component")
                             :icon i/add}]]

          (when-not multi?
            [:div {:class (stl/css :variant-property-list)}
             (for [[pos property] (map-indexed vector properties)]
               (let [last-prop? (<= (count properties) 1)
                     values     (->> (:value property)
                                     (move-empty-items-to-end)
                                     (replace {"" "--"})
                                     (str/join ", "))
                     is-editing (:editing? (meta property))]
                 [:div {:key (str (:id shape) pos)
                        :class (stl/css :variant-property-row)}
                  [:> input-with-meta* {:value (:name property)
                                        :meta values
                                        :is-editing is-editing
                                        :max-length ctv/property-max-length
                                        :data-position pos
                                        :on-blur update-property-name}]
                  [:> icon-button* {:variant "ghost"
                                    :aria-label (if last-prop?
                                                  (tr "workspace.shape.menu.remove-variant-property.last-property")
                                                  (tr "workspace.shape.menu.remove-variant-property"))
                                    :on-click remove-property
                                    :data-position pos
                                    :icon i/remove
                                    :disabled last-prop?}]]))])

          (if malformed?
            [:div {:class (stl/css :variant-warning-wrapper)}
             [:> icon* {:icon-id i/msg-neutral
                        :class (stl/css :variant-warning-darken)}]
             [:div {:class (stl/css :variant-warning-highlight)}
              (tr "workspace.options.component.variant.malformed.group.title")]
             [:button {:class (stl/css :variant-warning-button)
                       :on-click select-shapes-with-malformed}
              (tr "workspace.options.component.variant.malformed.group.locate")]]

            (when duplicated?
              [:div {:class (stl/css :variant-warning-wrapper)}
               [:> icon* {:icon-id i/msg-neutral
                          :class (stl/css :variant-warning-darken)}]
               [:div {:class (stl/css :variant-warning-highlight)}
                (tr "workspace.options.component.variant.duplicated.group.title")]
               [:button {:class (stl/css :variant-warning-button)
                         :on-click select-shapes-with-duplicated}
                (tr "workspace.options.component.variant.duplicated.group.locate")]]))])])))
