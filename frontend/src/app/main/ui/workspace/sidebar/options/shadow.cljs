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
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]))

(defn create-shadow []
  (let [id (uuid/next)]
    {:id id
     :style :drop-shadow
     :color "#000000"
     :opacity 0.2
     :offset-x 4
     :offset-y 4
     :blur 4
     :spread 0
     :hidden false}))

(defn valid-number? [value]
  (or (number? value) (not (js/isNaN (js/parseInt value)))))

(mf/defc shadow-entry
  [{:keys [ids index value]}]
  (let [open-shadow (mf/use-state false)

        basic-offset-x-ref (mf/use-ref nil)
        basic-offset-y-ref (mf/use-ref nil)
        basic-blur-ref (mf/use-ref nil)

        adv-offset-x-ref (mf/use-ref nil)
        adv-offset-y-ref (mf/use-ref nil)
        adv-blur-ref (mf/use-ref nil)
        adv-spread-ref (mf/use-ref nil)

        remove-shadow-by-id
        (fn [values id] (->> values (filterv (fn [s] (not= (:id s) id)))))

        

        on-remove-shadow
        (fn [id]
          (fn []
            (st/emit! (dwc/update-shapes ids #(update % :shadow remove-shadow-by-id id) ))))

        select-text
        (fn [ref] (fn [event] (dom/select-text! (mf/ref-val ref))))

        update-attr
        (fn update-attr
          ([index attr valid?]
           (update-attr index attr valid? nil))

          ([index attr valid? update-ref]
           (fn [event]
             (let [value (dom/get-value (dom/get-target event))]
               (when (or (not valid?) (valid? value))
                 (do
                   (when update-ref
                     (dom/set-value! (mf/ref-val update-ref) value))
                   (st/emit! (dwc/update-shapes ids #(assoc-in % [:shadow index attr] (js/parseInt value 10))))))))))
        
        update-color
        (fn [index]
          (fn [color opacity]
            (st/emit! (dwc/update-shapes
                       ids
                       #(-> %
                            (assoc-in [:shadow index :color] color)
                            (assoc-in [:shadow index :opacity] opacity))))))

        toggle-visibility
        (fn [index]
          (fn []
            (st/emit! (dwc/update-shapes ids #(update-in % [:shadow index :hidden] not)))))]
    [:*
     [:div.element-set-options-group 

      [:div.element-set-actions-button
       {:on-click #(reset! open-shadow true)}
       i/actions]
      
      [:input.input-text {:type "number"
                          :ref basic-offset-x-ref
                          :on-change (update-attr index :offset-x valid-number?)
                          :on-click (select-text basic-offset-x-ref)
                          :default-value (:offset-x value)}]
      [:input.input-text {:type "number"
                          :ref basic-offset-y-ref
                          :on-change (update-attr index :offset-y valid-number?)
                          :on-click (select-text basic-offset-y-ref)
                          :default-value (:offset-y value)}]
      [:input.input-text {:type "number"
                          :ref basic-blur-ref
                          :on-click (select-text basic-blur-ref)
                          :on-change (update-attr index :blur valid-number?)
                          :min 0
                          :default-value (:blur value)}]            

      [:div.element-set-actions
       [:div.element-set-actions-button {:on-click (toggle-visibility index)}
        (if (:hidden value) i/eye-closed i/eye)]
       [:div.element-set-actions-button {:on-click (on-remove-shadow (:id value))}
        i/minus]]]

     [:& advanced-options {:visible? @open-shadow
                           :on-close #(reset! open-shadow false)}
      [:div.row-grid-2
       [:select.input-select 
        [:option {:value ":drop-shadow"} "Drop shadow"]
        #_[:option {:value ":inner-shadow"} "Inner shadow"]]]
      
      [:div.row-grid-2
       [:div.input-element
        [:input.input-text {:type "number"
                            :ref adv-offset-x-ref
                            :no-validate true
                            :placeholder "--"
                            :on-click (select-text adv-offset-x-ref)
                            :on-change (update-attr index :offset-x valid-number? basic-offset-x-ref)
                            :default-value (:offset-x value)}]
        [:span.after "X"]]

       [:div.input-element
        [:input.input-text {:type "number"
                            :ref adv-offset-y-ref
                            :no-validate true
                            :placeholder "--"
                            :on-click (select-text adv-offset-y-ref)
                            :on-change (update-attr index :offset-y valid-number? basic-offset-y-ref)
                            :default-value (:offset-y value)}]
        [:span.after "Y"]]]

      [:div.row-grid-2
       [:div.input-element
        [:input.input-text {:type "number"
                            :ref adv-blur-ref
                            :no-validate true
                            :placeholder "--"
                            :on-click (select-text adv-blur-ref)
                            :on-change (update-attr index :blur valid-number? basic-blur-ref)
                            :min 0
                            :default-value (:blur value)}]
        [:span.after "Blur"]]

       [:div.input-element
        [:input.input-text {:type "number"
                            :ref adv-spread-ref
                            :no-validate true
                            :placeholder "--"
                            :on-click (select-text adv-spread-ref)
                            :on-change (update-attr index :spread valid-number?)
                            :min 0
                            :default-value (:spread value)}]
        [:span.after "Spread"]]]

      [:div.color-row-wrap
       [:& color-row {:color {:value (:color value) :opacity (:opacity value)}
                      :on-change (update-color index)
                      :on-open #(st/emit! dwc/start-undo-transaction)
                      :on-close #(st/emit! dwc/commit-undo-transaction)}]]]]))
(mf/defc shadow-menu
  [{:keys [ids type values] :as props}]

  (.log js/console "values" (clj->js values))
  (let [on-add-shadow
        (fn []
          (st/emit! (dwc/update-shapes ids #(update % :shadow (fnil conj []) (create-shadow)) )))]
    [:div.element-set.shadow-options
     [:div.element-set-title
      [:span "Shadow"]
      [:div.add-page {:on-click on-add-shadow} i/close]]

     (when (seq (:shadow values))
       [:div.element-set-content
        (for [[index {:keys [id] :as value}] (d/enumerate (:shadow values []))]
          [:& shadow-entry {:key (str "shadow-" id)
                            :ids ids
                            :value value
                            :index index}])])]))
