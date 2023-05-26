;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.fill
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.main.data.workspace.colors :as dc]
   [app.main.store :as st]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def fill-attrs
  [:fills
   :fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient
   :hide-fill-on-export])

(def fill-attrs-shape
  (conj fill-attrs :hide-fill-on-export))

(defn color-values
  [color]
  {:color (:fill-color color)
   :opacity (:fill-opacity color)
   :id (:fill-color-ref-id color)
   :file-id (:fill-color-ref-file color)
   :gradient (:fill-color-gradient color)})

(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values"]))]}
  [{:keys [ids type values disable-remove?] :as props}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-fill")
                :group (tr "workspace.options.group-fill")
                (tr "workspace.options.fill"))

        ;; Excluding nil values
        values (d/without-nils values)

        hide-fill-on-export? (:hide-fill-on-export values false)

        checkbox-ref (mf/use-ref)

        on-add
        (mf/use-callback
         (mf/deps ids)
         (fn [_]
           (st/emit! (dc/add-fill ids {:color default-color
                                       :opacity 1}))))

        on-change
        (mf/use-callback
         (mf/deps ids)
         (fn [index]
           (fn [color]
             (st/emit! (dc/change-fill ids color index)))))

        on-reorder
        (mf/use-callback
         (mf/deps ids)
         (fn [new-index]
           (fn [index]
             (st/emit! (dc/reorder-fills ids index new-index)))))

        on-remove
        (fn [index]
          (fn []
            (st/emit! (dc/remove-fill ids {:color default-color
                                           :opacity 1} index))))
        on-remove-all
        (fn [_]
          (st/emit! (dc/remove-all-fills ids {:color clr/black
                                              :opacity 1})))

        on-detach
        (mf/use-callback
         (mf/deps ids)
         (fn [index]
           (fn [color]
             (let [color (-> color
                             (assoc :id nil :file-id nil))]
               (st/emit! (dc/change-fill ids color index))))))

        on-change-show-fill-on-export
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dc/change-hide-fill-on-export ids (not value))))))

        disable-drag    (mf/use-state false)

        select-all (fn [event]
                     (when (not @disable-drag)
                       (dom/select-text! (dom/get-target event)))
                     (reset! disable-drag true))

        on-blur (fn [_]
                  (reset! disable-drag false))]

    (mf/use-layout-effect
      (mf/deps hide-fill-on-export?)
      #(let [checkbox (mf/ref-val checkbox-ref)]
         (when checkbox
           ;; Note that the "indeterminate" attribute only may be set by code, not as a static attribute.
           ;; See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/checkbox#attr-indeterminate
           (if (= hide-fill-on-export? :multiple)
             (dom/set-attribute! checkbox "indeterminate" true)
             (dom/remove-attribute! checkbox "indeterminate")))))

      [:div.element-set
       [:div.element-set-title
        [:span label]
        (when (and (not disable-remove?) (not (= :multiple (:fills values))))
          [:div.add-page {:on-click on-add} i/close])]

       [:div.element-set-content

        (cond
          (= :multiple (:fills values))
          [:div.element-set-options-group
           [:div.element-set-label (tr "settings.multiple")]
           [:div.element-set-actions
            [:div.element-set-actions-button {:on-click on-remove-all}
             i/minus]]]

          (seq (:fills values))
          [:& h/sortable-container {}
           (for [[index value] (d/enumerate (:fills values []))]
             [:& color-row {:color {:color (:fill-color value)
                                    :opacity (:fill-opacity value)
                                    :id (:fill-color-ref-id value)
                                    :file-id (:fill-color-ref-file value)
                                    :gradient (:fill-color-gradient value)}
                            :key index
                            :index index
                            :title (tr "workspace.options.fill")
                            :on-change (on-change index)
                            :on-reorder (on-reorder index)
                            :on-detach (on-detach index)
                            :on-remove (on-remove index)
                            :disable-drag disable-drag
                            :select-all select-all
                            :on-blur on-blur}])])

        (when (or (= type :frame)
                  (and (= type :multiple) (some? (:hide-fill-on-export values))))
          [:div.input-checkbox
           [:input {:type "checkbox"
                    :id "show-fill-on-export"
                    :ref checkbox-ref
                    :checked (not hide-fill-on-export?)
                    :on-change on-change-show-fill-on-export}]

           [:label {:for "show-fill-on-export"}
            (tr "workspace.options.show-fill-on-export")]])]]))
