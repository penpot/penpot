;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.exports
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.data.exports.assets :as de]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr c]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc exports
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [shapes page-id file-id share-id type] :as props}]
  (let [exports     (mf/use-state [])
        renderer-menu* (mf/use-state nil)
        renderer-menu-index (deref renderer-menu*)
        xstate      (mf/deref refs/export)
        vstate      (mf/deref refs/viewer-data)
        page        (get-in vstate [:pages page-id])
        filename    (if (= (count shapes) 1)
                      (let [sname   (-> shapes first :name)
                            suffix (-> @exports first :suffix)]
                        (cond-> sname
                          (and (= 1 (count @exports)) (some? suffix))
                          (str suffix)))
                      (:name page))

        default-jpeg-quality 95

        scale-enabled?
        (mf/use-callback
         (fn [export]
           (#{:png :jpeg :webp} (:type export))))

        quality-enabled?
        (mf/use-callback
         (fn [export]
           (= :jpeg (:type export))))

        renderer-enabled?
        (mf/use-callback
         (fn [export]
           (#{:png :jpeg :webp} (:type export))))

        renderer-label
        (mf/use-callback
         (fn [renderer]
           (tr (case renderer
                 :rasterizer "workspace.options.export.renderer.rasterizer"
                 :render-wasm "workspace.options.export.renderer.render-wasm"
                 "workspace.options.export.renderer.default"))))

        in-progress? (:in-progress xstate)

        on-download
        (fn [event]
          (dom/prevent-default event)
          (if (= :multiple type)
            (st/emit! (de/show-viewer-export-dialog {:shapes shapes
                                                     :exports @exports
                                                     :filename filename
                                                     :page-id page-id
                                                     :file-id file-id
                                                     :share-id share-id}))

            ;; In other all cases we only allowed to have a single
            ;; shape-id because multiple shape-ids are handled
            ;; separately by the export-modal.
            (let [defaults (-> {:page-id page-id
                                :file-id file-id
                                :name filename
                                :object-id (-> shapes first :id)}
                               (cond-> share-id (assoc :share-id share-id)))
                  exports  (mapv #(merge % defaults) @exports)]
              (st/emit!
               (de/request-export {:exports exports})
               (de/export-shapes-event exports "viewer")))))

        add-export
        (mf/use-callback
         (mf/deps shapes)
         (fn []
           (let [xspec {:type :png
                        :suffix ""
                        :scale 1}]
             (swap! exports conj xspec))))

        delete-export
        (mf/use-callback
         (mf/deps shapes)
         (fn [index]
           (swap! exports (fn [exports]
                            (let [[before after] (split-at index exports)]
                              (d/concat-vec before (rest after)))))))

        on-scale-change
        (mf/use-callback
         (mf/deps shapes)
         (fn [index event]
           (let [scale (d/parse-double event)]
             (swap! exports assoc-in [index :scale] scale))))

        on-suffix-change
        (mf/use-callback
         (mf/deps shapes)
         (fn [event]
           (let [value (dom/get-target-val event)
                 index (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (d/parse-integer))]
             (swap! exports assoc-in [index :suffix] value))))

        on-quality-change
        (mf/use-callback
         (mf/deps shapes)
         (fn [index event]
           (when-let [quality (-> event dom/get-target-val d/parse-integer)]
             (let [quality (mth/clamp quality 0 100)]
               (swap! exports assoc-in [index :quality] quality)))))

        open-renderer-menu
        (mf/use-callback
         (fn [event index]
           (dom/stop-propagation event)
           (reset! renderer-menu* index)))

        close-renderer-menu
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (reset! renderer-menu* nil)))

        on-renderer-change
        (mf/use-callback
         (mf/deps renderer-menu*)
         (fn [index renderer]
           (reset! renderer-menu* nil)
           (swap! exports update index
                  (fn [export]
                    (if (= renderer :default)
                      (dissoc export :renderer)
                      (assoc export :renderer renderer))))))

        on-type-change
        (mf/use-callback
         (mf/deps shapes)
         (fn [index event]
           (let [type (keyword event)]
             (swap! exports update index
                    (fn [export]
                      (let [export (assoc export :type type)]
                        (-> export
                            (cond-> (= :jpeg type)
                              (assoc :quality (or (:quality export) default-jpeg-quality))
                              (not= :jpeg type)
                              (dissoc :quality))
                            (cond-> (not (renderer-enabled? export))
                              (dissoc :renderer)))))))))

        manage-key-down
        (mf/use-callback
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
                      {:value "6" :label "6x"}]

        format-options [{:value "png" :label "PNG"}
                        {:value "jpeg" :label "JPG"}
                        {:value "webp" :label "WEBP"}
                        {:value "svg" :label "SVG"}
                        {:value "pdf" :label "PDF"}]]

    (mf/use-effect
     (mf/deps shapes)
     (fn []
       (reset! exports (-> (mapv #(:exports % []) shapes)
                           flatten
                           distinct
                           vec))))
    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable false
                      :title       (tr "workspace.options.export")
                      :class       (stl/css :title-spacing-export-viewer)}
       [:button {:class (stl/css :add-export)
                 :on-click add-export} deprecated-icon/add]]]

     (cond
       (= :multiple exports)
       [:div {:class (stl/css :multiple-exports)}
        [:div {:class (stl/css :label)} (tr "settings.multiple")]
        [:div {:class (stl/css :actions)}
         [:button {:class (stl/css :action-btn)
                   :on-click ()}
          deprecated-icon/remove-icon]]]

       (seq @exports)
       [:div {:class (stl/css :element-set-content)}
        (for [[index export] (d/enumerate @exports)]
          [:div {:class (stl/css :element-group)
                 :key index}
           [:div {:class (stl/css :input-wrapper)}
            [:div  {:class (stl/css :format-select)}
             [:& select
              {:default-value (d/name (:type export))
               :options format-options
               :dropdown-class (stl/css :dropdown-upwards)
               :on-change (partial on-type-change index)}]
             (when (renderer-enabled? export)
               [:div {:class (stl/css :renderer-select)}
                [:button {:class (stl/css :renderer-button)
                          :type "button"
                          :data-testid "export-renderer-button"
                          :aria-label (tr "workspace.options.export.renderer")
                          :on-click (fn [event] (open-renderer-menu event index))}
                 (renderer-label (:renderer export))]
                [:> dropdown-menu* {:show (= renderer-menu-index index)
                                    :on-close close-renderer-menu
                                    :id (str "export-renderer-menu-" index)
                                    :class (stl/css :renderer-menu)}
                 [:> dropdown-menu-item* {:class (stl/css-case :renderer-menu-item true
                                                               :renderer-menu-item-selected (nil? (:renderer export)))
                                          :on-click #(on-renderer-change index :default)}
                  (tr "workspace.options.export.renderer.default")]
                 [:> dropdown-menu-item* {:class (stl/css-case :renderer-menu-item true
                                                               :renderer-menu-item-selected (= :rasterizer (:renderer export)))
                                          :on-click #(on-renderer-change index :rasterizer)}
                  (tr "workspace.options.export.renderer.rasterizer")]
                 [:> dropdown-menu-item* {:class (stl/css-case :renderer-menu-item true
                                                               :renderer-menu-item-selected (= :render-wasm (:renderer export)))
                                          :on-click #(on-renderer-change index :render-wasm)}
                  (tr "workspace.options.export.renderer.render-wasm")]]])]
            (when (scale-enabled? export)
              [:div {:class (stl/css :size-select)}
               [:& select
                {:default-value (str (:scale export))
                 :options size-options
                 :dropdown-class (stl/css :dropdown-upwards)
                 :on-change (partial on-scale-change index)}]])
            (when (quality-enabled? export)
              [:label {:class (stl/css :quality-input)
                       :for "quality-export-input"}
               [:input {:class (stl/css :type-input)
                        :id "quality-export-input"
                        :type "number"
                        :min 0
                        :max 100
                        :step 1
                        :value (or (:quality export) default-jpeg-quality)
                        :placeholder (tr "workspace.options.export.quality")
                        :aria-label (tr "workspace.options.export.quality")
                        :on-change (partial on-quality-change index)
                        :on-key-down manage-key-down}]])
            [:label {:class (stl/css-case :suffix-input true
                                          :suffix-input-wide (not (quality-enabled? export)))
                     :for "suffix-export-input"}
             [:input {:class (stl/css :type-input)
                      :id "suffix-export-input"
                      :type "text"
                      :value (:suffix export)
                      :placeholder (tr "workspace.options.export.suffix")
                      :data-value (str index)
                      :on-change on-suffix-change
                      :on-key-down manage-key-down}]]]

           [:button {:class (stl/css :action-btn)
                     :on-click (partial delete-export index)}
            deprecated-icon/remove-icon]])])
     (when (or (= :multiple exports) (seq @exports))
       [:button
        {:on-click (when-not in-progress? on-download)
         :class (stl/css-case
                 :export-btn true
                 :btn-disabled in-progress?)
         :disabled in-progress?}
        (if in-progress?
          (tr "workspace.options.exporting-object")
          (tr "workspace.options.export-object" (c (count shapes))))])]))
