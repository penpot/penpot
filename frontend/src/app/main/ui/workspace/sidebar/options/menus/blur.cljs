;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.blur
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def blur-attrs [:blur])

(defn create-blur []
  (let [id (uuid/next)]
    {:id id
     :type :layer-blur
     :value 4
     :hidden false}))

(mf/defc blur-menu [{:keys [ids type values]}]
  (let [blur (:blur values)
        has-value? (not (nil? blur))
        multiple? (= blur :multiple)

        change! (fn [update-fn] (st/emit! (dch/update-shapes ids update-fn)))

        handle-add
        (fn []
          (change! #(assoc % :blur (create-blur))))

        handle-delete
        (fn []
          (change! #(dissoc % :blur)))

        handle-change
        (fn [value]
          (change! #(cond-> %
                      (not (contains? % :blur))
                      (assoc :blur (create-blur))

                      :always
                      (assoc-in [:blur :value] value))))

        handle-toggle-visibility
        (fn []
          (change! #(update-in % [:blur :hidden] not)))]

    [:div.element-set
     [:div.element-set-title
      [:span
       (case type
         :multiple (tr "workspace.options.blur-options.title.multiple")
         :group (tr "workspace.options.blur-options.title.group")
         (tr "workspace.options.blur-options.title"))]

      [:div.element-set-title-actions
       (when (and has-value? (not multiple?))
         [:div.add-page {:on-click handle-toggle-visibility} (if (:hidden blur) i/eye-closed i/eye)])

       (if has-value?
         [:div.add-page {:on-click handle-delete} i/minus]
         [:div.add-page {:on-click handle-add} i/close])]]

     (cond
       has-value?
       [:div.element-set-content
        [:& input-row {:label "Value"
                       :class "pixels"
                       :min "0"
                       :value (:value blur)
                       :placeholder (tr "settings.multiple")
                       :on-change handle-change}]])]))
