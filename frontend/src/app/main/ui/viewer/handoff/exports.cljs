;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.handoff.exports
  (:require
   [app.common.data :as d]
   [app.main.data.exports :as de]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr c]]
   [rumext.v2 :as mf]))

(mf/defc exports
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [shapes page-id file-id type] :as props}]
  (let [exports     (mf/use-state [])
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

        in-progress? (:in-progress xstate)

        on-download
        (fn [event]
          (dom/prevent-default event)
          (if (= :multiple type)
            (st/emit! (de/show-viewer-export-dialog {:shapes shapes
                                                     :exports @exports
                                                     :filename filename
                                                     :page-id page-id
                                                     :file-id file-id}))

            ;; In other all cases we only allowed to have a single
            ;; shape-id because multiple shape-ids are handled
            ;; separatelly by the export-modal.
            (let [defaults {:page-id page-id
                            :file-id file-id
                            :name filename
                            :object-id (-> shapes first :id)}
                  exports  (mapv #(merge % defaults) @exports)]
              (if (= 1 (count exports))
                (st/emit! (de/request-simple-export {:export (first exports)}))
                (st/emit! (de/request-multiple-export {:exports exports :filename filename}))))))

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
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (d/parse-double value)]
             (swap! exports assoc-in [index :scale] value))))

        on-suffix-change
        (mf/use-callback
         (mf/deps shapes)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)]
             (swap! exports assoc-in [index :suffix] value))))

        on-type-change
        (mf/use-callback
         (mf/deps shapes)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (keyword value)]
             (swap! exports assoc-in [index :type] value))))]

    (mf/use-effect
     (mf/deps shapes)
     (fn []
       (reset! exports (-> (mapv #(:exports % []) shapes)
                           flatten
                           distinct
                           vec))))

    [:div.element-set.exports-options
     [:div.element-set-title
      [:span (tr "workspace.options.export")]
      [:div.add-page {:on-click add-export} i/close]]

     (when (seq @exports)
       [:div.element-set-content
        (for [[index export] (d/enumerate @exports)]
          [:div.element-set-options-group
           {:key index}
           [:select.input-select {:on-change (partial on-scale-change index)
                                  :value (:scale export)}
            [:option {:value "0.5"}  "0.5x"]
            [:option {:value "0.75"} "0.75x"]
            [:option {:value "1"} "1x"]
            [:option {:value "1.5"} "1.5x"]
            [:option {:value "2"} "2x"]
            [:option {:value "4"} "4x"]
            [:option {:value "6"} "6x"]]

           [:input.input-text {:on-change (partial on-suffix-change index)
                               :value (:suffix export)}]
           [:select.input-select {:on-change (partial on-type-change index)
                                  :value (d/name (:type export))}
            [:option {:value "png"} "PNG"]
            [:option {:value "jpeg"} "JPEG"]
            [:option {:value "svg"} "SVG"]]

           [:div.delete-icon {:on-click (partial delete-export index)}
            i/minus]])

        [:div.btn-icon-dark.download-button
         {:on-click (when-not in-progress? on-download)
          :class (dom/classnames :btn-disabled in-progress?)
          :disabled in-progress?}
         (if in-progress?
           (tr "workspace.options.exporting-object")
           (tr "workspace.options.export-object" (c (count shapes))))]])]))

