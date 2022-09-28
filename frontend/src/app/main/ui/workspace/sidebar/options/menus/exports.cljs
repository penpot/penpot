;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.exports
  (:require
   [app.common.data :as d]
   [app.main.data.exports :as de]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.export]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer  [tr c]]
   [rumext.v2 :as mf]))

(def exports-attrs
  "Shape attrs that corresponds to exports. Used in other namespaces."
  [:exports])

(mf/defc exports-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "page-id" "file-id"]))]}
  [{:keys [ids type values page-id file-id] :as props}]
  (let [exports      (:exports values [])

        state        (mf/deref refs/export)
        in-progress? (:in-progress state)

        sname        (when (seqable? exports)
                       (let [shapes (wsh/lookup-shapes @st/state ids)
                             sname  (-> shapes first :name)
                             suffix (-> exports first :suffix)]
                         (cond-> sname
                           (and (= 1 (count exports)) (some? suffix))
                           (str suffix))))

        scale-enabled?
        (mf/use-callback
         (fn [export]
           (#{:png :jpeg} (:type export))))

        on-download
        (mf/use-fn
         (mf/deps ids page-id file-id exports)
         (fn [event]
           (dom/prevent-default event)
           (if (= :multiple type)
             (st/emit! (de/show-workspace-export-dialog {:selected (reverse ids)}))

             ;; In other all cases we only allowed to have a single
             ;; shape-id because multiple shape-ids are handled
             ;; separatelly by the export-modal.
             (let [defaults {:page-id page-id
                             :file-id file-id
                             :name sname
                             :object-id (first ids)}
                   exports  (mapv #(merge % defaults) exports)]
               (if (= 1 (count exports))
                 (let [export (first exports)]
                   (st/emit! (de/request-simple-export {:export export})))
                 (st/emit! (de/request-multiple-export {:exports exports})))))))

        ;; TODO: maybe move to specific events for avoid to have this logic here?
        add-export
        (mf/use-callback
         (mf/deps ids)
         (fn []
           (let [xspec {:type :png :suffix "" :scale 1}]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc shape :exports (into [xspec] (:exports shape)))))))))

        delete-export
        (mf/use-callback
         (mf/deps ids)
         (fn [position]
           (let [remove-fill-by-index (fn [values index] (->> (d/enumerate values)
                                                              (filterv (fn [[idx _]] (not= idx index)))
                                                              (mapv second)))

                 remove (fn [shape] (update shape :exports remove-fill-by-index position))]
             (st/emit! (dch/update-shapes ids remove)))))

        on-scale-change
        (mf/use-callback
         (mf/deps ids)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (d/parse-double value)]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc-in shape [:exports index :scale] value)))))))

        on-suffix-change
        (mf/use-callback
         (mf/deps ids)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc-in shape [:exports index :suffix] value)))))))

        on-type-change
        (mf/use-callback
         (mf/deps ids)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (keyword value)]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc-in shape [:exports index :type] value)))))))

        on-remove-all
        (mf/use-callback
         (mf/deps ids)
         (fn []
           (st/emit! (dch/update-shapes ids
                                        (fn [shape]
                                          (assoc shape :exports []))))))]

    [:div.element-set.exports-options
     [:div.element-set-title
      [:span (tr (if (> (count ids) 1) "workspace.options.export-multiple" "workspace.options.export"))]
      (when (not (= :multiple exports))
        [:div.add-page {:on-click add-export} i/close])]

     (cond
       (= :multiple exports)
       [:div.element-set-options-group
        [:div.element-set-label (tr "settings.multiple")]
        [:div.element-set-actions
         [:div.element-set-actions-button {:on-click on-remove-all}
          i/minus]]]

       (seq exports)
       [:div.element-set-content
        (for [[index export] (d/enumerate exports)]
          [:div.element-set-options-group
           {:key index}
           (when (scale-enabled? export)
             [:select.input-select {:on-change (partial on-scale-change index)
                                    :value (:scale export)}
              [:option {:value "0.5"}  "0.5x"]
              [:option {:value "0.75"} "0.75x"]
              [:option {:value "1"} "1x"]
              [:option {:value "1.5"} "1.5x"]
              [:option {:value "2"} "2x"]
              [:option {:value "4"} "4x"]
              [:option {:value "6"} "6x"]])
           [:input.input-text {:value (:suffix export)
                               :placeholder (tr "workspace.options.export.suffix")
                               :on-change (partial on-suffix-change index)}]
           [:select.input-select {:value (name (:type export))
                                  :on-change (partial on-type-change index)}
            [:option {:value "png"} "PNG"]
            [:option {:value "jpeg"} "JPEG"]
            [:option {:value "svg"} "SVG"]
            [:option {:value "pdf"} "PDF"]]
           [:div.delete-icon {:on-click (partial delete-export index)}
            i/minus]])])

     (when (or (= :multiple exports) (seq exports))
       [:div.btn-icon-dark.download-button
        {:on-click (when-not in-progress? on-download)
         :class (dom/classnames
                 :btn-disabled in-progress?)
         :disabled in-progress?}
        (if in-progress?
          (tr "workspace.options.exporting-object")
          (tr "workspace.options.export-object" (c (count ids))))])]))
