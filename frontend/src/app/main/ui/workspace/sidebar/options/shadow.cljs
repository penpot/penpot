;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shadow
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]))

(def shadow-attrs [:shadow])

(defn create-shadow []
  (let [id (uuid/next)]
    {:id id
     :style :drop-shadow
     :color {:color "#000000" :opacity 0.2}
     :offset-x 4
     :offset-y 4
     :blur 4
     :spread 0
     :hidden false}))

(defn valid-number? [value]
  (or (number? value) (not (js/isNaN (js/parseInt value)))))

(mf/defc shadow-entry
  [{:keys [ids index value]}]
  (let [locale (i18n/use-locale)
        open-shadow (mf/use-state false)

        basic-offset-x-ref (mf/use-ref nil)
        basic-offset-y-ref (mf/use-ref nil)
        basic-blur-ref (mf/use-ref nil)

        adv-offset-x-ref (mf/use-ref nil)
        adv-offset-y-ref (mf/use-ref nil)
        adv-blur-ref (mf/use-ref nil)
        adv-spread-ref (mf/use-ref nil)

        remove-shadow-by-index
        (fn [values index] (->> (d/enumerate values)
                                (filterv (fn [[idx s]] (not= idx index)))
                                (mapv second)))

        on-remove-shadow
        (fn [index]
          (fn []
            (st/emit! (dwc/update-shapes ids #(update % :shadow remove-shadow-by-index index) ))))

        select-text
        (fn [ref] (fn [event] (dom/select-text! (mf/ref-val ref))))

        update-attr
        (fn update-attr
          ([index attr valid?]
           (update-attr index attr valid? nil))

          ([index attr valid? update-ref]
           (fn [value]
             (when (or (not valid?) (valid? value))
               (do (st/emit! (dwc/update-shapes ids #(assoc-in % [:shadow index attr] value)))
                   (when update-ref (dom/set-value! (mf/ref-val update-ref) value)))))))

        update-color
        (fn [index]
          (fn [color opacity]
            (st/emit! (dwc/update-shapes
                       ids
                       #(-> %
                            (assoc-in [:shadow index :color] color)
                            (assoc-in [:shadow index :opacity] opacity))))))

        detach-color
        (fn [index]
          (fn [color opacity]
            (if-not (string? (:color value))
              (st/emit! (dwc/update-shapes
                          ids
                          #(assoc-in % [:shadow index :color]
                                     (dissoc (:color value) :id :file-id)))))))

        toggle-visibility
        (fn [index]
          (fn []
            (st/emit! (dwc/update-shapes ids #(update-in % [:shadow index :hidden] not)))))]
    [:*
     [:div.element-set-options-group 

      [:div.element-set-actions-button
       {:on-click #(reset! open-shadow true)}
       i/actions]

      [:> numeric-input {:ref basic-offset-x-ref
                         :on-change (update-attr index :offset-x valid-number?)
                         :on-click (select-text basic-offset-x-ref)
                         :value (:offset-x value)}]
      [:> numeric-input {:ref basic-offset-y-ref
                         :on-change (update-attr index :offset-y valid-number?)
                         :on-click (select-text basic-offset-y-ref)
                         :value (:offset-y value)}]
      [:> numeric-input {:ref basic-blur-ref
                         :on-click (select-text basic-blur-ref)
                         :on-change (update-attr index :blur valid-number?)
                         :min 0
                         :value (:blur value)}]           

      [:div.element-set-actions
       [:div.element-set-actions-button {:on-click (toggle-visibility index)}
        (if (:hidden value) i/eye-closed i/eye)]
       [:div.element-set-actions-button {:on-click (on-remove-shadow index)}
        i/minus]]]

     [:& advanced-options {:visible? @open-shadow
                           :on-close #(reset! open-shadow false)}
      [:div.row-grid-2
       [:select.input-select
        {:default-value (str (:style value))
         :on-change (fn [event]
                      (let [value (-> event dom/get-target dom/get-value d/read-string)]
                        (st/emit! (dwc/update-shapes ids #(assoc-in % [:shadow index :style] value)))))}
        [:option {:value ":drop-shadow"} (t locale "workspace.options.shadow-options.drop-shadow")]
        [:option {:value ":inner-shadow"} (t locale "workspace.options.shadow-options.inner-shadow")]]]

      [:div.row-grid-2
       [:div.input-element
        [:> numeric-input {:ref adv-offset-x-ref
                           :no-validate true
                           :placeholder "--"
                           :on-click (select-text adv-offset-x-ref)
                           :on-change (update-attr index :offset-x valid-number? basic-offset-x-ref)
                           :value (:offset-x value)}]
        [:span.after (t locale "workspace.options.shadow-options.offsetx")]]

       [:div.input-element
        [:> numeric-input {:ref adv-offset-y-ref
                           :no-validate true
                           :placeholder "--"
                           :on-click (select-text adv-offset-y-ref)
                           :on-change (update-attr index :offset-y valid-number? basic-offset-y-ref)
                           :value (:offset-y value)}]
        [:span.after (t locale "workspace.options.shadow-options.offsety")]]]

      [:div.row-grid-2
       [:div.input-element
        [:> numeric-input {:ref adv-blur-ref
                           :no-validate true
                           :placeholder "--"
                           :on-click (select-text adv-blur-ref)
                           :on-change (update-attr index :blur valid-number? basic-blur-ref)
                           :min 0
                           :value (:blur value)}]
        [:span.after (t locale "workspace.options.shadow-options.blur")]]

       [:div.input-element
        [:> numeric-input {:ref adv-spread-ref
                           :no-validate true
                           :placeholder "--"
                           :on-click (select-text adv-spread-ref)
                           :on-change (update-attr index :spread valid-number?)
                           :min 0
                           :value (:spread value)}]
        [:span.after (t locale "workspace.options.shadow-options.spread")]]]

      [:div.color-row-wrap
       [:& color-row {:color (if (string? (:color value))
                               ;; Support for old format colors
                               {:color (:color value) :opacity (:opacity value)}
                               (:color value))
                      :disable-gradient true
                      :on-change (update-color index)
                      :on-detach (detach-color index)
                      :on-open #(st/emit! (dwc/start-undo-transaction))
                      :on-close #(st/emit! (dwc/commit-undo-transaction))}]]]]))
(mf/defc shadow-menu
  [{:keys [ids type values] :as props}]
  (let [locale (i18n/use-locale)
        on-remove-all-shadows
        (fn [event]
          (st/emit! (dwc/update-shapes ids #(dissoc % :shadow) )))

        on-add-shadow
        (fn []
          (st/emit! (dwc/update-shapes ids #(update % :shadow (fnil conj []) (create-shadow)) )))]
    [:div.element-set.shadow-options
     [:div.element-set-title
      [:span
       (case type
         :multiple (t locale "workspace.options.shadow-options.title.multiple")
         :group (t locale "workspace.options.shadow-options.title.group")
         (t locale "workspace.options.shadow-options.title"))]

      (when-not (= :multiple (:shadow values))
        [:div.add-page {:on-click on-add-shadow} i/close])]

     (cond
       (= :multiple (:shadow values))
       [:div.element-set-content
        [:div.element-set-options-group
         [:div.element-set-label (t locale "settings.multiple")]
         [:div.element-set-actions
          [:div.element-set-actions-button {:on-click on-remove-all-shadows}
           i/minus]]]]

       (not (empty? (:shadow values)))
       [:div.element-set-content
        (for [[index {:keys [id] :as value}] (d/enumerate (:shadow values []))]
          [:& shadow-entry {:key (str "shadow-" index)
                            :ids ids
                            :value value
                            :index index}])])]))
