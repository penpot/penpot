;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.shadow
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
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

(mf/defc shadow-entry
  [{:keys [ids index value on-reorder disable-drag? on-blur open-state-ref]}]
  (let [basic-offset-x-ref (mf/use-ref nil)
        basic-offset-y-ref (mf/use-ref nil)
        basic-blur-ref     (mf/use-ref nil)

        adv-offset-x-ref   (mf/use-ref nil)
        adv-offset-y-ref   (mf/use-ref nil)
        adv-blur-ref       (mf/use-ref nil)
        adv-spread-ref     (mf/use-ref nil)

        shadow-style       (dm/str (:style value))
        shadow-id          (:id value)

        open-status-ref    (mf/with-memo [open-state-ref shadow-id]
                             (-> (l/key shadow-id)
                                 (l/derived open-state-ref)))
        open-shadow        (mf/deref open-status-ref)

        on-remove-shadow
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [index (-> (dom/get-current-target event)
                           (dom/get-data "index")
                           (parse-long))]
             (st/emit! (dch/update-shapes ids #(update % :shadow remove-shadow-by-index index))))))

        on-drop
        (mf/use-fn
         (mf/deps on-reorder index)
         (fn [_ data]
           (on-reorder index (:index data))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/shadow-entry"
         :on-drop on-drop
         :disabled disable-drag?
         :detect-center? false
         :data {:id (dm/str "shadow-" index)
                :index index
                :name (dm/str "Border row" index)})

        ;; FIXME: this function causes the numeric-input rerender
        ;; ALWAYS, this is causes because numeric-input design makes
        ;; imposible implement efficiently any component that uses it;
        ;; it should be refactored
        update-attr
        (fn update-attr
          ([index attr]
           (update-attr index attr nil))
          ([index attr update-ref]
           (fn [value]
             (when (mth/finite? value)
               (st/emit! (dch/update-shapes ids #(assoc-in % [:shadow index attr] value)))
               (when-let [update-node (and update-ref (mf/ref-val update-ref))]
                 (dom/set-value! update-node value))))))

        update-color
        (fn [index]
          (fn [color]
            (st/emit! (dch/update-shapes
                       ids
                       #(assoc-in % [:shadow index :color] color)))))

        detach-color
        (fn [index]
          (fn [_color _opacity]
            (when-not (string? (:color value))
              (st/emit! (dch/update-shapes
                         ids
                         #(assoc-in % [:shadow index :color]
                                    (dissoc (:color value) :id :file-id)))))))

        toggle-visibility
        (fn [index]
          (fn []
            (st/emit! (dch/update-shapes ids #(update-in % [:shadow index :hidden] not)))))

        on-toggle-open-shadow
        (fn []
          (swap! open-state-ref update shadow-id not))]
    [:*
     [:div.shadow-option {:class (dom/classnames
                                  :dnd-over-top (= (:over dprops) :top)
                                  :dnd-over-bot (= (:over dprops) :bot))
                          :ref dref}
      [:div.shadow-option-main {:style {:display (when open-shadow "none")}}
       [:div.element-set-actions-button
        {:on-click on-toggle-open-shadow}
        i/actions]

       [:select.input-select
        {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
         :default-value shadow-style
         :on-change (fn [event]
                      (let [value (-> event dom/get-target dom/get-value d/read-string)]
                        (st/emit! (dch/update-shapes ids #(assoc-in % [:shadow index :style] value)))))}
        [:option {:value ":drop-shadow"
                  :selected (when (= shadow-style ":drop-shadow") "selected")}
         (tr "workspace.options.shadow-options.drop-shadow")]
        [:option {:value ":inner-shadow"
                  :selected (when (= shadow-style ":inner-shadow") "selected")}
         (tr "workspace.options.shadow-options.inner-shadow")]]

       [:div.shadow-option-main-actions
        [:div.element-set-actions-button {:on-click (toggle-visibility index)}
         (if (:hidden value) i/eye-closed i/eye)]
        [:div.element-set-actions-button
         {:data-index index
          :on-click on-remove-shadow}
         i/minus]]]

      [:& advanced-options {:visible? open-shadow
                            :on-close on-toggle-open-shadow}
       [:div.color-data
        [:div.element-set-actions-button
         {:on-click on-toggle-open-shadow}
         i/actions]
        [:select.input-select
         {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
          :default-value shadow-style
          :on-change (fn [event]
                       (let [value (-> event dom/get-target dom/get-value d/read-string)]
                         (st/emit! (dch/update-shapes ids #(assoc-in % [:shadow index :style] value)))))}
         [:option {:value ":drop-shadow"
                   :selected (when (= shadow-style ":drop-shadow") "selected")}
          (tr "workspace.options.shadow-options.drop-shadow")]
         [:option {:value ":inner-shadow"
                   :selected (when (= shadow-style ":inner-shadow") "selected")}
          (tr "workspace.options.shadow-options.inner-shadow")]]]

       [:div.row-grid-2
        [:div.input-element {:title (tr "workspace.options.shadow-options.offsetx")}
         [:> numeric-input* {:ref adv-offset-x-ref
                             :no-validate true
                             :placeholder "--"
                             :on-change (update-attr index :offset-x basic-offset-x-ref)
                             :on-blur on-blur
                             :value (:offset-x value)}]
         [:span.after (tr "workspace.options.shadow-options.offsetx")]]

        [:div.input-element {:title (tr "workspace.options.shadow-options.offsety")}
         [:> numeric-input* {:ref adv-offset-y-ref
                             :no-validate true
                             :placeholder "--"
                             :on-change (update-attr index :offset-y basic-offset-y-ref)
                             :on-blur on-blur
                             :value (:offset-y value)}]
         [:span.after (tr "workspace.options.shadow-options.offsety")]]]

       [:div.row-grid-2
        [:div.input-element {:title (tr "workspace.options.shadow-options.blur")}
         [:> numeric-input* {:ref adv-blur-ref
                             :no-validate true
                             :placeholder "--"
                             :on-change (update-attr index :blur basic-blur-ref)
                             :on-blur on-blur
                             :min 0
                             :value (:blur value)}]
         [:span.after (tr "workspace.options.shadow-options.blur")]]

        [:div.input-element {:title (tr "workspace.options.shadow-options.spread")}
         [:> numeric-input* {:ref adv-spread-ref
                             :no-validate true
                             :placeholder "--"
                             :on-change (update-attr index :spread)
                             :on-blur on-blur
                             :value (:spread value)}]
         [:span.after (tr "workspace.options.shadow-options.spread")]]]

       [:div.color-row-wrap
        [:& color-row {:color (if (string? (:color value))
                                ;; Support for old format colors
                                {:color (:color value) :opacity (:opacity value)}
                                (:color value))
                       :title (tr "workspace.options.shadow-options.color")
                       :disable-gradient true
                       :on-change (update-color index)
                       :on-detach (detach-color index)
                       :on-open #(st/emit! (dwu/start-undo-transaction :color-row))
                       :on-close #(st/emit! (dwu/commit-undo-transaction :color-row))}]]]]]))
(mf/defc shadow-menu
  {::mf/wrap-props false}
  [props]
  (let [ids            (unchecked-get props "ids")
        type           (unchecked-get props "type")
        values         (unchecked-get props "values")

        shadows        (:shadow values [])
        open-state-ref (mf/with-memo [] (l/atom {}))

        disable-drag*  (mf/use-state false)
        disable-drag?  (deref disable-drag*)

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (dch/update-shapes ids #(dissoc % :shadow)))))

        handle-reorder
        (mf/use-fn
         (mf/deps ids)
         (fn [new-index index]
           (st/emit! (dc/reorder-shadows ids index new-index))))

        on-blur
        (mf/use-fn
         #(reset! disable-drag* false))

        on-add-shadow
        (mf/use-fn
         (mf/deps ids)
         #(st/emit! (dc/add-shadow ids (create-shadow))))]

    [:div.element-set.shadow-options
     [:div.element-set-title
      [:span
       (case type
         :multiple (tr "workspace.options.shadow-options.title.multiple")
         :group (tr "workspace.options.shadow-options.title.group")
         (tr "workspace.options.shadow-options.title"))]

      (when-not (= :multiple shadows)
        [:div.add-page {:on-click on-add-shadow} i/close])]

     (cond
       (= :multiple shadows)
       [:div.element-set-content
        [:div.element-set-options-group
         [:div.element-set-label (tr "settings.multiple")]
         [:div.element-set-actions
          [:div.element-set-actions-button {:on-click on-remove-all}
           i/minus]]]]

       (seq shadows)
       [:& h/sortable-container {}
        [:div.element-set-content
         (for [[index value] (d/enumerate shadows)]
           [:& shadow-entry
            {:key (dm/str "shadow-" index)
             :ids ids
             :value value
             :on-reorder handle-reorder
             :disable-drag? disable-drag?
             :on-blur on-blur
             :index index
             :open-state-ref open-state-ref}])]])]))
