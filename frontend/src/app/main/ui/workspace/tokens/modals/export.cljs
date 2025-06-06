;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.export
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.json :as json]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.components.code-block :refer [code-block]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [app.util.zip :as zip]
   [rumext.v2 :as mf]))

(mf/defc export-tab*
  {::mf/private true}
  [{:keys [on-export is-disabled children]}]
  [:div {:class (stl/css :export-preview)}
   (when-not is-disabled
     [:> text* {:as "span" :typography "body-medium" :class (stl/css :preview-label)}
      (tr "workspace.tokens.export.preview")])
   (if is-disabled
     [:div {:class (stl/css :disabled-message)}
      (tr "workspace.tokens.export.no-tokens-themes-sets")]
     children)
   [:div {:class (stl/css :export-actions)}
    [:> button* {:variant "secondary"
                 :type "button"
                 :on-click modal/hide!}
     (tr "labels.cancel")]
    [:> button* {:variant "primary"
                 :type "button"
                 :disabled is-disabled
                 :on-click on-export}
     (tr "workspace.tokens.export")]]])

(mf/defc single-file-tab*
  {::mf/private true}
  []
  (let [tokens-data (some-> (deref refs/tokens-lib)
                            (ctob/export-dtcg-json))
        tokens-json (some-> tokens-data
                            (json/encode :key-fn identity :indent 2))
        is-disabled (empty? tokens-data)
        on-export
        (mf/use-fn
         (mf/deps tokens-json)
         (fn []
           (when tokens-json
             (->> (wapi/create-blob (or tokens-json "{}") "application/json")
                  (dom/trigger-download "tokens.json")))))]
    [:> export-tab* {:is-disabled is-disabled
                     :on-export on-export}
     [:div {:class (stl/css :json-preview)}
      [:> code-block {:code tokens-json :type "json"}]]]))

(defn download-tokens-zip! [multi-file-entries]
  (let [writer (-> (zip/blob-writer {:mtype "application/zip"})
                   (zip/writer))]
    (doseq [[path content] multi-file-entries]
      (zip/add writer path (json/encode content :key-fn identity :indent 2)))
    (-> (zip/close writer)
        (.then #(dom/trigger-download "tokens.zip" %)))))

(mf/defc multi-file-tab*
  {::mf/private true}
  []
  (let [files (some->> (deref refs/tokens-lib)
                       (ctob/export-dtcg-multi-file))
        is-disabled (or (empty? files)
                        (every? (fn [[_ v]] (empty? v)) files))
        on-export
        (mf/use-fn
         (mf/deps files)
         (fn []
           (download-tokens-zip! files)))]
    [:> export-tab* {:on-export on-export
                     :is-disabled is-disabled}
     [:div {:class (stl/css :preview-container)}
      [:ul {:class (stl/css :file-list)}
       (for [[path] files]
         [:li {:key path
               :class (stl/css :file-item)}
          [:div {:class (stl/css :file-icon)}
           [:> icon* {:icon-id "document"}]]
          [:div {:class (stl/css :file-name) :title path}
           path]])]]]))

(mf/defc export-modal-body*
  {::mf/private true}
  []
  (let [selected-tab (mf/use-state "single-file")

        on-change-tab
        (mf/use-fn
         (fn [tab-id]
           (reset! selected-tab tab-id)))

        single-file-content
        (mf/html [:> single-file-tab*])

        multiple-files-content
        (mf/html [:> multi-file-tab*])

        tabs #js [#js {:label (tr "workspace.tokens.export.single-file")
                       :id "single-file"
                       :content single-file-content}
                  #js {:label (tr "workspace.tokens.export.multiple-files")
                       :id "multiple-files"
                       :content multiple-files-content}]]

    [:div {:class (stl/css :export-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :export-modal-title)}
      (tr "workspace.tokens.export-tokens")]

     [:> tab-switcher*
      {:tabs tabs
       :selected @selected-tab
       :on-change-tab on-change-tab}]]))

(mf/defc export-modal*
  {::mf/register modal/components
   ::mf/register-as :tokens/export}
  []
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-dialog)}
    [:> icon-button* {:class (stl/css :close-btn)
                      :on-click modal/hide!
                      :aria-label (tr "labels.close")
                      :variant "ghost"
                      :icon "close"}]
    [:> export-modal-body*]]])
