;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.exports
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.exports :as de]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.export]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer  [tr c]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(def exports-attrs
  "Shape attrs that corresponds to exports. Used in other namespaces."
  [:exports])

(mf/defc exports-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "page-id" "file-id"]))]}
  [{:keys [ids type values page-id file-id] :as props}]
  (let [new-css-system     (mf/use-ctx ctx/new-css-system)
        exports            (:exports values [])
        has-exports?       (or (= :multiple exports) (some? (seq exports)))

        comp-state*        (mf/use-state true)
        open?              (deref comp-state*)

        toggle-content     (mf/use-fn #(swap! comp-state* not))

        state              (mf/deref refs/export)
        in-progress?       (:in-progress state)

        shapes-with-exports (->> (wsh/lookup-shapes @st/state ids)
                                 (filter #(pos? (count (:exports %)))))

        sname               (when (seqable? exports)
                              (let [sname  (-> shapes-with-exports first :name)
                                    suffix (-> exports first :suffix)]
                                (cond-> sname
                                  (and (= 1 (count exports)) (some? suffix))
                                  (str suffix))))

        scale-enabled?
        (mf/use-fn
         (fn [export]
           (#{:png :jpeg} (:type export))))

        on-download
        (mf/use-fn
         (mf/deps ids page-id file-id exports)
         (fn [event]
           (dom/prevent-default event)
           (if (= :multiple type)
             ;; I can select multiple shapes all of them with no export settings and one of them with only one
             ;; In that situation we must export it directly
             (if (and (= 1 (count shapes-with-exports)) (= 1 (-> shapes-with-exports first :exports count)))
               (let [shape    (-> shapes-with-exports first)
                     export   (-> shape :exports first)
                     sname    (:name shape)
                     suffix   (:suffix export)
                     defaults {:page-id page-id
                               :file-id file-id
                               :name sname
                               :object-id (:id (first shapes-with-exports))}]
                 (cond-> sname
                   (some? suffix)
                   (str suffix))
                 (st/emit! (de/request-simple-export {:export (merge export defaults)})))
               (st/emit! (de/show-workspace-export-dialog {:selected (reverse ids)})))

             ;; In other all cases we only allowed to have a single
             ;; shape-id because multiple shape-ids are handled
             ;; separately by the export-modal.
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
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (let [xspec {:type :png :suffix "" :scale 1}]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc shape :exports (into [xspec] (:exports shape)))))))))

        delete-export
        (mf/use-fn
         (mf/deps ids)
         (fn [position]
           (let [remove-fill-by-index (fn [values index] (->> (d/enumerate values)
                                                              (filterv (fn [[idx _]] (not= idx index)))
                                                              (mapv second)))

                 remove (fn [shape] (update shape :exports remove-fill-by-index position))]
             (st/emit! (dch/update-shapes ids remove)))))

        on-scale-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index event]
           (let [scale (if new-css-system
                         (d/parse-double event)
                         (-> event
                             (dom/get-target-val)
                             (d/parse-double)))]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc-in shape [:exports index :scale] scale)))))))

        on-suffix-change
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value   (dom/get-target-val event)
                 index   (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (int))]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc-in shape [:exports index :suffix] value)))))))

        on-type-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index event]
           (let [type (if new-css-system
                         (keyword event)
                         (-> event
                             (dom/get-target-val)
                             (keyword)))]
             (st/emit! (dch/update-shapes ids
                                          (fn [shape]
                                            (assoc-in shape [:exports index :type] type)))))))

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (dch/update-shapes ids
                                        (fn [shape]
                                          (assoc shape :exports []))))))
        manage-key-down
        (mf/use-fn
         (fn [event]
           (let [esc?   (kbd/esc? event)]
             (when esc?
               (dom/blur! (dom/get-target event))))))

        size-options [{:value "0.5" :label "0.5x"}
                      {:value "0.75" :label "0.75x"}
                      {:value "1" :label "1x"}
                      {:value "1.5" :label "1.5x"}
                      {:value "2" :label "2x"}
                      {:value "4" :label "4x"}
                      {:value "6" :label "6"}]

        format-options [{:value "png" :label "PNG"}
                        {:value "jpeg" :label "JPE"}
                        {:value "svg" :label "SVG"}
                        {:value "pdf" :label "PDF"}]]

    (if new-css-system
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:& title-bar {:collapsable? has-exports?
                       :collapsed?   (not open?)
                       :on-collapsed toggle-content
                       :title        (tr (if (> (count ids) 1) "workspace.options.export-multiple" "workspace.options.export"))
                       :class        (stl/css-case :title-spacing-export (not has-exports?))}
         [:button {:class (stl/css :add-export)
                   :on-click add-export} i/add-refactor]]]
       (when open?
         [:div {:class (stl/css :element-set-content)}

          (cond
            (= :multiple exports)
            [:div {:class (stl/css :multiple-exports)}
             [:div {:class (stl/css :label)} (tr "settings.multiple")]
             [:div {:class (stl/css :actions)}
              [:button {:class (stl/css :action-btn)
                        :on-click on-remove-all}
               i/remove-refactor]]]

            (seq exports)
            [:*
             (for [[index export] (d/enumerate exports)]
               [:div {:class (stl/css :element-group)
                      :key index}
                [:div {:class (stl/css :input-wrapper)}
                 [:div  {:class (stl/css :format-select)}
                  [:& select
                   {:default-value (d/name (:type export))
                    :options format-options
                    :dropdown-class (stl/css :dropdown-upwards)
                    :on-change (partial on-type-change index)}]]
                 (when (scale-enabled? export)
                   [:div {:class (stl/css :size-select)}
                    [:& select
                     {:default-value (str (:scale export))
                      :options size-options
                      :dropdown-class (stl/css :dropdown-upwards)
                      :on-change (partial on-scale-change index)}]])
                 [:div {:class (stl/css :suffix-input)}
                  [:input {:class (stl/css :type-input)
                           :value (:suffix export)
                           :placeholder (tr "workspace.options.export.suffix")
                           :data-value index
                           :on-change on-suffix-change
                           :on-key-down manage-key-down}]]]

                [:button {:class (stl/css :action-btn)
                          :on-click (partial delete-export index)}
                 i/remove-refactor]])])

          (when (or (= :multiple exports) (seq exports))
            [:button
             {:on-click (when-not in-progress? on-download)
              :class (stl/css-case
                      :export-btn true
                      :btn-disabled in-progress?)
              :disabled in-progress?}
             (if in-progress?
               (tr "workspace.options.exporting-object")
               (tr "workspace.options.export-object" (c (count shapes-with-exports))))])])]


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
               [:select.input-select {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
                                      :on-change (partial on-scale-change index)
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
                                 :data-value index
                                 :on-change on-suffix-change
                                 :on-key-down manage-key-down}]
             [:select.input-select {:data-mousetrap-dont-stop true ;; makes mousetrap to not stop at this element
                                    :value (d/name (:type export))
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
            (tr "workspace.options.export-object" (c (count shapes-with-exports))))])])))
