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
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.texts :as dwt]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [uxbox.util.object :as obj]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(def fill-attrs [:fill-color :fill-opacity])

(defn- fill-menu-memo-equals?
  [np op]
  (let [new-ids    (obj/get np "ids")
        old-ids    (obj/get op "ids")
        new-editor (obj/get np "editor")
        old-editor (obj/get op "editor")
        new-values (obj/get np "values")
        old-values (obj/get op "values")]
    (and (= new-ids old-ids)
         (= new-editor old-editor)
         (identical? (:fill-color new-values)
                     (:fill-color old-values))
         (identical? (:fill-opacity new-values)
                     (:fill-opacity old-values)))))

(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % fill-menu-memo-equals?)]}
  [{:keys [ids type values editor] :as props}]
  (let [locale (i18n/use-locale)

        shapes (deref (refs/objects-by-id ids))
        text-ids (map :id (filter #(= (:type %) :text) shapes))
        other-ids (map :id (filter #(not= (:type %) :text) shapes))

        label (case type
                :multiple (t locale "workspace.options.selection-fill")
                :group (t locale "workspace.options.group-fill")
                (t locale "workspace.options.fill"))
        color {:value (:fill-color values)
               :opacity (:fill-opacity values)}

        handle-change-color (fn [value opacity]
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
                                          text-ids))))]
    [:div.element-set
     [:div.element-set-title label]
     [:div.element-set-content
      [:& color-row {:color color
                     :on-change handle-change-color}]]]))
