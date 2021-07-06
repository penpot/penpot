;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.exports
  (:require
   [app.common.data :as d]
   [app.main.data.messages :as dm]
   [app.main.data.workspace :as udw]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer  [tr]]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(defn request-export
  [shape exports]
  (rp/query! :export
             {:page-id (:page-id shape)
              :file-id  (:file-id shape)
              :object-id (:id shape)
              :name (:name shape)
              :exports exports}))

(mf/defc exports-menu
  [{:keys [shape page-id file-id] :as props}]
  (let [exports  (:exports shape [])
        loading? (mf/use-state false)

        filename (cond-> (:name shape)
                   (and (= (count exports) 1)
                        (not (empty (:suffix (first exports)))))
                   (str (:suffix (first exports))))

        scale-enabled?
        (mf/use-callback
          (fn [export]
            (#{:png :jpeg} (:type export))))

        on-download
        (mf/use-callback
         (mf/deps shape)
         (fn [event]
           (dom/prevent-default event)
           (swap! loading? not)
           (->> (request-export (assoc shape :page-id page-id :file-id file-id) exports)
                (rx/subs
                 (fn [body]
                   (dom/trigger-download filename body))
                 (fn [_error]
                   (swap! loading? not)
                   (st/emit! (dm/error (tr "errors.unexpected-error"))))
                 (fn []
                   (swap! loading? not))))))

        add-export
        (mf/use-callback
         (mf/deps shape)
         (fn []
           (let [xspec {:type :png
                        :suffix ""
                        :scale 1}]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports (conj exports xspec)})))))
        delete-export
        (mf/use-callback
         (mf/deps shape)
         (fn [index]
           (let [[before after] (split-at index exports)
                 exports (d/concat [] before (rest after))]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))

        on-scale-change
        (mf/use-callback
         (mf/deps shape)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (d/parse-double value)
                 exports (assoc-in exports [index :scale] value)]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))

        on-suffix-change
        (mf/use-callback
         (mf/deps shape)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 exports (assoc-in exports [index :suffix] value)]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))

        on-type-change
        (mf/use-callback
         (mf/deps shape)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (keyword value)
                 exports (assoc-in exports [index :type] value)]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))]

    [:div.element-set.exports-options
     [:div.element-set-title
      [:span (tr "workspace.options.export")]
      [:div.add-page {:on-click add-export} i/close]]
     (when (seq exports)
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
            i/minus]])

        [:div.btn-icon-dark.download-button
         {:on-click (when-not @loading? on-download)
          :class (dom/classnames
                  :btn-disabled @loading?)
          :disabled @loading?}
         (if @loading?
           (tr "workspace.options.exporting-object")
           (tr "workspace.options.export-object"))]])]))

