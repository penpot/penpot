;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.fill
  (:require
   [rumext.alpha :as mf]
   [app.common.pages :as cp]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.object :as obj]))

(def fill-attrs [:fill-color :fill-opacity :fill-color-ref-id :fill-color-ref-file])

(defn- fill-menu-props-equals?
  [np op]
  (let [new-ids    (obj/get np "ids")
        old-ids    (obj/get op "ids")
        new-editor (obj/get np "editor")
        old-editor (obj/get op "editor")
        new-values (obj/get np "values")
        old-values (obj/get op "values")]
    (and (= new-ids old-ids)
         (= new-editor old-editor)
         (= (:fill-color new-values)
            (:fill-color old-values))
         (= (:fill-opacity new-values)
            (:fill-opacity old-values)))))


(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % fill-menu-props-equals?)]}
  [{:keys [ids type values editor] :as props}]
  (let [locale    (mf/deref i18n/locale)
        show?     (not (nil? (:fill-color values)))

        label (case type
                :multiple (t locale "workspace.options.selection-fill")
                :group (t locale "workspace.options.group-fill")
                (t locale "workspace.options.fill"))

        color {:value (:fill-color values)
               :opacity (:fill-opacity values)
               :id (:fill-color-ref-id values)
               :file-id (:fill-color-ref-file values)}

        on-add
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (st/emit! (dc/change-fill ids cp/default-color nil nil))))

        on-delete
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (st/emit! (dc/change-fill ids nil nil nil))))

        on-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value opacity id file-id]
           (st/emit! (dc/change-fill ids value id file-id))))

        on-open-picker
        (mf/use-callback
         (mf/deps ids)
         (fn [value opacity id file-id]
           (st/emit! dwc/start-undo-transaction)))

        on-close-picker
        (mf/use-callback
         (mf/deps ids)
         (fn [value opacity id file-id]
           (st/emit! dwc/commit-undo-transaction)))]

    (if show?
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-delete} i/minus]]

       [:div.element-set-content
        [:& color-row {:color color
                       :on-change on-change
                       :on-open on-open-picker
                       :on-close on-close-picker}]]]

      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-add} i/close]]])))


