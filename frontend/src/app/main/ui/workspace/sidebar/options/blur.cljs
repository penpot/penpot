;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.blur
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
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]))

(defn create-blur []
  (let [id (uuid/next)]
    {:id id
     :type :layer-blur
     :value 4
     :hidden false}))

(mf/defc blur-menu [{:keys [ids values]}]
  (let [locale (i18n/use-locale)
        blur (:blur values)
        has-value? (not (nil? blur))

        change! (fn [update-fn] (st/emit! (dwc/update-shapes ids update-fn)))

        handle-add
        (fn []
          (change! #(assoc % :blur (create-blur))))

        handle-delete
        (fn []
          (change! #(dissoc % :blur)))

        handle-change
        (fn [value]
          (change! #(assoc-in % [:blur :value] value)))

        handle-toggle-visibility
        (fn []
          (change! #(update-in % [:blur :hidden] not)))]

    [:div.element-set
     [:div.element-set-title
      [:span (t locale "workspace.options.blur-options.title")]
      [:div.element-set-title-actions
       (if has-value?
         [:div.add-page {:on-click handle-toggle-visibility} (if (:hidden blur) i/eye-closed i/eye)])
       (if has-value?
         [:div.add-page {:on-click handle-delete} i/minus]
         [:div.add-page {:on-click handle-add} i/close])]]

     (when has-value?
       [:div.element-set-content
        [:& input-row {:label "Value"
                       :class "pixels"
                       :min 0
                       :value (:value blur)
                       :on-change handle-change}]])]))
