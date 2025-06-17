;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.shadow :as ctss]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.reorder-handler :refer [reorder-handler]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def shadow-attrs [:shadow])

(defn- create-shadow
  []
  {:id (uuid/next)
   :style :drop-shadow
   :color {:color clr/black
           :opacity 0.2}
   :offset-x 4
   :offset-y 4
   :blur 4
   :spread 0
   :hidden false})

(defn- remove-shadow-by-index
  [values index]
  (->> (d/enumerate values)
       (filterv (fn [[idx _]] (not= idx index)))
       (mapv second)))

(mf/defc shadow-entry*
  [{:keys [index shadow is-open
           on-reorder
           on-toggle-open
           on-detach-color
           on-update
           on-remove
           on-toggle-visibility]}]
  (let [shadow-style       (:style shadow)
        shadow-id          (:id shadow)

        hidden?            (:hidden shadow)

        on-drop
        (mf/use-fn
         (mf/deps on-reorder index)
         (fn [_ data]
           (on-reorder index (:index data))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/shadow-entry"
         :on-drop on-drop
         :detect-center? false
         :data {:id (dm/str "shadow-" index)
                :index index
                :name (dm/str "Border row" index)})

        on-remove
        (mf/use-fn (mf/deps index) #(on-remove index))

        on-update-offset-x
        (mf/use-fn (mf/deps index) #(on-update index :offset-x %))

        on-update-offset-y
        (mf/use-fn (mf/deps index) #(on-update index :offset-y %))

        on-update-spread
        (mf/use-fn (mf/deps index) #(on-update index :spread %))

        on-update-blur
        (mf/use-fn (mf/deps index) #(on-update index :blur %))

        on-update-color
        (mf/use-fn
         (mf/deps index on-update)
         (fn [color]
           (on-update index :color color)))

        on-detach-color
        (mf/use-fn (mf/deps index) #(on-detach-color index))

        on-style-change
        (mf/use-fn (mf/deps index) #(on-update index :style (keyword %)))

        on-toggle-visibility
        (mf/use-fn (mf/deps index) #(on-toggle-visibility index))

        on-toggle-open
        (mf/use-fn
         (mf/deps shadow-id on-toggle-open)
         #(on-toggle-open shadow-id))

        type-options
        (mf/with-memo []
          [{:value "drop-shadow" :label (tr "workspace.options.shadow-options.drop-shadow")}
           {:value "inner-shadow" :label (tr "workspace.options.shadow-options.inner-shadow")}])

        on-open-row
        (mf/use-fn #(st/emit! (dwu/start-undo-transaction :color-row)))

        on-close-row
        (mf/use-fn #(st/emit! (dwu/commit-undo-transaction :color-row)))]

    [:div {:class (stl/css-case :global/shadow-option true
                                :shadow-element true
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))}
     (when (some? on-reorder)
       [:& reorder-handler {:ref dref}])

     [:*
      [:div {:class (stl/css :basic-options)}
       [:div {:class (stl/css-case :shadow-info true
                                   :hidden hidden?)}
        [:button {:class (stl/css-case :more-options true
                                       :selected is-open)
                  :on-click on-toggle-open}
         i/menu]
        [:div {:class (stl/css :type-select)}
         [:& select
          {:class (stl/css :shadow-type-select)
           :default-value (d/name shadow-style)
           :options type-options
           :on-change on-style-change}]]]
       [:div {:class (stl/css :actions)}
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.shadow-options.toggle-shadow")
                          :on-click on-toggle-visibility
                          :icon (if hidden? "hide" "shown")}]
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.shadow-options.remove-shadow")
                          :on-click on-remove
                          :icon "remove"}]]]
      (when is-open
        [:& advanced-options {:class (stl/css :shadow-advanced-options)
                              :visible? is-open
                              :on-close on-toggle-open}

         [:div {:class (stl/css :first-row)}
          [:div {:class (stl/css :offset-x-input)
                 :title (tr "workspace.options.shadow-options.offsetx")}
           [:span {:class (stl/css :input-label)}
            "X"]
           [:> numeric-input* {:class (stl/css :numeric-input)
                               :no-validate true
                               :placeholder "--"
                               :on-change on-update-offset-x
                               :value (:offset-x shadow)}]]

          [:div {:class (stl/css :blur-input)
                 :title (tr "workspace.options.shadow-options.blur")}
           [:span {:class (stl/css :input-label)}
            (tr "workspace.options.shadow-options.blur")]
           [:> numeric-input* {:class (stl/css :numeric-input)
                               :no-validate true
                               :placeholder "--"
                               :on-change on-update-blur
                               :min 0
                               :value (:blur shadow)}]]

          [:div {:class (stl/css :spread-input)
                 :title (tr "workspace.options.shadow-options.spread")}
           [:span {:class (stl/css :input-label)}
            (tr "workspace.options.shadow-options.spread")]
           [:> numeric-input* {:class (stl/css :numeric-input)
                               :no-validate true
                               :placeholder "--"
                               :on-change on-update-spread
                               :value (:spread shadow)}]]]

         [:div {:class (stl/css :second-row)}
          [:div {:class (stl/css :offset-y-input)
                 :title (tr "workspace.options.shadow-options.offsety")}
           [:span {:class (stl/css :input-label)}
            "Y"]
           [:> numeric-input* {:class (stl/css :numeric-input)
                               :no-validate true
                               :placeholder "--"
                               :on-change on-update-offset-y
                               :value (:offset-y shadow)}]]

          [:> color-row* {:class (stl/css :shadow-color)
                          :color (:color shadow)
                          :title (tr "workspace.options.shadow-options.color")
                          :disable-gradient true
                          :disable-image true
                          :on-change on-update-color
                          :on-detach on-detach-color
                          :on-open on-open-row
                          :on-close on-close-row}]]])]]))

(def ^:private xf:add-index
  (map-indexed (fn [index shadow]
                 (assoc shadow ::index index))))

(mf/defc shadow-menu*
  [{:keys [ids type values] :as props}]
  (let [shadows        (mf/with-memo [values]
                         (if (= :multiple values)
                           values
                           (not-empty (into [] xf:add-index values))))

        ids-ref        (h/use-update-ref ids)

        open-state*    (mf/use-state {})
        open-state     (deref open-state*)

        has-shadows?   (or (= :multiple shadows)
                           (some? (seq shadows)))

        show-content*  (mf/use-state true)
        show-content?  (deref show-content*)

        toggle-content
        (mf/use-fn #(swap! show-content* not))

        on-toggle-open
        (mf/use-fn #(swap! open-state* update % not))

        on-remove-all
        (mf/use-fn
         (fn []
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dwsh/update-shapes ids #(dissoc % :shadow))))))

        handle-reorder
        (mf/use-fn
         (fn [new-index index]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dc/reorder-shadows ids index new-index)))))

        on-add-shadow
        (mf/use-fn
         (fn []
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dc/add-shadow ids (create-shadow))))))

        on-detach-color
        (mf/use-fn
         (fn [index]
           (let [ids (mf/ref-val ids-ref)
                 f   #(update-in % [:shadow index :color] dissoc :id :file-id :ref-id :ref-file)]
             (st/emit! (dwsh/update-shapes ids f)))))

        on-toggle-visibility
        (mf/use-fn
         (fn [index]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dwsh/update-shapes ids #(update-in % [:shadow index :hidden] not))))))

        on-remove
        (mf/use-fn
         (fn [index]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dwsh/update-shapes ids #(update % :shadow remove-shadow-by-index index))))))

        on-update
        (mf/use-fn
         (fn [index attr value]
           (let [ids (mf/ref-val ids-ref)]
             (st/emit! (dwsh/update-shapes ids
                                           (fn [shape]
                                             (update-in shape [:shadow index]
                                                        (fn [shadow]
                                                          (-> shadow
                                                              (assoc attr value)
                                                              (ctss/check-shadow))))))))))]
    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:& title-bar {:collapsable  has-shadows?
                     :collapsed    (not show-content?)
                     :on-collapsed toggle-content
                     :title        (case type
                                     :multiple (tr "workspace.options.shadow-options.title.multiple")
                                     :group (tr "workspace.options.shadow-options.title.group")
                                     (tr "workspace.options.shadow-options.title"))
                     :class        (stl/css-case :title-spacing-shadow (not has-shadows?))}

       (when-not (= :multiple shadows)
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.shadow-options.add-shadow")
                           :on-click on-add-shadow
                           :icon "add"
                           :data-testid "add-shadow"}])]]

     (when show-content?
       (cond
         (= :multiple shadows)
         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :multiple-shadows)}
           [:div {:class (stl/css :label)} (tr "settings.multiple")]
           [:div {:class (stl/css :actions)}
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "workspace.options.shadow-options.remove-shadow")
                              :on-click on-remove-all
                              :icon "remove"}]]]]

         (some? shadows)
         [:& h/sortable-container {}
          [:div {:class (stl/css :element-set-content)}
           (for [{:keys [::index id] :as shadow} shadows]
             [:> shadow-entry*
              {:key (dm/str index)
               :index index
               :shadow shadow
               :on-update on-update
               :on-remove on-remove
               :on-toggle-visibility on-toggle-visibility
               :on-detach-color on-detach-color
               :is-open (get open-state id)
               :on-reorder handle-reorder
               :on-toggle-open on-toggle-open}])]]))]))
