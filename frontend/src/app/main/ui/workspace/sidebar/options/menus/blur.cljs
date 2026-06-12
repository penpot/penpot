;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.blur
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def blur-attrs [:blur :background-blur])

(defn create-blur [type]
  (let [id (uuid/next)]
    {:id id
     :type type
     :value 4
     :hidden false}))

(mf/defc blur-menu-content*
  [{:keys [blur-key value change-fn blur-values]}]
  (let [render-wasm?        (features/use-feature "render-wasm/v1")
        bg-blur?            (and render-wasm?
                                 (contains? cf/flags :background-blur))
        is-hidden           (get value :hidden)
        show-more-options*  (mf/use-state false)
        show-more-options   (deref show-more-options*)
        toggle-more-options (mf/use-fn #(swap! show-more-options* not))

        handle-delete
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn []
           (change-fn #(dissoc % blur-key))))

        handle-toggle-visibility
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn []
           (change-fn #(update-in % [blur-key :hidden] not))))

        handle-change
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn [value]
           (change-fn #(assoc-in % [blur-key :value] value))))

        handle-type-change
        (mf/use-fn
         (mf/deps change-fn value blur-key)
         (fn [type]
           (let [type-kw    (keyword type)
                 target-key (if (= type-kw :layer-blur) :blur :background-blur)]
             (change-fn
              (fn [shape]
                (cond
                  ;; mismo tipo
                  (= blur-key target-key)
                  shape

                  ;; ya existe un blur del tipo destino
                  (contains? shape target-key)
                  shape

                  ;; blur origen no existe
                  (not (contains? shape blur-key))
                  shape

                  :else
                  (let [blur (get shape blur-key)]
                    (-> shape
                        (dissoc blur-key)
                        (assoc target-key
                               (assoc blur :type type-kw))))))))))

        bb-disabled? (and (= 2 (count blur-values))
                          (not= blur-key :background-blur))
        lb-disabled? (and (= 2 (count blur-values))
                          (not= blur-key :blur))
        label-ref (mf/use-ref nil)

        type-options
        [{:value "layer-blur"  :disabled lb-disabled? :id "layer-blur" :label (tr "workspace.options.blur-options.layer-blur")}
         {:value "background-blur" :disabled bb-disabled?  :id "background-blur" :label (tr "workspace.options.blur-options.background-blur")}]


        background-blur-disabled?
        (and (= blur-key :background-blur)
             (not bg-blur?))

        label-text
        (cond
          (= blur-key :background-blur)
          (tr "workspace.options.blur-options.background-blur")

          bg-blur?
          (tr "workspace.options.blur-options.layer-blur")

          :else
          (tr "labels.blur"))

        label
        (mf/html [:span {:aria-labelledby "background-blur-disabled-label"
                         :ref label-ref
                         :class (stl/css-case :label true
                                              :disabled-label background-blur-disabled?)}
                  label-text])]

    [:*
     [:div {:class (stl/css-case :first-row true
                                 :hidden is-hidden)}
      [:div {:class (stl/css :blur-info)
             :data-testid "blur-info"}
       [:> icon-button* {:class (stl/css-case :show-more true
                                              :selected show-more-options)
                         :on-click toggle-more-options
                         :selected show-more-options
                         :variant "ghost"
                         :disabled (or
                                    is-hidden
                                    (and (= blur-key :background-blur)
                                         (= false bg-blur?)))
                         :aria-label (tr "workspace.options.blur-options.toggle-more-options")
                         :icon i/menu}]
       (cond bg-blur?
             [:> select*
              {:class (stl/css :blur-type-select)
               :default-selected (d/name (:type value))
               :aria-label (tr "workspace.options.blur-options.blur-type-select")
               :options type-options
               :disabled is-hidden
               :on-change handle-type-change}]
             background-blur-disabled?
             [:> tooltip*
              {:trigger-ref label-ref
               :id "background-blur-disabled-label"
               :class (stl/css :disabled-label-tooltip)
               :content (tr "workspace.options.blur-options.disabled-blur-label")}
              label]
             :else
             label)]

      [:div {:class (stl/css :actions)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.blur-options.toggle-blur")
                         :on-click handle-toggle-visibility
                         :disabled (and (= blur-key :background-blur)
                                        (= false bg-blur?))
                         :tooltip-placement "top-left"
                         :icon (if (or is-hidden
                                       (and (= blur-key :background-blur)
                                            (= false bg-blur?))) i/hide i/shown)}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.blur-options.remove-blur")
                         :on-click handle-delete
                         :tooltip-placement "top-left"
                         :icon i/remove}]]]

     (when show-more-options
       [:div {:class (stl/css :second-row)}
        [:> numeric-input*
         {:class (stl/css :numeric-input)
          :placeholder "--"
          :min 0
          :text-icon "value"
          :on-change handle-change
          :name "blur-value"
          :value (:value value)}]])]))

(defn get-blurs [values]
  (cond-> []
    (:blur values)
    (conj {:key :blur
           :value (:blur values)})

    (:background-blur values)
    (conj {:key :background-blur
           :value (:background-blur values)})))

(defn- check-blur-menu-props
  [old-props new-props]
  (let [old-values (unchecked-get old-props "values")
        new-values (unchecked-get new-props "values")]
    (and (identical? (unchecked-get old-props "ids")
                     (unchecked-get new-props "ids"))
         (identical? (unchecked-get old-props "type")
                     (unchecked-get new-props "type"))
         (identical? (get old-values :blur)
                     (get new-values :blur)))))

(mf/defc blur-menu*
  {::mf/wrap [#(mf/memo' % check-blur-menu-props)]}
  [{:keys [ids type values]}]
  (let [render-wasm?        (features/use-feature "render-wasm/v1")
        bg-blur?            (and render-wasm?
                                 (contains? cf/flags :background-blur))



        blur-values          (get-blurs values)

        mixed-state (and (or (= :group type)
                             (= :multiple type))
                         (boolean
                          (some #(= :multiple (:value %)) blur-values)))

        state*         (mf/use-state {:show-content true})
        state          (deref state*)
        open?          (:show-content state)

        toggle-content (mf/use-fn #(swap! state* update :show-content not))

        change!
        (mf/use-fn
         (mf/deps ids)
         (fn [update-fn]
           (st/emit! (dwsh/update-shapes ids update-fn)
                     (udw/trigger-bounding-box-cloaking ids))))

        handle-delete-all
        (mf/use-fn
         (mf/deps change!)
         (fn []
           (change! #(dissoc % :blur :background-blur))))

        handle-add
        (mf/use-fn
         (mf/deps change! blur-values)
         (fn []
           (cond
             (= 1 (count blur-values))
             (let [existing-key (:key (first blur-values))
                   new-key      (if (= existing-key :blur)
                                  :background-blur
                                  :blur)]
               (change! #(assoc % new-key (create-blur (if (= :blur new-key)
                                                         :layer-blur
                                                         :background-blur)))))
             (= 0 (count blur-values))
             (change! #(assoc % :blur (create-blur :layer-blur))))
           :else
           blur-values))]

    [:section {:class (stl/css :element-set)
               :hidden (not open?)
               :aria-label (if bg-blur?
                             (tr "labels.blur-effects")
                             (tr "labels.blur"))}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  (seq blur-values)
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :aria-expanded open?
                      :aria-controls "blur-content"
                      :title        (if bg-blur?
                                      (cond
                                        (= type :multiple) (tr "workspace.options.blur-effects-options.title.multiple")
                                        (= type :group) (tr "workspace.options.blur-effects-options.title.group")
                                        :else (tr "labels.blur-effects"))
                                      (cond
                                        (= type :multiple) (tr "workspace.options.blur-options.title.multiple")
                                        (= type :group) (tr "workspace.options.blur-options.title.group")
                                        :else (tr "labels.blur")))
                      :class        (stl/css-case :title-spacing-blur (not (seq blur-values))
                                                  :long-title true)}
       (when (and (not mixed-state)
                  (if bg-blur?
                    (< (count blur-values) 2)
                    (nil? (:blur values))))
         [:> icon-button*
          {:variant "ghost"
           :aria-label (tr "workspace.options.blur-options.add-blur")
           :on-click handle-add
           :icon i/add
           :tooltip-placement "top-left"
           :data-testid "add-blur"}])]]
     (when (and open? (seq blur-values))
       [:div {:class (stl/css :element-set-content)
              :hidden (not open?)
              :id "blur-content"}
        (if mixed-state
          [:div  {:class (stl/css :first-row)}
           [:span {:class (stl/css :mixed-label)}
            (tr "labels.mixed-values")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.blur-options.remove-blur")
                             :on-click handle-delete-all
                             :tooltip-placement "top-left"
                             :icon i/remove}]]

          (for [{:keys [key value]} blur-values]
            [:> blur-menu-content*
             {:key key
              :blur-key key
              :value value
              :blur-values blur-values
              :change-fn change!}]))])]))