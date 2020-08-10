;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.fill
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.pages :as cp]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.texts :as dwt]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.object :as obj]))

(def fill-attrs [:fill-color :fill-opacity])

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
        shapes    (deref (refs/objects-by-id ids))
        text-ids  (map :id (filter #(= (:type %) :text) shapes))
        other-ids (map :id (filter #(not= (:type %) :text) shapes))
        show?     (not (nil? (:fill-color values)))

        label (case type
                :multiple (t locale "workspace.options.selection-fill")
                :group (t locale "workspace.options.group-fill")
                (t locale "workspace.options.fill"))

        color {:value (:fill-color values)
               :opacity (:fill-opacity values)}

        on-add
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (st/emit! (dwc/update-shapes ids #(assoc % :fill-color cp/default-color)))))


        on-delete
        (mf/use-callback
         (mf/deps ids)
         (fn [event]
           (st/emit! (dwc/update-shapes ids #(dissoc % :fill-color)))))

        on-change
        (mf/use-callback
         (mf/deps ids)
         (fn [value opacity]
           (let [change #(cond-> %
                           value (assoc :fill-color value)
                           opacity (assoc :fill-opacity opacity))
                 converted-attrs (cond-> {}
                                   value (assoc :fill value)
                                   opacity (assoc :opacity opacity))]

             (when-not (empty? other-ids)
               (st/emit! (dwc/update-shapes ids change)))
             (when-not (empty? text-ids)
               (run! #(st/emit! (dwt/update-text-attrs
                                 {:id %
                                  :editor editor
                                  :attrs converted-attrs}))
                     text-ids)))))]
    (prn "fill-menu" ids values)
    (if show?
      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-delete} i/minus]]

       [:div.element-set-content
        [:& color-row {:color color :on-change on-change}]]]

      [:div.element-set
       [:div.element-set-title
        [:span label]
        [:div.add-page {:on-click on-add} i/close]]])))

