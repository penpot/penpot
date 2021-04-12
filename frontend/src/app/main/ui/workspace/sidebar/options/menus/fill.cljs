;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.fill
  (:require
   [app.common.pages :as cp]
   [app.main.data.colors :as dc]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.color :as uc]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(def fill-attrs
  [:fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient])

(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values"]))]}
  [{:keys [ids type values] :as props}]
  (let [locale    (mf/deref i18n/locale)
        show?     (or (not (nil? (:fill-color values)))
                      (not (nil? (:fill-color-gradient values))))

        label (case type
                :multiple (t locale "workspace.options.selection-fill")
                :group (t locale "workspace.options.group-fill")
                (t locale "workspace.options.fill"))

        color {:color (:fill-color values)
               :opacity (:fill-opacity values)
               :id (:fill-color-ref-id values)
               :file-id (:fill-color-ref-file values)
               :gradient (:fill-color-gradient values)}

        on-add
        (mf/use-callback
          (mf/deps ids)
          (fn [event]
            (st/emit! (dc/change-fill ids {:color cp/default-color
                                           :opacity 1}))))

        on-delete
        (mf/use-callback
          (mf/deps ids)
          (fn [event]
            (st/emit! (dc/change-fill ids (into {} uc/empty-color)))))

        on-change
        (mf/use-callback
          (mf/deps ids)
          (fn [color]
            (let [remove-multiple (fn [[key value]] (not= value :multiple))
                  color (into {} (filter remove-multiple) color)]
              (st/emit! (dc/change-fill ids color)))))

        on-detach
        (mf/use-callback
          (mf/deps ids)
          (fn []
            (let [remove-multiple (fn [[key value]] (not= value :multiple))
                  color (-> (into {} (filter remove-multiple) color)
                            (assoc :id nil :file-id nil))]
              (st/emit! (dc/change-fill ids color)))))

        on-open-picker
        (mf/use-callback
          (mf/deps ids)
          (fn [value opacity id file-id]
            (st/emit! (dwc/start-undo-transaction))))

        on-close-picker
        (mf/use-callback
          (mf/deps ids)
          (fn [value opacity id file-id]
            (st/emit! (dwc/commit-undo-transaction))))]

    (if show?
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-delete} i/minus]]

       [:div.element-set-content
        [:& color-row {:color color
                       :on-change on-change
                       :on-detach on-detach
                       :on-open on-open-picker
                       :on-close on-close-picker}]]]

      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-add} i/close]]])))


